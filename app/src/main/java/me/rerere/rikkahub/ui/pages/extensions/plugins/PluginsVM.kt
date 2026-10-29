package me.rerere.rikkahub.ui.pages.extensions.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginErrorReason
import me.rerere.rikkahub.data.plugin.PluginException
import me.rerere.rikkahub.data.plugin.PluginLoadError
import me.rerere.rikkahub.data.plugin.PluginManager

/** 安装动作的状态机，失败原因交给页面翻成本地化文案。 */
sealed interface PluginInstallState {
    data object Idle : PluginInstallState

    data object Running : PluginInstallState

    data class Success(val pluginName: String) : PluginInstallState

    data class Failed(val reason: PluginErrorReason?, val detail: String) : PluginInstallState
}

class PluginsVM(
    private val pluginManager: PluginManager,
) : ViewModel() {
    val plugins: StateFlow<List<InstalledPlugin>> = pluginManager.installed
    val loadErrors: StateFlow<List<PluginLoadError>> = pluginManager.loadErrors

    private val _installState = MutableStateFlow<PluginInstallState>(PluginInstallState.Idle)
    val installState: StateFlow<PluginInstallState> = _installState.asStateFlow()

    fun installFromUrl(url: String) {
        if (url.isBlank() || _installState.value is PluginInstallState.Running) return
        viewModelScope.launch {
            _installState.value = PluginInstallState.Running
            _installState.value = pluginManager.installFromUrl(url).fold(
                onSuccess = { PluginInstallState.Success(it.manifest.name) },
                onFailure = { it.toInstallFailure() },
            )
        }
    }

    fun installSample() {
        if (_installState.value is PluginInstallState.Running) return
        viewModelScope.launch {
            _installState.value = PluginInstallState.Running
            _installState.value = pluginManager.installFromJson(SAMPLE_PLUGIN_JSON).fold(
                onSuccess = { PluginInstallState.Success(it.manifest.name) },
                onFailure = { it.toInstallFailure() },
            )
        }
    }

    fun dismissInstallState() {
        if (_installState.value !is PluginInstallState.Running) {
            _installState.value = PluginInstallState.Idle
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { pluginManager.setEnabled(id, enabled) }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            pluginManager.delete(id)
        }
    }

    fun refresh() {
        pluginManager.refresh()
    }
}

private fun Throwable.toInstallFailure(): PluginInstallState.Failed = when (this) {
    is PluginException -> PluginInstallState.Failed(
        reason = reason,
        detail = message.orEmpty(),
    )

    else -> PluginInstallState.Failed(
        reason = null,
        detail = message ?: this::class.java.simpleName,
    )
}
