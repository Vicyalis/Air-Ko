package me.rerere.rikkahub.data.recommend

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser

/**
 * DSH 插件包解析。
 *
 * DSH 插件是 npm 包（`.tgz`）或 GitHub 归档（`.zip`），内部可能是：
 * - 根目录一个 `SKILL.md`（整个包就是一个技能）；
 * - `skills/<name>/SKILL.md` 多个技能；
 * - 只有 `INSTRUCTIONS.md` / `FOR-AI.md` 的提示词包；
 * - 只有 `lib/xxx.js` + `cordis.patch.yml` 的 Cordis/Node 插件（Android 不能执行）。
 *
 * 这里统一把压缩包读成「路径 → 字节」，再做技能/指令识别。所有路径都经
 * [normalizeArchivePath] 归一化，并限制文件数、单文件体积和总解压体积，避免 zip bomb。
 */
internal sealed interface DshArchiveResult {
    data class Success(val files: Map<String, ByteArray>) : DshArchiveResult
    data class Failure(val message: String) : DshArchiveResult
}

internal data class DshSkillBundle(
    val name: String,
    val description: String,
    val files: Map<String, ByteArray>,
)

internal data class DshExtractResult(
    val skills: List<DshSkillBundle>,
    val prompt: String?,
    val promptFile: String?,
    val notes: List<String>,
    val fileCount: Int,
)

internal fun extractDshArchive(bytes: ByteArray): DshArchiveResult {
    if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
        return runCatching { extractTarGz(bytes) }
            .fold(
                onSuccess = { files ->
                    if (files.isEmpty()) DshArchiveResult.Failure("插件包里没有文件")
                    else DshArchiveResult.Success(files)
                },
                onFailure = { DshArchiveResult.Failure("解压 tgz 失败：${it.message ?: "压缩包已损坏"}") },
            )
    }
    if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
        return runCatching { extractZip(bytes) }
            .fold(
                onSuccess = { files ->
                    if (files.isEmpty()) DshArchiveResult.Failure("插件包里没有文件")
                    else DshArchiveResult.Success(files)
                },
                onFailure = { DshArchiveResult.Failure("解压 zip 失败：${it.message ?: "压缩包已损坏"}") },
            )
    }
    return DshArchiveResult.Failure("不支持的插件包格式（只支持 npm tgz 和 GitHub zip）")
}

/**
 * 从解包后的文件里提取所有技能与提示词。
 *
 * 技能之间可能嵌套（根 `SKILL.md` 描述一个完整工作流，`skills/<name>` 是原子技能）；
 * 每个 `SKILL.md` 都单独安装，安装器会按技能名去重，不擅自丢弃包作者的结构。
 */
internal fun extractDshBundles(files: Map<String, ByteArray>): DshExtractResult {
    val notes = mutableListOf<String>()
    val archiveRoot = commonArchiveRoot(files.keys)
    val skillMdPaths = files.keys
        .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
        .sortedBy { it.count { char -> char == '/' } }

    val skills = mutableListOf<DshSkillBundle>()
    val seenNames = mutableSetOf<String>()

    for (skillMdPath in skillMdPaths) {
        val base = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
        val skillMd = files[skillMdPath]?.toString(Charsets.UTF_8)
        if (skillMd.isNullOrBlank()) {
            notes += "跳过 $skillMdPath：SKILL.md 读取失败"
            continue
        }
        val frontmatter = SkillFrontmatterParser.parse(skillMd)
        val name = frontmatter["name"]?.trim().orEmpty()
        val description = frontmatter["description"]?.trim().orEmpty()
        if (name.isBlank() || description.isBlank()) {
            notes += "跳过 $skillMdPath：缺少 name/description"
            continue
        }
        if (!isSafeDshSkillName(name)) {
            notes += "跳过 $skillMdPath：技能名非法（$name）"
            continue
        }
        if (!seenNames.add(name)) {
            notes += "跳过重复技能：$name"
            continue
        }

        val relativeFiles = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        var tooLarge = false
        for ((path, content) in files) {
            if (base.isNotEmpty() && path != base && !path.startsWith("$base/")) continue
            val relative = if (base.isEmpty()) path else path.removePrefix("$base/").takeIf { it != path } ?: continue
            if (relative.isBlank()) continue
            if (relative == "node_modules" || relative.startsWith("node_modules/")) continue
            if (relative == ".git" || relative.startsWith(".git/")) continue
            totalBytes += content.size
            if (totalBytes > MAX_SKILL_BYTES) {
                tooLarge = true
                break
            }
            relativeFiles[if (relative.equals("SKILL.md", ignoreCase = true)) "SKILL.md" else relative] = content
        }
        if (tooLarge) {
            notes += "跳过技能 $name：附属文件超过 ${MAX_SKILL_BYTES / 1024 / 1024} MB"
            continue
        }
        if (!relativeFiles.containsKey("SKILL.md")) {
            notes += "跳过技能 $name：没有 SKILL.md"
            continue
        }
        skills += DshSkillBundle(name = name, description = description, files = relativeFiles)
    }

    val prompt = if (skills.isEmpty()) {
        findDshPrompt(files, archiveRoot)
    } else {
        null
    }

    if (skills.isEmpty() && prompt == null) {
        notes += "包内没有 SKILL.md，也没有 INSTRUCTIONS.md/FOR-AI.md 等指令文件；" +
            "这通常是 Cordis/Node 运行时插件，需要 DSH 宿主才能执行"
    }

    return DshExtractResult(
        skills = skills,
        prompt = prompt?.second,
        promptFile = prompt?.first,
        notes = notes,
        fileCount = files.size,
    )
}

