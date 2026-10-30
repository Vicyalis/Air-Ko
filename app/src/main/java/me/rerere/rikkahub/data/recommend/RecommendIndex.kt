package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser

/**
 * 推荐商店的 Index 协议（照上游 `RecommendedProviders.kt` 的风格扩展而来）。
 *
 * 一个 index 就是一份 JSON：既可以是裸数组 `[{...}]`，也可以是
 * `{"items": [...]}` / `{"entries": [...]}` 这类包了一层的对象。字段定义见 [RecommendEntryDto]。
 *
 * 解析刻意做成「宽进严出」：
 * - 未知字段忽略、缺省字段取默认值、`tags`/`deps` 写成逗号分隔的字符串也认；
 * - 但必填字段（id / type）缺失、或者 sha256 格式非法、非 inline 条目没有合法的 http(s) 地址时，
 *   这条会被**跳过并记录原因**，而不是带着可疑数据进入安装流程 —— 装进来的是能改 AI 行为的内容，
 *   宁可不显示也不能显示错的。
 */

/** 推荐条目的类目，对应 index 的 `type` 字段。 */
enum class RecommendType(val wire: String) {
    SKILL("skill"),
    PROMPT("prompt"),
    QUICK_MESSAGE("quick_message"),
    MCP("mcp"),

    /** DeepSeek Harness（DSH）生态插件；安装路径由 [RecommendEntry.dshKind] 决定。 */
    PLUGIN("plugin");

