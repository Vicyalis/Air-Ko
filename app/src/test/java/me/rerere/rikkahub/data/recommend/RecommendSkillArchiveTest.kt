package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** skill 压缩包解包：只认最外层 SKILL.md，路径穿越与嵌套技能都要被挡住。 */
class RecommendSkillArchiveTest {
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun skillMd(name: String, description: String = "demo skill"): ByteArray =
        "---\nname: $name\ndescription: $description\n---\n\n# $name\n\n正文\n".toByteArray(Charsets.UTF_8)

    @Test
    fun `extract picks outermost skill and strips base path`() {
        val archive = zipOf(
            "repo-main/skills/foo/SKILL.md" to skillMd("demo-skill"),
            "repo-main/skills/foo/notes.md" to "notes".toByteArray(Charsets.UTF_8),
            "repo-main/skills/foo/helpers/util.md" to "util".toByteArray(Charsets.UTF_8),
            "repo-main/skills/foo/inner/SKILL.md" to skillMd("inner-skill"),
            "repo-main/skills/foo/inner/deep.md" to "deep".toByteArray(Charsets.UTF_8),
            "repo-main/skills/bar/SKILL.md" to skillMd("other-skill"),
        )

        val result = extractSkillArchive(archive) as SkillArchiveResult.Success

        assertEquals("demo-skill", result.archive.skillName)
        assertEquals(setOf("SKILL.md", "notes.md", "helpers/util.md"), result.archive.files.keys)
        assertTrue(result.archive.files.getValue("SKILL.md").toString(Charsets.UTF_8).startsWith("---\nname: demo-skill"))
        assertEquals("notes", result.archive.files.getValue("notes.md").toString(Charsets.UTF_8))
        assertEquals("util", result.archive.files.getValue("helpers/util.md").toString(Charsets.UTF_8))
    }

    @Test
    fun `extract supports flat skill archive`() {
        val archive = zipOf(
            "SKILL.md" to skillMd("flat-skill"),
            "extra/tool.md" to "tool".toByteArray(Charsets.UTF_8),
        )

        val result = extractSkillArchive(archive) as SkillArchiveResult.Success

        assertEquals("flat-skill", result.archive.skillName)
        assertEquals(setOf("SKILL.md", "extra/tool.md"), result.archive.files.keys)
    }

    @Test
    fun `extract drops traversal entries`() {
        val archive = zipOf(
            "repo/skill/SKILL.md" to skillMd("safe-skill"),
            "repo/skill/../evil.md" to "evil".toByteArray(Charsets.UTF_8),
            "../escape.md" to "escape".toByteArray(Charsets.UTF_8),
        )

        val result = extractSkillArchive(archive) as SkillArchiveResult.Success

        assertEquals(setOf("SKILL.md"), result.archive.files.keys)
        assertFalse(result.archive.files.keys.any { it.contains("..") })
    }

    @Test
    fun `extract fails when SKILL md is missing`() {
        val archive = zipOf("readme.md" to "hello".toByteArray(Charsets.UTF_8))

        val result = extractSkillArchive(archive)

        assertTrue(result is SkillArchiveResult.Failure)
        assertTrue((result as SkillArchiveResult.Failure).message.contains("SKILL.md"))
    }

    @Test
    fun `extract fails for empty archive`() {
        val result = extractSkillArchive(zipOf())

        assertTrue(result is SkillArchiveResult.Failure)
        assertTrue((result as SkillArchiveResult.Failure).message.contains("没有文件"))
    }

    @Test
    fun `extract fails for corrupted bytes`() {
        val corrupted = ByteArray(64) { (it * 7 + 3).toByte() }

        val result = extractSkillArchive(corrupted)

        assertTrue(result is SkillArchiveResult.Failure)
    }

    @Test
    fun `extract fails when frontmatter misses name or description`() {
        val noName = zipOf("SKILL.md" to "---\ndescription: x\n---\nbody".toByteArray(Charsets.UTF_8))
        val noDescription = zipOf("SKILL.md" to "---\nname: x\n---\nbody".toByteArray(Charsets.UTF_8))

        assertTrue(extractSkillArchive(noName) is SkillArchiveResult.Failure)
        assertTrue(extractSkillArchive(noDescription) is SkillArchiveResult.Failure)
    }

    @Test
    fun `normalize archive path strips prefixes and rejects traversal`() {
        assertEquals("a/b/c", normalizeArchivePath("a/b/c"))
        assertEquals("a/b/c", normalizeArchivePath("/a/b/c"))
        assertEquals("a/b", normalizeArchivePath("a\\b"))
        assertEquals("a/b", normalizeArchivePath("./a/./b"))

        assertNull(normalizeArchivePath(""))
        assertNull(normalizeArchivePath("/"))
        assertNull(normalizeArchivePath("."))
        assertNull(normalizeArchivePath(".."))
        assertNull(normalizeArchivePath("../evil"))
        assertNull(normalizeArchivePath("a/../b"))
        assertNull(normalizeArchivePath("a/.."))
    }
}
