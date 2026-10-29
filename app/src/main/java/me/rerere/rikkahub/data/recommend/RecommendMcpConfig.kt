package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState

/**
 * 推荐条目里的 MCP 传输方式。
 *
 * 商店 index 用 `mcp_transport` 表达；解析 JSON 配置时同时接受
 * `streamable-http`、`streamable_http`、`http` 与 `sse` 几种写法。
 */
enum class McpTransport(val wire: String) {
    STREAMABLE_HTTP("streamable_http"),
    SSE("sse");

    val displayName: String
        get() = when (this) {
            STREAMABLE_HTTP -> "Streamable HTTP"
            SSE -> "SSE"
        }

    companion object {
        fun fromWire(value: String?): McpTransport? {
            val normalized = value?.trim()?.lowercase()?.replace('-', '_') ?: return null
            return when (normalized) {
                "", "streamable_http", "streamablehttp", "http" -> STREAMABLE_HTTP
                "sse" -> SSE
                else -> null
            }
        }
    }
}

/** 从 index / 下载到的 JSON 里解析出来的 MCP 连接信息（还没套上显示名与 id）。 */
internal data class McpServerDraft(
    val transport: McpTransport,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val oauth: McpOAuthState? = null,
    val name: String = "",
)

internal sealed interface McpConfigParseResult {
    data class Success(val draft: McpServerDraft) : McpConfigParseResult
    data class Failure(val message: String) : McpConfigParseResult
}

internal sealed interface McpHeadersParseResult {
    data object Absent : McpHeadersParseResult
    data class Parsed(val headers: List<Pair<String, String>>) : McpHeadersParseResult
    data class Invalid(val message: String) : McpHeadersParseResult
}

internal sealed interface McpOAuthParseResult {
    data object Absent : McpOAuthParseResult
    data class Parsed(val state: McpOAuthState) : McpOAuthParseResult
    data class Invalid(val message: String) : McpOAuthParseResult
}

/**
 * 解析 MCP 配置 payload：支持 JSON 与 zip（zip 里找一个能用的 JSON 配置文件）。
 *
 * 兼容常见写法：
 * - Claude Desktop / 设置页导入格式：`{"mcpServers":{"name":{"type":"sse","url":"…","headers":{…}}}}`
 * - 单个 server 对象：`{"type":"streamable_http","url":"…","headers":{…},"oauth":{…}}`
 * - MCP 注册表 server.json：`{"name":"…","remotes":[{"type":"streamable-http","url":"…"}]}`
 * - 列表：`{"servers":[{"server":{…}}]}` 或直接数组
 *
 * 本地 stdio（`command`/`args`）会明确报错，而不是写一条永远连不上的配置。
 */
internal fun parseMcpConfigPayload(
    bytes: ByteArray,
    preferredName: String? = null,
): McpConfigParseResult {
    if (bytes.isEmpty()) return McpConfigParseResult.Failure("MCP 配置为空（0 字节）")
    if (isZipPayload(bytes)) return parseMcpConfigZip(bytes, preferredName)
    val text = bytes.toString(Charsets.UTF_8)
    if (text.isBlank()) return McpConfigParseResult.Failure("MCP 配置为空")
    return parseMcpConfigJson(text, preferredName)
}

internal fun parseMcpConfigJson(
    text: String,
    preferredName: String? = null,
): McpConfigParseResult {
    // 配置文件带 UTF-8 BOM 时 parseToJsonElement 会直接失败，先去掉
    val trimmed = text.trim().removePrefix("\uFEFF").trim()
    if (trimmed.isEmpty()) return McpConfigParseResult.Failure("MCP 配置为空")
    if (trimmed.startsWith("<")) {
        return McpConfigParseResult.Failure("MCP 配置是 HTML 页面而不是 JSON（下载地址可能指向了网页）")
    }
    val root = runCatching { RecommendIndexJson.parseToJsonElement(trimmed) }.getOrNull()
        ?: return McpConfigParseResult.Failure("MCP 配置不是合法的 JSON")
    val selected = selectMcpServer(root, preferredName)
        ?: return McpConfigParseResult.Failure("MCP 配置里没有可用的 server（缺少 mcpServers/url/remotes）")
    return parseMcpServerObject(selected.obj, selected.name)
}

internal fun parseMcpHeadersElement(element: JsonElement?): McpHeadersParseResult {
    if (element == null || element is JsonNull) return McpHeadersParseResult.Absent
    val headers = LinkedHashMap<String, String>()
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            val name = key.trim()
            if (name.isEmpty()) return@forEach
            val headerValue = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            headers[name] = headerValue
        }

        is JsonArray -> element.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val name = obj.stringOrNull("name")
                ?: obj.stringOrNull("key")
                ?: obj.stringOrNull("header")
                ?: return@forEach
            val value = obj.stringOrNull("value")
                ?: obj.stringOrNull("default")
                ?: return@forEach
            headers[name] = value
        }

        else -> return McpHeadersParseResult.Invalid("MCP 配置的 headers 必须是对象或数组")
    }
    return McpHeadersParseResult.Parsed(headers.map { it.key to it.value })
}

