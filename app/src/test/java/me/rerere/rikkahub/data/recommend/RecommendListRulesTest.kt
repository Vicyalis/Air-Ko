package me.rerere.rikkahub.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 列表的排序/搜索/启动命令规则（纯函数，全部可在 JVM 上验证）。 */
class RecommendListRulesTest {
    private fun entry(
        id: String,
        name: String = id,
        author: String = "",
        description: String = "",
        tags: List<String> = emptyList(),
        sort: Int = 0,
        installKind: InstallKind = InstallKind.INLINE,
        runCommand: String = "",
        type: RecommendType = RecommendType.SKILL,
    ) = RecommendEntry(
        id = id,
        type = type,
        name = name,
        version = "",
        author = author,
        description = description,
        tags = tags,
        sourceUrl = "",
        sha256 = "",
        installKind = installKind,
        deps = emptyList(),
        deployDoc = "",
        sort = sort,
        content = "x",
        remoteUrl = "",
        runCommand = runCommand,
        originId = "src",
        originName = "src",
    )

    @Test
    fun `default sort orders by index sort then name`() {
        val entries = listOf(
            entry("c", name = "B", sort = 2),
            entry("a", name = "b", sort = 1),
            entry("b", name = "a", sort = 1),
        )

        val sorted = sortEntries(entries, RecommendSort.DEFAULT)

        // sort=1 的两条按名字排：name "a"（id=b）在 name "b"（id=a）前；sort=2 的排最后
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `name sort is case insensitive both ways`() {
        val entries = listOf(entry("a", name = "Banana"), entry("b", name = "apple"), entry("c", name = "Cherry"))

        assertEquals(listOf("b", "a", "c"), sortEntries(entries, RecommendSort.NAME_ASC).map { it.id })
        assertEquals(listOf("c", "a", "b"), sortEntries(entries, RecommendSort.NAME_DESC).map { it.id })
    }

    @Test
    fun `author sort groups by author then sort`() {
        val entries = listOf(
            entry("a", name = "A", author = "Zoe"),
            entry("b", name = "B", author = "alice", sort = 2),
            entry("c", name = "C", author = "alice", sort = 1),
        )

        val sorted = sortEntries(entries, RecommendSort.AUTHOR)

        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun `runtime sort puts one click installs first`() {
        val entries = listOf(
            entry("docker", installKind = InstallKind.DOCKER),
            entry("npx", installKind = InstallKind.NPX),
            entry("inline", installKind = InstallKind.INLINE),
            entry("uvx", installKind = InstallKind.UVX),
            entry("remote", installKind = InstallKind.REMOTE),
        )

        val sorted = sortEntries(entries, RecommendSort.RUNTIME)

        assertEquals(listOf("inline", "remote", "npx", "uvx", "docker"), sorted.map { it.id })
    }

    @Test
    fun `filter matches name description author id and tags`() {
        val entries = listOf(
            entry("findme-by-id"),
            entry("a", name = "正则拆解"),
            entry("b", description = "帮助整理 CHANGELOG"),
            entry("c", author = "Air-Ko"),
            entry("d", tags = listOf("写作")),
            entry("e", name = "无关"),
        )

        assertEquals(listOf("a"), filterEntries(entries, "正则").map { it.id })
        assertEquals(listOf("b"), filterEntries(entries, "changelog").map { it.id })
        assertEquals(listOf("c"), filterEntries(entries, "air-ko").map { it.id })
        assertEquals(listOf("d"), filterEntries(entries, "写作").map { it.id })
        assertEquals(listOf("findme-by-id"), filterEntries(entries, "findme").map { it.id })
        assertTrue(filterEntries(entries, "不存在的关键词").isEmpty())
    }

    @Test
    fun `filter with blank query returns everything`() {
        val entries = listOf(entry("a"), entry("b"))

        assertEquals(entries, filterEntries(entries, ""))
        assertEquals(entries, filterEntries(entries, "   "))
    }

    @Test
    fun `runtime command derives from install kind and honors override`() {
        assertEquals("npx -y pkg", runtimeCommand(entry("pkg", installKind = InstallKind.NPX)))
        assertEquals("uvx pkg", runtimeCommand(entry("pkg", installKind = InstallKind.UVX)))
        assertEquals("docker run -i --rm pkg", runtimeCommand(entry("pkg", installKind = InstallKind.DOCKER)))
        assertEquals("", runtimeCommand(entry("pkg", installKind = InstallKind.REMOTE)))
        assertEquals("", runtimeCommand(entry("pkg", installKind = InstallKind.INLINE)))
        assertEquals(
            "npx -y @scope/pkg --flag",
            runtimeCommand(entry("pkg", installKind = InstallKind.NPX, runCommand = "npx -y @scope/pkg --flag")),
        )
    }

    @Test
    fun `runtime probe command exists only for local runtimes`() {
        assertTrue(runtimeProbeCommand(InstallKind.NPX).contains("node"))
        assertTrue(runtimeProbeCommand(InstallKind.UVX).contains("uv"))
        assertTrue(runtimeProbeCommand(InstallKind.DOCKER).contains("docker"))
        assertEquals("", runtimeProbeCommand(InstallKind.INLINE))
        assertEquals("", runtimeProbeCommand(InstallKind.REMOTE))
    }

    @Test
    fun `needs runtime flag only for command based kinds`() {
        assertTrue(InstallKind.NPX.needsRuntime)
        assertTrue(InstallKind.UVX.needsRuntime)
        assertTrue(InstallKind.DOCKER.needsRuntime)
        assertEquals(false, InstallKind.REMOTE.needsRuntime)
        assertEquals(false, InstallKind.INLINE.needsRuntime)
    }

    @Test
    fun `wire parsing tolerates case and blanks`() {
        assertEquals(RecommendType.QUICK_MESSAGE, RecommendType.fromWire(" QUICK_MESSAGE "))
        assertEquals(null, RecommendType.fromWire(""))
        assertEquals(null, RecommendType.fromWire(null))
        assertEquals(InstallKind.NPX, InstallKind.fromWire("NPX"))
        assertEquals(null, InstallKind.fromWire("apt"))
        assertEquals(RecommendSort.DEFAULT, RecommendSort.fromWire("default"))
        assertEquals(null, RecommendSort.fromWire("unknown"))
    }
}