    companion object {
        fun fromWire(value: String?): RecommendType? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** 条目的安装方式，对应 index 的 `install_kind` 字段。 */
enum class InstallKind(val wire: String) {
    /** Node 生态，用 npx 拉起（一般用于本地 stdio MCP server） */
    NPX("npx"),

    /** Python 生态，用 uvx/uv 拉起 */
    UVX("uvx"),

    /** 容器镜像 */
    DOCKER("docker"),

    /** 远程服务，直接填地址即可用 */
    REMOTE("remote"),

    /** 内容直接内嵌在 index 里，不需要下载 */
    INLINE("inline"),

    /**
     * DSH 生态插件。不是在本机跑 Node，而是下载 npm tarball / Git 归档后，
     * 安装内置移植版、提取 `SKILL.md` 或指令文件；纯 Cordis/Node 代码会明确拒绝。
     */
    DSH("dsh");

    /** 这个安装方式是否需要在设备上跑命令（决定「部署讲解」要不要出现终端入口） */
    val needsRuntime: Boolean
        get() = this == NPX || this == UVX || this == DOCKER

    companion object {
        fun fromWire(value: String?): InstallKind? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/** 列表排序方式，作为用户偏好持久化。 */
enum class RecommendSort(val wire: String) {
    /** index 自带的 sort 升序，再按名称 */
    DEFAULT("default"),
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    AUTHOR("author"),

    /** 按安装方式分组（inline/remote 在前，需要跑命令的在后） */
    RUNTIME("runtime");

    companion object {
        fun fromWire(value: String?): RecommendSort? {
            val normalized = value?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == normalized }
        }
    }
}

/**
 * 校验通过的推荐条目。
 *
 * [originId] / [originName] 记录它来自哪个源，UI 要能让用户一眼看出内容是从哪来的（安全要求）。
 */
data class RecommendEntry(
    val id: String,
    val type: RecommendType,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val sourceUrl: String,
    val sha256: String,
    val installKind: InstallKind,
    val deps: List<String>,
    val deployDoc: String,
    val sort: Int,
    val content: String,
    val remoteUrl: String,
    val runCommand: String,
    val originId: String,
    val originName: String,
    // 以下字段只对 MCP 条目有意义；index 里可以显式写 mcp_transport / mcp_headers / mcp_oauth
    val mcpTransport: McpTransport = McpTransport.STREAMABLE_HTTP,
    val mcpHeaders: List<Pair<String, String>> = emptyList(),
    val mcpOauth: McpOAuthState? = null,
    // 以下字段只对 DSH 插件条目有意义。索引本身不可信，asset 路径只从 [DshPorts] 查表，
    // 绝不由远端 index 直接指定。
    val dshPackage: String = "",
    val dshRepository: String = "",
    val dshHomepage: String = "",
    val dshIconUrl: String = "",
    val dshCategory: String = "",
    val dshCategoryLabel: String = "",
    val dshStars: Int = 0,
    val dshEngines: String = "",
    val dshSourceType: String = "",
    val dshRegistry: String = "",
    val dshSourceCommit: String = "",
    val dshKind: DshPluginKind = DshPluginKind.AUTO,
    val dshPortAsset: String = "",
    val dshPortName: String = "",
) {
    /** 跨源唯一的条目标识，用于「已确认/已安装」记录与导航参数 */
    val identity: String get() = "${type.wire}:$id"

    /** 下载/安装前要展示的来源地址；remote 类条目的 source_url 就是服务地址 */
    val displaySourceUrl: String get() = sourceUrl.ifBlank { remoteUrl }

    /** 是否带了 sha256。没带时 UI 必须明确提示「无法校验完整性」 */
    val hasChecksum: Boolean get() = sha256.isNotBlank()

    /** MCP 条目真正能写进设置里的地址（仅 remote 类有） */
    val resolvedRemoteUrl: String
        get() = when {
            remoteUrl.isNotBlank() -> remoteUrl
            installKind == InstallKind.REMOTE -> sourceUrl
            else -> ""
        }

    /**
     * 可以直接写进设置的 MCP 端点。
     *
     * `source_url` 指向 `.json`/`.zip` 时它是「配置 payload」而不是端点，
     * 必须下载解析后再写设置，不能把配置文件的地址当成 MCP 地址。
     */
    val mcpDirectUrl: String
        get() = when {
            installKind.needsRuntime -> ""
            remoteUrl.isNotBlank() -> remoteUrl
            sourceUrl.isNotBlank() && !isMcpConfigPayloadUrl(sourceUrl) &&
                (installKind == InstallKind.REMOTE ||
                    (installKind == InstallKind.INLINE && content.isBlank())) -> sourceUrl

            else -> ""
        }

    /** 需要下载并解析的 MCP 配置地址（内嵌 content 为空时才有值）。 */
    val mcpConfigUrl: String
        get() = when {
            installKind.needsRuntime -> ""
            content.isNotBlank() -> ""
            sourceUrl.isNotBlank() && isMcpConfigPayloadUrl(sourceUrl) -> sourceUrl
            else -> ""
        }

    /** 这个 MCP 条目是否需要先拿到一份配置 payload 才能安装。 */
    val hasMcpConfigPayload: Boolean
        get() = type == RecommendType.MCP &&
            !installKind.needsRuntime &&
            (content.isNotBlank() || mcpConfigUrl.isNotBlank())
}

/** 被跳过的条目，[reason] 会在 UI 上以「N 条被跳过」的形式展示。 */
data class SkippedRecommendEntry(
    val id: String,
    val name: String,
    val reason: String,
)

sealed interface RecommendIndexParseResult {
    data class Success(
        val entries: List<RecommendEntry>,
        val skipped: List<SkippedRecommendEntry>,
    ) : RecommendIndexParseResult

    data class Invalid(val message: String) : RecommendIndexParseResult
}

/** 协议原始字段；解析失败的单条会被跳过，所以这里所有字段都得有默认值。 */
@Serializable
internal data class RecommendEntryDto(
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val version: String = "",
    val author: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("source_url") val sourceUrl: String = "",
    val sha256: String = "",
    @SerialName("install_kind") val installKind: String = "",
    val deps: List<String> = emptyList(),
    @SerialName("deploy_doc") val deployDoc: String = "",
    val sort: Int = 0,
    // 以下三个是协议的可选扩展字段，内嵌内容与自定义启动命令都靠它们表达
    val content: String = "",
    @SerialName("remote_url") val remoteUrl: String = "",
    @SerialName("run_command") val runCommand: String = "",
    // MCP 专用字段。除了本协议推荐的 mcp_* 写法，也接受更直观的 url/headers/oauth/transport，
    // 方便直接把 Claude Desktop 风格的配置片段放进 index。
    val url: JsonElement? = null,
    val headers: JsonElement? = null,
    val oauth: JsonElement? = null,
    val transport: JsonElement? = null,
    @SerialName("mcp_transport") val mcpTransport: String = "",
    @SerialName("mcp_headers") val mcpHeaders: JsonElement? = null,
    @SerialName("mcp_oauth") val mcpOauth: JsonElement? = null,
    // DSH 扩展字段：用于展示详情、定位 npm/Git 包与记录兼容级别。
    @SerialName("dsh_package") val dshPackage: String = "",
    @SerialName("dsh_repository") val dshRepository: String = "",
    @SerialName("dsh_homepage") val dshHomepage: String = "",
    @SerialName("dsh_icon") val dshIcon: String = "",
    @SerialName("dsh_category") val dshCategory: String = "",
    @SerialName("dsh_category_label") val dshCategoryLabel: String = "",
    @SerialName("dsh_stars") val dshStars: Int = 0,
    @SerialName("dsh_engines") val dshEngines: String = "",
    @SerialName("dsh_source_type") val dshSourceType: String = "",
    @SerialName("dsh_registry") val dshRegistry: String = "",
    @SerialName("dsh_source_commit") val dshSourceCommit: String = "",
    @SerialName("dsh_kind") val dshKind: String = "",
)

/**
 * 解析用的宽松 Json：
 * - `ignoreUnknownKeys`：新版本 index 多加字段时老客户端不会整份解析失败
 * - `isLenient` / `coerceInputValues`：字符串里带换行、字段显式给 null 时按默认值处理
 */
internal val RecommendIndexJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

private val ARRAY_KEYS = listOf("items", "entries", "packages", "data")

/**
 * 把一份 index 文本解析成条目列表。
 *
 * 整份文本不是 JSON、或者没有数组字段 → [RecommendIndexParseResult.Invalid]；
 * 单条有问题 → 记进 [RecommendIndexParseResult.Success.skipped]，不影响其它条目。
 */
fun parseRecommendIndex(
    text: String,
    originId: String,
    originName: String,
    baseUrl: String? = null,
): RecommendIndexParseResult {
    val root = runCatching {
        RecommendIndexJson.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
    }.getOrNull()
        ?: return RecommendIndexParseResult.Invalid("index 不是合法的 JSON")

    val array = extractEntryArray(root)
        ?: return RecommendIndexParseResult.Invalid("index 里没有条目数组（items/entries）")

    val entries = mutableListOf<RecommendEntry>()
    val skipped = mutableListOf<SkippedRecommendEntry>()

    array.forEach { element ->
        val raw = runCatching {
            normalizeEntryElement(element)
        }.getOrElse { element }

        val dto = runCatching {
            RecommendIndexJson.decodeFromJsonElement(RecommendEntryDto.serializer(), raw)
        }.getOrNull()

        if (dto == null) {
            val fallback = (raw as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }?.content
            skipped += SkippedRecommendEntry(
                id = fallback.orEmpty(),
                name = "",
                reason = "字段类型不符合协议",
            )
            return@forEach
        }

        when (val result = validateEntryDto(dto, baseUrl)) {
            is EntryValidation.Valid -> entries += result.entry.copy(
                originId = originId,
                originName = originName,
            )

            is EntryValidation.Invalid -> skipped += SkippedRecommendEntry(
                id = dto.id.trim(),
                name = dto.name.trim(),
                reason = result.reason,
            )
        }
    }

    return RecommendIndexParseResult.Success(entries, skipped)
}

private fun extractEntryArray(root: JsonElement): JsonArray? = when (root) {
    is JsonArray -> root
    is JsonObject -> ARRAY_KEYS.firstNotNullOfOrNull { key ->
        (root[key] as? JsonArray)?.takeIf { it.isNotEmpty() }
    } ?: ARRAY_KEYS.firstNotNullOfOrNull { key -> root[key] as? JsonArray }

    else -> null
}

/**
 * 把字段类型写歪的条目尽量掰正：
 * - `tags` / `deps` 写成 `"a, b"` 字符串时拆成数组；
 * - `sort` 写成 `"3"` 时转成数字。
 *
 * 掰不动的原样返回，交给反序列化去报错（该条会被跳过并记录）。
 */
internal fun normalizeEntryElement(element: JsonElement): JsonElement {
    val obj = element as? JsonObject ?: return element
    return JsonObject(
        obj.mapValues { (key, value) ->
            when (key) {
                "tags", "deps" -> when {
                    value is JsonNull -> value
                    value is JsonPrimitive && value.isString ->
                        JsonArray(
                            value.content.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .map { JsonPrimitive(it) }
                        )

                    else -> value
                }

                "sort" -> when {
                    value is JsonPrimitive && !value.isString -> {
                        val parsed = value.content.toIntOrNull()
                        if (parsed != null) JsonPrimitive(parsed) else value
                    }

                    else -> value
                }

                else -> value
            }
        }
    )
}

internal sealed interface EntryValidation {
    data class Valid(val entry: RecommendEntry) : EntryValidation
    data class Invalid(val reason: String) : EntryValidation
}

internal fun validateEntryDto(dto: RecommendEntryDto, baseUrl: String?): EntryValidation {
    val id = dto.id.trim()
    if (id.isEmpty()) return EntryValidation.Invalid("缺少 id")

    val type = RecommendType.fromWire(dto.type)
        ?: return EntryValidation.Invalid("type 缺失或未知：${dto.type.ifBlank { "(空)" }}")

    val installKind = if (dto.installKind.isBlank()) {
        if (type == RecommendType.PLUGIN) InstallKind.DSH else InstallKind.INLINE
    } else {
        InstallKind.fromWire(dto.installKind)
            ?: return EntryValidation.Invalid("install_kind 未知：${dto.installKind}")
    }

    // 相对地址按 index 自身地址解析，避免 `./configs/mcp.json` 这类条目被误判成非法
    val sourceUrl = resolveRelativeHttpUrl(dto.sourceUrl, baseUrl)
    if (sourceUrl.isNotEmpty() && !isHttpUrl(sourceUrl)) {
        return EntryValidation.Invalid("source_url 不是 http/https 地址")
    }

    val urlAlias = (dto.url as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val rawRemoteUrl = dto.remoteUrl.ifBlank { if (type == RecommendType.MCP) urlAlias else "" }
    val remoteUrl = resolveRelativeHttpUrl(rawRemoteUrl, baseUrl)
    if (remoteUrl.isNotEmpty() && !isHttpUrl(remoteUrl)) {
        return EntryValidation.Invalid("remote_url 不是 http/https 地址")
    }

    // MCP 专用扩展字段；非 MCP 条目忽略，避免旧 index 里同名字段造成误伤
    val transportAlias = (dto.transport as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val rawTransport = if (type == RecommendType.MCP) {
        dto.mcpTransport.ifBlank { transportAlias }
    } else {
        ""
    }
    val mcpTransport = if (rawTransport.isNotBlank()) {
        McpTransport.fromWire(rawTransport)
            ?: return EntryValidation.Invalid("mcp_transport 未知：$rawTransport")
    } else {
        McpTransport.STREAMABLE_HTTP
    }
    val mcpHeaders = if (type == RecommendType.MCP) {
        when (val parsed = parseMcpHeadersElement(dto.mcpHeaders ?: dto.headers)) {
            McpHeadersParseResult.Absent -> emptyList()
            is McpHeadersParseResult.Parsed -> parsed.headers
            is McpHeadersParseResult.Invalid -> return EntryValidation.Invalid(parsed.message)
        }
    } else {
        emptyList()
    }
    val mcpOauth = if (type == RecommendType.MCP) {
        when (val parsed = parseMcpOAuthElement(dto.mcpOauth ?: dto.oauth)) {
            McpOAuthParseResult.Absent -> null
            is McpOAuthParseResult.Parsed -> parsed.state
            is McpOAuthParseResult.Invalid -> return EntryValidation.Invalid(parsed.message)
        }
    } else {
        null
    }

    // 没给内嵌内容又没给下载/服务地址，装不出东西来
    val hasMcpRemoteUrl = type == RecommendType.MCP && remoteUrl.isNotEmpty()
    if (installKind == InstallKind.INLINE && dto.content.isBlank() &&
        sourceUrl.isEmpty() && !hasMcpRemoteUrl
    ) {
        return EntryValidation.Invalid("inline 条目既没有 content 也没有 source_url")
    }
    if (type == RecommendType.PLUGIN && dto.dshPackage.isBlank()) {
        return EntryValidation.Invalid("DSH 插件缺少 dsh_package")
    }
    if (type == RecommendType.PLUGIN && installKind != InstallKind.DSH) {
        return EntryValidation.Invalid("DSH 插件必须使用 install_kind=dsh")
    }
    if (type != RecommendType.PLUGIN && dto.dshPackage.isNotBlank()) {
        return EntryValidation.Invalid("非 plugin 条目不能声明 dsh_package")
    }
    val hasMcpInlineContent = type == RecommendType.MCP && dto.content.isNotBlank()
    if (installKind == InstallKind.REMOTE && sourceUrl.isEmpty() &&
        remoteUrl.isEmpty() && !hasMcpInlineContent
    ) {
        return EntryValidation.Invalid(
            if (type == RecommendType.MCP) {
                "remote 条目缺少地址（mcp 需要 url/source_url/content）"
            } else {
                "remote 条目缺少地址"
            }
        )
    }

    val sha256 = normalizeSha256(dto.sha256)
    if (dto.sha256.isNotBlank() && sha256 == null) {
        return EntryValidation.Invalid("sha256 不是 64 位十六进制")
    }

    val name = dto.name.trim().ifEmpty { id }

    val dshPort = if (type == RecommendType.PLUGIN) {
        DshPorts.find(dto.dshPackage, dto.dshRepository)
    } else {
        null
    }
    val dshKind = when {
        dshPort != null -> DshPluginKind.PORT
        else -> DshPluginKind.fromWire(dto.dshKind) ?: DshPluginKind.AUTO
    }

    return EntryValidation.Valid(
        RecommendEntry(
            id = id,
            type = type,
            name = name,
            version = dto.version.trim(),
            author = dto.author.trim(),
            description = dto.description.trim(),
            tags = dto.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            sourceUrl = sourceUrl,
            sha256 = sha256.orEmpty(),
            installKind = installKind,
            deps = dto.deps.map { it.trim() }.filter { it.isNotEmpty() },
            deployDoc = dto.deployDoc,
            sort = dto.sort,
            content = dto.content,
            remoteUrl = remoteUrl,
            runCommand = dto.runCommand.trim(),
            originId = "",
            originName = "",
            mcpTransport = mcpTransport,
            mcpHeaders = mcpHeaders,
            mcpOauth = mcpOauth,
            dshPackage = dto.dshPackage.trim(),
            dshRepository = dto.dshRepository.trim(),
            dshHomepage = dto.dshHomepage.trim(),
            dshIconUrl = dto.dshIcon.trim(),
            dshCategory = dto.dshCategory.trim(),
            dshCategoryLabel = dto.dshCategoryLabel.trim(),
            dshStars = dto.dshStars.coerceAtLeast(0),
            dshEngines = dto.dshEngines.trim(),
            dshSourceType = dto.dshSourceType.trim(),
            dshRegistry = dto.dshRegistry.trim(),
            dshSourceCommit = dto.dshSourceCommit.trim(),
            dshKind = dshKind,
            dshPortAsset = dshPort?.assetPath.orEmpty(),
            dshPortName = dshPort?.name.orEmpty(),
        )
    )
}

/**
 * 相对地址按 index 自己的地址解析（GitHub 上常见的 `./configs/mcp.json`）。
 *
 * 没有 baseUrl、或者地址本身带了别的 scheme（ftp://、file://）时原样返回，
 * 交给 [isHttpUrl] 拒绝；协议相对地址 `//host/x` 也不解析，避免绕过来源校验。
 */
internal fun resolveRelativeHttpUrl(raw: String, baseUrl: String?): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    if (isHttpUrl(trimmed)) return trimmed
    if (baseUrl.isNullOrBlank() || !isHttpUrl(baseUrl)) return trimmed
    if (trimmed.startsWith("//") || trimmed.contains("://")) return trimmed
    return runCatching { java.net.URI(baseUrl).resolve(trimmed).toString() }.getOrDefault(trimmed)
}

/** `.json`/`.zip`/`.mcpb` 这类地址是配置 payload，不是 MCP 端点，安装时必须下载解析。 */
fun isMcpConfigPayloadUrl(url: String): Boolean {
    val path = url.trim().substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".json") || path.endsWith(".jsonc") ||
        path.endsWith(".zip") || path.endsWith(".mcpb")
}

/** 只接受 http/https，其它 scheme（file://、content://、data:）一律当非法，避免被引导去本地路径。 */
fun isHttpUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return false
    }
    val host = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return host.isNotBlank() && !host.contains(' ') && host.contains('.')
}

/** 规范化 sha256：去空白、转小写；不是 64 位十六进制时返回 null。 */
fun normalizeSha256(value: String): String? {
    val normalized = value.trim().lowercase().replace(" ", "")
    if (normalized.length != 64) return null
    if (!normalized.all { it in '0'..'9' || it in 'a'..'f' }) return null
    return normalized
}

/** 计算字节流的 sha256（小写十六进制），用于安装前校验。 */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** 校验内容与 index 声明的 sha256 是否一致；声明缺失时返回 false，由调用方决定是否放行。 */
fun matchesSha256(bytes: ByteArray, expected: String): Boolean {
    val normalized = normalizeSha256(expected) ?: return false
    return sha256Hex(bytes) == normalized
}

/** 按用户选的排序方式排列表。 */
fun sortEntries(entries: List<RecommendEntry>, sort: RecommendSort): List<RecommendEntry> =
    when (sort) {
        RecommendSort.DEFAULT -> entries.sortedWith(compareBy({ it.sort }, { it.name.lowercase() }))
        RecommendSort.NAME_ASC -> entries.sortedBy { it.name.lowercase() }
        RecommendSort.NAME_DESC -> entries.sortedByDescending { it.name.lowercase() }
        RecommendSort.AUTHOR -> entries.sortedWith(compareBy({ it.author.lowercase() }, { it.sort }, { it.name.lowercase() }))
        RecommendSort.RUNTIME -> entries.sortedWith(
            compareBy({ installKindPriority(it.installKind) }, { it.sort }, { it.name.lowercase() })
        )
    }

/** RUNTIME 排序里各类安装方式的分组顺序：一键能用的在前，要跑命令的在后面。 */
private fun installKindPriority(kind: InstallKind): Int = when (kind) {
    InstallKind.INLINE, InstallKind.DSH -> 0
    InstallKind.REMOTE -> 1
    InstallKind.NPX -> 2
    InstallKind.UVX -> 3
    InstallKind.DOCKER -> 4
}

/** 搜索：匹配名称 / 描述 / 作者 / 标签 / id。 */
fun filterEntries(entries: List<RecommendEntry>, query: String): List<RecommendEntry> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return entries
    return entries.filter { entry ->
        entry.name.contains(keyword, ignoreCase = true) ||
            entry.description.contains(keyword, ignoreCase = true) ||
            entry.author.contains(keyword, ignoreCase = true) ||
            entry.id.contains(keyword, ignoreCase = true) ||
            entry.dshPackage.contains(keyword, ignoreCase = true) ||
            entry.dshCategoryLabel.contains(keyword, ignoreCase = true) ||
            entry.tags.any { it.contains(keyword, ignoreCase = true) }
    }
}

