package me.rerere.rikkahub.data.recommend

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.isValidMcpServerName
import me.rerere.rikkahub.data.ai.mcp.sanitizeMcpServerName
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.ai.mcp.uniqueMcpServerName
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 安装结果。
 *
 * [steps] 是给用户看的执行流水（下载了多少字节、sha256 是哪个、落到了哪个目录），
 * 安装这种「会改变 AI 行为」的操作，必须让用户看得见每一步做了什么。
 */
data class RecommendInstallResult(
    val success: Boolean,
    val message: String,
    val steps: List<String> = emptyList(),
    val identity: String? = null,
)

/**
 * 把推荐条目真正装进 App：
 * - skill → 解压/落盘到沙箱 `filesDir/skills`（工作区终端里就是 `/skills`）
 * - prompt / quick_message → 写进设置里的对应表
 * - mcp → 生成可用的 MCP 配置并写入 MCP 设置（仅远程类；本地 npx/uvx/docker 需要先在终端跑起来）
 *
 * 安全约束（见设计规格 §9 的坑②）：
 * 1. 先下载、再校验 sha256，**不匹配立即拒绝**，不落盘；
 * 2. 没提供 sha256 时允许继续，但在流水里明确写「未提供 sha256，跳过完整性校验」；
 * 3. 压缩包内路径做了归一化，`..` 之类的条目不参与落盘。
 */
