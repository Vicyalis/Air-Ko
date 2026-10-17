package me.rerere.rikkahub.data.recommend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 推荐源的增删改查/排序规则（纯对象，UI 与存储都走这里）。 */
class RecommendSourceRulesTest {
    private fun custom(
        id: String = "src-1",
        name: String = "custom",
        url: String = "https://example.com/index.json",
        enabled: Boolean = true,
    ) = RecommendSource(id = id, name = name, url = url, builtin = false, local = false, enabled = enabled)

    @Test
    fun `default sources contain the offline local source plus the two real remote sources`() {
        val sources = RecommendSourceRules.defaultSources()

        val local = sources.first()
        assertEquals(RecommendSourceRules.LOCAL_SOURCE_ID, local.id)
        assertEquals(RecommendSourceRules.LOCAL_SOURCE_URL, local.url)
        assertTrue(local.local)
        assertTrue(local.builtin)
        assertTrue(local.enabled)
        assertEquals(RecommendSourceFormat.NATIVE, local.format)

        val remote = sources.drop(1)
        assertEquals(2, remote.size)
        assertTrue(remote.all { it.builtin && !it.local && it.enabled })
        assertTrue(remote.all { RecommendSourceRules.isValidUrl(it.url) })
        assertTrue(remote.any { it.id == "anthropic-skills" && it.format == RecommendSourceFormat.CLAUDE_MARKETPLACE })
        assertTrue(remote.any { it.id == "mcp-registry" && it.format == RecommendSourceFormat.MCP_REGISTRY })
    }

    @Test
    fun `normalize drops deprecated 404 presets`() {
        val deprecated = RecommendSource(
            id = "anthropics-skills",
            name = "anthropics/skills",
            url = "https://raw.githubusercontent.com/anthropics/skills/main/recommend-index.json",
            builtin = true,
            enabled = false,
        )

        val normalized = RecommendSourceRules.normalize(listOf(deprecated, custom()))

        assertTrue(normalized.none { it.id == "anthropics-skills" })
        assertEquals(RecommendSourceRules.LOCAL_SOURCE_ID, normalized.first().id)
        assertTrue(normalized.any { it.id == "src-1" })
    }

    @Test
    fun `add appends a custom enabled source`() {
        val result = RecommendSourceRules.add(RecommendSourceRules.defaultSources(), "  我的源  ", "  https://example.com/a.json  ")

        val sources = result.getOrThrow()
        val added = sources.last()
        assertEquals("我的源", added.name)
        assertEquals("https://example.com/a.json", added.url)
        assertFalse(added.builtin)
        assertFalse(added.local)
        assertTrue(added.enabled)
        assertTrue(added.id.startsWith("src-"))
        assertEquals(RecommendSourceRules.defaultSources().size + 1, sources.size)
    }

    @Test
    fun `add derives name from host when name blank`() {
        val added = RecommendSourceRules.add(emptyList(), "   ", "https://mirror.example.org/path/index.json").getOrThrow().last()

        assertEquals("mirror.example.org", added.name)
    }

    @Test
    fun `add rejects invalid urls`() {
        assertTrue(RecommendSourceRules.add(emptyList(), "x", "").isFailure)
        assertTrue(RecommendSourceRules.add(emptyList(), "x", "not a url").isFailure)
        assertTrue(RecommendSourceRules.add(emptyList(), "x", "ftp://example.com/a").isFailure)
        assertTrue(RecommendSourceRules.add(emptyList(), "x", "https://nodot/a").isFailure)
    }

    @Test
    fun `add rejects duplicate url case insensitively`() {
        val existing = listOf(custom(url = "https://example.com/index.json"))

        val result = RecommendSourceRules.add(existing, "dup", "HTTPS://EXAMPLE.COM/INDEX.JSON")

        assertTrue(result.isFailure)
    }

    @Test
    fun `update changes name and url`() {
        val sources = RecommendSourceRules.update(listOf(custom()), "src-1", "新名字", "https://example.com/v2.json").getOrThrow()

        assertEquals("新名字", sources.single().name)
        assertEquals("https://example.com/v2.json", sources.single().url)
    }

    @Test
    fun `update keeps old name when name blank`() {
        val sources = RecommendSourceRules.update(listOf(custom(name = "原名")), "src-1", " ", "https://example.com/v2.json").getOrThrow()

        assertEquals("原名", sources.single().name)
    }

    @Test
    fun `update allows builtin source to point at a mirror`() {
        val builtin = RecommendSource(
            id = "mirror-preset",
            name = "mirror preset",
            url = "https://example.com/original.json",
            builtin = true,
            enabled = false,
        )

        val sources = RecommendSourceRules.update(listOf(builtin), builtin.id, builtin.name, "https://mirror.example.com/skills.json").getOrThrow()

        val updated = sources.single()
        assertTrue(updated.builtin)
        assertEquals("https://mirror.example.com/skills.json", updated.url)
    }

    @Test
    fun `update fails for unknown source missing url or duplicate url`() {
        val sources = listOf(custom(id = "src-1", url = "https://example.com/a.json"), custom(id = "src-2", url = "https://example.com/b.json"))

        assertTrue(RecommendSourceRules.update(sources, "missing", "x", "https://example.com/c.json").isFailure)
        assertTrue(RecommendSourceRules.update(sources, "src-1", "x", "bad").isFailure)
        assertTrue(RecommendSourceRules.update(sources, "src-1", "x", "HTTPS://EXAMPLE.COM/B.JSON").isFailure)
    }

