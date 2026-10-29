package me.rerere.rikkahub.data.plugin

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Air-Ko 插件清单。
 *
 * 插件是「提示词/工作流包」：安装后只把 [prompt] 追加到系统提示词，
 * 不包含也不执行任何 Node/JS 代码。字段全部带默认值，坏 JSON 交给 [parsePluginManifest] 校验。
 */
@Serializable
data class PluginManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val homepage: String = "",
    val prompt: String = "",
    /** 插件依赖 Harness 调参模式时置 true，仅在界面上标记，不改变注入行为 */
    val minHarness: Boolean = false,
)

/** 已安装插件 + 它在设置里的启用状态。 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    val enabled: Boolean,
)

/** 读不出来的插件文件，列表页会提示用户，而不是静默当作没有插件。 */
data class PluginLoadError(
    val fileName: String,
    val message: String,
)

enum class PluginErrorReason {
    INVALID_URL,
    NETWORK,
    HTTP_STATUS,
    TOO_LARGE,
    INVALID_JSON,
    INVALID_MANIFEST,
    IO,
}

class PluginException(
    val reason: PluginErrorReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** id 同时是文件名，只允许字母数字、`-`、`_`，避免路径穿越。 */
private val PLUGIN_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

private const val MAX_NAME_LENGTH = 120
private const val MAX_VERSION_LENGTH = 32
private const val MAX_AUTHOR_LENGTH = 120
private const val MAX_DESCRIPTION_LENGTH = 1_000
private const val MAX_PROMPT_LENGTH = 64_000
private const val MAX_HOMEPAGE_LENGTH = 2_048

/**
 * 解析并校验插件清单。JSON 不合法、id 非法或必填字段缺失都会抛 [PluginException]。
 */
internal fun parsePluginManifest(text: String): PluginManifest {
    val manifest = try {
        // 有些托管站点会给 JSON 加 UTF-8 BOM，解析前先去掉
        JsonInstant.decodeFromString<PluginManifest>(text.removePrefix("\uFEFF"))
    } catch (e: Exception) {
        throw PluginException(PluginErrorReason.INVALID_JSON, "Invalid plugin manifest JSON", e)
    }
    return manifest.validated()
}

private fun PluginManifest.validated(): PluginManifest {
    val trimmedId = id.trim()
    if (!PLUGIN_ID_REGEX.matches(trimmedId)) {
        throw PluginException(
            PluginErrorReason.INVALID_MANIFEST,
            "id must contain only letters, digits, '-' or '_' (max 64 chars)",
        )
    }
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "name is required")
    }
    if (trimmedName.length > MAX_NAME_LENGTH) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "name is too long")
    }
    val trimmedVersion = version.trim().ifEmpty { "1.0.0" }
    if (trimmedVersion.length > MAX_VERSION_LENGTH) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "version is too long")
    }
    val trimmedAuthor = author.trim()
    if (trimmedAuthor.length > MAX_AUTHOR_LENGTH) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "author is too long")
    }
    val trimmedDescription = description.trim()
    if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "description is too long")
    }
    val trimmedHomepage = homepage.trim()
    if (trimmedHomepage.length > MAX_HOMEPAGE_LENGTH) {
        throw PluginException(PluginErrorReason.INVALID_MANIFEST, "homepage is too long")
    }
    if (trimmedHomepage.isNotEmpty()) {
        val url = trimmedHomepage.toHttpUrlOrNull()
        if (url == null || (url.scheme != "http" && url.scheme != "https")) {
            throw PluginException(PluginErrorReason.INVALID_MANIFEST, "homepage must be an http(s) URL")
        }
    }
    val trimmedPrompt = prompt.trim()
    if (trimmedPrompt.length > MAX_PROMPT_LENGTH) {
        throw PluginException(PluginErrorReason.TOO_LARGE, "prompt is too long")
    }
    return copy(
        id = trimmedId,
        name = trimmedName,
        version = trimmedVersion,
        description = trimmedDescription,
        author = trimmedAuthor,
        homepage = trimmedHomepage,
        prompt = trimmedPrompt,
    )
}

/**
 * 插件的安装、启用与读取。
 *
 * 文件落在 `filesDir/plugins/<id>.json`，启用状态存在 [me.rerere.rikkahub.data.datastore.Settings.enabledPluginIds]。
 * 列表由文件版本号 + 设置流驱动，安装/删除后不需要手动刷新界面。
 */
