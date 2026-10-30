package me.rerere.rikkahub.ui.pages.extensions.recommend

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.recommend.RecommendEntry
import me.rerere.rikkahub.data.recommend.RecommendIndexRepository
import me.rerere.rikkahub.data.recommend.RecommendInstallResult
import me.rerere.rikkahub.data.recommend.RecommendInstaller
import me.rerere.rikkahub.data.recommend.RecommendSort
import me.rerere.rikkahub.data.recommend.RecommendSourceStore
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceManager

/** 推荐列表页 + 源管理页共用的状态源（列表本身纯函数式过滤/排序，不进 VM）。 */
class RecommendListVM(
    private val repository: RecommendIndexRepository,
    private val sourceStore: RecommendSourceStore,
) : ViewModel() {
    val sources = sourceStore.sources
    val sort = sourceStore.sort
    val installed = sourceStore.installed

    fun feedFor(type: RecommendType) = repository.stateFor(type)

    fun ensureLoaded(type: RecommendType) {
        viewModelScope.launch { repository.ensureLoaded(type) }
    }

    fun refresh(type: RecommendType) {
        viewModelScope.launch { repository.refresh(type) }
    }

    fun setSort(value: RecommendSort) {
        viewModelScope.launch { sourceStore.setSort(value) }
    }
}

/** 源管理页的一次性提示，交给页面翻成本地化文案。 */
enum class RecommendSourceFeedback {
    ADDED,
    UPDATED,
    REMOVED,
    RESET,
    INVALID_URL,
    DUPLICATE_URL,
    NOT_FOUND,
}

class RecommendSourcesVM(
    private val sourceStore: RecommendSourceStore,
    private val repository: RecommendIndexRepository,
) : ViewModel() {
    val sources = sourceStore.sources
    val sort = sourceStore.sort

    private val _feedback = MutableStateFlow<RecommendSourceFeedback?>(null)
    val feedback = _feedback.asStateFlow()

    fun consumeFeedback() {
        _feedback.value = null
    }

    fun add(name: String, url: String) {
        viewModelScope.launch {
            sourceStore.addSource(name, url).fold(
                onSuccess = {
                    _feedback.value = RecommendSourceFeedback.ADDED
                    repository.invalidate()
                },
                onFailure = { _feedback.value = it.toFeedback() },
            )
        }
    }

    fun update(id: String, name: String, url: String) {
        viewModelScope.launch {
            sourceStore.updateSource(id, name, url).fold(
                onSuccess = {
                    _feedback.value = RecommendSourceFeedback.UPDATED
                    repository.invalidate()
                },
                onFailure = { _feedback.value = it.toFeedback() },
            )
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            sourceStore.removeSource(id)
            _feedback.value = RecommendSourceFeedback.REMOVED
            repository.invalidate()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            sourceStore.setSourceEnabled(id, enabled)
            repository.invalidate()
        }
    }

    fun move(from: Int, to: Int) {
        viewModelScope.launch { sourceStore.moveSource(from, to) }
    }

    fun moveBy(id: String, offset: Int) {
        viewModelScope.launch { sourceStore.moveSourceBy(id, offset) }
    }

    fun setSort(value: RecommendSort) {
        viewModelScope.launch { sourceStore.setSort(value) }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            sourceStore.resetToDefaults()
            _feedback.value = RecommendSourceFeedback.RESET
            repository.invalidate()
        }
    }

    fun refreshAll() {
        repository.invalidate()
    }
}

private fun Throwable.toFeedback(): RecommendSourceFeedback = when (message) {
    "invalid_url" -> RecommendSourceFeedback.INVALID_URL
    "duplicate_url" -> RecommendSourceFeedback.DUPLICATE_URL
    "source_not_found" -> RecommendSourceFeedback.NOT_FOUND
    else -> RecommendSourceFeedback.NOT_FOUND
}

/** 安装动作的状态机：UI 只做展示，失败原因都带上具体信息，不静默。 */
sealed interface RecommendInstallState {
    data object Idle : RecommendInstallState

    data class Running(val entryName: String) : RecommendInstallState

    data class Finished(val result: RecommendInstallResult) : RecommendInstallState
}

/** 在「内置终端」里跑命令的状态。 */
sealed interface RecommendTerminalState {
    data object Idle : RecommendTerminalState

    /** 还没有装了 rootfs 的可用工作区 */
    data object Unavailable : RecommendTerminalState

