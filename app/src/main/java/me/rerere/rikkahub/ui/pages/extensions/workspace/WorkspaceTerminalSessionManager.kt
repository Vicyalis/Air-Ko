package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WorkspaceTerminalService
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns workspace terminal sessions independently from the terminal page lifecycle.
 *
 * A page only attaches a [com.termux.view.TerminalView] to the selected tab. Navigating away
 * therefore keeps every shell and its screen buffer alive; a session is finished only when its
 * tab is explicitly closed (or the shell exits by itself).
 */
class WorkspaceTerminalSessionManager internal constructor(
    context: Context,
    private val appScope: AppScope,
    private val settingsStore: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val workspaceStates = MutableStateFlow<Map<String, WorkspaceTerminalTabsState>>(emptyMap())
    private val nextTabId = AtomicLong(1)
    private val creationJobs = mutableMapOf<String, Job>()

    // 会话热池: 提前跑起来的 proot shell, 新标签页直接取, 省掉 1~3 秒冷启动.
    // 池中会话不属于任何标签页, 因此不计入 activeSessionCount, 也不会撑起前台服务.
    private val warmSessions = mutableMapOf<String, ArrayDeque<WarmSession>>()
    private val warmLock = Any()
    private val prewarmJobs = mutableMapOf<String, Job>()
    private var reclaimJob: Job? = null

    /**
     * 跨工作区的「仍在运行」会话数。已退出的 tab 会留在界面上让用户看到退出信息，
     * 但 PTY 已经没了，因此不计入。
     */
    internal val activeSessionCount: Flow<Int> = workspaceStates
        .map { states -> states.values.sumOf { state -> state.tabs.count { !it.finished } } }
        .distinctUntilChanged()

    init {
        // 有会话在跑就把进程提到前台优先级。否则用户切走应用后进程可能被系统回收，
        // PTY 全部断开——这是「放久了自动断联」的真正原因，与页面生命周期无关。
        // 服务在会话归零时自行退出，这里只负责把它拉起来。
        appScope.launch {
            activeSessionCount.collect { count ->
                if (count > 0) WorkspaceTerminalService.ensureStarted(appContext)
            }
        }

        // 热池被关掉(或调小到 0)时立刻回收已预热的会话
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.terminalPrewarmSessions }
                .distinctUntilChanged()
                .collect { target -> if (target <= 0) discardWarmSessions() }
        }
    }

    internal fun observeWorkspace(root: String): Flow<WorkspaceTerminalTabsState> =
        workspaceStates
            .map { states -> states[root] ?: WorkspaceTerminalTabsState() }
            .distinctUntilChanged()

    internal fun ensureSession(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = true, shellCompatibilityMode = shellCompatibilityMode)
    }

    internal fun createTab(root: String, shellCompatibilityMode: Boolean) {
        launchCreateTab(root = root, onlyIfEmpty = false, shellCompatibilityMode = shellCompatibilityMode)
    }

    /**
     * 取得该工作区常驻的「AI」终端会话，没有就现建一个。
     *
     * 刻意与用户自己的标签页隔离：多标签页时 AI 的目标是确定的，也不会把命令打进一个
     * 正在跑常驻前台进程（`runserver` 之类）的标签页。用户切到 AI 标签页就能实时看着 AI 敲命令。
     *
     * 上一个 AI 会话若已退出（例如超时被 Ctrl-C 打断后我们主动结束），这里会清掉它的残留标签页
     * 再建新的——不确定 shell 是否干净时，重建比复用安全。
     */
    internal suspend fun obtainAiSession(
        root: String,
        shellCompatibilityMode: Boolean,
    ): TerminalSession? {
        currentState(root).tabs
            .firstOrNull { it.isAi && !it.finished }
            ?.session
            ?.takeIf { it.isAlive }
            ?.let { return it }

        updateState(root) { state ->
            state.copy(tabs = state.tabs.filterNot { it.isAi && it.finished })
        }
        return createTab(
            root = root,
            onlyIfEmpty = false,
            shellCompatibilityMode = shellCompatibilityMode,
            isAi = true,
            select = false,
        )
    }

    /**
     * 丢弃该工作区的 AI 会话。命令被强制中断、shell 状态不再可信时调用，
     * 下一个命令会拿到一个全新的会话。
     */
    internal fun discardAiSession(root: String) {
        var discarded: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val target = state.tabs.firstOrNull { it.isAi && !it.finished } ?: return@updateState state
            discarded = target
            state.copy(
                tabs = state.tabs.map { if (it.id == target.id) it.copy(finished = true) else it },
            )
        }
        discarded?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunningSafely()
        }
    }

    internal fun selectTab(root: String, tabId: Long) {
        updateState(root) { state ->
            if (state.tabs.none { it.id == tabId }) state else state.copy(selectedTabId = tabId)
        }
    }

    internal fun closeTab(root: String, tabId: Long) {
        var closedTab: WorkspaceTerminalTab? = null
        updateState(root) { state ->
            val closedIndex = state.tabs.indexOfFirst { it.id == tabId }
            if (closedIndex < 0) return@updateState state

            closedTab = state.tabs[closedIndex]
            val remainingTabs = state.tabs.filterNot { it.id == tabId }
            val selectedTabId = if (state.selectedTabId == tabId) {
                remainingTabs.getOrNull(closedIndex)?.id
                    ?: remainingTabs.getOrNull(closedIndex - 1)?.id
            } else {
                state.selectedTabId
            }
            state.copy(
                tabs = remainingTabs,
                selectedTabId = selectedTabId,
            )
        }

        // Remove it from observable state before finishing so the finish callback cannot put it
        // back into the UI while the selected TerminalView is being disposed.
        closedTab?.let { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunningSafely()
        }
    }

    /**
     * Stops all sessions owned by [root] before its rootfs is replaced or the workspace is deleted.
     */
    internal suspend fun closeWorkspace(root: String) = withContext(Dispatchers.Main.immediate) {
        // Wait for rootfs preparation to leave its IO section before callers delete or replace the
        // same files. CancellationException is deliberately rethrown by createTab().
        creationJobs[root]?.cancelAndJoin()
        // 池里可能还留着这个工作区的预热会话: rootfs 即将被替换或删除, 它们必须一起结束
        discardWarmSessions(root)

        val state = workspaceStates.getAndUpdate { states -> states - root }[root]
            ?: return@withContext
        state.tabs.forEach { tab ->
            tab.client.terminalView = null
            tab.session.finishIfRunningSafely()
        }
    }

    /**
     * 结束所有工作区的全部会话，供前台服务通知上的「全部关闭」使用。
     */
    internal suspend fun closeAllSessions() = withContext(Dispatchers.Main.immediate) {
        val states = workspaceStates.getAndUpdate { emptyMap() }
        // 用户主动「全部关闭」时把热池也清掉, 否则下次还会立刻冒出预热好的会话
        discardWarmSessions()
        states.values.forEach { state ->
            state.tabs.forEach { tab ->
                tab.client.terminalView = null
                tab.session.finishIfRunningSafely()
            }
        }
    }

    /**
     * 预热会话池。
     *
     * 冷启动一次 proot 要 1~3 秒, 预热让第二次开标签页、下一条 AI 命令几乎瞬时完成。
     * 只在 rootfs 已就绪时执行; 池中会话不占标签页也不撑前台服务 —— 用户没在用就该让系统回收进程。
     */
    internal fun prewarm(root: String, shellCompatibilityMode: Boolean) {
        val target = settingsStore.settingsFlow.value.terminalPrewarmSessions
        if (target <= 0) return
        if (currentState(root).readiness != WorkspaceTerminalReadiness.Ready) return
        if (root in prewarmJobs) return

        lateinit var job: Job
        job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val missing = synchronized(warmLock) {
                        target - (warmSessions[root]?.count { it.shellCompatibilityMode == shellCompatibilityMode } ?: 0)
                    }
                    if (missing <= 0) break
                    val client = WorkspaceTerminalSessionClient(context = appContext)
                    // TerminalSession 构造器会创建 MainThreadHandler，要求当前线程有 Looper；
                    // 预热协程跑在 IO 线程上，必须切到主线程再创建。
                    val session = runCatching {
                        withContext(Dispatchers.Main.immediate) {
                            createWorkspaceTerminalSession(
                                context = appContext,
                                root = root,
                                client = client,
                                shellCompatibilityMode = shellCompatibilityMode,
                            )
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to prewarm terminal for workspace $root", error)
                    }.getOrNull() ?: break
                    synchronized(warmLock) {
                        warmSessions.getOrPut(root) { ArrayDeque() }
                            .addLast(
                                WarmSession(
                                    session = session,
                                    client = client,
                                    shellCompatibilityMode = shellCompatibilityMode,
                                    createdAt = System.currentTimeMillis(),
                                )
                            )
                    }
                }
                scheduleReclaim()
            } finally {
                prewarmJobs.remove(root, job)
            }
        }
        prewarmJobs[root] = job
        job.start()
    }

    /**
     * 取一个预热好的会话给新标签页用。
     *
     * 只认仍存活的会话(proot 可能已经自己退出), 顺手把过期项清出池位 —— 否则池位会被死会话占住,
     * 再也预热不出可用的会话。
     */
    private fun claimWarmSession(root: String, shellCompatibilityMode: Boolean): WarmSession? {
        val dead = mutableListOf<WarmSession>()
        val claimed = synchronized(warmLock) {
            val queue = warmSessions[root] ?: return@synchronized null
            val index = queue.indexOfFirst {
                it.shellCompatibilityMode == shellCompatibilityMode && it.session.isAlive
            }
            if (index < 0) {
                val stale = queue.filter { !it.session.isAlive || it.shellCompatibilityMode != shellCompatibilityMode }
                queue.removeAll(stale)
                dead += stale
                if (queue.isEmpty()) warmSessions.remove(root)
                return@synchronized null
            }
            val candidate = queue.removeAt(index)
            if (queue.isEmpty()) warmSessions.remove(root)
            candidate
        }
        // 关闭会话可能涉及杀进程, 放在锁外做
        dead.forEach { it.close() }
        return claimed
    }

    /** 池里的空闲会话按设置超时回收: 预热只是缓存, 不该长期占住内存和 proot 进程 */
    private fun scheduleReclaim() {
        if (reclaimJob?.isActive == true) return
        reclaimJob = appScope.launch(Dispatchers.IO) {
            while (true) {
                val idleMs = settingsStore.settingsFlow.value.terminalPrewarmIdleMinutes
                    .coerceIn(1, 24 * 60) * 60_000L
                delay(idleMs)
                val now = System.currentTimeMillis()
                val expired = mutableListOf<WarmSession>()
                synchronized(warmLock) {
                    warmSessions.values.forEach { queue ->
                        val stale = queue.filter { now - it.createdAt >= idleMs }
                        queue.removeAll(stale)
                        expired += stale
                    }
                    warmSessions.entries.removeAll { it.value.isEmpty() }
                }
                expired.forEach { it.close() }
                if (!hasWarmSessions()) break
            }
        }
    }

    private fun hasWarmSessions(): Boolean =
        synchronized(warmLock) { warmSessions.values.any { it.isNotEmpty() } }

    /** 关闭并清空热池; [root] 为 null 时清空全部工作区 */
    private fun discardWarmSessions(root: String? = null) {
        val discarded = synchronized(warmLock) {
            if (root == null) {
                warmSessions.values.flatten().also { warmSessions.clear() }
            } else {
                warmSessions.remove(root)?.toList().orEmpty()
            }
        }
        discarded.forEach { it.close() }
    }

    /** 池中会话: 已经跑起来的 proot shell, 被某个标签页取走前不绑定任何回调 */
    private class WarmSession(
        val session: TerminalSession,
        val client: WorkspaceTerminalSessionClient,
        val shellCompatibilityMode: Boolean,
        val createdAt: Long,
    ) {
        fun close() = session.finishIfRunningSafely()
    }

    private fun launchCreateTab(root: String, onlyIfEmpty: Boolean, shellCompatibilityMode: Boolean) {
        if (root in creationJobs) return

        lateinit var job: Job
        job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                createTab(root = root, onlyIfEmpty = onlyIfEmpty, shellCompatibilityMode = shellCompatibilityMode)
            } finally {
                creationJobs.remove(root, job)
            }
        }
        creationJobs[root] = job
        job.start()
    }

    private suspend fun createTab(
        root: String,
        onlyIfEmpty: Boolean,
        shellCompatibilityMode: Boolean,
        isAi: Boolean = false,
        select: Boolean = true,
    ): TerminalSession? = withContext(Dispatchers.Main.immediate) {
        val initialState = currentState(root)
        if (initialState.isCreating || (onlyIfEmpty && initialState.tabs.isNotEmpty())) {
            return@withContext null
        }
        updateState(root) { it.copy(isCreating = true) }

        // 池里有预热好的会话就直接挂成标签页: 跳过 prepare 与 proot 冷启动
        claimWarmSession(root, shellCompatibilityMode)?.let { warm ->
            registerTab(root, warm.session, warm.client, isAi, select)
            // 池中会话创建时回调还没绑定, 这里补一次当前标题
            warm.client.onTitleUpdated(warm.session.title)
            prewarm(root, shellCompatibilityMode)
            return@withContext warm.session
        }

        val prepared = if (initialState.readiness == WorkspaceTerminalReadiness.Ready) {
            true
        } else {
            try {
                withContext(Dispatchers.IO) {
                    if (!workspaceRootfsReady(appContext, root)) {
                        false
                    } else {
                        prepareWorkspaceTerminalSession(appContext, root)
                        true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to prepare terminal for workspace $root", error)
                false
            }
        }

        if (!prepared) {
            updateState(root) {
                it.copy(
                    readiness = WorkspaceTerminalReadiness.NotInstalled,
                    isCreating = false,
                )
            }
            return@withContext null
        }

        val client = WorkspaceTerminalSessionClient(context = appContext)
        val session = runCatching {
            createWorkspaceTerminalSession(
                context = appContext,
                root = root,
                client = client,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to create terminal for workspace $root", error)
        }.getOrNull()

        if (session == null) {
            updateState(root) { it.copy(isCreating = false) }
            return@withContext null
        }

        registerTab(root, session, client, isAi, select)
        // 顺手把池补满: 下一次开标签页、下一条 AI 命令就能直接命中
        prewarm(root, shellCompatibilityMode)
        session
    }

    /**
     * 把一个会话挂成一个标签页。
     *
     * 池里预热出来的会话在创建时并不知道自己会属于哪个标签页, 因此标题/退出回调在这里绑定。
     */
    private fun registerTab(
        root: String,
        session: TerminalSession,
        client: WorkspaceTerminalSessionClient,
        isAi: Boolean,
        select: Boolean,
    ) {
        val tabId = nextTabId.getAndIncrement()
        val tabNumber = currentState(root).nextTabNumber
        client.onTitleUpdated = { title -> updateTitle(root, tabId, title) }
        client.onFinished = { markFinished(root = root, tabId = tabId) }
        val tab = WorkspaceTerminalTab(
            id = tabId,
            number = tabNumber,
            session = session,
            client = client,
            isAi = isAi,
        )
        updateState(root) { state ->
            state.copy(
                tabs = state.tabs + tab,
                // AI 会话默认不抢用户的选中项：用户可能正在别的标签页里敲命令，
                // 被抢走焦点是纯粹的干扰。没有选中项时才落到它上面（页面需要一个选中项）。
                selectedTabId = if (select) tab.id else state.selectedTabId ?: tab.id,
                readiness = WorkspaceTerminalReadiness.Ready,
                isCreating = false,
                nextTabNumber = tabNumber + 1,
            )
        }
    }

    private fun updateTitle(root: String, tabId: Long, title: String?) {
        val normalizedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(title = normalizedTitle) else tab
                },
            ))
        }
    }

    private fun markFinished(root: String, tabId: Long) {
        workspaceStates.update { states ->
            val state = states[root] ?: return@update states
            if (state.tabs.none { it.id == tabId }) return@update states

            states + (root to state.copy(
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(finished = true) else tab
                },
            ))
        }
    }

    private fun currentState(root: String): WorkspaceTerminalTabsState =
        workspaceStates.value[root] ?: WorkspaceTerminalTabsState()

    private inline fun updateState(
        root: String,
        transform: (WorkspaceTerminalTabsState) -> WorkspaceTerminalTabsState,
    ) {
        workspaceStates.update { states ->
            states + (root to transform(states[root] ?: WorkspaceTerminalTabsState()))
        }
    }

    private companion object {
        const val TAG = "WorkspaceTerminalManager"
    }
}

internal data class WorkspaceTerminalTabsState(
    val tabs: List<WorkspaceTerminalTab> = emptyList(),
    val selectedTabId: Long? = null,
    val readiness: WorkspaceTerminalReadiness = WorkspaceTerminalReadiness.Loading,
    val isCreating: Boolean = false,
    val nextTabNumber: Int = 1,
)

internal data class WorkspaceTerminalTab(
    val id: Long,
    val number: Int,
    val session: TerminalSession,
    val client: WorkspaceTerminalSessionClient,
    val title: String? = null,
    val finished: Boolean = false,
    /** AI 命令专用会话；用户自己的标签页为 false。参见 [WorkspaceResidentShellRunner]。 */
    val isAi: Boolean = false,
)

internal enum class WorkspaceTerminalReadiness {
    Loading,
    Ready,
    NotInstalled,
}
