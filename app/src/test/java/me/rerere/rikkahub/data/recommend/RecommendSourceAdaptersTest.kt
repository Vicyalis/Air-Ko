package me.rerere.rikkahub.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公开目录 → 本协议 的适配器（Claude 插件市场 / MCP 官方注册表）。 */
class RecommendSourceAdaptersTest {
    private val marketplaceSource = RecommendSource(
        id = "anthropic-skills",
        name = "Anthropic Skills",
        url = "https://raw.githubusercontent.com/anthropics/skills/main/.claude-plugin/marketplace.json",
        builtin = true,
        format = RecommendSourceFormat.CLAUDE_MARKETPLACE,
    )

    private val registrySource = RecommendSource(
        id = "mcp-registry",
        name = "MCP Registry",
        url = "https://registry.modelcontextprotocol.io/v0/servers?limit=100",
        builtin = true,
        format = RecommendSourceFormat.MCP_REGISTRY,
    )

    @Test
    fun `claude marketplace converts to skill entries with raw SKILL md urls`() {
        val raw = """
            {"plugins":[{"name":"document-skills","description":"Docs","skills":["./skills/xlsx","./skills/pdf"]}]}
        """.trimIndent()

        val converted = convertSourceText(marketplaceSource, raw)
        assertTrue(converted is SourceConvertResult.Converted)
        val json = (converted as SourceConvertResult.Converted).json

        val parsed = parseRecommendIndex(json, marketplaceSource.id, marketplaceSource.name)
        assertTrue(parsed is RecommendIndexParseResult.Success)
        val entries = (parsed as RecommendIndexParseResult.Success).entries

        assertEquals(2, entries.size)
        val xlsx = entries.first { it.id == "anthropic-xlsx" }
        assertEquals(RecommendType.SKILL, xlsx.type)
        assertEquals(InstallKind.INLINE, xlsx.installKind)
        assertEquals(
            "https://raw.githubusercontent.com/anthropics/skills/main/skills/xlsx/SKILL.md",
            xlsx.sourceUrl,
        )
        assertEquals("Anthropic", xlsx.author)
        assertTrue(xlsx.tags.contains("document-skills"))
    }

    @Test
    fun `mcp registry converts remotes and npm packages`() {
        val raw = """
            {"servers":[
              {"server":{"name":"ac.inference.sh/mcp","title":"inference.sh","description":"Apps",
                "remotes":[{"type":"streamable-http","url":"https://api.inference.sh/mcp"}]}},
              {"server":{"name":"ai.example/tool","packages":[{"registryType":"npm","identifier":"example-mcp","version":"1.0.0"}]}}
            ]}
        """.trimIndent()

        val converted = convertSourceText(registrySource, raw)
        assertTrue(converted is SourceConvertResult.Converted)
        val json = (converted as SourceConvertResult.Converted).json

        val parsed = parseRecommendIndex(json, registrySource.id, registrySource.name)
        assertTrue(parsed is RecommendIndexParseResult.Success)
        val entries = (parsed as RecommendIndexParseResult.Success).entries

        assertEquals(2, entries.size)
        val remote = entries.first { it.id == "mcp-ac.inference.sh-mcp" }
        assertEquals(RecommendType.MCP, remote.type)
        assertEquals(InstallKind.REMOTE, remote.installKind)
        assertEquals("https://api.inference.sh/mcp", remote.resolvedRemoteUrl)

        val npx = entries.first { it.installKind == InstallKind.NPX }
        assertEquals("npx -y example-mcp", npx.runCommand)
    }

    @Test
    fun `broken payload reports a readable failure`() {
        val failed = convertSourceText(marketplaceSource, "not json at all")
        assertTrue(failed is SourceConvertResult.Failed)

        val empty = convertSourceText(registrySource, """{"servers":[]}""")
        assertTrue(empty is SourceConvertResult.Failed)
    }
}
