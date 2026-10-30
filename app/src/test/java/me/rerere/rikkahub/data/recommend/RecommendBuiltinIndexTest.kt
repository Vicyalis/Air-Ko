package me.rerere.rikkahub.data.recommend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包内置 index 的自检。
 *
 * 本地源是商店的兜底（预置远端源默认关闭、断网时也只有它能用），
 * 所以这份 asset 必须始终能被解析、内嵌内容的 sha256 必须与实际内容一致 ——
 * 否则用户点安装时会被自己的安全闸门拦住。
 */
class RecommendBuiltinIndexTest {
    private fun builtinIndexFile(): File = findBuiltinIndexFile()

    @Test
    fun `bundled index parses without skipped entries`() {
        val result = parseRecommendIndex(
            builtinIndexFile().readText(Charsets.UTF_8),
            originId = "builtin",
            originName = "builtin",
        )

        val success = result as RecommendIndexParseResult.Success
        assertEquals(0, success.skipped.size)
        assertTrue(success.entries.size >= 9)
        assertEquals(success.entries.size, success.entries.map { it.identity }.distinct().size)
    }

    @Test
    fun `every inline entry content matches its declared sha256`() {
        val entries = (parseRecommendIndex(
            builtinIndexFile().readText(Charsets.UTF_8),
            originId = "builtin",
            originName = "builtin",
        ) as RecommendIndexParseResult.Success).entries

        val inline = entries.filter { it.installKind == InstallKind.INLINE }
        // 内嵌条目是「离线也能一键装」的主力，数量归零说明 asset 被写坏了
        assertTrue(inline.size >= 4)
        for (entry in inline) {
            assertTrue("${entry.id} 缺少 sha256", entry.hasChecksum)
            assertTrue(
                "${entry.id} 的内嵌内容与声明 sha256 不一致",
                matchesSha256(entry.content.toByteArray(Charsets.UTF_8), entry.sha256),
            )
        }
    }

    @Test
    fun `bundled index covers the four offline categories`() {
        val entries = (parseRecommendIndex(
            builtinIndexFile().readText(Charsets.UTF_8),
            originId = "builtin",
            originName = "builtin",
        ) as RecommendIndexParseResult.Success).entries

        // PLUGIN 不放在离线 index 里：DSH 插件来自远端目录，且安装前必须显示兼容性。
        assertEquals(
            setOf(RecommendType.SKILL, RecommendType.PROMPT, RecommendType.QUICK_MESSAGE, RecommendType.MCP),
            entries.map { it.type }.toSet(),
        )
        // 每个类目至少两条，列表页才不至于空荡
        listOf(RecommendType.SKILL, RecommendType.PROMPT, RecommendType.QUICK_MESSAGE, RecommendType.MCP).forEach { type ->
            assertTrue("$type 条目不足", entries.count { it.type == type } >= 2)
        }
    }

    @Test
    fun `remote mcp entries expose http endpoint and runtime entries carry deploy info`() {
        val entries = (parseRecommendIndex(
            builtinIndexFile().readText(Charsets.UTF_8),
            originId = "builtin",
            originName = "builtin",
        ) as RecommendIndexParseResult.Success).entries

        val remoteMcp = entries.filter { it.type == RecommendType.MCP && it.installKind == InstallKind.REMOTE }
        assertTrue(remoteMcp.isNotEmpty())
        remoteMcp.forEach { entry ->
            assertTrue(isHttpUrl(entry.resolvedRemoteUrl))
            assertTrue(entry.deployDoc.isNotBlank())
        }

        val runtimeMcp = entries.filter { it.installKind.needsRuntime }
        runtimeMcp.forEach { entry ->
            assertTrue("${entry.id} 缺少部署文档", entry.deployDoc.isNotBlank())
            assertTrue("${entry.id} 缺少运行命令", runtimeCommand(entry).isNotBlank())
        }
    }

    @Test
    fun `local source points at bundled asset`() {
        assertEquals("asset:builtin_index.json", RecommendSourceRules.LOCAL_SOURCE_URL)
        assertEquals("builtin_index.json", builtinIndexFile().name)
    }

    /**
     * 单测的工作目录一般是 app 模块（Gradle 默认行为），但不同 AGP 版本可能不同，
     * 所以从工作目录逐级向上找，找不到就直接失败而不是跳过 —— 这份自检不能被静默跳过。
     */
    private fun findBuiltinIndexFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, "app/src/main/assets/recommend/builtin_index.json").takeIf { it.isFile }?.let { return it }
            File(dir, "src/main/assets/recommend/builtin_index.json").takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("找不到 app/src/main/assets/recommend/builtin_index.json（工作目录探测失败）")
    }
}