/** 需要在设备上跑起来的启动命令；inline/remote 没有命令。 */
fun runtimeCommand(entry: RecommendEntry): String = entry.runCommand.ifBlank {
    when (entry.installKind) {
        InstallKind.NPX -> "npx -y ${entry.id}"
        InstallKind.UVX -> "uvx ${entry.id}"
        InstallKind.DOCKER -> "docker run -i --rm ${entry.id}"
        InstallKind.REMOTE, InstallKind.INLINE, InstallKind.DSH -> ""
    }
}

/** 检测运行时的命令，部署页的「检测运行时」按钮用。 */
fun runtimeProbeCommand(kind: InstallKind): String = when (kind) {
    InstallKind.NPX -> "node --version && npx --version"
    InstallKind.UVX -> "uv --version || uvx --version"
    InstallKind.DOCKER -> "docker --version"
    InstallKind.REMOTE, InstallKind.INLINE, InstallKind.DSH -> ""
}

/**
 * skill 压缩包的解包结果。
 *
 * [files] 的 key 已经剥掉了 skill 根目录前缀（SKILL.md 固定在根），可以直接交给 SkillManager 落盘。
 */
data class SkillArchive(
    val files: Map<String, ByteArray>,
    val skillName: String,
)

sealed interface SkillArchiveResult {
    data class Success(val archive: SkillArchive) : SkillArchiveResult
    data class Failure(val message: String) : SkillArchiveResult
}

