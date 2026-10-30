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

    /** [key] 是触发安装的条目（url / sample / 商店 id / all），页面用它显示单项进度。 */
    data class Running(val key: String? = null) : PluginInstallState

    data class Success(
        val pluginName: String,
        val count: Int = 1,
        val key: String? = null,
    ) : PluginInstallState

    data class Failed(
        val reason: PluginErrorReason?,
        val detail: String,
        val key: String? = null,
    ) : PluginInstallState
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
            _installState.value = PluginInstallState.Running("url")
            _installState.value = pluginManager.installFromUrl(url).fold(
                onSuccess = { PluginInstallState.Success(it.manifest.name, key = "url") },
                onFailure = { it.toInstallFailure("url") },
            )
        }
    }

    fun installSample() {
        if (_installState.value is PluginInstallState.Running) return
        viewModelScope.launch {
            _installState.value = PluginInstallState.Running("sample")
            _installState.value = pluginManager.installFromJson(SAMPLE_PLUGIN_JSON).fold(
                onSuccess = { PluginInstallState.Success(it.manifest.name, key = "sample") },
                onFailure = { it.toInstallFailure("sample") },
            )
        }
    }

    /** 安装商店里的内置移植插件：资源随 APK 打包，点一下即安装并自动启用。 */
    fun installBundled(key: String, assetPath: String) {
        if (_installState.value is PluginInstallState.Running) return
        viewModelScope.launch {
            _installState.value = PluginInstallState.Running(key)
            _installState.value = pluginManager.installFromAsset(assetPath).fold(
                onSuccess = { PluginInstallState.Success(it.manifest.name, key = key) },
                onFailure = { it.toInstallFailure(key) },
            )
        }
    }

    /** 一键安装全部尚未安装的商店插件。 */
    fun installAllBundled(entries: List<RecommendedHarnessPlugin>) {
        if (_installState.value is PluginInstallState.Running) return
        viewModelScope.launch {
            val missing = entries.filter { entry ->
                pluginManager.installed.value.none { it.manifest.id == entry.id }
            }
            if (missing.isEmpty()) return@launch

            _installState.value = PluginInstallState.Running("all")
            var installed = 0
            var lastFailure: PluginInstallState.Failed? = null
            for (entry in missing) {
                _installState.value = PluginInstallState.Running(entry.id)
                pluginManager.installFromAsset(entry.assetPath).fold(
                    onSuccess = { installed++ },
                    onFailure = { lastFailure = it.toInstallFailure(entry.id) },
                )
            }
            _installState.value = lastFailure ?: PluginInstallState.Success(
                pluginName = "",
                count = installed,
                key = "all",
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

private fun Throwable.toInstallFailure(key: String? = null): PluginInstallState.Failed = when (this) {
    is PluginException -> PluginInstallState.Failed(
        reason = reason,
        detail = message.orEmpty(),
        key = key,
    )

    else -> PluginInstallState.Failed(
        reason = null,
        detail = message ?: this::class.java.simpleName,
        key = key,
    )
}