internal fun parseMcpOAuthElement(element: JsonElement?): McpOAuthParseResult {
    if (element == null || element is JsonNull) return McpOAuthParseResult.Absent
    if (element is JsonPrimitive) {
        val enabled = element.booleanOrNull
            ?: return McpOAuthParseResult.Invalid("MCP 配置的 oauth 必须是布尔值或对象")
        return McpOAuthParseResult.Parsed(McpOAuthState(enabled = enabled))
    }
    val obj = element as? JsonObject
        ?: return McpOAuthParseResult.Invalid("MCP 配置的 oauth 必须是布尔值或对象")

    val scopes = obj["scopes"]
    val scope = obj.stringOrNull("scope") ?: when (scopes) {
        is JsonArray -> scopes
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { value -> value.isNotEmpty() } }
            .joinToString(" ")
            .ifBlank { null }

        is JsonPrimitive -> scopes.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        else -> null
    }

    return McpOAuthParseResult.Parsed(
        McpOAuthState(
            enabled = obj.booleanOrNull("enabled") ?: true,
            clientId = obj.stringOrNull("clientId") ?: obj.stringOrNull("client_id"),
            clientSecret = obj.stringOrNull("clientSecret") ?: obj.stringOrNull("client_secret"),
            authorizationEndpoint = obj.stringOrNull("authorizationEndpoint")
                ?: obj.stringOrNull("authorization_endpoint"),
            tokenEndpoint = obj.stringOrNull("tokenEndpoint") ?: obj.stringOrNull("token_endpoint"),
            registrationEndpoint = obj.stringOrNull("registrationEndpoint")
                ?: obj.stringOrNull("registration_endpoint"),
            redirectUri = obj.stringOrNull("redirectUri") ?: obj.stringOrNull("redirect_uri"),
            scope = scope,
            accessToken = obj.stringOrNull("accessToken") ?: obj.stringOrNull("access_token"),
            refreshToken = obj.stringOrNull("refreshToken") ?: obj.stringOrNull("refresh_token"),
            expiresAt = obj.longOrNull("expiresAt") ?: obj.longOrNull("expires_at") ?: 0L,
        )
    )
}