/**
 * 解出压缩包里的一个 skill。
 *
 * 只取**最外层**的 SKILL.md（压缩包里常见 `repo-main/skills/foo/SKILL.md` 这种多层结构），
 * 并把同目录下的附属文件一并带上；`..` 之类的路径直接丢弃，防止把内容写到沙箱外。
 */
fun extractSkillArchive(bytes: ByteArray): SkillArchiveResult {
    val files = LinkedHashMap<String, ByteArray>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeArchivePath(entry.name)
                        if (path != null) files[path] = zip.readBytes()
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
    } catch (e: Exception) {
        return SkillArchiveResult.Failure("解压失败：${e.message ?: "压缩包已损坏"}")
    }

    if (files.isEmpty()) return SkillArchiveResult.Failure("压缩包里没有文件")

    val skillMdPath = files.keys
        .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        .minByOrNull { it.count { char -> char == '/' } }
        ?: return SkillArchiveResult.Failure("压缩包里没有 SKILL.md")

    val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")

    // 只保留这一个 skill 目录下的文件：嵌套的子 skill 目录会被丢弃
    val nestedSkillBases = files.keys
        .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        .map { it.substringBeforeLast('/', missingDelimiterValue = "") }
        .filter { it != basePath && isInsidePath(it, basePath) }

    val relativeFiles = LinkedHashMap<String, ByteArray>()
    for ((path, content) in files) {
        if (nestedSkillBases.any { nested -> isInsidePath(path, nested) }) continue
        val relative = if (basePath.isEmpty()) path else {
            if (path == basePath) continue
            path.removePrefix("$basePath/").takeIf { it != path } ?: continue
        }
        val target = if (relative.equals("SKILL.md", ignoreCase = true)) "SKILL.md" else relative
        relativeFiles[target] = content
    }

    val skillMd = relativeFiles["SKILL.md"]?.toString(Charsets.UTF_8)
        ?: return SkillArchiveResult.Failure("SKILL.md 读取失败")

    val frontmatter = SkillFrontmatterParser.parse(skillMd)
    val name = frontmatter["name"]?.trim().orEmpty()
    if (name.isBlank()) return SkillArchiveResult.Failure("SKILL.md 缺少 name 字段")
    if (frontmatter["description"].isNullOrBlank()) {
        return SkillArchiveResult.Failure("SKILL.md 缺少 description 字段")
    }

    return SkillArchiveResult.Success(SkillArchive(files = relativeFiles, skillName = name))
}

private fun isInsidePath(path: String, base: String): Boolean =
    base.isNotEmpty() && (path == base || path.startsWith("$base/"))

/** 归一化压缩包内路径：丢掉绝对路径、`.`、`..` 段，返回 null 表示该条目不可用。 */
internal fun normalizeArchivePath(path: String): String? {
    val parts = path.replace('\\', '/')
        .trimStart('/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }
    if (parts.isEmpty() || parts.any { it == ".." }) return null
    return parts.joinToString("/")
}
