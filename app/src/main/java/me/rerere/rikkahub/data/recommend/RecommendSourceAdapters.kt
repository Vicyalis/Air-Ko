/*
 * 推荐源的格式适配器：把公开目录转换成商店自己的 index 协议。
 *
 * 上游基本不可能按我们的协议提供 `recommend-index.json`，所以与其等上游，不如适配已有的公开目录：
 * - Claude 插件市场（`.claude-plugin/marketplace.json`，如 anthropics/skills）→ skill 条目
 * - MCP 官方注册表（registry.modelcontextprotocol.io）→ mcp 条目
 *
 * 转换结果是一份「本协议 JSON」，之后完全走 [parseRecommendIndex] 的校验与安装流程，
 * 因此安全规则（必填字段、http(s) 限制、sha256 校验、zip 防穿越）对适配源同样生效。
 */
package me.rerere.rikkahub.data.recommend

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 适配结果：转换成功给出本协议 JSON；[NotApplicable] 表示按原生协议解析。 */
internal sealed interface SourceConvertResult {
    data class Converted(val json: String, val note: String?) : SourceConvertResult

    /**
     * 已经直接构造并校验好的条目。
     *
     * DSH 目录最大近 4 MB / 数千条，再拼一份 JSON 交给 [parseRecommendIndex] 会白白多一次
     * 大对象解析；这里只对单条构造 DTO 并复用同一套 [validateEntryDto] 安全校验。
     */
    data class Parsed(
        val entries: List<RecommendEntry>,
        val skipped: List<SkippedRecommendEntry>,
        val note: String?,
    ) : SourceConvertResult

    data object NotApplicable : SourceConvertResult
    data class Failed(val message: String) : SourceConvertResult
}

internal fun convertSourceText(source: RecommendSource, raw: String): SourceConvertResult =
    when (source.format) {
        RecommendSourceFormat.NATIVE -> SourceConvertResult.NotApplicable
        RecommendSourceFormat.CLAUDE_MARKETPLACE -> convertClaudeMarketplace(source, raw)
        RecommendSourceFormat.MCP_REGISTRY -> convertMcpRegistry(source, raw)
        RecommendSourceFormat.DSH_CATALOG -> convertDshCatalog(source, raw)
    }

// ---------------------------------------------------------------------------------------------
// Claude 插件市场
// ---------------------------------------------------------------------------------------------

/**
 * `.claude-plugin/marketplace.json` 结构：
 * `{"plugins":[{"name":"…","description":"…","skills":["./skills/xlsx", …]}]}`
 *
 * 每个 skill 生成一条条目：安装时从同一个仓库的 raw 地址取该目录下的 `SKILL.md`
 * （`install_kind=inline` + `source_url`＝「下载安装」，没有 sha256，安装流水里会明确标注
 * 「跳过完整性校验」）。技能自带的脚本等附属文件不在单文件下载范围内。
 */
