package me.rerere.rikkahub.data.sync.terminal

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDesktopFailure
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDesktopManager
import me.rerere.workspace.RootfsDistroCatalog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.WorkspaceDesktop

private const val TAG = "TerminalRestore"

enum class TerminalRestoreStage {
    IDLE,
    PREPARING,
    DOWNLOADING_ROOTFS,
    INSTALLING_DESKTOP,
    RESTORING_FILES,
    COMPLETED,
    FAILED,
}

data class TerminalRestoreState(
    val stage: TerminalRestoreStage = TerminalRestoreStage.IDLE,
    val workspaceName: String = "",
    val workspaceIndex: Int = 0,
    val workspaceCount: Int = 0,
    /** null 表示当前步骤没有可计算的百分比, UI 显示不确定进度条 */
    val percent: Int? = null,
    val error: String? = null,
) {
    val isVisible: Boolean get() = stage != TerminalRestoreStage.IDLE
    val isFinished: Boolean
        get() = stage == TerminalRestoreStage.COMPLETED || stage == TerminalRestoreStage.FAILED
}

/**
 * 备份恢复后的终端重建流程。
 *
 * 由 RouteActivity 在 Koin / Room 初始化完成后调用 [startIfNeeded] 启动:
 * 1. 按备份里的元数据补建缺失的工作区;
 * 2. 用 RootfsDistroCatalog 里同一发行版的现有下载地址安装 rootfs;
 * 3. 用 WorkspaceDesktopManager 安装同一桌面方案;
 * 4. 把 terminal/files/<id> 复制回 /workspace。
 *
 * 状态通过 [state] 暴露给全屏进度层; 失败时保留 staging, 可以 [retry] 或 [skip]。
 */