    data class Running(val command: String, val workspaceName: String) : RecommendTerminalState

    data class Finished(
        val command: String,
        val output: String,
        val exitCode: Int,
        val timedOut: Boolean,
    ) : RecommendTerminalState

    data class Failed(val command: String, val message: String) : RecommendTerminalState
}

class RecommendInstallVM(
    private val type: RecommendType,
    private val entryId: String,
    private val repository: RecommendIndexRepository,
    private val sourceStore: RecommendSourceStore,
    private val installer: RecommendInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceManager: WorkspaceManager,
) : ViewModel() {
    private val identity = "${type.wire}:$entryId"

    val entry: StateFlow<RecommendEntry?> = repository.stateFor(type)
        .map { it.findEntry(type, entryId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.findEntry(type, entryId))

    val installed: StateFlow<Boolean> = sourceStore.installed
        .map { identity in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, identity in sourceStore.installed.value)

    val confirmed: StateFlow<Boolean> = sourceStore.confirmed
        .map { identity in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, identity in sourceStore.confirmed.value)

    private val _installState = MutableStateFlow<RecommendInstallState>(RecommendInstallState.Idle)
    val installState = _installState.asStateFlow()

    private val _terminalState = MutableStateFlow<RecommendTerminalState>(RecommendTerminalState.Idle)
    val terminalState = _terminalState.asStateFlow()

    private val _workspaceName = MutableStateFlow<String?>(null)

    /** 可用于跑部署命令的工作区名；null 表示还没有可用的工作区终端 */
    val workspaceName = _workspaceName.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureLoaded(type)
        }
        viewModelScope.launch {
            _workspaceName.value = withContext(Dispatchers.IO) { findWorkspace()?.name }
        }
    }

    fun resetInstallState() {
        _installState.value = RecommendInstallState.Idle
    }

    /** 用户确认过安全提示（首次安装必须确认，之后不再反复弹强提示）。 */
    fun confirmSecurityNotice() {
        viewModelScope.launch { sourceStore.markConfirmed(identity) }
    }

    fun install(target: RecommendEntry? = null) {
        val current = target ?: entry.value ?: return
        if (_installState.value is RecommendInstallState.Running) return
        viewModelScope.launch {
            _installState.value = RecommendInstallState.Running(current.name)
            val result = installer.install(current)
            if (result.success) {
                sourceStore.markInstalled(identity)
                sourceStore.markConfirmed(identity)
            }
            _installState.value = RecommendInstallState.Finished(result)
        }
    }

    /** 在常驻的工作区终端里执行命令（部署讲解里的检测/装依赖都走这里）。 */
    fun runInTerminal(command: String) {
        if (command.isBlank()) return
        if (_terminalState.value is RecommendTerminalState.Running) return
        viewModelScope.launch {
            val workspace = withContext(Dispatchers.IO) { findWorkspace() }
            if (workspace == null) {
                _terminalState.value = RecommendTerminalState.Unavailable
                return@launch
            }
            _terminalState.value = RecommendTerminalState.Running(command, workspace.name)
            _terminalState.value = runCatching {
                workspaceRepository.executeCommand(
                    id = workspace.id,
                    command = command,
                    timeoutMillis = TERMINAL_TIMEOUT_MS,
                )
            }.fold(
                onSuccess = { result ->
                    RecommendTerminalState.Finished(
                        command = command,
                        output = buildString {
                            if (result.stdout.isNotBlank()) append(result.stdout.trimEnd())
                            if (result.stderr.isNotBlank()) {
                                if (isNotEmpty()) appendLine()
                                append(result.stderr.trimEnd())
                            }
                        }.ifBlank { "(没有输出)" },
                        exitCode = result.exitCode,
                        timedOut = result.timedOut,
                    )
                },
                onFailure = { error ->
                    RecommendTerminalState.Failed(command, error.message ?: error::class.java.simpleName)
                },
            )
        }
    }

    /** 挑一个已经装好 rootfs 的工作区；没有就返回 null，由 UI 引导用户去工作区页。 */
    private suspend fun findWorkspace(): WorkspaceEntity? =
        workspaceRepository.listFlow().first()
            .firstOrNull { runCatching { workspaceManager.hasRootfs(it.root) }.getOrDefault(false) }

    private companion object {
        /** 装包/拉镜像可能很慢，给足时间；超时会由终端里的 Ctrl-C 兜底 */
        const val TERMINAL_TIMEOUT_MS = 300_000L
    }
}