private fun convertClaudeMarketplace(source: RecommendSource, raw: String): SourceConvertResult {
    val root = runCatching { RecommendIndexJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SourceConvertResult.Failed("marketplace.json 不是合法的 JSON 对象")
    val plugins = root["plugins"] as? JsonArray
        ?: return SourceConvertResult.Failed("marketplace.json 里没有 plugins 数组")

    // raw 地址形如 …/main/.claude-plugin/marketplace.json → 仓库 raw 前缀 …/main/
    val base = source.url.substringBefore("/.claude-plugin/").trimEnd('/') + "/"

    val items = buildJsonArray {
        plugins.forEach { pluginElement ->
            val plugin = pluginElement as? JsonObject ?: return@forEach
            val pluginName = (plugin["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val description = (plugin["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val skills = plugin["skills"] as? JsonArray ?: return@forEach
            skills.forEach { skillElement ->
                val rawPath = (skillElement as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
                val path = rawPath.removePrefix("./").trim('/')
                if (path.isEmpty()) return@forEach
                val skillName = path.substringAfterLast('/')
                if (skillName.isEmpty()) return@forEach
                val id = "anthropic-" + skillName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
                add(
                    buildJsonObject {
                        put("id", id)
                        put("type", "skill")
                        put("name", skillName)
                        put("author", "Anthropic")
                        put("description", description.ifBlank { path })
                        put("tags", buildJsonArray { if (pluginName.isNotBlank()) add(pluginName) })
                        put("source_url", "$base$path/SKILL.md")
                        put("install_kind", "inline")
                        put("deploy_doc", "来自 Anthropic Skills 仓库（$base$path）。安装会下载该目录的 SKILL.md；技能附带的脚本等文件不在单文件下载范围内。")
                        put("sort", 100)
                    }
                )
            }
        }
    }

    val count = items.size
    if (count == 0) return SourceConvertResult.Failed("marketplace.json 里没有可用的 skill 条目")
    return SourceConvertResult.Converted(
        json = buildJsonObject { put("items", items) }.toString(),
        note = "已把 $count 个插件技能转换成商店条目",
    )
}

// ---------------------------------------------------------------------------------------------
// MCP 官方注册表
// ---------------------------------------------------------------------------------------------

/**
 * `registry.modelcontextprotocol.io/v0/servers` 结构：
 * `{"servers":[{"server":{"name":"ac.inference.sh/mcp","title":"…","description":"…",
 *  "remotes":[{"type":"streamable-http","url":"https://…"}],"packages":[{"registryType":"npm",
 *  "identifier":"…","version":"…"}]}}]}`
 *
 * 有远程地址的条目装成「remote」（写入设置即可用）；只有包信息的装成 npx/uvx/docker，
 * 商店里给部署讲解与终端入口（与本地 MCP 的既有流程一致）。
 */
private fun convertMcpRegistry(source: RecommendSource, raw: String): SourceConvertResult {
    val root = runCatching { RecommendIndexJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SourceConvertResult.Failed("注册表响应不是合法的 JSON 对象")
    val servers = root["servers"] as? JsonArray
        ?: return SourceConvertResult.Failed("注册表响应里没有 servers 数组")

    val items = buildJsonArray {
        servers.forEach { element ->
            val server = ((element as? JsonObject)?.get("server") as? JsonObject) ?: return@forEach
            val fullName = (server["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (fullName.isEmpty()) return@forEach
            val title = (server["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val description = (server["description"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            val id = "mcp-" + fullName.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
            val displayName = title.ifBlank { fullName }

            // 注册表的 remotes 带传输类型与可选 headers；必须原样带到 index，
            // 否则 SSE 服务会被当成 Streamable HTTP，带鉴权的服务会丢掉请求头。
            val remote = (server["remotes"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { remote ->
                    val url = (remote["url"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    (url.startsWith("http://") || url.startsWith("https://")) &&
                        !url.contains('{') && !url.contains('}')
                }
            val remoteUrl = (remote?.get("url") as? JsonPrimitive)?.contentOrNull?.trim()
            val remoteType = (remote?.get("type") as? JsonPrimitive)?.contentOrNull?.trim()
            val remoteHeaders = remote?.get("headers") as? JsonArray
            val remoteIsSse = remoteType?.contains("sse", ignoreCase = true) == true

            val resolvedRemoteUrl = remoteUrl?.takeIf { it.isNotBlank() }
            if (resolvedRemoteUrl != null) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("type", "mcp")
                        put("name", displayName)
                        put("author", "MCP Registry")
                        put("description", description.ifBlank { fullName })
                        put("source_url", resolvedRemoteUrl)
                        put("remote_url", resolvedRemoteUrl)
                        put("install_kind", "remote")
                        put("mcp_transport", if (remoteIsSse) "sse" else "streamable_http")
                        if (remoteHeaders != null && remoteHeaders.isNotEmpty()) {
                            put(
                                "mcp_headers",
                                buildJsonObject {
                                    remoteHeaders.forEach { header ->
                                        val headerObj = header as? JsonObject ?: return@forEach
                                        val headerName = (headerObj["name"] as? JsonPrimitive)
                                            ?.contentOrNull?.trim().orEmpty()
                                        val headerValue = (headerObj["value"] as? JsonPrimitive)?.contentOrNull
                                            ?: (headerObj["default"] as? JsonPrimitive)?.contentOrNull
                                        if (headerName.isNotEmpty() && !headerValue.isNullOrBlank()) {
                                            put(headerName, headerValue)
                                        }
                                    }
                                }
                            )
                        }
                        put(
                            "deploy_doc",
                            "远程 MCP 服务（${if (remoteIsSse) "SSE" else "Streamable HTTP"}）：" +
                                "安装时直接把地址写进「MCP 服务器」，不需要在设备上跑命令。"
                        )
                        put("sort", 200)
                    }
                )
                return@forEach
            }

            val pkg = (server["packages"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@forEach
            val registryType = (pkg["registryType"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
            val identifier = (pkg["identifier"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
            if (identifier.isEmpty()) return@forEach
            val (kind, command) = when (registryType) {
                "npm" -> "npx" to "npx -y $identifier"
                "pypi" -> "uvx" to "uvx $identifier"
                "oci" -> "docker" to "docker run -i --rm $identifier"
                else -> return@forEach
            }
            add(
                buildJsonObject {
                    put("id", id)
                    put("type", "mcp")
                    put("name", displayName)
                    put("author", "MCP Registry")
                    put("description", description.ifBlank { fullName })
                    put("install_kind", kind)
                    put("run_command", command)
                    put("deploy_doc", "本地 MCP：先在工作区终端里跑 `$command`（或按包页面说明部署），再按提示把连接方式填进「MCP 服务器」。")
                    put("sort", 300)
                }
            )
        }
    }

    val count = items.size
    if (count == 0) return SourceConvertResult.Failed("注册表里没有可用的条目")
    return SourceConvertResult.Converted(
        json = buildJsonObject { put("items", items) }.toString(),
        note = "已把 $count 个 MCP 服务转换成商店条目",
    )
}

// ---------------------------------------------------------------------------------------------
// DSH 插件市场（dshopen.org / dsh-plugin-catalog 的 format=1 index.json）
// ---------------------------------------------------------------------------------------------

/**
 * DSH 目录结构（format 1）：
 * ```json
 * {
 *   "format": 1,
 *   "metadata": {"categories": {"skills": {"title": {"en": "Skills", "zh": "技能"}}}},
 *   "plugins": [{
 *     "name": "dsh-fact-check",
 *     "title": {"en": "...", "zh": "..."},
 *     "description": {"en": "...", "zh": "..."},
 *     "repository": {"url": "https://github.com/..."},
 *     "metadata": {"category": "skills", "keywords": [...], "sortKeys": {"github-stars": 62}},
 *     "versions": [{
 *       "version": "1.5.1",
 *       "source": {"type": "npm", "registry": "https://registry.npmjs.org/"},
 *       "engines": {"dsh": ">=0.2.0"}
 *     }]
 *   }]
 * }
 * ```
 *
 * 我们**不执行** DSH 插件代码：只展示 npm/Git 元信息，安装时按包内容走
 * [DshPluginKind.PORT] / [DshPluginKind.SKILL] / [DshPluginKind.AUTO] / [DshPluginKind.RUNTIME]。
 */
private fun convertDshCatalog(source: RecommendSource, raw: String): SourceConvertResult {
    val root = runCatching { RecommendIndexJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SourceConvertResult.Failed("DSH index 不是合法的 JSON 对象")
    val plugins = root["plugins"] as? JsonArray
        ?: return SourceConvertResult.Failed("DSH index 里没有 plugins 数组")

    val zh = Locale.getDefault().language.startsWith("zh")
    val categoryTitles = parseDshCategoryTitles(root, zh)
    val labelTitles = parseDshLabelTitles(root, zh)

    val entries = ArrayList<RecommendEntry>(plugins.size)
    val skipped = ArrayList<SkippedRecommendEntry>()

    plugins.forEach { element ->
        val plugin = element as? JsonObject ?: run {
            skipped += SkippedRecommendEntry("", "", "DSH 条目不是 JSON 对象")
            return@forEach
        }
        val packageName = (plugin["name"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        if (packageName.isEmpty()) {
            skipped += SkippedRecommendEntry("", "", "DSH 条目缺少 npm 包名")
            return@forEach
        }
        val repositoryUrl = dshRepositoryUrl(plugin)
        val versionObject = (plugin["versions"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.lastOrNull()
        val version = (versionObject?.get("version") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val sourceObject = versionObject?.get("source") as? JsonObject
        val sourceType = (sourceObject?.get("type") as? JsonPrimitive)
            ?.contentOrNull.orEmpty().trim().lowercase()
        val sourceUrl = (sourceObject?.get("url") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        // 少数 Git 条目只写 source.url，不写顶层 repository；安装时仍要能定位仓库。
        // 只接受 http(s)，避免详情页把 file:/intent: 之类 URL 交给系统打开。
        val effectiveRepository = repositoryUrl.ifBlank { sourceUrl.takeIf { sourceType == "git" }.orEmpty() }
            .takeIf { isHttpUrl(it) }
            .orEmpty()
        val registry = (sourceObject?.get("registry") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val commit = (sourceObject?.get("commit") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val engines = (versionObject?.get("engines") as? JsonObject)
            ?.get("dsh")
            ?.let(::dshLocalized)
            .orEmpty()

        val metadata = plugin["metadata"] as? JsonObject
        val category = (metadata?.get("category") as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val stars = (metadata?.get("sortKeys") as? JsonObject)
            ?.get("github-stars")
            ?.let { it as? JsonPrimitive }
            ?.intOrNull
            ?: 0
        val keywords = (metadata?.get("keywords") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(4)
            .orEmpty()
        val labels = (metadata?.get("labels") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.mapNotNull { labelTitles[it] ?: it.takeIf { it.isNotBlank() } }
            ?.take(2)
            .orEmpty()

        val title = dshLocalized(plugin["title"]).trim()
        val description = dshLocalized(plugin["description"])
            .replace(Regex("^★\\s*[\\d,.]+\\s*[│|]\\s*"), "")
            .trim()
        val displayName = title.ifBlank {
            packageName.substringAfterLast('/').ifBlank { packageName }
        }
        val icon = (plugin["icon"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        val iconUrl = resolveDshIcon(source.url, icon)
        val author = dshLocalized(plugin["author"]).trim().ifBlank {
            DshPorts.normalizeRepo(effectiveRepository)?.substringBefore('/').orEmpty()
        }
        val homepage = dshLocalized(plugin["homepage"]).trim().ifBlank { effectiveRepository }
            .takeIf { isHttpUrl(it) }
            .orEmpty()
        val kind = dshKind(category)

        val npmPage = "https://www.npmjs.com/package/$packageName"
        val displaySource = when (sourceType) {
            "npm" -> npmPage
            "git" -> effectiveRepository
            else -> effectiveRepository.ifBlank { npmPage }
        }
        val deployDoc = if (zh) {
            "DSH 插件（${sourceType.ifBlank { "未知" }} 源）。安装时优先使用 Air-Ko 内置移植版；" +
                "否则下载包并提取 SKILL.md / 指令文件。纯 Cordis/Node 代码需要 DSH 运行时，不会在 Android 上执行。"
        } else {
            "DSH plugin (${sourceType.ifBlank { "unknown" }} source). Air-Ko uses a bundled port when available; " +
                "otherwise it downloads the package and extracts SKILL.md / instruction files. " +
                "Pure Cordis/Node code requires the DSH runtime and does not execute on Android."
        }

        val dto = RecommendEntryDto(
            id = dshEntryId(packageName),
            type = RecommendType.PLUGIN.wire,
            name = displayName,
            version = version,
            author = author,
            description = description.ifBlank { displayName },
            tags = (keywords + labels).distinct().take(6),
            sourceUrl = displaySource,
            installKind = InstallKind.DSH.wire,
            deployDoc = deployDoc,
            sort = if (stars > 0) -stars else 0,
            dshPackage = packageName,
            dshRepository = effectiveRepository,
            dshHomepage = homepage,
            dshIcon = iconUrl,
            dshCategory = category,
            dshCategoryLabel = categoryTitles[category].orEmpty(),
            dshStars = stars,
            dshEngines = engines,
            dshSourceType = sourceType,
            dshRegistry = registry,
            dshSourceCommit = commit,
            dshKind = kind.wire,
        )

        when (val result = validateEntryDto(dto, source.url)) {
            is EntryValidation.Valid -> entries += result.entry.copy(
                originId = source.id,
                originName = source.name,
            )

            is EntryValidation.Invalid -> skipped += SkippedRecommendEntry(
                id = dto.id,
                name = displayName,
                reason = result.reason,
            )
        }
    }

    if (entries.isEmpty()) {
        return SourceConvertResult.Failed(
            if (zh) "DSH index 里没有可用插件" else "No usable plugins in the DSH index"
        )
    }
    val note = if (zh) {
        "已解析 ${entries.size} 个 DSH 插件" + if (skipped.isNotEmpty()) "，跳过 ${skipped.size} 个" else ""
    } else {
        "Parsed ${entries.size} DSH plugins" + if (skipped.isNotEmpty()) ", skipped ${skipped.size}" else ""
    }
    return SourceConvertResult.Parsed(entries = entries, skipped = skipped, note = note)
}

private fun dshKind(category: String): DshPluginKind = when (category.lowercase()) {
    "skill", "skills" -> DshPluginKind.SKILL
    // 明确依赖界面/宿主运行时的类目。tools / integrations / workflow 等混合类目统一走
    // AUTO：下载后按包内 SKILL.md / 指令文件判断，避免把可移植的技能包误判成“不能用”。
    "ui", "interface", "theme", "appearance", "providers", "model", "usage",
    "session", "browser", "vision", "voice", "docs", "git", "wsl", "security", "development",
    "dev", "identity", "remote", "market", "notify", "fun",
    -> DshPluginKind.RUNTIME

    else -> DshPluginKind.AUTO
}

private fun parseDshCategoryTitles(root: JsonObject, zh: Boolean): Map<String, String> {
    val categories = (root["metadata"] as? JsonObject)?.get("categories") as? JsonObject
        ?: return emptyMap()
    return categories.mapNotNull { (key, value) ->
        val title = dshLocalized((value as? JsonObject)?.get("title"), zh).trim()
        if (title.isBlank()) null else key to title
    }.toMap()
}

private fun parseDshLabelTitles(root: JsonObject, zh: Boolean): Map<String, String> {
    val labels = (root["metadata"] as? JsonObject)?.get("labels") as? JsonObject
        ?: return emptyMap()
    return labels.mapNotNull { (key, value) ->
        val title = dshLocalized((value as? JsonObject)?.get("title"), zh).trim()
        if (title.isBlank()) null else key to title
    }.toMap()
}

private fun dshRepositoryUrl(plugin: JsonObject): String = when (val repo = plugin["repository"]) {
    is JsonPrimitive -> repo.contentOrNull.orEmpty().trim()
    is JsonObject -> (repo["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
    else -> ""
}

private fun dshLocalized(element: JsonElement?, zh: Boolean = isChineseLocale()): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonObject -> {
        val preferred = if (zh) "zh" else "en"
        val fallback = if (zh) "en" else "zh"
        dshLocalized(element[preferred] ?: element[fallback] ?: element.values.firstOrNull(), zh)
    }

    else -> ""
}

private fun isChineseLocale(): Boolean = Locale.getDefault().language.startsWith("zh")

private fun resolveDshIcon(sourceUrl: String, icon: String): String {
    if (icon.isBlank()) return ""
    if (isHttpUrl(icon)) return icon
    val resolved = runCatching { java.net.URI(sourceUrl).resolve(icon).toString() }.getOrDefault("")
    // 只允许 http(s) 图标；data:/file: 等 URL 不交给图片加载器。
    return resolved.takeIf { isHttpUrl(it) }.orEmpty()
}

/** 稳定、可读、可用于导航参数的 DSH 条目标识。 */
private fun dshEntryId(packageName: String): String {
    val base = packageName.lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "plugin" }
    // 不同包名（例如 @a/b 与 a-b）清洗后可能撞车；带一段包名哈希保证跨源身份稳定且唯一。
    return "dsh-${base.take(64)}-${sha256Hex(packageName.toByteArray()).take(8)}"
}
