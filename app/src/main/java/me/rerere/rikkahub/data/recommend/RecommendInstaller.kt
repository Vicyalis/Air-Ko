package me.rerere.rikkahub.data.recommend

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.plugin.PluginManifest
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.utils.JsonInstant
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
    private val pluginManager: PluginManager,
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
            if (entry.type == RecommendType.PLUGIN) {
                return@withContext installDshPlugin(entry, steps)
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
                RecommendType.PLUGIN -> error("DSH 插件已在上面单独处理")
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

    private suspend fun installDshPlugin(
        entry: RecommendEntry,
        steps: MutableList<String>,
    ): RecommendInstallResult {
        steps += "DSH 插件：${entry.dshPackage.ifBlank { entry.id }} @ ${entry.version.ifBlank { "latest" }}"

        // 1. 有随 APK 打包的 Air-Ko 移植版：直接装，和技能一样安装后即可用。
        if (entry.dshPortAsset.isNotBlank()) {
            steps += "发现内置 Air-Ko 移植版：${entry.dshPortName.ifBlank { entry.name }}"
            val portResult = pluginManager.installFromAsset(entry.dshPortAsset)
            val installed = portResult.getOrNull()
            if (installed != null) {
                steps += "已写入插件目录并自动启用：${installed.manifest.id}"
                return RecommendInstallResult(
                    success = true,
                    message = "已安装 Air-Ko 移植版「${installed.manifest.name}」，插件已启用",
                    steps = steps,
                    identity = entry.identity,
                )
            }
            steps += "内置移植版安装失败（${portResult.exceptionOrNull()?.message ?: "未知错误"}），改为下载原包"
        }

        // 2. 下载 npm tarball / GitHub 归档。
        val payload = downloadDshPackage(entry, steps)
        steps += "已下载插件包：${payload.size} 字节"

        val files = when (val archive = extractDshArchive(payload)) {
            is DshArchiveResult.Failure -> return RecommendInstallResult(
                success = false,
                message = archive.message,
                steps = steps,
            )

            is DshArchiveResult.Success -> archive.files
        }
        val extracted = extractDshBundles(files)
        steps += "包内 ${extracted.fileCount} 个文件，识别到 ${extracted.skills.size} 个技能"
        extracted.notes.take(4).forEach { steps += it }

        // 3. 技能包：每个 SKILL.md 单独落盘，保留脚本/参考资料等附属文件。
        if (extracted.skills.isNotEmpty()) {
            val installedNames = mutableListOf<String>()
            extracted.skills.forEach { bundle ->
                val saved = skillManager.saveSkillFileBytesAtomically(bundle.name, bundle.files)
                if (saved) {
                    installedNames += bundle.name
                    steps += "已安装技能「${bundle.name}」（${bundle.files.size} 个文件）"
                } else {
                    steps += "技能「${bundle.name}」写入失败，已跳过"
                }
            }
            if (installedNames.isNotEmpty()) {
                val preview = installedNames.take(6).joinToString("、")
                val more = if (installedNames.size > 6) " 等 ${installedNames.size} 个" else ""
                return RecommendInstallResult(
                    success = true,
                    message = "已安装 DSH 技能：$preview$more（可在技能页启用）",
                    steps = steps,
                    identity = entry.identity,
                )
            }
            steps += "所有技能都写入失败；继续尝试提示词安装"
        }

        // 4. 提示词包：只有 INSTRUCTIONS.md / FOR-AI.md 等明确指令文件时，安装成 Air-Ko 插件。
        val prompt = extracted.prompt
        if (prompt != null) {
            val homepage = entry.dshHomepage.ifBlank { entry.dshRepository }
                .takeIf { isHttpUrl(it) }
                .orEmpty()
            val manifest = PluginManifest(
                id = dshPluginId(entry),
                name = entry.name.take(120),
                version = entry.version.take(32).ifBlank { "1.0.0" },
                description = entry.description.take(1_000),
                author = entry.author.take(120),
                homepage = homepage.take(2_048),
                prompt = prompt,
            )
            val json = JsonInstant.encodeToString(PluginManifest.serializer(), manifest)
            val result = pluginManager.installFromJson(json)
            val installed = result.getOrNull()
            if (installed != null) {
                steps += "已从 ${extracted.promptFile ?: "指令文件"} 安装插件并自动启用"
                return RecommendInstallResult(
                    success = true,
                    message = "DSH 插件「${entry.name}」的指令已安装为 Air-Ko 插件并启用",
                    steps = steps,
                    identity = entry.identity,
                )
            }
            steps += "提示词插件写入失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
        }

        // 5. 纯 Cordis/Node 插件：如实失败，不伪造“已安装”。
        val runtimeHint = if (looksLikeCordisRuntime(files)) {
            "该包包含 cordis.patch.yml / dsh 代码，属于需要 Node.js 运行时的 DSH 插件。"
        } else {
            "该包没有 SKILL.md 或明确的指令文件。"
        }
        return RecommendInstallResult(
            success = false,
            message = "$runtimeHint Air-Ko 是 Android 原生客户端，无法执行 Cordis/Node 插件；" +
                "你可以在 DSH 桌面端安装它，或打开详情页查看源码仓库。",
            steps = steps,
        )
    }

    /** npm 包先读 registry 元数据拿 tarball 与 shasum；Git 源按 commit 下 GitHub 归档。 */
    private suspend fun downloadDshPackage(
        entry: RecommendEntry,
        steps: MutableList<String>,
    ): ByteArray {
        val sourceType = entry.dshSourceType.ifBlank {
            if (entry.dshPackage.isNotBlank()) "npm" else ""
        }.lowercase()

        if (sourceType == "git") {
            val repo = DshPorts.normalizeRepo(entry.dshRepository.ifBlank { entry.dshHomepage })
                ?: throw IOException("Git 源缺少可识别的 GitHub 仓库地址")
            val commit = entry.dshSourceCommit
            if (commit.isBlank()) throw IOException("Git 源缺少 commit，无法固定下载版本")
            val url = "https://codeload.github.com/$repo/zip/$commit"
            steps += "下载 GitHub 归档：$repo@${commit.take(8)}"
            return download(url, MAX_DSH_PACKAGE_BYTES)
        }

        if (entry.dshPackage.isBlank()) throw IOException("DSH 条目缺少 npm 包名")
        val registry = entry.dshRegistry.ifBlank { "https://registry.npmjs.org/" }.trimEnd('/')
        val version = entry.version.ifBlank { "latest" }
        val metadataUrl = "$registry/${encodeNpmPackage(entry.dshPackage)}/" +
            URLEncoder.encode(version, Charsets.UTF_8.name())
        steps += "读取 npm 元数据：${entry.dshPackage}@$version"
        val metadata = runCatching {
            RecommendIndexJson.parseToJsonElement(
                download(metadataUrl, MAX_DSH_METADATA_BYTES).toString(Charsets.UTF_8)
            ).jsonObject
        }.getOrElse { error ->
            throw IOException("解析 npm 元数据失败：${error.message ?: "unknown"}", error)
        }
        val dist = metadata["dist"] as? JsonObject
            ?: throw IOException("npm 元数据缺少 dist 字段")
        val tarball = (dist["tarball"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { isHttpUrl(it) }
            ?: throw IOException("npm 元数据缺少合法的 tarball 地址")
        val expectedSha1 = (dist["shasum"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.contentOrNull?.trim().orEmpty()
        steps += "下载 npm tarball：$tarball"
        val bytes = download(tarball, MAX_DSH_PACKAGE_BYTES)
        if (expectedSha1.isNotBlank()) {
            val actual = sha1Hex(bytes)
            if (!actual.equals(expectedSha1, ignoreCase = true)) {
                throw IOException("npm tarball shasum 校验失败（声明 $expectedSha1，实际 $actual）")
            }
            steps += "npm shasum 校验通过"
        } else {
            steps += "npm 元数据未提供 shasum，已跳过 tarball 完整性校验"
        }
        return bytes
    }

    private fun dshPluginId(entry: RecommendEntry): String {
        val base = entry.dshPackage.ifBlank { entry.id }
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-', '_')
        return ("dsh-$base").take(64).ifBlank { "dsh-plugin" }
    }

    private fun encodeNpmPackage(name: String): String =
        if (name.startsWith("@") && name.count { it == '/' } == 1) {
            name.replaceFirst("/", "%2F")
        } else {
            name
        }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

    private suspend fun download(url: String, maxBytes: Long = RecommendInstallerLimits.MAX_PAYLOAD_BYTES): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .build()
        return try {
            client.newCall(request).awaitBytes(maxBytes)
        } catch (e: IOException) {
            // 把地址和 HTTP 状态一起带给 UI，用户才知道是哪个源、哪种失败
            throw IOException("下载 $url 失败：${e.message ?: "网络错误"}", e)
        }
    }

    private companion object {
        const val TAG = "RecommendInstaller"
        const val MAX_DSH_PACKAGE_BYTES = 32L * 1024 * 1024
        const val MAX_DSH_METADATA_BYTES = 2L * 1024 * 1024
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
private suspend fun Call.awaitBytes(maxBytes: Long): ByteArray = suspendCancellableCoroutine { continuation ->
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
                        if (body.contentLength() > maxBytes) {
                            throw IOException("文件超过 ${maxBytes / 1024 / 1024} MB")
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > maxBytes) {
                                    throw IOException("文件超过 ${maxBytes / 1024 / 1024} MB")
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
