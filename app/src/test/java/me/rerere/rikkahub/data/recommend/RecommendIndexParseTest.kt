package me.rerere.rikkahub.data.recommend

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Index 解析的宽松档位与「跳过而非污染」的行为。
 *
 * 推荐条目装进来是会改 AI 行为的内容，所以解析规则必须钉死：
 * 缺字段、耍格式的条目要被跳过并给出原因，不能带着可疑数据进安装流程。
 */
class RecommendIndexParseTest {
    @Test
    fun `parse accepts bare array`() {
        val text = """
            [
              {"id":"a","type":"skill","name":"A","install_kind":"inline","content":"hello"}
            ]
        """.trimIndent()

        val result = parseRecommendIndex(text, originId = "src", originName = "测试源")

        val success = result as RecommendIndexParseResult.Success
        assertEquals(1, success.entries.size)
        assertEquals(0, success.skipped.size)
        val entry = success.entries.single()
        assertEquals("a", entry.id)
        assertEquals(RecommendType.SKILL, entry.type)
        assertEquals("src", entry.originId)
        assertEquals("测试源", entry.originName)
    }

    @Test
    fun `parse accepts wrapped items and entries`() {
        val wrapped = """
            {"version":1,"items":[{"id":"a","type":"prompt","install_kind":"inline","content":"x"}]}
        """.trimIndent()
        val entries = """
            {"entries":[{"id":"b","type":"quick_message","install_kind":"inline","content":"y"}]}
        """.trimIndent()

        val itemsResult = parseRecommendIndex(wrapped, "src", "src") as RecommendIndexParseResult.Success
        val entriesResult = parseRecommendIndex(entries, "src", "src") as RecommendIndexParseResult.Success

        assertEquals("a", itemsResult.entries.single().id)
        assertEquals("b", entriesResult.entries.single().id)
    }

    @Test
    fun `parse ignores unknown fields instead of failing whole index`() {
        val text = """
            [
              {
                "id":"a","type":"skill","install_kind":"inline","content":"x",
                "future_field": {"nested": true},
                "another_one": 42
              }
            ]
        """.trimIndent()

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(1, result.entries.size)
        assertEquals(0, result.skipped.size)
    }

