package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.WorkspaceDesktop

class WorkspaceVM(
    private val repository: WorkspaceRepository,
    private val terminalSessionManager: WorkspaceTerminalSessionManager,
    private val desktopManager: WorkspaceDesktopManager,
) : ViewModel() {
    val workspaces = repository.listFlow()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun create(
        name: String,
        desktop: WorkspaceDesktop = WorkspaceDesktop.NONE,
        customCommand: String = "",
        customPort: Int = 0,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.create(
                    name = name,
                    desktop = desktop,
                    desktopCustomCommand = customCommand,
                    desktopCustomPort = customPort,
                )
            }
        }
    }

    fun rename(workspace: WorkspaceEntity, name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspace.id, name) }
        }
    }

    /**
     * 编辑弹窗同时改名字和桌面方案。必须在一个协程里顺序执行:
     * [WorkspaceRepository.rename] 是整行 upsert, 和桌面列的 UPDATE 并发会互相覆盖。
     */
    fun updateWorkspace(
        workspace: WorkspaceEntity,
        name: String,
        desktop: WorkspaceDesktop,
        customCommand: String,
        customPort: Int,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.rename(workspace.id, name)
                repository.updateDesktop(workspace.id, desktop, customCommand, customPort)
            }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            terminalSessionManager.closeWorkspace(workspace.root)
            // 安装可能还在跑, 工作区都要删了就别留着动作和状态
            desktopManager.discard(workspace.id)
            repository.delete(workspace.id)
        }
    }
}
