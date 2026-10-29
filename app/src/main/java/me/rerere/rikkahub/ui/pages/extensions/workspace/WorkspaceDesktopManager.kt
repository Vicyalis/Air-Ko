package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.util.Log
import kotlinx.coroutines.CancellationException
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.device.WorkspaceDesktopPrefs
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceDesktopCatalog
import me.rerere.workspace.WorkspaceDesktopPlan
import me.rerere.workspace.WorkspaceDesktopStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 正在执行的桌面动作; busy 用来禁用同一工作区的其它按钮 */
enum class WorkspaceDesktopAction {
    INSTALL,
    START,
    STOP,
}

/**
 * 桌面操作失败的结构化描述。
 *
 * 刻意不存本地化文案: 管理器在 AppScope 上跑, 拿不到 Activity 的 Context;
 * 文案由 UI 按 [Kind] 与附带的数据拼出来。
 */
data class WorkspaceDesktopFailure(
    val kind: Kind,
    /** 脚本退出码; null 表示没跑到能拿退出码的一步 */
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    /** 该方案的端口; 0 表示未知 (自定义方案没填) */
    val port: Int = 0,
    /** 失败时刻 rootfs 日志的尾巴, 让用户不用再翻终端 */
    val logTail: String? = null,
) {
    enum class Kind {
        /** rootfs 还没装 */
        ROOTFS_MISSING,

        /** 方案没配置 / 自定义方案没填命令或端口 */
        NOT_CONFIGURED,

        /** 安装脚本退出码非 0 或超时 */
        INSTALL_FAILED,

        /** 安装脚本报成功, 但探测不到安装结果 */
        PROBE_FAILED,

        /** 启动脚本失败, 或端口一直没起来 */
        START_FAILED,

        /** 停止脚本失败, 或端口还在响应 */
        STOP_FAILED,

        /** 脚本根本没跑起来 (proot 起不来等) */
        COMMAND_FAILED,
    }
}

/**
 * 安装下载阶段的进度。
 *
 * 下载脚本每秒往安装日志打一行「已下载字节 总字节」(见 [WorkspaceDesktopCatalog.DOWNLOAD_PROGRESS_PREFIX]),
 * 管理器解析日志尾巴里最后一行得到它。
 */
data class WorkspaceDesktopDownloadProgress(
    val doneBytes: Long,
    /** 0 表示服务器没给 Content-Length, 只能显示已下载量 */
    val totalBytes: Long = 0L,
) {
    /** 拿不到总量时是 null, UI 退回不确定进度条 */
    val percent: Int?
        get() = if (totalBytes > 0) {
            ((doneBytes.coerceAtMost(totalBytes) * 100) / totalBytes).toInt()
        } else {
            null
        }
}

data class WorkspaceDesktopState(
    val workspaceId: String = "",
    val desktop: WorkspaceDesktop = WorkspaceDesktop.NONE,
    val plan: WorkspaceDesktopPlan? = null,
    val rootfsReady: Boolean = false,
    /** null 表示该方案没有可探测的安装结果 (自定义方案), 视为已就绪 */
    val installed: Boolean? = null,
    val running: Boolean = false,
    val busy: WorkspaceDesktopAction? = null,
    /** 安装过程的实时日志尾巴 */
    val logTail: String? = null,
    /** 安装中的下载进度; 还没到下载阶段 / 安装已结束时为 null */
    val download: WorkspaceDesktopDownloadProgress? = null,
    val failure: WorkspaceDesktopFailure? = null,
    /** 0 表示还没探测过, UI 据此显示加载态 */
    val refreshedAt: Long = 0L,
) {
    val status: WorkspaceDesktopStatus
        get() = WorkspaceDesktopCatalog.statusOf(desktop, rootfsReady, installed, running)

    val loaded: Boolean get() = refreshedAt > 0L
}