class TerminalRestoreManager(
    context: Context,
    private val repository: WorkspaceRepository,
    private val desktopManager: WorkspaceDesktopManager,
    private val appScope: AppScope,
    private val json: Json,
) {
    private val store = TerminalRestoreStore(context)
    private val _state = MutableStateFlow(TerminalRestoreState())
    val state: StateFlow<TerminalRestoreState> = _state.asStateFlow()

    private var job: Job? = null

    /** 进程启动后调用; 没有待恢复标记时什么都不做。 */
    fun startIfNeeded() {
        if (job?.isActive == true) return
        if (!store.hasPending()) return
        launchRestore()
    }

    fun retry() {
        if (job?.isActive == true) return
        if (!store.hasPending()) {
            _state.value = TerminalRestoreState()
            return
        }
        launchRestore()
    }

    /** 用户放弃本次终端恢复: 取消正在跑的任务并删除 staging, 避免下次启动重复执行。 */
    fun skip() {
        val running = job
        job = null
        running?.cancel()
        appScope.launch(Dispatchers.IO) {
            running?.join()
            store.clearPending()
            _state.value = TerminalRestoreState()
        }
    }

    /** 关闭完成/失败的全屏层; 失败时 staging 仍然保留, 可稍后 [retry]。 */
    fun dismiss() {
        if (_state.value.isFinished) {
            _state.value = TerminalRestoreState()
        }
    }

    private fun launchRestore() {
        if (!TerminalRestoreStore.isRestoring.compareAndSet(false, true)) return
        _state.value = TerminalRestoreState(stage = TerminalRestoreStage.PREPARING)
        job = appScope.launch { restoreInternal() }
    }

    private suspend fun restoreInternal() {
        try {
            val marker = withContext(Dispatchers.IO) { store.readMarker(json) }
            if (marker == null) {
                withContext(Dispatchers.IO) { store.clearPending() }
                _state.value = TerminalRestoreState()
                return
            }

            var progress: TerminalRestoreMarker = marker
            val total = marker.workspaces.size
            _state.value = TerminalRestoreState(
                stage = TerminalRestoreStage.PREPARING,
                workspaceCount = total,
            )
            val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

            marker.workspaces.forEachIndexed { index, info ->
                currentCoroutineContext().ensureActive()
                if (info.id in progress.completedWorkspaceIds) return@forEachIndexed
                _state.value = TerminalRestoreState(
                    stage = TerminalRestoreStage.PREPARING,
                    workspaceName = info.name,
                    workspaceIndex = index + 1,
                    workspaceCount = total,
                )
                progress = restoreWorkspace(
                    info = info,
                    marker = progress,
                    abi = abi,
                    index = index + 1,
                    total = total,
                )
            }

            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { store.clearPending() }
            _state.value = TerminalRestoreState(
                stage = TerminalRestoreStage.COMPLETED,
                workspaceIndex = total,
                workspaceCount = total,
                percent = 100,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Terminal restore failed", error)
            _state.value = _state.value.copy(
                stage = TerminalRestoreStage.FAILED,
                percent = null,
                error = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            TerminalRestoreStore.isRestoring.set(false)
            job = null
        }
    }

    private suspend fun restoreWorkspace(
        info: TerminalWorkspaceInfo,
        marker: TerminalRestoreMarker,
        abi: String,
        index: Int,
        total: Int,
    ): TerminalRestoreMarker {
        var progress = marker
        _state.update {
            it.copy(
                stage = TerminalRestoreStage.PREPARING,
                workspaceName = info.name,
                workspaceIndex = index,
                workspaceCount = total,
                percent = null,
                error = null,
            )
        }

        val created = repository.getById(info.id) == null
        if (created) {
            repository.createRestored(
                id = info.id,
                name = info.name,
                root = info.root,
                desktop = WorkspaceDesktop.fromId(info.desktop),
                desktopCustomCommand = info.desktopCustomCommand,
                desktopCustomPort = info.desktopCustomPort,
                shellCompatibilityMode = info.shellCompatibilityMode,
            )
            progress = progress.copy(createdWorkspaceIds = progress.createdWorkspaceIds + info.id)
            writeMarker(progress)
        }

        val workspace = repository.getById(info.id)
            ?: error("Workspace not found after restore: ${info.name}")

        if (!repository.hasRootfs(workspace.id)) {
            val url = resolveRootfsUrl(info.distroId, abi)
            _state.update { it.copy(stage = TerminalRestoreStage.DOWNLOADING_ROOTFS, percent = 0) }
            val installed = repository.installRootfs(workspace.id, url) { installProgress ->
                updateRootfsProgress(installProgress)
            }
            check(installed) { "Rootfs install did not start for ${info.name}" }
            progress = progress.copy(installedRootfsIds = progress.installedRootfsIds + info.id)
            writeMarker(progress)
        }

        val desktop = workspace.desktopScheme()
        val shouldInstallDesktop = desktop != WorkspaceDesktop.NONE &&
            (info.id in progress.createdWorkspaceIds || info.id in progress.installedRootfsIds)
        if (shouldInstallDesktop) {
            _state.update { it.copy(stage = TerminalRestoreStage.INSTALLING_DESKTOP, percent = null) }
            installDesktopWithProgress(workspace.id)
        }

        _state.update { it.copy(stage = TerminalRestoreStage.RESTORING_FILES, percent = 0) }
        val source = store.workspaceFiles(info.id)
        if (source.isDirectory) {
            repository.restoreWorkspaceFiles(workspace.id, source) { copied, totalBytes ->
                val percent = if (totalBytes > 0) {
                    ((copied * 100) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                    100
                }
                _state.update { it.copy(stage = TerminalRestoreStage.RESTORING_FILES, percent = percent) }
            }
        } else {
            _state.update { it.copy(stage = TerminalRestoreStage.RESTORING_FILES, percent = 100) }
        }

        progress = progress.copy(completedWorkspaceIds = progress.completedWorkspaceIds + info.id)
        writeMarker(progress)
        return progress
    }

    private suspend fun installDesktopWithProgress(workspaceId: String) {
        val progressJob = appScope.launch {
            desktopManager.observe(workspaceId).collect { desktopState ->
                desktopState.download?.percent?.let { percent ->
                    _state.update {
                        it.copy(stage = TerminalRestoreStage.INSTALLING_DESKTOP, percent = percent)
                    }
                }
            }
        }
        try {
            val failure = desktopManager.installNow(workspaceId)
            // 自定义桌面没填命令时没有可执行的安装脚本, 视为「没有桌面要装」, 不阻塞文件恢复。
            if (failure != null && failure.kind != WorkspaceDesktopFailure.Kind.NOT_CONFIGURED) {
                throw IllegalStateException(failure.toMessage())
            }
        } finally {
            progressJob.cancel()
        }
    }

    private fun updateRootfsProgress(progress: RootfsInstallProgress) {
        val percent = when (progress.stage) {
            RootfsInstallStage.DOWNLOADING -> progress.totalBytes?.takeIf { it > 0 }?.let {
                ((progress.bytesRead * 100) / it).toInt().coerceIn(0, 100)
            }

            RootfsInstallStage.VERIFYING, RootfsInstallStage.EXTRACTING -> null
            RootfsInstallStage.INSTALLED -> 100
        }
        _state.update {
            it.copy(stage = TerminalRestoreStage.DOWNLOADING_ROOTFS, percent = percent)
        }
    }

    private suspend fun writeMarker(marker: TerminalRestoreMarker) {
        withContext(Dispatchers.IO) {
            store.writeMarker(json, marker)
        }
    }

    private fun resolveRootfsUrl(distroId: String?, abi: String): String {
        val distro = distroId?.let { RootfsDistroCatalog.find(it) }
        val preferred = distro?.let { RootfsDistroCatalog.preferredUrl(it, abi) }
        val fallback = RootfsDistroCatalog.defaultUrl(abi)
        return (preferred ?: fallback).ifBlank {
            error("No Linux rootfs image is available for this device")
        }
    }

    private fun WorkspaceDesktopFailure.toMessage(): String = buildString {
        append("Desktop install failed: ")
        append(kind.name.lowercase())
        exitCode?.let { append(" (exit code ").append(it).append(')') }
        if (timedOut) append(" (timed out)")
    }
}