    @Test
    fun `update keeping own url is allowed`() {
        val sources = listOf(custom(url = "https://example.com/a.json"))

        val result = RecommendSourceRules.update(sources, "src-1", "renamed", "https://example.com/a.json")

        assertTrue(result.isSuccess)
        assertEquals("renamed", result.getOrThrow().single().name)
    }

    @Test
    fun `remove deletes custom sources only`() {
        val builtin = RecommendSource(
            id = "preset-x",
            name = "preset",
            url = "https://example.com/preset.json",
            builtin = true,
            enabled = true,
        )
        val sources = listOf(RecommendSourceRules.defaultSources().first(), custom(), builtin)

        val afterRemoveCustom = RecommendSourceRules.remove(sources, "src-1")
        assertEquals(listOf(RecommendSourceRules.LOCAL_SOURCE_ID, builtin.id), afterRemoveCustom.map { it.id })

        val afterRemoveBuiltin = RecommendSourceRules.remove(afterRemoveCustom, builtin.id)
        assertEquals(listOf(RecommendSourceRules.LOCAL_SOURCE_ID, builtin.id), afterRemoveBuiltin.map { it.id })
    }

    @Test
    fun `set enabled toggles sources but never local`() {
        val local = RecommendSourceRules.defaultSources().first()
        val sources = listOf(local, custom())

        val disabled = RecommendSourceRules.setEnabled(sources, "src-1", false)
        assertFalse(disabled.last().enabled)

        val reEnabled = RecommendSourceRules.setEnabled(disabled, "src-1", true)
        assertTrue(reEnabled.last().enabled)

        val localDisabled = RecommendSourceRules.setEnabled(sources, RecommendSourceRules.LOCAL_SOURCE_ID, false)
        assertTrue(localDisabled.first().enabled)
    }

    @Test
    fun `move reorders and clamps out of range indexes`() {
        val sources = listOf(custom("a"), custom("b"), custom("c"))

        assertEquals(listOf("b", "c", "a"), RecommendSourceRules.move(sources, 0, 2).map { it.id })
        assertEquals(listOf("b", "a", "c"), RecommendSourceRules.move(sources, 1, 0).map { it.id })
        // 越界的目标下标夹到合法范围
        assertEquals(listOf("b", "c", "a"), RecommendSourceRules.move(sources, 0, 99).map { it.id })
        assertEquals(listOf("c", "a", "b"), RecommendSourceRules.move(sources, 2, -5).map { it.id })
        // 非法起始下标原样返回
        assertEquals(sources, RecommendSourceRules.move(sources, 3, 0))
        assertEquals(sources, RecommendSourceRules.move(sources, -1, 0))
        // 移动到自己身上是 no-op
        assertEquals(sources, RecommendSourceRules.move(sources, 1, 1))
    }

    @Test
    fun `move by shifts a single source`() {
        val sources = listOf(custom("a"), custom("b"), custom("c"))

        assertEquals(listOf("b", "a", "c"), RecommendSourceRules.moveBy(sources, "a", 1).map { it.id })
        assertEquals(listOf("b", "c", "a"), RecommendSourceRules.moveBy(sources, "a", 2).map { it.id })
        assertEquals(sources, RecommendSourceRules.moveBy(sources, "missing", 1))
        assertEquals(sources, RecommendSourceRules.moveBy(sources, "a", 0))
    }

    @Test
    fun `normalize drops broken sources and dedups urls`() {
        val sources = listOf(
            custom("", url = ""),
            custom("dup-1", url = "https://example.com/a.json"),
            custom("dup-2", url = "HTTPS://EXAMPLE.COM/A.JSON"),
        )

        val normalized = RecommendSourceRules.normalize(sources)

        // 空地址被丢弃、重复地址只留第一个；本地源与预置源补齐在最前
        assertTrue(normalized.first().local)
        val kept = normalized.last()
        assertEquals("dup-1", kept.id)
        assertEquals(RecommendSourceRules.defaultSources().size + 1, normalized.size)
    }

    @Test
    fun `normalize fills blank id and forces local source flags`() {
        val blank = custom("", url = "https://example.com/b.json")
        val fakeLocal = RecommendSource(
            id = RecommendSourceRules.LOCAL_SOURCE_ID,
            name = "伪装本地源",
            url = "https://example.com/c.json",
            builtin = false,
            local = false,
            enabled = false,
        )

        val normalized = RecommendSourceRules.normalize(listOf(blank, fakeLocal))

        val fixedLocal = normalized.first { it.url.endsWith("c.json") }
        assertTrue(fixedLocal.local)
        assertTrue(fixedLocal.builtin)
        assertTrue(fixedLocal.enabled)
        assertEquals(RecommendSourceRules.LOCAL_SOURCE_ID, fixedLocal.id)

        val fixedBlank = normalized.first { it.url.endsWith("b.json") }
        assertTrue(fixedBlank.id.startsWith("src-"))
    }

    @Test
    fun `normalize guarantees a local source`() {
        val normalized = RecommendSourceRules.normalize(listOf(custom()))

        assertEquals(RecommendSourceRules.LOCAL_SOURCE_ID, normalized.first().id)
        assertTrue(normalized.first().local)
        assertTrue(normalized.first().enabled)
        assertTrue(normalized.any { it.id == "src-1" })

        val empty = RecommendSourceRules.normalize(emptyList())
        assertEquals(RecommendSourceRules.defaultSources().map { it.id }, empty.map { it.id })
    }

    @Test
    fun `new source ids are unique and prefixed`() {
        val ids = (1..20).map { RecommendSourceRules.newSourceId() }

        assertTrue(ids.all { it.startsWith("src-") })
        assertEquals(ids.size, ids.distinct().size)
    }
}