internal fun isZipPayload(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

// ---------------------------------------------------------------------------------------------
// 内部解析
// ---------------------------------------------------------------------------------------------

private data class SelectedMcpServer(
    val name: String?,
    val obj: JsonObject,
)

private fun selectMcpServer(root: JsonElement, preferredName: String?): SelectedMcpServer? {
    return when (root) {
        is JsonArray -> root.firstNotNullOfOrNull { selectMcpServer(it, preferredName) }

        is JsonObject -> {
            val collectionKey = listOf("mcpServers", "mcp_servers", "servers")
                .firstOrNull { root.containsKey(it) }
            if (collectionKey != null) {
                selectFromCollection(root.getValue(collectionKey), preferredName)
            } else {
                val server = root["server"]
                if (server is JsonObject) {
                    selectMcpServer(server, preferredName)
                } else if (root.containsKey("remotes") || root.containsKey("url")) {
                    SelectedMcpServer(root.stringOrNull("name") ?: root.stringOrNull("title"), root)
                } else {
                    root.values.firstNotNullOfOrNull { value ->
                        if (value is JsonObject) selectMcpServer(value, preferredName) else null
                    }
                }
            }
        }

        else -> null
    }
}

private fun selectFromCollection(value: JsonElement, preferredName: String?): SelectedMcpServer? {
    return when (value) {
        is JsonObject -> {
            // 有些 index 把单个 server 直接写在 mcpServers 里（没有 name 这一层）
            if (value.containsKey("url") || value.containsKey("command") ||
                value.containsKey("remotes") || value.containsKey("server")
            ) {
                selectMcpServer(value, preferredName)
            } else {
                val entries = value.entries.filter { it.value is JsonObject }
                val chosen = preferredName?.let { name ->
                    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
                } ?: entries.firstOrNull()
                chosen?.let { (name, element) ->
                    val obj = element as JsonObject
                    if (obj.containsKey("server")) {
                        selectMcpServer(obj, preferredName)
                    } else {
                        SelectedMcpServer(name, obj)
                    }
                }
            }
        }

        is JsonArray -> {
            val objects = value.filterIsInstance<JsonObject>()
            val preferred = preferredName?.let { name ->
                objects.firstOrNull {
                    it.stringOrNull("name").equals(name, ignoreCase = true) ||
                        it.stringOrNull("id").equals(name, ignoreCase = true)
                }
            }
            (listOfNotNull(preferred) + objects.filter { it !== preferred })
                .firstNotNullOfOrNull { obj ->
                    if (obj.containsKey("server") || obj.containsKey("url") ||
                        obj.containsKey("remotes") || obj.containsKey("command")
                    ) {
                        selectMcpServer(obj, preferredName)
                    } else {
                        null
                    }
                }
        }

        else -> null
    }
}

private fun parseMcpServerObject(obj: JsonObject, fallbackName: String?): McpConfigParseResult {
    val remotes = obj["remotes"] as? JsonArray
    if (remotes != null) {
        val remote = remotes.filterIsInstance<JsonObject>().firstOrNull {
            !it.stringOrNull("url").isNullOrBlank()
        } ?: return McpConfigParseResult.Failure("MCP 配置的 remotes 里没有可用的 url")
        return parseMcpServerObject(remote, fallbackName)
    }

    // 有些 server.json 把连接参数包在 transport 对象里：
    // {"transport":{"type":"streamable-http","url":"…","headers":[…]}}
    val transportObject = obj["transport"] as? JsonObject
    val url = obj.stringOrNull("url")
        ?: obj.stringOrNull("serverUrl")
        ?: obj.stringOrNull("endpoint")
        ?: transportObject?.stringOrNull("url")
    if (url.isNullOrBlank()) {
        if (obj.containsKey("command") || obj.containsKey("args")) {
            return McpConfigParseResult.Failure(
                "这是本地 stdio MCP 配置（command/args），本 App 只支持远程 HTTP；请先用代理暴露 HTTP 地址"
            )
        }
        return McpConfigParseResult.Failure("MCP 配置缺少 url 字段")
    }
    if (!isHttpUrl(url)) {
        return McpConfigParseResult.Failure("MCP 配置的 url 不是 http/https：$url")
    }

    val rawTransport = obj.stringOrNull("type")
        ?: obj.stringOrNull("transport")
        ?: obj.stringOrNull("transportType")
        ?: transportObject?.stringOrNull("type")
    val transport = if (rawTransport.isNullOrBlank()) {
        McpTransport.STREAMABLE_HTTP
    } else {
        McpTransport.fromWire(rawTransport)
            ?: return McpConfigParseResult.Failure("MCP 配置的传输类型不受支持：$rawTransport")
    }

    val headers = when (val parsed = parseMcpHeadersElement(obj["headers"] ?: transportObject?.get("headers"))) {
        McpHeadersParseResult.Absent -> emptyList()
        is McpHeadersParseResult.Parsed -> parsed.headers
        is McpHeadersParseResult.Invalid -> return McpConfigParseResult.Failure(parsed.message)
    }

    // authorization 可能是 OAuth 对象/布尔值，也可能只是请求头的字符串；后者不当 OAuth 处理
    val authorizationElement = obj["authorization"]?.takeIf { element ->
        element is JsonObject || (element is JsonPrimitive && element.booleanOrNull != null)
    }
    val oauth = when (val parsed = parseMcpOAuthElement(obj["oauth"] ?: authorizationElement)) {
        McpOAuthParseResult.Absent -> null
        is McpOAuthParseResult.Parsed -> parsed.state
        is McpOAuthParseResult.Invalid -> return McpConfigParseResult.Failure(parsed.message)
    }

    val name = obj.stringOrNull("name") ?: obj.stringOrNull("title") ?: fallbackName.orEmpty()
    return McpConfigParseResult.Success(
        McpServerDraft(
            transport = transport,
            url = url,
            headers = headers,
            oauth = oauth,
            name = name,
        )
    )
}

private fun parseMcpConfigZip(bytes: ByteArray, preferredName: String?): McpConfigParseResult {
    val candidates = mutableListOf<Pair<String, String>>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        val path = normalizeArchivePath(entry.name)
                        if (path != null &&
                            (path.endsWith(".json", ignoreCase = true) ||
                                path.endsWith(".jsonc", ignoreCase = true))
                        ) {
                            val content = zip.readEntryBytes(MAX_MCP_CONFIG_FILE_BYTES)
                                .toString(Charsets.UTF_8)
                            if (content.isNotBlank()) candidates += path to content
                            if (candidates.size >= MAX_MCP_CONFIG_FILES) break
                        }
                    }
                } finally {
                    zip.closeEntry()
                }
            }
        }
    } catch (e: Exception) {
        return McpConfigParseResult.Failure("解压 MCP 配置失败：${e.message ?: "压缩包已损坏"}")
    }

    if (candidates.isEmpty()) {
        return McpConfigParseResult.Failure("压缩包里没有 JSON 配置文件")
    }

    var lastError = "MCP 配置不可用"
    for ((_, content) in candidates.sortedBy { mcpConfigFileScore(it.first) }) {
        when (val result = parseMcpConfigJson(content, preferredName)) {
            is McpConfigParseResult.Success -> return result
            is McpConfigParseResult.Failure -> lastError = result.message
        }
    }
    return McpConfigParseResult.Failure("压缩包里的 JSON 不是可用的 MCP 配置：$lastError")
}

private fun mcpConfigFileScore(path: String): Int {
    val name = path.substringAfterLast('/').lowercase()
    return when {
        name == "server.json" || name == "mcp.json" || name == ".mcp.json" -> 0
        name == "manifest.json" -> 1
        name.contains("mcp") -> 2
        name.contains("config") -> 3
        else -> 10
    }
}

private fun ZipInputStream.readEntryBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw IOException("单个配置文件超过 ${limit / 1024} KB")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.booleanOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.longOrNull

private const val MAX_MCP_CONFIG_FILES = 20
private const val MAX_MCP_CONFIG_FILE_BYTES = 2 * 1024 * 1024