class RecommendInstaller(
    private val client: OkHttpClient,
    private val skillManager: SkillManager,
    private val settingsStore: SettingsStore,
) {
    suspend fun install(entry: RecommendEntry): RecommendInstallResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<String>()
        try {
            // MCP 不能先下载 payload：remote 条目的 source_url 就是服务端点，
            // 对端点发 GET 会得到 405/406（DeepWiki/Context7 都是这样），
            // 以前正是在这里失败，导致所有远程 MCP 条目都装不上。
            if (entry.type == RecommendType.MCP) {
                return@withContext installMcp(entry, steps)
            }

            val payload = resolvePayload(entry, steps)
                ?: return@withContext RecommendInstallResult(
                    success = false,
                    message = "这个条目没有可安装的内容（content 和 source_url 都为空）",
                    steps = steps,
                )

            // inline 内容是从 index 里读出来的，来源就是 index 自己
            if (entry.installKind == InstallKind.INLINE) {
                steps += "使用 index 内嵌内容（${payload.size} 字节）"
            }

            if (!verifyPayload(entry, payload, steps)) {
                return@withContext RecommendInstallResult(
                    success = false,
                    message = "sha256 校验失败，已拒绝安装（内容与 index 声明不一致）",
                    steps = steps,
                )
            }

            when (entry.type) {
                RecommendType.SKILL -> installSkill(entry, payload, steps)
                RecommendType.PROMPT -> installPrompt(entry, payload, steps)
                RecommendType.QUICK_MESSAGE -> installQuickMessage(entry, payload, steps)
                RecommendType.MCP -> error("MCP 已在上面单独处理")
            }
        } catch (e: Exception) {
            Log.w(TAG, "install: ${entry.identity} 安装失败", e)
            RecommendInstallResult(
                success = false,
                message = "安装失败：${e.message ?: e::class.java.simpleName}",
                steps = steps,
            )
        }
    }

    /** 取到待安装的字节：优先内嵌内容，其次按 source_url 下载。 */
    private suspend fun resolvePayload(entry: RecommendEntry, steps: MutableList<String>): ByteArray? {
        if (entry.installKind == InstallKind.INLINE && entry.content.isNotBlank()) {
            return entry.content.toByteArray(Charsets.UTF_8)
        }
        if (entry.sourceUrl.isBlank()) return null

        steps += "来源：${entry.sourceUrl}"
        val bytes = download(entry.sourceUrl)
        steps += "已下载 ${bytes.size} 字节"
        return bytes
    }

    private fun verifyPayload(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): Boolean {
        if (!entry.hasChecksum) {
            steps += "该条目未提供 sha256，已跳过完整性校验（来源见上）"
            return true
        }
        val actual = sha256Hex(payload)
        steps += "sha256：$actual"
        if (actual == entry.sha256) {
            steps += "sha256 校验通过"
            return true
        }
        steps += "index 声明：${entry.sha256}"
        return false
    }

    private suspend fun installSkill(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val files: Map<String, ByteArray>
        val skillName: String

        if (isZipPayload(payload)) {
            steps += "识别为压缩包，开始解压"
            when (val result = extractSkillArchive(payload)) {
                is SkillArchiveResult.Failure -> return RecommendInstallResult(false, result.message, steps)
                is SkillArchiveResult.Success -> {
                    files = result.archive.files
                    skillName = result.archive.skillName
                    steps += "解压出 ${files.size} 个文件，技能名：$skillName"
                }
            }
        } else {
            val text = payload.toString(Charsets.UTF_8)
            val frontmatter = SkillFrontmatterParser.parse(text)
            val name = frontmatter["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                return RecommendInstallResult(false, "SKILL.md 缺少 name 字段，无法安装", steps)
            }
            if (frontmatter["description"].isNullOrBlank()) {
                return RecommendInstallResult(false, "SKILL.md 缺少 description 字段，无法安装", steps)
            }
            files = mapOf("SKILL.md" to payload)
            skillName = name
            steps += "识别为单个 SKILL.md，技能名：$skillName"
        }

        if (!isSafeSkillName(skillName)) {
            return RecommendInstallResult(false, "技能名包含非法字符：$skillName", steps)
        }

        val saved = skillManager.saveSkillFileBytesAtomically(skillName, files)
        if (!saved) {
            return RecommendInstallResult(false, "写入技能目录失败（沙箱目录不可写）", steps)
        }
        steps += "已写入沙箱技能目录：${skillManager.getSkillsDir().absolutePath}"
        return RecommendInstallResult(
            success = true,
            message = "技能「$skillName」已安装，可在技能页启用",
            steps = steps,
            identity = entry.identity,
        )
    }

    private suspend fun installPrompt(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val content = payload.toString(Charsets.UTF_8).trim()
        if (content.isEmpty()) {
            return RecommendInstallResult(false, "提示词内容为空，已取消", steps)
        }
        val injection = PromptInjection.ModeInjection(
            name = entry.name,
            content = content,
            enabled = true,
        )
        settingsStore.update { settings ->
            val existing = settings.modeInjections.firstOrNull { it.name == injection.name }
            settings.copy(
                modeInjections = if (existing != null) {
                    settings.modeInjections.map { if (it.id == existing.id) injection.copy(id = existing.id) else it }
                } else {
                    settings.modeInjections + injection
                }
            )
        }
        steps += "已写入模式注入（设置 → 提示词 → 模式注入）"
        return RecommendInstallResult(
            success = true,
            message = "提示词「${entry.name}」已加入模式注入",
            steps = steps,
            identity = entry.identity,
        )
    }

    private suspend fun installQuickMessage(
        entry: RecommendEntry,
        payload: ByteArray,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        val content = payload.toString(Charsets.UTF_8).trim()
        if (content.isEmpty()) {
            return RecommendInstallResult(false, "快捷消息内容为空，已取消", steps)
        }
        settingsStore.update { settings ->
            val existing = settings.quickMessages.firstOrNull { it.title == entry.name }
            val message = QuickMessage(title = entry.name, content = content)
            settings.copy(
                quickMessages = if (existing != null) {
                    settings.quickMessages.map { if (it.id == existing.id) message.copy(id = existing.id) else it }
                } else {
                    settings.quickMessages + message
                }
            )
        }
        steps += "已写入快捷消息列表"
        return RecommendInstallResult(
            success = true,
            message = "快捷消息「${entry.name}」已添加",
            steps = steps,
            identity = entry.identity,
        )
    }

    /**
     * MCP 安装：支持三种来源。
     * 1. `content` 内嵌配置 JSON；
     * 2. `remote_url` / `source_url` 直接是 MCP 端点（不能再下载，端点只接受 POST/SSE）；
     * 3. `source_url` 指向 `.json`/`.zip` 配置 payload，下载后解析。
     *
     * 解析结果会保留 sse / streamable_http、headers 与 oauth，而不是一律写成
     * Streamable HTTP 空请求头 —— 后者会让需要鉴权的服务装进去也连不上。
     */
    private suspend fun installMcp(
        entry: RecommendEntry,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        // 本地 stdio 类：本 App 的 MCP 客户端只支持 SSE / Streamable HTTP。
        // 直接拒绝，不要下载任何东西（npm 页面、镜像地址都不是 MCP 配置）。
        if (entry.installKind.needsRuntime) {
            val command = runtimeCommand(entry)
            return RecommendInstallResult(
                success = false,
                message = buildString {
                    append("这是本地 ${entry.installKind.wire} 类型的 MCP server，本 App 只支持远程 HTTP 连接。")
                    if (command.isNotBlank()) append("请先在工作区终端运行 `$command`，")
                    append("再用它暴露的 HTTP 地址手动添加。")
                },
                steps = steps,
            )
        }

        val draft = when {
            // 有明确的端点地址时优先用它：`remote_url`/remote 条目的 `source_url` 就是服务地址，
            // 不能下载（MCP 端点只接受 POST/SSE，GET 会 405/406）。
            entry.mcpDirectUrl.isNotBlank() -> {
                if (entry.hasChecksum) {
                    steps += "远程 MCP 端点不做 sha256 校验（index 的 sha256 只适用于下载内容）"
                }
                McpServerDraft(
                    transport = entry.mcpTransport,
                    url = entry.mcpDirectUrl,
                    headers = entry.mcpHeaders,
                    oauth = entry.mcpOauth,
                    name = entry.name,
                )
            }

            entry.content.isNotBlank() -> {
                val bytes = entry.content.toByteArray(Charsets.UTF_8)
                steps += "使用 index 内嵌的 MCP 配置（${bytes.size} 字节）"
                if (!verifyPayload(entry, bytes, steps)) {
                    return RecommendInstallResult(
                        success = false,
                        message = "sha256 校验失败，已拒绝安装（内嵌 MCP 配置与 index 声明不一致）",
                        steps = steps,
                    )
                }
                when (val parsed = parseMcpConfigJson(entry.content, preferredName = entry.name)) {
                    is McpConfigParseResult.Success -> parsed.draft
                    is McpConfigParseResult.Failure -> return RecommendInstallResult(
                        success = false,
                        message = "解析内嵌 MCP 配置失败：${parsed.message}",
                        steps = steps,
                    )
                }
            }

            entry.mcpConfigUrl.isNotBlank() -> {
                steps += "下载 MCP 配置：${entry.mcpConfigUrl}"
                val payload = download(entry.mcpConfigUrl)
                steps += "已下载 ${payload.size} 字节"
                if (!verifyPayload(entry, payload, steps)) {
                    return RecommendInstallResult(
                        success = false,
                        message = "sha256 校验失败，已拒绝安装（MCP 配置与 index 声明不一致）",
                        steps = steps,
                    )
                }
                when (val parsed = parseMcpConfigPayload(payload, preferredName = entry.name)) {
                    is McpConfigParseResult.Success -> parsed.draft
                    is McpConfigParseResult.Failure -> return RecommendInstallResult(
                        success = false,
                        message = "解析 MCP 配置失败：${parsed.message}",
                        steps = steps,
                    )
                }
            }

            else -> return RecommendInstallResult(
                success = false,
                message = "MCP 条目缺少可安装的内容（url、source_url 和 content 都为空）",
                steps = steps,
            )
        }

        if (!isHttpUrl(draft.url)) {
            return RecommendInstallResult(false, "MCP 地址不是 http/https：${draft.url}", steps)
        }

        // MCP 名称会拼进工具名，只允许字母数字；展示名里的空格/中文在这里统一转换。
        val requestedName = entry.name.ifBlank { draft.name }
        val baseName = sanitizeMcpServerName(requestedName, fallback = entry.id)
        if (baseName != requestedName.trim()) {
            steps += "MCP 名称只允许字母和数字，已规范化为「$baseName」"
        }

        val currentServers = settingsStore.settingsFlow.value.mcpServers
        val existingByUrl = currentServers.firstOrNull { it.serverUrl == draft.url }
        val existingByName = currentServers.firstOrNull { it.commonOptions.name == baseName }
        val existing = existingByUrl ?: existingByName
        val finalName = when {
            existingByUrl != null && isValidMcpServerName(existingByUrl.commonOptions.name) ->
                existingByUrl.commonOptions.name

            existingByName != null -> baseName
            else -> uniqueMcpServerName(baseName, currentServers.map { it.commonOptions.name }.toSet())
        }
        if (existingByUrl == null && finalName != baseName) {
            steps += "名称「$baseName」已被占用，改用「$finalName」"
        }

        val existingOptions = existing?.commonOptions
        val commonOptions = McpCommonOptions(
            enable = existingOptions?.enable ?: true,
            name = finalName,
            headers = draft.headers.ifEmpty { existingOptions?.headers.orEmpty() },
            tools = existingOptions?.tools.orEmpty(),
            oauth = mergeMcpOAuth(draft.oauth, existingOptions?.oauth),
        )
        val config = when (draft.transport) {
            McpTransport.SSE -> McpServerConfig.SseTransportServer(
                commonOptions = commonOptions,
                url = draft.url,
            )

            McpTransport.STREAMABLE_HTTP -> McpServerConfig.StreamableHTTPServer(
                commonOptions = commonOptions,
                url = draft.url,
            )
        }
        val finalConfig = existing?.let { config.clone(id = it.id) } ?: config

        settingsStore.update { settings ->
            settings.copy(
                mcpServers = if (existing != null) {
                    settings.mcpServers.map { if (it.id == existing.id) finalConfig else it }
                } else {
                    settings.mcpServers + finalConfig
                }
            )
        }

        steps += "MCP 传输：${draft.transport.displayName}"
        steps += "MCP 地址：${draft.url}"
        if (commonOptions.headers.isNotEmpty()) {
            steps += "请求头：${commonOptions.headers.size} 个"
        }
        commonOptions.oauth?.takeIf { it.enabled }?.let { oauth ->
            val scope = oauth.scope?.takeIf { it.isNotBlank() }?.let { "（scope=$it）" }.orEmpty()
            steps += "OAuth：已启用$scope"
        }
        if (commonOptions.headers.any { it.second.contains('{') && it.second.contains('}') }) {
            steps += "警告：请求头里有未替换的变量（如 {API_KEY}），请在 MCP 设置页补全"
        }
        steps += if (commonOptions.enable) {
            "已写入 MCP 设置并启用：$finalName → ${draft.url}"
        } else {
            "已写入 MCP 设置（保留原有的禁用状态）：$finalName → ${draft.url}"
        }

        return RecommendInstallResult(
            success = true,
            message = if (existing != null) {
                "MCP server「$finalName」已更新"
            } else {
                "MCP server「$finalName」已写入设置并启用"
            },
            steps = steps,
            identity = entry.identity,
        )
    }

    /**
     * 重新安装时不要把已有的 OAuth 令牌冲掉：index 里通常只有 `oauth.enabled/scope`，
     * 真正的 access/refresh token 是用户在本机授权后保存的。
     */
    private fun mergeMcpOAuth(
        incoming: McpOAuthState?,
        existing: McpOAuthState?,
    ): McpOAuthState? {
        if (incoming == null) return existing
        if (existing == null || !incoming.enabled) return incoming
        return incoming.copy(
            clientId = incoming.clientId ?: existing.clientId,
            clientSecret = incoming.clientSecret ?: existing.clientSecret,
            authorizationEndpoint = incoming.authorizationEndpoint ?: existing.authorizationEndpoint,
            tokenEndpoint = incoming.tokenEndpoint ?: existing.tokenEndpoint,
            registrationEndpoint = incoming.registrationEndpoint ?: existing.registrationEndpoint,
            redirectUri = incoming.redirectUri ?: existing.redirectUri,
            scope = incoming.scope ?: existing.scope,
            accessToken = incoming.accessToken ?: existing.accessToken,
            refreshToken = incoming.refreshToken ?: existing.refreshToken,
            expiresAt = incoming.expiresAt.takeIf { it > 0L } ?: existing.expiresAt,
        )
    }

    private suspend fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .build()
        return try {
            client.newCall(request).awaitBytes()
        } catch (e: IOException) {
            // 把地址和 HTTP 状态一起带给 UI，用户才知道是哪个源、哪种失败
            throw IOException("下载 $url 失败：${e.message ?: "网络错误"}", e)
        }
    }

    private companion object {
        const val TAG = "RecommendInstaller"
    }

    /** 技能名会当成目录名用，只允许常规字符，避免路径穿越。 */
    private fun isSafeSkillName(name: String): Boolean =
        name.isNotBlank() &&
            !name.contains('/') &&
            !name.contains('\\') &&
            !name.contains("..") &&
            name.none { it.isISOControl() } &&
            File(name).name == name
}

/** 带体积上限的下载，超限直接失败而不是把设备内存吃满。 */
private suspend fun Call.awaitBytes(): ByteArray = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (!response.isSuccessful) {
                            throw IOException("HTTP ${response.code} ${response.message}".trim())
                        }
                        val body = response.body ?: throw IOException("响应没有 body")
                        if (body.contentLength() == 0L) throw IOException("响应为空（0 字节）")
                        if (body.contentLength() > RecommendInstallerLimits.MAX_PAYLOAD_BYTES) {
                            throw IOException("文件超过 ${RecommendInstallerLimits.MAX_PAYLOAD_BYTES / 1024 / 1024} MB")
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > RecommendInstallerLimits.MAX_PAYLOAD_BYTES) {
                                    throw IOException("文件超过 ${RecommendInstallerLimits.MAX_PAYLOAD_BYTES / 1024 / 1024} MB")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        if (output.size() == 0) throw IOException("响应为空（0 字节）")
                        if (continuation.isActive) continuation.resume(output.toByteArray())
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        }
    )
}

/** 下载上限对文件级 helper 也可用（companion 常量无法直接从顶层函数访问）。 */
private object RecommendInstallerLimits {
    const val MAX_PAYLOAD_BYTES = 8L * 1024 * 1024
}