/** 判断文件集里是否只包含代码，给安装结果补充可读原因。 */
internal fun looksLikeCordisRuntime(files: Map<String, ByteArray>): Boolean {
    if (files.keys.any { it.endsWith("cordis.patch.yml", ignoreCase = true) || it.endsWith("cordis.patch.yaml", ignoreCase = true) }) {
        return true
    }
    return files.any { (path, content) ->
        path.endsWith("package.json", ignoreCase = true) &&
            content.toString(Charsets.UTF_8).contains("\"dsh\"")
    }
}

private fun findDshPrompt(
    files: Map<String, ByteArray>,
    archiveRoot: String,
): Pair<String, String>? {
    val preferred = listOf(
        "INSTRUCTIONS.md",
        "FOR-AI.md",
        "AGENTS.md",
        "PROMPT.md",
        "prompt.md",
        "instructions.md",
    )
    for (name in preferred) {
        val path = if (archiveRoot.isBlank()) name else "$archiveRoot/$name"
        val content = files[path]?.toString(Charsets.UTF_8)?.trim().orEmpty()
        if (content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES) {
            return path to content
        }
    }
    // 有些包把指令放在二级目录（例如 package/docs/FOR-AI.md），只接受明确命名的文件。
    return files.entries
        .asSequence()
        .filter { (path, _) -> path.substringAfterLast('/') in preferred }
        .map { (path, bytes) -> path to bytes.toString(Charsets.UTF_8).trim() }
        .firstOrNull { (_, content) ->
            content.isNotBlank() && content.toByteArray(Charsets.UTF_8).size <= MAX_PROMPT_BYTES
        }
}

private fun commonArchiveRoot(paths: Set<String>): String {
    val first = paths.firstOrNull()?.substringBefore('/', missingDelimiterValue = "") ?: return ""
    if (first.isBlank()) return ""
    return if (paths.all { it.startsWith("$first/") }) first else ""
}

private fun isSafeDshSkillName(name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains("..") &&
        name.none { it.isISOControl() }

// ---------------------------------------------------------------------------------------------
// zip
// ---------------------------------------------------------------------------------------------

private fun extractZip(bytes: ByteArray): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>()
    var total = 0L
    var count = 0
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            try {
                if (!entry.isDirectory) {
                    count++
                    if (count > MAX_ENTRIES) throw IllegalArgumentException("文件数超过 $MAX_ENTRIES")
                    val path = normalizeArchivePath(entry.name)
                    if (path != null) {
                        val content = readLimited(zip, MAX_FILE_BYTES.toInt())
                        total += content.size
                        if (total > MAX_TOTAL_BYTES) {
                            throw IllegalArgumentException("解压后总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
                        }
                        files[path] = content
                    }
                }
            } finally {
                zip.closeEntry()
            }
        }
    }
    return files
}

// ---------------------------------------------------------------------------------------------
// tar.gz
// ---------------------------------------------------------------------------------------------