/**
 * 工作区桌面的跨页面状态机: 安装 / 启动 / 停止 / 探测。
 *
 * 与终端会话管理器一样挂 AppScope, 因为「装 code-server」要跑好几分钟,
 * 用户切走页面甚至切到别的界面都不能把安装打断 (取消只能由用户显式点击)。
 *
 * 执行通道复用 AI 的工作区命令通道:
 * - 安装带 `stdin = ByteArray(0)` → 走一次性 proot, 取消时进程树被 `--kill-on-exit` 连带杀掉;
 * - 启动/停止不带 stdin → 走常驻终端会话, `nohup ... &` 拉起的服务才不会随 proot 一起退出。
 *
 * 「运行中」一律以 App 侧对 127.0.0.1:<端口> 的 HTTP 探测为准, 不信脚本自己报的状态。
 */
class WorkspaceDesktopManager internal constructor(
    private val context: Context,
    private val repository: WorkspaceRepository,
    private val appScope: AppScope,
    okHttpClient: OkHttpClient,
) {
    private val states = MutableStateFlow<Map<String, WorkspaceDesktopState>>(emptyMap())
    private val actionJobs = mutableMapOf<String, Job>()
    private val probeJobs = mutableMapOf<String, Job>()

    // 探测走独立的小客户端: 只连回环地址, 必须短超时, 否则端口没人听时一次探测要等十几秒
    private val probeClient = okHttpClient.newBuilder()
        .connectTimeout(HTTP_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(HTTP_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun observe(workspaceId: String): Flow<WorkspaceDesktopState> =
        states
            .map { it[workspaceId] ?: WorkspaceDesktopState(workspaceId = workspaceId) }
            .distinctUntilChanged()

    /** 重新探测 rootfs/安装/运行状态; 装完、启动完内部也会走一次 */
    fun refresh(workspaceId: String) {
        if (probeJobs[workspaceId]?.isActive == true) return
        probeJobs[workspaceId] = appScope.launch {
            try {
                probeNow(workspaceId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to probe desktop state for workspace $workspaceId", error)
            } finally {
                probeJobs -= workspaceId
            }
        }
    }

    /** 安装桌面: 在 rootfs 内下载/装包, 实时把安装日志尾巴推给 UI */
    fun install(workspaceId: String) = launchAction(workspaceId, WorkspaceDesktopAction.INSTALL) {
        performInstall(workspaceId)
    }

    /**
     * 供终端备份恢复使用: 在当前协程里直接安装并等待结果。
     *
     * 与 [install] 的区别是不占 AppScope 的 actionJobs, 调用方可以按「rootfs -> 桌面 -> 文件」
     * 的顺序串行恢复, 同时照常通过 [observe] 拿到安装日志和下载进度。
     */
    suspend fun installNow(workspaceId: String): WorkspaceDesktopFailure? {
        if (actionJobs[workspaceId]?.isActive == true) {
            return WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.COMMAND_FAILED)
        }
        updateState(workspaceId) {
            it.copy(busy = WorkspaceDesktopAction.INSTALL, failure = null, download = null)
        }
        var failure: WorkspaceDesktopFailure? = null
        try {
            failure = performInstall(workspaceId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Desktop install failed: workspace=$workspaceId", error)
            failure = WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.COMMAND_FAILED)
        } finally {
            updateState(workspaceId) {
                it.copy(busy = null, download = null, failure = failure)
            }
            if (currentCoroutineContext().isActive) {
                try {
                    probeNow(workspaceId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.e(TAG, "Probe after install failed: workspace=$workspaceId", error)
                }
            }
        }
        return failure
    }

    private suspend fun performInstall(workspaceId: String): WorkspaceDesktopFailure? {
        if (!repository.hasRootfs(workspaceId)) {
            return WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.ROOTFS_MISSING)
        }
        val plan = repository.desktopPlan(workspaceId)
        val script = plan?.installScript
        if (plan == null || script == null) {
            return WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.NOT_CONFIGURED)
        }

        val poller = appScope.launch { pollInstallLog(workspaceId) }
        val result = try {
            runDesktopScript(
                workspaceId = workspaceId,
                fileName = DESKTOP_INSTALL_SCRIPT,
                script = buildInstallCommand(withDownloadPreferences(script)),
                timeoutMillis = INSTALL_TIMEOUT_MS,
                // 非 null 的 stdin 让命令走一次性 proot: 取消时能确定地把整棵进程树带走
                stdin = ByteArray(0),
            )
        } finally {
            poller.cancel()
        }

        val logTail = readInstallLog(workspaceId)
        if (result.exitCode != 0) {
            return WorkspaceDesktopFailure(
                kind = WorkspaceDesktopFailure.Kind.INSTALL_FAILED,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                logTail = logTail,
            )
        }

        // 脚本报成功不算数: 必须真的探测到 (例如 code-server 缺 glibc 兼容层时能装上却起不来)
        if (plan.probeScript != null && !probeInstalled(workspaceId, plan)) {
            return WorkspaceDesktopFailure(
                kind = WorkspaceDesktopFailure.Kind.PROBE_FAILED,
                logTail = readInstallLog(workspaceId) ?: logTail,
            )
        }
        return null
    }

    /** 取消正在进行的安装 (用户显式操作) */
    fun cancelInstall(workspaceId: String) {
        actionJobs[workspaceId]?.cancel()
    }

    /** 启动桌面, 并等端口真的能响应 HTTP */
    fun start(workspaceId: String) = launchAction(workspaceId, WorkspaceDesktopAction.START) {
        val plan = repository.desktopPlan(workspaceId)
        val script = plan?.startScript
        if (plan == null || script == null || plan.port == 0) {
            return@launchAction WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.NOT_CONFIGURED)
        }
        if (!repository.hasRootfs(workspaceId)) {
            return@launchAction WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.ROOTFS_MISSING)
        }

        // 桌面启动必须「发射后不管」: 守护进程的 leader proot 要长期留守当翻译层,
        // 命令本身不会退出, 启动成败统一交给端口探测 + 日志退出码标记判定
        runDesktopScript(
            workspaceId = workspaceId,
            fileName = DESKTOP_START_SCRIPT,
            script = script,
            timeoutMillis = SCRIPT_TIMEOUT_MS,
            detached = true,
        )

        if (awaitHttpState(plan.port, wantRunning = true, workspaceId = workspaceId)) return@launchAction null
        WorkspaceDesktopFailure(
            kind = WorkspaceDesktopFailure.Kind.START_FAILED,
            port = plan.port,
            logTail = readRuntimeLog(workspaceId),
        )
    }

    /** 停止桌面, 并等端口不再响应 */
    fun stop(workspaceId: String) = launchAction(workspaceId, WorkspaceDesktopAction.STOP) {
        val plan = repository.desktopPlan(workspaceId)
        val script = plan?.stopScript
        if (plan == null || script == null) {
            return@launchAction WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.NOT_CONFIGURED)
        }

        val result = runDesktopScript(
            workspaceId = workspaceId,
            fileName = DESKTOP_STOP_SCRIPT,
            script = script,
            timeoutMillis = SCRIPT_TIMEOUT_MS,
        )
        if (result.exitCode != 0 || result.timedOut) {
            return@launchAction WorkspaceDesktopFailure(
                kind = WorkspaceDesktopFailure.Kind.STOP_FAILED,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                port = plan.port,
                logTail = readRuntimeLog(workspaceId),
            )
        }

        // pkill 只是发了信号, 是否真的停下来只能靠端口确认
        if (plan.port == 0 || awaitHttpState(plan.port, wantRunning = false)) return@launchAction null
        WorkspaceDesktopFailure(
            kind = WorkspaceDesktopFailure.Kind.STOP_FAILED,
            port = plan.port,
            logTail = readRuntimeLog(workspaceId),
        )
    }

    fun dismissFailure(workspaceId: String) {
        updateState(workspaceId) { it.copy(failure = null) }
    }

    /** 工作区被删除时调用: 取消在跑的动作并丢掉状态 */
    fun discard(workspaceId: String) {
        actionJobs.remove(workspaceId)?.cancel()
        probeJobs.remove(workspaceId)?.cancel()
        states.update { it - workspaceId }
    }

    /**
     * 桌面脚本统一「先写入工作区文件, 再用 bash 执行文件」。
     *
     * 不能把脚本文本直接当命令传: ProotShellRunner 会把命令放进 proot 进程的 argv,
     * 脚本里任何 `pkill -f <模式>` 都会把承载它的 proot 进程自己匹配上 —— proot 一死,
     * --kill-on-exit 会带走整棵进程树, 停止脚本当场被截断 (实测 vpid 1 terminated by signal 15)。
     * 写成文件后 argv 里只剩文件路径, 脚本内容再怎么写都误伤不到自己。
     *
     * [detached] = true 用于启动桌面: 守护进程的 leader proot 要留守当翻译层, 命令不会退出,
     * 因此不等结果、也不带 --kill-on-exit (否则命令一结束就把刚拉起来的桌面连根拔了)。
     */
    private suspend fun runDesktopScript(
        workspaceId: String,
        fileName: String,
        script: String,
        timeoutMillis: Long,
        stdin: ByteArray? = null,
        detached: Boolean = false,
    ): WorkspaceCommandResult {
        val path = "$DESKTOP_SCRIPT_DIR/$fileName"
        repository.writeText(workspaceId, path, script, overwrite = true)
        // 启动走 wrapper 文件: 守护进程的 leader proot 会留守当翻译层, 命令不会退出,
        // 借 wrapper 把真实脚本的退出码追进日志, 让启动失败秒级可见 (见 buildDetachedRunScript)
        val executePath = if (detached) {
            val wrapper = "$DESKTOP_SCRIPT_DIR/desktop-run.sh"
            repository.writeText(workspaceId, wrapper, buildDetachedRunScript(path), overwrite = true)
            wrapper
        } else {
            path
        }
        return repository.executeCommand(
            id = workspaceId,
            command = "bash /workspace/$executePath",
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            killOnExit = !detached,
            detachedOutputPath = if (detached) WorkspaceDesktopCatalog.LOG_PATH else null,
        )
    }

    /**
     * 「发射后不管」执行的 wrapper: 先清空旧日志 (防止上一轮的退出码标记骗过失败判断),
     * 跑真正的脚本, 再把退出码追进日志末尾。
     *
     * 启动命令本身不会退出 —— leader proot 要作为守护进程的翻译层长期留守, 因此没有进程可等,
     * 启动成败 = 端口探测 + 这条退出码标记。
     */
    private fun buildDetachedRunScript(scriptPath: String): String = buildString {
        appendLine(": > ${WorkspaceDesktopCatalog.LOG_PATH}")
        appendLine("bash /workspace/$scriptPath")
        appendLine("__airko_rc=\$?")
        appendLine(
            "printf '\\n[Air-Ko] start exit code: %s\\n' \"\$__airko_rc\" " +
                ">> ${WorkspaceDesktopCatalog.LOG_PATH}"
        )
        appendLine("exit \$__airko_rc")
    }

    /**
     * 把安装脚本包一层: 日志落盘 + 把退出码带回来。
     *
     * 一次性 proot 的执行结果里 stdout/stderr 会被截断, 而 apt/pip 的输出本来就该完整留在
     * rootfs 的日志文件里给用户回看, 所以这里统一重定向, App 侧只读文件尾。
     */
    /**
     * 把「下载加速前缀 / code-server 版本」偏好写进脚本。
     *
     * 安装脚本原本读 AIRKO_DESKTOP_* 环境变量，但 proot 用 env -i 起进程会把环境清掉，
     * 所以这里直接把值替换进脚本文本（值来自应用内设置，不是用户输入的 shell 片段）。
     */
    private fun withDownloadPreferences(script: String): String {
        val mirror = runCatching { WorkspaceDesktopPrefs.downloadMirror(context) }.getOrDefault("")
        val version = runCatching { WorkspaceDesktopPrefs.codeServerVersion(context) }.getOrDefault("")
        return script
            .replace("\${AIRKO_DESKTOP_DOWNLOAD_MIRROR:-}", mirror)
            .replace("\${AIRKO_DESKTOP_VERSION:-}", version)
    }

    private fun buildInstallCommand(script: String): String = buildString {
        appendLine(": > ${WorkspaceDesktopCatalog.INSTALL_LOG_PATH}")
        appendLine("{")
        appendLine(script)
        append("} >> ${WorkspaceDesktopCatalog.INSTALL_LOG_PATH} 2>&1")
        appendLine()
        // 安装脚本自带 set -e, 失败后要先把 errexit 关掉再取退出码, 否则这行赋值本身就会被带走
        appendLine("set +e")
        appendLine("__airko_rc=$?")
        appendLine(
            "printf '\\n[Air-Ko] install exit code: %s\\n' \"\$__airko_rc\" " +
                ">> ${WorkspaceDesktopCatalog.INSTALL_LOG_PATH}"
        )
        appendLine("exit \$__airko_rc")
    }

    private fun launchAction(
        workspaceId: String,
        action: WorkspaceDesktopAction,
        block: suspend () -> WorkspaceDesktopFailure?,
    ) {
        if (actionJobs[workspaceId]?.isActive == true) return
        updateState(workspaceId) { it.copy(busy = action, failure = null, download = null) }

        actionJobs[workspaceId] = appScope.launch {
            var failure: WorkspaceDesktopFailure? = null
            try {
                failure = block()
            } catch (error: CancellationException) {
                Log.i(TAG, "Desktop action $action cancelled: workspace=$workspaceId")
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop action $action failed: workspace=$workspaceId", error)
                failure = WorkspaceDesktopFailure(WorkspaceDesktopFailure.Kind.COMMAND_FAILED)
            } finally {
                actionJobs -= workspaceId
                updateState(workspaceId) { state ->
                    state.copy(busy = null, download = null, failure = failure ?: state.failure)
                }
                // 动作结束把「运行中」探测对一次；失败时也要对：停止等超时误报时桌面可能其实
                // 已停了，不探测就会把陈旧的「运行中」状态留在页面上
                if (currentCoroutineContext().isActive) probeNow(workspaceId)
            }
        }
    }

    private suspend fun pollInstallLog(workspaceId: String) {
        while (currentCoroutineContext().isActive) {
            readInstallLog(workspaceId)?.let { tail ->
                updateState(workspaceId) {
                    it.copy(logTail = tail, download = parseDownloadProgress(tail))
                }
            }
            delay(LOG_POLL_INTERVAL_MS)
        }
    }

    /**
     * 从安装日志尾巴里取最后一行的下载进度标记 (脚本每秒打一条, 见 DOWNLOAD_PROGRESS_PREFIX)。
     * 日志会不断追加, 所以只认最后一条; 没有标记时 (还没开始下载 / 下载已结束) 返回 null 清掉进度。
     */
    private fun parseDownloadProgress(tail: String): WorkspaceDesktopDownloadProgress? {
        val match = DOWNLOAD_PROGRESS_REGEX.findAll(tail).lastOrNull() ?: return null
        val done = match.groupValues[1].toLongOrNull() ?: return null
        val total = match.groupValues[2].toLongOrNull() ?: return null
        return WorkspaceDesktopDownloadProgress(doneBytes = done, totalBytes = total)
    }

    /** 一次性把 rootfs 状况、安装结果、端口状态都探一遍 */
    private suspend fun probeNow(workspaceId: String) {
        val desktop = repository.getById(workspaceId)?.desktopScheme() ?: WorkspaceDesktop.NONE
        val plan = repository.desktopPlan(workspaceId)
        val rootfsReady = repository.hasRootfs(workspaceId)
        val installed = if (plan?.probeScript != null && rootfsReady) {
            probeInstalled(workspaceId, plan)
        } else {
            null
        }
        val running = plan != null && rootfsReady && plan.port > 0 && probeHttpPort(plan.port)

        updateState(workspaceId) {
            it.copy(
                desktop = desktop,
                plan = plan,
                rootfsReady = rootfsReady,
                installed = installed,
                running = running,
                refreshedAt = System.currentTimeMillis(),
            )
        }
    }

    /** rootfs 内的探测脚本; 退出码 0 才算装好 */
    private suspend fun probeInstalled(workspaceId: String, plan: WorkspaceDesktopPlan): Boolean {
        val probe = plan.probeScript ?: return true
        val result = repository.executeCommand(
            id = workspaceId,
            command = probe,
            timeoutMillis = PROBE_TIMEOUT_MS,
            stdin = ByteArray(0),
        )
        return result.exitCode == 0
    }

    /**
     * 轮询端口, 直到到达期望状态。
     *
     * 启动要等进程把端口拉起来 (code-server 解包后第一次启动可能要几秒), 停止要等信号生效,
     * 所以两边都留一段窗口而不是只探一次。
     */
    private suspend fun awaitHttpState(
        port: Int,
        wantRunning: Boolean,
        // 启动时传入: 发现启动 wrapper 写下非零退出码就立刻判失败, 不干等端口超时
        workspaceId: String? = null,
    ): Boolean {
        val deadline = System.currentTimeMillis() +
            if (wantRunning) START_WAIT_MS else STOP_WAIT_MS
        while (true) {
            if (probeHttpPort(port) == wantRunning) return true
            if (wantRunning && workspaceId != null && readStartExitCode(workspaceId)?.let { it != 0 } == true) {
                return false
            }
            if (System.currentTimeMillis() >= deadline) return false
            delay(HTTP_POLL_INTERVAL_MS)
        }
    }

    private suspend fun probeHttpPort(port: Int): Boolean = withContext(Dispatchers.IO) {
        val url = WorkspaceDesktopCatalog.urlFor(port) ?: return@withContext false
        runCatching {
            probeClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                // 200、302、401 都说明端口后面站着一个 HTTP 服务; 什么响应都没有才算没起来
                response.code in 100..599
            }
        }.getOrDefault(false)
    }

    private suspend fun readRuntimeLog(workspaceId: String): String? =
        repository.readRootfsLogTail(workspaceId, WorkspaceDesktopCatalog.LOG_PATH)

    /** 读启动 wrapper 写进日志的退出码标记 (见 buildDetachedRunScript); 还没出现返回 null */
    private suspend fun readStartExitCode(workspaceId: String): Int? {
        val tail = readRuntimeLog(workspaceId) ?: return null
        return START_EXIT_REGEX.findAll(tail).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun readInstallLog(workspaceId: String): String? =
        repository.readRootfsLogTail(workspaceId, WorkspaceDesktopCatalog.INSTALL_LOG_PATH)

    private fun updateState(
        workspaceId: String,
        transform: (WorkspaceDesktopState) -> WorkspaceDesktopState,
    ) {
        states.update { current ->
            current + (workspaceId to transform(
                current[workspaceId] ?: WorkspaceDesktopState(workspaceId = workspaceId)
            ))
        }
    }

    private companion object {
        const val TAG = "WorkspaceDesktop"

        /** 安装日志里的下载进度标记行: `[airko-dl] <已下载字节> <总字节>` */
        val DOWNLOAD_PROGRESS_REGEX =
            Regex(Regex.escape(WorkspaceDesktopCatalog.DOWNLOAD_PROGRESS_PREFIX) + " (\\d+) (\\d+)")

        /** 启动 wrapper 追进日志的退出码标记 (见 buildDetachedRunScript) */
        val START_EXIT_REGEX = Regex("\\[Air-Ko] start exit code: (\\d+)")

        /** 安装的上限; apt/pip 在手机上装 jupyterlab 要几分钟, 留足余量 */
        const val INSTALL_TIMEOUT_MS = 30 * 60_000L

        /** 启停脚本本身只是发信号/拉起进程, 不该跑很久 */
        const val SCRIPT_TIMEOUT_MS = 60_000L

        /** 桌面脚本落盘目录 (相对 /workspace; 带点前缀, 尽量不打扰用户文件) */
        const val DESKTOP_SCRIPT_DIR = ".airko"
        const val DESKTOP_INSTALL_SCRIPT = "desktop-install.sh"
        const val DESKTOP_START_SCRIPT = "desktop-start.sh"
        const val DESKTOP_STOP_SCRIPT = "desktop-stop.sh"

        /** 探测脚本要冷启一次 proot */
        const val PROBE_TIMEOUT_MS = 30_000L

        /** 启动等待：proot 冷启动 + node 起服务在手机上可能要几十秒，给足时间 */
        const val START_WAIT_MS = 75_000L

        /**
         * 停止等待：websockify 退出要先关掉 WebSocket 连接、cage 偶发不吃 SIGTERM，
         * 真机上优雅退出要 10 秒量级；8 秒窗口会误报「停止失败」（桌面其实已经停了）。
         * 脚本侧的 -9 兜底（见 Catalog.stopSweep）保证最迟 ~12 秒收干净，30 秒窗口足够。
         */
        const val STOP_WAIT_MS = 30_000L

        const val LOG_POLL_INTERVAL_MS = 400L
        const val HTTP_POLL_INTERVAL_MS = 500L
        const val HTTP_PROBE_TIMEOUT_SECONDS = 2L
    }
}
