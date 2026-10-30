package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DSH 目录适配与包解析：这两步决定「点下载后到底装什么」。 */
class RecommendDshTest {
    private val source = RecommendSource(
        id = RecommendSourceRules.DSH_OPEN_SOURCE_ID,
        name = "DSH Open Market",
        url = "https://dshopen.org/index.json",
        builtin = true,
        format = RecommendSourceFormat.DSH_CATALOG,
        targetTypes = listOf(RecommendType.PLUGIN),
    )

    private val catalog = """
        {
          "format": 1,
          "metadata": {
            "categories": {
              "skills": {"title": {"en": "Skills", "zh": "技能包"}},
              "memory": {"title": {"en": "Memory", "zh": "记忆"}},
              "interface": {"title": {"en": "UI", "zh": "界面"}}
            },
            "labels": {"verified": {"title": {"en": "Verified", "zh": "已验证"}}}
          },
          "plugins": [
            {
              "name": "dsh-fact-check",
              "title": {"en": "Fact Check", "zh": "事实核查"},
              "description": {"en": "★ 62 │ Check claims.", "zh": "★ 62 │ 核查事实。"},
              "icon": "icons/fact-check.svg",
              "author": "alice",
              "repository": {"url": "https://github.com/alice/dsh-fact-check"},
              "metadata": {
                "category": "skills",
                "keywords": ["fact", "check"],
                "labels": ["verified"],
                "sortKeys": {"github-stars": 62}
              },
              "versions": [{
                "version": "1.2.3",
                "source": {"type": "npm", "registry": "https://registry.npmjs.org/"}
              }]
            },
            {
              "name": "@tt-a1i/archify-dsh",
              "title": {"en": "Archify", "zh": "架构图"},
              "description": {"en": "Draw diagrams.", "zh": "画架构图。"},
              "repository": {"url": "https://github.com/tt-a1i/archify"},
              "metadata": {"category": "interface"},
              "versions": [{
                "version": "2.0.0",
                "source": {"type": "npm", "registry": "https://registry.npmjs.org/"},
                "engines": {"dsh": ">=0.2.0"}
              }]
            },
            {
              "name": "dsh-project-memory",
              "title": "Project Memory",
              "description": {"en": "Remember project context.", "zh": "记住项目上下文。"},
              "metadata": {"category": "memory"},
              "versions": [{
                "version": "0.4.0",
                "source": {"type": "git", "url": "https://github.com/alice/dsh-project-memory", "commit": "abcdef123456"}
              }]
            },
            {
              "name": "",
              "title": "Broken",
              "versions": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `dsh catalog maps npm and git entries with compatibility levels`() {
        val result = convertSourceText(source, catalog) as SourceConvertResult.Parsed

        assertEquals(3, result.entries.size)
        assertEquals(1, result.skipped.size)

        val skill = result.entries.first { it.dshPackage == "dsh-fact-check" }
        assertEquals(RecommendType.PLUGIN, skill.type)
        assertEquals(InstallKind.DSH, skill.installKind)
        assertEquals(DshPluginKind.SKILL, skill.dshKind)
        assertEquals("1.2.3", skill.version)
        assertEquals(-62, skill.sort)
        assertEquals("https://dshopen.org/icons/fact-check.svg", skill.dshIconUrl)
        assertTrue(skill.dshCategoryLabel.isNotBlank())
        assertTrue(skill.tags.any { it.equals("Verified", true) || it == "已验证" })

        val port = result.entries.first { it.dshPackage == "@tt-a1i/archify-dsh" }
        assertEquals(DshPluginKind.PORT, port.dshKind)
        assertTrue(port.dshPortAsset.endsWith("deepseek-archify.json"))
        assertEquals(">=0.2.0", port.dshEngines)

        val git = result.entries.first { it.dshPackage == "dsh-project-memory" }
        assertEquals("git", git.dshSourceType)
        assertEquals("abcdef123456", git.dshSourceCommit)
        assertEquals(DshPluginKind.AUTO, git.dshKind)
    }

    @Test
    fun `dsh catalog resolves scoped npm metadata and keeps duplicate identity stable`() {
        val parsed = (convertSourceText(source, catalog) as SourceConvertResult.Parsed).entries
        val first = parsed.first { it.dshPackage == "@tt-a1i/archify-dsh" }
        val second = (convertSourceText(source, catalog) as SourceConvertResult.Parsed)
            .entries.first { it.dshPackage == "@tt-a1i/archify-dsh" }

        assertEquals(first.id, second.id)
        assertTrue(first.id.startsWith("dsh-"))
        assertTrue(first.id.none { it == '@' || it == '/' })
    }

    @Test
    fun `npm tarball extracts skill bundles and keeps auxiliary files`() {
        val archive = tarGz(
            mapOf(
                "package/SKILL.md" to """
                    ---
                    name: dsh-fact-check
                    description: Check claims against sources
                    ---
                    Use this skill to check facts.
                """.trimIndent(),
                "package/scripts/check.sh" to "#!/bin/sh\necho check\n",
                "package/references/guide.md" to "# Guide\n",
            )
        )

        val files = (extractDshArchive(archive) as DshArchiveResult.Success).files
        val extracted = extractDshBundles(files)

        assertEquals(1, extracted.skills.size)
        assertEquals("dsh-fact-check", extracted.skills.single().name)
        assertTrue(extracted.skills.single().files.containsKey("scripts/check.sh"))
        assertTrue(extracted.skills.single().files.containsKey("references/guide.md"))
        assertEquals(null, extracted.prompt)
    }

    @Test
    fun `multiple skills and instruction-only packages are detected`() {
        val zip = zip(
            mapOf(
                "repo-123/skills/alpha/SKILL.md" to "---\nname: alpha\ndescription: Alpha skill\n---\n",
                "repo-123/skills/alpha/run.sh" to "echo alpha\n",
                "repo-123/skills/beta/SKILL.md" to "---\nname: beta\ndescription: Beta skill\n---\n",
            )
        )
        val files = (extractDshArchive(zip) as DshArchiveResult.Success).files
        val extracted = extractDshBundles(files)
        assertEquals(listOf("alpha", "beta"), extracted.skills.map { it.name })
        assertTrue(extracted.skills.all { it.files.size >= 1 })

        val promptZip = zip(mapOf("pkg/INSTRUCTIONS.md" to "Always answer in haiku."))
        val promptFiles = (extractDshArchive(promptZip) as DshArchiveResult.Success).files
        val prompt = extractDshBundles(promptFiles)
        assertEquals(0, prompt.skills.size)
        assertEquals("Always answer in haiku.", prompt.prompt)
        assertEquals("pkg/INSTRUCTIONS.md", prompt.promptFile)
    }

    @Test
    fun `runtime-only packages are reported honestly`() {
        val zip = zip(
            mapOf(
                "pkg/package.json" to """{"name":"dsh-ui","dsh":{"bundle":{"patch":"cordis.patch.yml"}}}""",
                "pkg/cordis.patch.yml" to "plugins: []\n",
                "pkg/lib/index.js" to "export default {}\n",
            )
        )
        val files = (extractDshArchive(zip) as DshArchiveResult.Success).files
        val extracted = extractDshBundles(files)

        assertEquals(0, extracted.skills.size)
        assertEquals(null, extracted.prompt)
        assertTrue(looksLikeCordisRuntime(files))
    }

    @Test
    fun `zip slip and hidden runtime directories are ignored`() {
        val zip = zip(
            mapOf(
                "pkg/SKILL.md" to "---\nname: safe\ndescription: Safe\n---\n",
                "pkg/../../evil.md" to "evil",
                "pkg/node_modules/x/index.js" to "evil",
            )
        )
        val files = (extractDshArchive(zip) as DshArchiveResult.Success).files
        assertTrue(files.keys.none { it.contains("..") })
        val bundle = extractDshBundles(files).skills.single()
        assertTrue(bundle.files.keys.none { it.startsWith("node_modules/") })
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    /** 最小 ustar 生成器，用来验证真实 npm 包会走的 tar.gz 分支。 */
    private fun tarGz(files: Map<String, String>): ByteArray {
        val raw = ByteArrayOutputStream()
        files.forEach { (path, content) ->
            val bytes = content.toByteArray(Charsets.UTF_8)
            val header = ByteArray(512)
            writeAscii(header, 0, path)
            writeAscii(header, 100, "0000644")
            writeAscii(header, 108, "0000000")
            writeAscii(header, 116, "0000000")
            writeAscii(header, 124, bytes.size.toString(8).padStart(11, '0'))
            writeAscii(header, 136, "00000000000")
            header[156] = '0'.code.toByte()
            writeAscii(header, 257, "ustar")
            writeAscii(header, 263, "00")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            val checksum = header.sumOf { it.toInt() and 0xff }
            writeAscii(header, 148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
            raw.write(header)
            raw.write(bytes)
            val padding = (512 - bytes.size % 512) % 512
            raw.write(ByteArray(padding))
        }
        raw.write(ByteArray(1024))

        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw.toByteArray()) }
        return output.toByteArray()
    }

    private fun writeAscii(target: ByteArray, offset: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        bytes.copyInto(target, offset, 0, minOf(bytes.size, target.size - offset))
    }
}
