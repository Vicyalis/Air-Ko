package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.rerere.rikkahub.data.ai.mcp.isValidMcpServerName
import me.rerere.rikkahub.data.ai.mcp.sanitizeMcpServerName
import me.rerere.rikkahub.data.ai.mcp.uniqueMcpServerName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 安装链路里最容易出错的几段纯逻辑：
 * payload 解析（mcpServers / remotes / zip）、名称规范化、index 字段映射。
 */
class RecommendMcpConfigTest {
    @Test
    fun `parses mcpServers payload with sse headers and oauth`() {
        val json = """
            {
              "mcpServers": {
                "demo": {
                  "type": "sse",
                  "url": "https://mcp.example.com/sse",
                  "headers": {"Authorization": "Bearer token"},
                  "oauth": {"enabled": true, "scope": "tools.read"}
                }
              }
            }
        """.trimIndent()

        val result = parseMcpConfigJson(json) as McpConfigParseResult.Success

        assertEquals(McpTransport.SSE, result.draft.transport)
        assertEquals("https://mcp.example.com/sse", result.draft.url)
        assertEquals(listOf("Authorization" to "Bearer token"), result.draft.headers)
        assertTrue(result.draft.oauth?.enabled == true)
        assertEquals("tools.read", result.draft.oauth?.scope)
    }

    @Test
    fun `parses registry style remotes and keeps transport`() {
        val json = """
            {
              "name": "ac.example/mcp",
              "remotes": [
                {"type": "streamable-http", "url": "https://mcp.example.com/mcp"}
              ]
            }
        """.trimIndent()

        val result = parseMcpConfigJson(json) as McpConfigParseResult.Success

        assertEquals(McpTransport.STREAMABLE_HTTP, result.draft.transport)
        assertEquals("https://mcp.example.com/mcp", result.draft.url)
    }

    @Test
    fun `rejects stdio config instead of writing an unusable server`() {
        val json = """{"mcpServers":{"local":{"command":"npx","args":["-y","server"]}}}"""

        val result = parseMcpConfigJson(json)

        assertTrue(result is McpConfigParseResult.Failure)
        assertTrue((result as McpConfigParseResult.Failure).message.contains("stdio"))
    }

    @Test
    fun `parses zip payload by finding the config json`() {
        val zip = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zipOutput ->
                zipOutput.putNextEntry(ZipEntry("package/readme.txt"))
                zipOutput.write("not a config".toByteArray())
                zipOutput.closeEntry()
                zipOutput.putNextEntry(ZipEntry("package/server.json"))
                zipOutput.write(
                    """{"url":"https://mcp.example.com/mcp","type":"streamable_http"}""".toByteArray()
                )
                zipOutput.closeEntry()
            }
        }.toByteArray()

        val result = parseMcpConfigPayload(zip) as McpConfigParseResult.Success

        assertEquals("https://mcp.example.com/mcp", result.draft.url)
        assertEquals(McpTransport.STREAMABLE_HTTP, result.draft.transport)
    }

    @Test
    fun `sanitizes display names into mcp safe names`() {
        assertEquals("DeepWiki", sanitizeMcpServerName("DeepWiki"))
        assertEquals("MCP", sanitizeMcpServerName("MCP 官方示例服务器"))
        assertEquals("servereverything", sanitizeMcpServerName("", "server-everything"))
        assertEquals("MCPServer", sanitizeMcpServerName("中文", "中文"))
        assertTrue(isValidMcpServerName("DeepWiki2"))
        assertFalse(isValidMcpServerName("Deep Wiki"))
        assertEquals("DeepWiki2", uniqueMcpServerName("DeepWiki", setOf("DeepWiki")))
    }

    @Test
    fun `index maps mcp transport headers oauth and relative urls`() {
        val text = """
            [{
              "id":"demo","type":"mcp","name":"Demo","install_kind":"remote",
              "source_url":"./configs/demo.json",
              "mcp_transport":"sse",
              "mcp_headers":{"Authorization":"Bearer token"},
              "mcp_oauth":{"enabled":true,"scope":"tools.read"}
            }]
        """.trimIndent()

        val entry = (parseRecommendIndex(
            text,
            originId = "src",
            originName = "src",
            baseUrl = "https://example.com/recommend/index.json",
        ) as RecommendIndexParseResult.Success).entries.single()

        assertEquals("https://example.com/recommend/configs/demo.json", entry.sourceUrl)
        assertEquals(McpTransport.SSE, entry.mcpTransport)
        assertEquals(listOf("Authorization" to "Bearer token"), entry.mcpHeaders)
        assertEquals("tools.read", entry.mcpOauth?.scope)
        // 配置文件地址不是 MCP 端点，安装时必须先下载解析
        assertEquals("", entry.mcpDirectUrl)
        assertEquals("https://example.com/recommend/configs/demo.json", entry.mcpConfigUrl)
    }
}