    @Test
    fun `parse rejects text that is not an index`() {
        assertTrue(parseRecommendIndex("{ not json", "src", "src") is RecommendIndexParseResult.Invalid)
        assertTrue(parseRecommendIndex("""{"version":1}""", "src", "src") is RecommendIndexParseResult.Invalid)
        assertTrue(parseRecommendIndex(""""just a string"""", "src", "src") is RecommendIndexParseResult.Invalid)
    }

    @Test
    fun `parse treats empty items array as empty index`() {
        val result = parseRecommendIndex("""{"items":[]}""", "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertEquals(0, result.skipped.size)
    }

    @Test
    fun `parse skips entries with missing id`() {
        val text = """[{"type":"skill","name":"没有 id","install_kind":"inline","content":"x"}]"""

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        val skipped = result.skipped.single()
        assertEquals("缺少 id", skipped.reason)
        assertEquals("没有 id", skipped.name)
    }

    @Test
    fun `parse skips entries with unknown or missing type`() {
        val text = """
            [
              {"id":"a","type":"theme","install_kind":"inline","content":"x"},
              {"id":"b","install_kind":"inline","content":"x"}
            ]
        """.trimIndent()

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertEquals(2, result.skipped.size)
        assertTrue(result.skipped[0].reason.contains("type"))
        assertTrue(result.skipped[1].reason.contains("type"))
    }

    @Test
    fun `parse skips entries with non-http source url`() {
        val text = """
            [
              {"id":"a","type":"skill","install_kind":"inline","content":"x","source_url":"ftp://example.com/s.zip"},
              {"id":"b","type":"skill","install_kind":"inline","content":"x","source_url":"file:///etc/passwd"},
              {"id":"c","type":"skill","install_kind":"inline","content":"x","source_url":"https://"}
            ]
        """.trimIndent()

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertEquals(3, result.skipped.size)
        result.skipped.forEach { assertTrue(it.reason.contains("source_url")) }
    }

    @Test
    fun `parse skips entries with malformed sha256`() {
        val text = """
            [
              {"id":"a","type":"skill","install_kind":"inline","content":"x","sha256":"xyz"},
              {"id":"b","type":"skill","install_kind":"inline","content":"x","sha256":"${"a".repeat(63)}"},
              {"id":"c","type":"skill","install_kind":"inline","content":"x","sha256":"${"g".repeat(64)}"}
            ]
        """.trimIndent()

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertEquals(3, result.skipped.size)
        result.skipped.forEach { assertTrue(it.reason.contains("sha256")) }
    }

    @Test
    fun `parse skips inline entry without content and url`() {
        val result = parseRecommendIndex(
            """[{"id":"a","type":"skill","install_kind":"inline"}]""",
            "src",
            "src",
        ) as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertTrue(result.skipped.single().reason.contains("content"))
    }

    @Test
    fun `parse skips remote entry without address`() {
        val result = parseRecommendIndex(
            """[{"id":"a","type":"mcp","install_kind":"remote"}]""",
            "src",
            "src",
        ) as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        assertTrue(result.skipped.single().reason.contains("remote"))
    }

    @Test
    fun `parse defaults blank install_kind to inline`() {
        val result = parseRecommendIndex(
            """[{"id":"a","type":"skill","content":"x"}]""",
            "src",
            "src",
        ) as RecommendIndexParseResult.Success

        assertEquals(InstallKind.INLINE, result.entries.single().installKind)
    }

    @Test
    fun `parse normalizes comma separated tags and deps`() {
        val text = """
            [{"id":"a","type":"skill","install_kind":"inline","content":"x","tags":"写作, 效率 ,写作","deps":"node, python"}]
        """

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success
        val entry = result.entries.single()

        assertEquals(listOf("写作", "效率"), entry.tags)
        assertEquals(listOf("node", "python"), entry.deps)
    }

    @Test
    fun `parse trims tags and drops empties`() {
        val text = """[{"id":"a","type":"skill","install_kind":"inline","content":"x","tags":[" a ", "", "  ","a"]}]"""

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(listOf("a"), result.entries.single().tags)
    }

    @Test
    fun `parse coerces null list fields to empty`() {
        val text = """[{"id":"a","type":"skill","install_kind":"inline","content":"x","tags":null,"deps":null}]"""

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success
        val entry = result.entries.single()

        assertEquals(emptyList<String>(), entry.tags)
        assertEquals(emptyList<String>(), entry.deps)
    }

    @Test
    fun `parse skips entry whose field type does not match protocol`() {
        val text = """[{"id":"bad-types","type":"skill","install_kind":"inline","content":"x","tags":5}]"""

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success

        assertEquals(0, result.entries.size)
        val skipped = result.skipped.single()
        assertEquals("bad-types", skipped.id)
        assertTrue(skipped.reason.contains("字段类型"))
    }

    @Test
    fun `parse is case insensitive for type and install kind`() {
        val text = """[{"id":"a","type":" SKILL ","install_kind":" INLINE ","content":"x"}]"""

        val result = parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success
        val entry = result.entries.single()

        assertEquals(RecommendType.SKILL, entry.type)
        assertEquals(InstallKind.INLINE, entry.installKind)
    }

    @Test
    fun `parse falls back to id when name missing`() {
        val result = parseRecommendIndex(
            """[{"id":"fallback-name","type":"skill","install_kind":"inline","content":"x"}]""",
            "src",
            "src",
        ) as RecommendIndexParseResult.Success

        assertEquals("fallback-name", result.entries.single().name)
    }

    @Test
    fun `entry identity and url helpers`() {
        val inline = parseRecommendIndex(
            """[{"id":"a","type":"skill","install_kind":"inline","content":"x"}]""",
            "src",
            "src",
        ).let { (it as RecommendIndexParseResult.Success).entries.single() }
        val remote = parseRecommendIndex(
            """[{"id":"r","type":"mcp","install_kind":"remote","source_url":"https://mcp.example.com/mcp"}]""",
            "src",
            "src",
        ).let { (it as RecommendIndexParseResult.Success).entries.single() }

        assertEquals("skill:a", inline.identity)
        assertFalse(inline.hasChecksum)
        assertEquals("mcp:r", remote.identity)
        assertEquals("https://mcp.example.com/mcp", remote.displaySourceUrl)
        assertEquals("https://mcp.example.com/mcp", remote.resolvedRemoteUrl)
        assertEquals("", inline.resolvedRemoteUrl)
    }

    @Test
    fun `remote_url wins over source_url for mcp wiring`() {
        val text = """
            [{"id":"r","type":"mcp","install_kind":"remote","source_url":"https://page.example.com/doc","remote_url":"https://mcp.example.com/mcp"}]
        """

        val entry = (parseRecommendIndex(text, "src", "src") as RecommendIndexParseResult.Success).entries.single()

        assertEquals("https://mcp.example.com/mcp", entry.resolvedRemoteUrl)
    }

    @Test
    fun `normalize entry element rewrites comma string lists to arrays`() {
        val raw = RecommendIndexJson.parseToJsonElement("""{"tags":"a, b","deps":"x","sort":5}""")

        val normalized = normalizeEntryElement(raw) as JsonObject

        val tags = normalized.getValue("tags") as JsonArray
        assertEquals(listOf("a", "b"), tags.map { it.jsonPrimitive.content })
        val deps = normalized.getValue("deps") as JsonArray
        assertEquals(listOf("x"), deps.map { it.jsonPrimitive.content })
        assertEquals(5, normalized.getValue("sort").jsonPrimitive.content.toInt())
    }

    @Test
    fun `normalize entry element leaves arrays untouched`() {
        val raw = RecommendIndexJson.parseToJsonElement("""{"tags":["a"],"deps":["x"],"sort":3}""")

        val normalized = normalizeEntryElement(raw) as JsonObject

        assertEquals(raw, normalized)
    }

    @Test
    fun `normalize entry element passes through non object elements`() {
        val primitive = JsonPrimitive("hello")

        assertEquals(primitive, normalizeEntryElement(primitive))
    }

    @Test
    fun `is http url accepts only real http hosts`() {
        assertTrue(isHttpUrl("https://example.com/index.json"))
        assertTrue(isHttpUrl("http://127.0.0.1:8080/mcp"))
        assertTrue(isHttpUrl("HTTPS://EXAMPLE.COM/a"))
        assertTrue(isHttpUrl("https://raw.githubusercontent.com/org/repo/main/recommend-index.json"))

        assertFalse(isHttpUrl(""))
        assertFalse(isHttpUrl("ftp://example.com/a"))
        assertFalse(isHttpUrl("file:///tmp/index.json"))
        assertFalse(isHttpUrl("content://media/x"))
        assertFalse(isHttpUrl("data:text/plain,hi"))
        assertFalse(isHttpUrl("https://"))
        assertFalse(isHttpUrl("https://localhost/index.json"))
        assertFalse(isHttpUrl("https://exa mple.com/a"))
    }
}