private fun extractTarGz(bytes: ByteArray): Map<String, ByteArray> {
    val files = LinkedHashMap<String, ByteArray>()
    GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val header = ByteArray(TAR_BLOCK)
        var total = 0L
        var count = 0
        var paxPath: String? = null
        var longName: String? = null

        while (true) {
            if (!readFullyOrEof(input, header)) break
            if (header.all { it == 0.toByte() }) break

            val name = tarString(header, 0, 100)
            val prefix = tarString(header, 345, 155)
            val headerPath = if (prefix.isBlank()) name else "$prefix/$name"
            val size = parseTarNumber(header, 124, 12)
            if (size < 0 || size > MAX_FILE_BYTES) {
                throw IllegalArgumentException("tar 条目过大：$headerPath")
            }
            val type = header[156].toInt().and(0xff).toChar()
            val data = readExactly(input, size.toInt())
            skipTarPadding(input, size)

            when (type) {
                'x', 'X' -> {
                    val pax = parsePax(data)
                    pax["path"]?.let { paxPath = it }
                }

                'g' -> {
                    // 全局 PAX 头很少见；只处理 path，避免把全局属性带到错误的文件上。
                    val pax = parsePax(data)
                    if (paxPath == null) pax["path"]?.let { paxPath = it }
                }

                'L' -> longName = data.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                'K' -> Unit
                '0', '\u0000', '7' -> {
                    count++
                    if (count > MAX_ENTRIES) throw IllegalArgumentException("文件数超过 $MAX_ENTRIES")
                    val path = normalizeArchivePath(longName ?: paxPath ?: headerPath) ?: continue
                    total += data.size
                    if (total > MAX_TOTAL_BYTES) {
                        throw IllegalArgumentException("解压后总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB")
                    }
                    files[path] = data
                    paxPath = null
                    longName = null
                }

                '5', '1', '2' -> {
                    paxPath = null
                    longName = null
                }

                else -> {
                    paxPath = null
                    longName = null
                }
            }
        }
    }
    return files
}

private fun parsePax(data: ByteArray): Map<String, String> {
    val text = data.toString(Charsets.UTF_8)
    val result = mutableMapOf<String, String>()
    var index = 0
    while (index < text.length) {
        val space = text.indexOf(' ', index)
        if (space < 0) break
        val length = text.substring(index, space).toIntOrNull() ?: break
        if (length <= 0 || index + length > text.length) break
        val record = text.substring(space + 1, index + length).trimEnd('\n')
        val equals = record.indexOf('=')
        if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
        index += length
    }
    return result
}

private fun parseTarNumber(header: ByteArray, offset: Int, length: Int): Long {
    if (header[offset].toInt() and 0x80 != 0) {
        var value = 0L
        for (i in 1 until length) {
            value = (value shl 8) or (header[offset + i].toLong() and 0xff)
        }
        return value
    }
    val raw = String(header, offset, length, Charsets.US_ASCII)
        .trim { it == '\u0000' || it == ' ' }
    if (raw.isEmpty()) return 0L
    return raw.toLongOrNull(8) ?: 0L
}

private fun tarString(header: ByteArray, offset: Int, length: Int): String =
    String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000').trim()

private fun readFullyOrEof(input: InputStream, target: ByteArray): Boolean {
    var read = 0
    while (read < target.size) {
        val count = input.read(target, read, target.size - read)
        if (count < 0) {
            if (read == 0) return false
            throw IllegalArgumentException("tar 头不完整")
        }
        read += count
    }
    return true
}

private fun readExactly(input: InputStream, size: Int): ByteArray {
    val output = ByteArrayOutputStream(size.coerceAtMost(64 * 1024))
    val buffer = ByteArray(64 * 1024)
    var remaining = size
    while (remaining > 0) {
        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) throw IllegalArgumentException("tar 数据不完整")
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private fun skipTarPadding(input: InputStream, size: Long) {
    val padding = ((TAR_BLOCK - (size % TAR_BLOCK)) % TAR_BLOCK).toInt()
    if (padding == 0) return
    var remaining = padding
    val buffer = ByteArray(padding)
    while (remaining > 0) {
        val read = input.read(buffer, 0, remaining)
        if (read < 0) throw IllegalArgumentException("tar padding 不完整")
        remaining -= read
    }
}

private fun readLimited(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(32 * 1024)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw IllegalArgumentException("单个文件超过 ${limit / 1024 / 1024} MB")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val TAR_BLOCK = 512
private const val MAX_ENTRIES = 20_000
private const val MAX_FILE_BYTES = 16L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
private const val MAX_SKILL_BYTES = 16L * 1024 * 1024
private const val MAX_PROMPT_BYTES = 240 * 1024