class PluginManager(
    context: Context,
    client: OkHttpClient,
    private val settingsStore: SettingsStore,
    appScope: AppScope,
) {
    companion object {
        private const val TAG = "PluginManager"

        /** 单个插件文件上限，避免用户误装一个几 MB 的提示词包 */
        const val MAX_PLUGIN_BYTES = 256 * 1024L
    }

    private val appContext = context.applicationContext

    // 插件只有 256 KB，给一个短超时，避免坏链接让页面转十分钟
    private val downloadClient = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // 文件增删时自增，驱动列表重新读盘
    private val revision = MutableStateFlow(0L)

    private val _installed = MutableStateFlow<List<InstalledPlugin>>(emptyList())

    /** 当前已安装插件（带启用状态）。由 [listFlow] 在应用作用域里持续更新。 */
    val installed: StateFlow<List<InstalledPlugin>> = _installed.asStateFlow()

    private val _loadErrors = MutableStateFlow<List<PluginLoadError>>(emptyList())
    val loadErrors: StateFlow<List<PluginLoadError>> = _loadErrors.asStateFlow()

    /**
     * 读取插件目录并按启用状态合并。坏文件会被跳过，错误记录在 [loadErrors]。
     * 只有设置里的启用集合变化或文件增删时才会重新读盘。
     */
    fun listFlow(): Flow<List<InstalledPlugin>> =
        combine(revision, settingsStore.settingsFlow) { _, settings -> settings.enabledPluginIds }
            .distinctUntilChanged()
            .map { enabledIds -> readInstalled(enabledIds) }
            .flowOn(Dispatchers.IO)

    init {
        appScope.launch {
            listFlow().collect { _installed.value = it }
        }
    }

    /** 目录被外部改动后调用，强制重新读盘。 */
    fun refresh() {
        revision.value++
    }

    fun pluginsDir(): File {
        val dir = appContext.filesDir.resolve(FileFolders.PLUGINS)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun pluginFile(id: String): File = pluginsDir().resolve("$id.json")

    /** 从 URL 下载并安装。只接受 http/https，响应体超过 [MAX_PLUGIN_BYTES] 直接失败。 */
    suspend fun installFromUrl(url: String): Result<InstalledPlugin> = try {
        Result.success(installFromUrlOrThrow(url))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** 直接安装一段 JSON（示例插件、剪贴板导入等）。 */
    suspend fun installFromJson(json: String): Result<InstalledPlugin> = try {
        Result.success(installText(json))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** 删除插件文件并清理启用状态。文件本来就不存在时也会清理残留 id。 */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        if (!PLUGIN_ID_REGEX.matches(id)) return@withContext false
        val file = pluginFile(id)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            revision.value++
            settingsStore.update { settings ->
                settings.copy(enabledPluginIds = settings.enabledPluginIds - id)
            }
        } else {
            Log.w(TAG, "delete: failed to delete ${file.absolutePath}")
        }
        deleted
    }

    /** 启用/禁用插件。启用状态只存在设置里，插件文件保持不动。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        if (!PLUGIN_ID_REGEX.matches(id)) return
        settingsStore.update { settings ->
            val ids = if (enabled) {
                settings.enabledPluginIds + id
            } else {
                settings.enabledPluginIds - id
            }
            if (ids == settings.enabledPluginIds) settings else settings.copy(enabledPluginIds = ids)
        }
    }

    /**
     * 生成循环用的插件提示词片段。
     *
     * 只在 [enabledIds] 非空时读盘；空 prompt 和读不出来的插件静默跳过。
     * 返回的文本以空行开头，可以直接 append 到系统提示词末尾。
     */
    suspend fun buildPromptSection(enabledIds: Set<String>): String {
        if (enabledIds.isEmpty()) return ""
        return withContext(Dispatchers.IO) {
            buildString {
                enabledIds.sorted().forEach { id ->
                    val manifest = readManifestOrNull(id) ?: return@forEach
                    val prompt = manifest.prompt.trim()
                    if (prompt.isEmpty()) return@forEach
                    appendLine()
                    appendLine()
                    append("## Plugin: ")
                    append(manifest.name)
                    appendLine()
                    append(prompt)
                }
            }
        }
    }

    private suspend fun installFromUrlOrThrow(url: String): InstalledPlugin {
        val target = url.trim().toHttpUrlOrNull()
            ?: throw PluginException(PluginErrorReason.INVALID_URL, "Only http/https URLs are supported")
        if (target.scheme != "http" && target.scheme != "https") {
            throw PluginException(PluginErrorReason.INVALID_URL, "Only http/https URLs are supported")
        }
        val text = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(target).get().build()
                downloadClient.newCall(request).execute().use { result ->
                    if (!result.isSuccessful) {
                        throw PluginException(
                            PluginErrorReason.HTTP_STATUS,
                            "HTTP ${result.code} ${result.message}".trim(),
                        )
                    }
                    result.body.readBytesLimited(MAX_PLUGIN_BYTES).toString(Charsets.UTF_8)
                }
            } catch (e: PluginException) {
                throw e
            } catch (e: IOException) {
                throw PluginException(PluginErrorReason.NETWORK, e.message ?: "Network error", e)
            }
        }
        return installText(text)
    }

    private suspend fun installText(text: String): InstalledPlugin = withContext(Dispatchers.IO) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_PLUGIN_BYTES) {
            throw PluginException(PluginErrorReason.TOO_LARGE, "Plugin manifest is larger than 256 KB")
        }
        val manifest = parsePluginManifest(text)
        writeManifest(manifest)
        // 新装插件默认启用，否则用户装完看不到任何效果
        settingsStore.update { settings ->
            settings.copy(enabledPluginIds = settings.enabledPluginIds + manifest.id)
        }
        InstalledPlugin(manifest = manifest, enabled = true)
    }

    private fun writeManifest(manifest: PluginManifest) {
        val dir = pluginsDir()
        val target = dir.resolve("${manifest.id}.json")
        val temp = dir.resolve(".${manifest.id}.json.tmp")
        try {
            temp.writeText(JsonInstantPretty.encodeToString(manifest))
            if (!temp.renameTo(target)) {
                // 有些文件系统（包括部分 Windows 环境）不允许 rename 覆盖已存在的文件
                if (target.exists() && target.delete() && temp.renameTo(target)) {
                    // 第二次 rename 成功
                } else {
                    throw PluginException(PluginErrorReason.IO, "Failed to write ${target.name}")
                }
            }
        } catch (e: IOException) {
            throw PluginException(PluginErrorReason.IO, e.message ?: "Failed to write plugin file", e)
        } finally {
            if (temp.exists()) temp.delete()
        }
        revision.value++
    }

    private fun readInstalled(enabledIds: Set<String>): List<InstalledPlugin> {
        val dir = pluginsDir()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return emptyList()
        val errors = mutableListOf<PluginLoadError>()
        val plugins = files.mapNotNull { file ->
            try {
                val manifest = readManifest(file)
                InstalledPlugin(manifest = manifest, enabled = manifest.id in enabledIds)
            } catch (e: PluginException) {
                Log.w(TAG, "readInstalled: skip broken plugin ${file.name}: ${e.message}")
                errors += PluginLoadError(file.name, e.message ?: "Invalid plugin")
                null
            } catch (e: Exception) {
                Log.w(TAG, "readInstalled: skip broken plugin ${file.name}", e)
                errors += PluginLoadError(file.name, e.message ?: e::class.java.simpleName)
                null
            }
        }.sortedBy { it.manifest.name.lowercase() }
        _loadErrors.value = errors
        return plugins
    }

    private fun readManifestOrNull(id: String): PluginManifest? {
        if (!PLUGIN_ID_REGEX.matches(id)) return null
        return try {
            readManifest(pluginFile(id))
        } catch (e: Exception) {
            Log.w(TAG, "buildPromptSection: skip plugin $id", e)
            null
        }
    }

    private fun readManifest(file: File): PluginManifest {
        if (!file.isFile) {
            throw PluginException(PluginErrorReason.IO, "Plugin file does not exist")
        }
        if (file.length() > MAX_PLUGIN_BYTES) {
            throw PluginException(PluginErrorReason.TOO_LARGE, "Plugin file is larger than 256 KB")
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            throw PluginException(PluginErrorReason.IO, e.message ?: "Failed to read plugin file", e)
        }
        val manifest = parsePluginManifest(text)
        // 文件按 id 命名，文件名和清单不一致说明文件被手动改过，按坏文件处理
        if (manifest.id != file.nameWithoutExtension) {
            throw PluginException(
                PluginErrorReason.INVALID_MANIFEST,
                "File name does not match plugin id '${manifest.id}'",
            )
        }
        return manifest
    }
}

/** 分块读取响应体，超过上限立刻失败，避免先把整个响应读进内存。 */
private fun ResponseBody.readBytesLimited(limit: Long): ByteArray {
    if (contentLength() > limit) {
        throw PluginException(PluginErrorReason.TOO_LARGE, "Plugin manifest is larger than 256 KB")
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    byteStream().use { input ->
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            if (output.size() > limit) {
                throw PluginException(PluginErrorReason.TOO_LARGE, "Plugin manifest is larger than 256 KB")
            }
        }
    }
    return output.toByteArray()
}
