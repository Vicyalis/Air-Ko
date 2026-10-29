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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 适配结果：转换成功给出本协议 JSON；[NotApplicable] 表示按原生协议解析。 */
internal sealed interface SourceConvertResult {
    data class Converted(val json: String, val note: String?) : SourceConvertResult
    data object NotApplicable : SourceConvertResult
    data class Failed(val message: String) : SourceConvertResult
}

internal fun convertSourceText(source: RecommendSource, raw: String): SourceConvertResult =
    when (source.format) {
        RecommendSourceFormat.NATIVE -> SourceConvertResult.NotApplicable
        RecommendSourceFormat.CLAUDE_MARKETPLACE -> convertClaudeMarketplace(source, raw)
        RecommendSourceFormat.MCP_REGISTRY -> convertMcpRegistry(source, raw)
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
