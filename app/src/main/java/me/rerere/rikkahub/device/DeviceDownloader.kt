/*
 * 设备控制·下载器——能力对齐 Reticle 的 Downloader.java（Apache-2.0，Copyright 2026 Bumfuzzle；
 * 已改写为 Kotlin，落盘白名单与「同步/后台」判定口径保持一致）。
 *
 * 为什么有它：助手在完全没有电脑、完全在手机上的场景下也能把文件拿到手（安装包、素材、数据），
 * 并且「小文件当场拿、大文件丢后台带进度通知」——不让一次工具调用把对话卡死。
 *
 * 白名单（只写这两个地方，其它路径一律拒绝）：
 *   ① 应用自己的下载目录：<外部私有目录>/Download（默认）；
 *   ② 系统「下载」目录：/storage/emulated/0/Download（需要「所有文件访问」授权）。
 *
 * 判定口径：
 *   ≤ 8MB 且拿得到大小 → 同步完成（自身也带 45 秒上限，超了自动转后台续传）；
 *   源不给大小 → 先同步试 20 秒 / 12MB，超了转后台续传；
 *   > 8MB 或明确很大 → 直接后台，带进度通知；
 *   不加 force 时 2GB 封顶（force=1 可突破并覆盖同名文件）。
 *
 * 校验：可选 sha256 核对（不一致＝明确报错并删除坏文件，不许假装成功）；可选 unzip 解压
 * （带 zip-slip 防护，解到同名文件夹）。
 */
package me.rerere.rikkahub.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "DeviceDownloader"

object DeviceDownloader {

    /** 同步完成的上限；超过就转后台（Reticle 同款阈值）。 */
    private const val SYNC_MAX_BYTES = 8L * 1024 * 1024

    /** 同步路径的整体时限（转后台续传，不丢进度）。 */
    private const val SYNC_DEADLINE_MS = 45_000L

    /** 源不给大小：先同步试这个时长 / 字节数，超了转后台。 */
    private const val UNKNOWN_SYNC_DEADLINE_MS = 20_000L
    private const val UNKNOWN_SYNC_MAX_BYTES = 12L * 1024 * 1024

    /** 不加 force 时的大小封顶。 */
    private const val MAX_BYTES_WITHOUT_FORCE = 2L * 1024 * 1024 * 1024

    private const val FIRST_NOTIFICATION_ID = 2201
    private const val NOTIFICATION_ID_RANGE = 50
    private const val MAX_TASKS = 20
    private const val BUFFER_BYTES = 64 * 1024
    private const val PART_SUFFIX = ".part"

    sealed class Outcome {
        data class Saved(
            val path: String,
            val bytes: Long,
            val sha256: String,
            val unzippedTo: String? = null,
            val unzippedCount: Int = 0,
            val verified: Boolean = false,
        ) : Outcome()

        data class Started(val id: String, val name: String, val note: String) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    data class Task(
        val id: String,
        val url: String,
        val name: String,
        val targetPath: String,
        @Volatile var state: String = "running",
        @Volatile var bytesRead: Long = 0,
        @Volatile var totalBytes: Long = -1,
        @Volatile var sha256Actual: String? = null,
        @Volatile var resultPath: String? = null,
        @Volatile var error: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        @Volatile var finishedAt: Long = 0L,
        @Volatile var speedBps: Long = 0L,
    )

    private val tasks = ConcurrentHashMap<String, Task>()
    private val taskOrder = java.util.Collections.synchronizedList(ArrayList<String>())
    private val notificationIdCounter = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("device-download"))

    // ------------------------------------------------------------------ 目录白名单

    /** 应用自己的下载目录（默认落点；不需要任何授权）。 */
    fun appDownloadDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Download")
    }

    /** 「所有文件访问」是否已授权（系统下载目录的前置条件）。 */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** 系统「下载」目录；没有「所有文件访问」、或用户选择只用应用目录时返回 null。 */
    private fun sharedDownloadDir(context: Context): File? {
        if (runCatching { DeviceControlPrefs.downloadToAppDirOnly(context) }.getOrDefault(false)) return null
        if (!hasAllFilesAccess(context)) return null
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    /**
     * 解析 dir 参数：空白＝应用下载目录；"download"/"Downloads"/系统下载目录……＝系统下载目录；
     * 也接受应用下载目录的完整路径。其它一律拒绝（白名单语义）。
     */
    internal fun resolveDir(context: Context, dirSpec: String?): File? {
        val appDir = appDownloadDir(context)
        val spec = dirSpec?.trim().orEmpty()
        if (spec.isEmpty()) return appDir
        val lowered = spec.lowercase(Locale.US).removeSuffix("/")
        val looksLikeSystem = lowered == "download" || lowered == "downloads" ||
            lowered == "/sdcard/download" || lowered == "/storage/emulated/0/download"
        if (looksLikeSystem) {
            return sharedDownloadDir(context)
        }
        val asFile = File(spec)
        if (runCatching { asFile.canonicalPath == appDir.canonicalPath }.getOrDefault(false)) {
            return appDir
        }
        runCatching {
            val shared = sharedDownloadDir(context)
            if (shared != null && asFile.canonicalPath == shared.canonicalPath) return shared
        }
        return null
    }

    /** 白名单被拒时的说明（工具层直接给模型看）。 */
    fun whitelistHint(context: Context): String =
        "dir whitelist: \"\" (app download dir ${appDownloadDir(context).absolutePath}) or " +
            "\"Download\" (system Downloads; needs all-files access on Android 11+ or the storage " +
            "permission on Android 10 and below)"

    // ------------------------------------------------------------------ 入口

    /**
     * 下载一个 URL。[dirSpec] 见 [resolveDir]；[headersRaw] 支持 JSON 对象或 "Key: value" 多行。
     */
    fun download(
        context: Context,
        url: String,
        dirSpec: String?,
        name: String?,
        headersRaw: String?,
        expectedSha256: String?,
        unzip: Boolean,
        force: Boolean,
    ): Outcome {
        val parsed = runCatching { URL(url.trim()) }.getOrNull()
            ?: return Outcome.Failed("not a valid URL: $url")
        if (parsed.protocol != "http" && parsed.protocol != "https") {
            return Outcome.Failed("only http/https URLs are supported (got ${parsed.protocol})")
        }
        val dir = resolveDir(context, dirSpec)
            ?: return Outcome.Failed(
                if (dirSpec.isNullOrBlank() || dirSpec.trim().isEmpty()) {
                    "could not resolve the app download dir"
                } else {
                    "dir rejected; ${whitelistHint(context)}" +
                        (if (dirSpec.trim().lowercase(Locale.US).startsWith("download") ||
                            dirSpec.trim().contains("emulated")
                        ) " - the system Downloads dir needs the all-files access permission" else "")
                },
            )
        if (!dir.exists() && !dir.mkdirs()) {
            return Outcome.Failed("cannot create dir ${dir.absolutePath}")
        }
        val fileName = sanitizeFileName(name?.takeIf { it.isNotBlank() } ?: fileNameFromUrl(url))
            .ifBlank { "download-${System.currentTimeMillis()}" }
        val target = File(dir, fileName)
        if (target.exists() && !force) {
            return Outcome.Failed("file exists: ${target.absolutePath} (pass force=1 to overwrite)")
        }
        val headers = parseHeaders(headersRaw)

        return runCatching {
            val conn = openConnection(parsed, headers)
            val length = conn.contentLengthLong
            if (length > MAX_BYTES_WITHOUT_FORCE && !force) {
                conn.disconnect()
                return@runCatching Outcome.Failed(
                    "size ${formatBytes(length)} exceeds the 2GB cap (pass force=1 to allow)",
                )
            }
            val part = File(target.absolutePath + PART_SUFFIX)
            if (length in 0..SYNC_MAX_BYTES) {
                // 明确的小文件：当场拿
                val stream = streamToPart(
                    conn, part,
                    deadlineAt = System.currentTimeMillis() + SYNC_DEADLINE_MS,
                    byteLimit = null,
                    onProgress = null,
                )
                when (stream) {
                    is Stream.Complete -> finalize(context, target, part, expectedSha256, unzip)
                    is Stream.Beyond -> handOver(context, parsed, headers, target, part, expectedSha256, unzip, force, stream.bytes, length)
                    is Stream.Timeout -> handOver(context, parsed, headers, target, part, expectedSha256, unzip, force, stream.bytes, length)
                    is Stream.Failed -> Outcome.Failed(stream.reason)
                }
            } else if (length > SYNC_MAX_BYTES || length < 0) {
                // 大文件或源不给大小：先同步试一段（不给大小才有意义），超了转后台
                if (length > SYNC_MAX_BYTES) {
                    conn.disconnect()
                    handOver(context, parsed, headers, target, part, expectedSha256, unzip, force, 0L, length)
                } else {
                    val stream = streamToPart(
                        conn, part,
                        deadlineAt = System.currentTimeMillis() + UNKNOWN_SYNC_DEADLINE_MS,
                        byteLimit = UNKNOWN_SYNC_MAX_BYTES,
                        onProgress = null,
                    )
                    when (stream) {
                        is Stream.Complete -> finalize(context, target, part, expectedSha256, unzip)
                        is Stream.Beyond -> handOver(context, parsed, headers, target, part, expectedSha256, unzip, force, stream.bytes, -1)
                        is Stream.Timeout -> handOver(context, parsed, headers, target, part, expectedSha256, unzip, force, stream.bytes, -1)
                        is Stream.Failed -> Outcome.Failed(stream.reason)
                    }
                }
            } else {
                conn.disconnect()
                Outcome.Failed("server returned no data")
            }
        }.getOrElse { t ->
            Log.w(TAG, "download failed: $url", t)
            Outcome.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ 后台任务

    private fun handOver(
        context: Context,
        url: URL,
        headers: Map<String, String>,
        target: File,
        part: File,
        expectedSha256: String?,
        unzip: Boolean,
        force: Boolean,
        alreadyBytes: Long,
        expectedTotal: Long,
    ): Outcome {
        val id = "%08x".format(System.currentTimeMillis() and 0xFFFFFFFFL)
        val task = Task(
            id = id,
            url = url.toString(),
            name = target.name,
            targetPath = target.absolutePath,
            bytesRead = alreadyBytes,
            totalBytes = expectedTotal,
        )
        register(task)
        val notificationId = FIRST_NOTIFICATION_ID + (notificationIdCounter.getAndIncrement() % NOTIFICATION_ID_RANGE)
        val app = context.applicationContext
        scope.launch {
            runBackground(app, notificationId, task, url, headers, target, part, expectedSha256, unzip, force)
        }
        return Outcome.Started(
            id = id,
            name = target.name,
            note = "large or unknown size; downloading in the background " +
                "(notification id #$notificationId) - call device_download_status with id=$id",
        )
    }

    private suspend fun runBackground(
        context: Context,
        notificationId: Int,
        task: Task,
        url: URL,
        headers: Map<String, String>,
        target: File,
        part: File,
        expectedSha256: String?,
        unzip: Boolean,
        force: Boolean,
    ) {
        try {
            DeviceControlNotifier.progress(
                context, notificationId,
                context.getString(R.string.device_download_notification_title),
                "${task.name} - starting",
                if (task.totalBytes > 0) 0 else null,
            )
            var resumed = false
            var conn = openConnection(url, headers)
            if (part.exists() && part.length() > 0) {
                conn.disconnect()
                conn = openConnection(url, headers, rangeFrom = part.length())
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    resumed = true
                } else {
                    // 源不支持续传：从头再来
                    part.delete()
                }
            }
            val baseBytes = if (resumed) part.length() else 0L
            if (task.totalBytes <= 0) {
                val len = conn.contentLengthLong
                if (len > 0) task.totalBytes = baseBytes + len
            }
            var lastNotifyAt = 0L
            var lastBytes = baseBytes
            var lastAt = System.currentTimeMillis()
            val stream = streamToPart(
                conn, part,
                deadlineAt = null,
                byteLimit = null,
                append = resumed,
                onProgress = { bytes ->
                    val now = System.currentTimeMillis()
                    task.bytesRead = bytes
                    val elapsed = now - lastAt
                    if (elapsed >= 1_000L) {
                        task.speedBps = ((bytes - lastBytes) * 1_000L / elapsed).coerceAtLeast(0)
                        lastBytes = bytes
                        lastAt = now
                    }
                    if (now - lastNotifyAt >= 700L) {
                        lastNotifyAt = now
                        val percent = if (task.totalBytes > 0) {
                            ((bytes * 100) / task.totalBytes).toInt().coerceIn(0, 100)
                        } else null
                        val text = buildProgressText(task.name, bytes, task.totalBytes, task.speedBps)
                        DeviceControlNotifier.progress(
                            context, notificationId,
                            context.getString(R.string.device_download_notification_title),
                            text, percent,
                        )
                    }
                },
            )
            when (stream) {
                is Stream.Complete -> {
                    when (val outcome = finalize(context, target, part, expectedSha256, unzip)) {
                        is Outcome.Saved -> {
                            task.state = "done"
                            task.bytesRead = outcome.bytes
                            task.totalBytes = if (task.totalBytes > 0) task.totalBytes else outcome.bytes
                            task.sha256Actual = outcome.sha256
                            task.resultPath = outcome.unzippedTo ?: outcome.path
                            task.finishedAt = System.currentTimeMillis()
                            DeviceControlNotifier.progress(
                                context, notificationId,
                                context.getString(R.string.device_download_notification_done),
                                "${task.name} -> ${task.resultPath}",
                                null,
                            )
                        }

                        is Outcome.Failed -> failTask(context, notificationId, task, outcome.reason)
                        is Outcome.Started -> failTask(context, notificationId, task, "unexpected state")
                    }
                }

                is Stream.Failed -> failTask(context, notificationId, task, stream.reason)
                is Stream.Timeout, is Stream.Beyond ->
                    failTask(context, notificationId, task, "unexpected stop")
            }
        } catch (t: Throwable) {
            failTask(context, notificationId, task, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun failTask(context: Context, notificationId: Int, task: Task, reason: String) {
        task.state = "error"
        task.error = reason
        task.finishedAt = System.currentTimeMillis()
        runCatching {
            DeviceControlNotifier.progress(
                context, notificationId,
                context.getString(R.string.device_download_notification_failed),
                "${task.name}: $reason",
                null,
            )
        }
    }

    // ------------------------------------------------------------------ 流式下载

    private sealed class Stream {
        data class Complete(val bytes: Long) : Stream()
        data class Beyond(val bytes: Long) : Stream()
        data class Timeout(val bytes: Long) : Stream()
        data class Failed(val reason: String) : Stream()
    }

    private fun openConnection(
        url: URL,
        headers: Map<String, String>,
        rangeFrom: Long? = null,
    ): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Air-Ko/device-control")
        headers.forEach { (key, value) -> runCatching { conn.setRequestProperty(key, value) } }
        if (rangeFrom != null && rangeFrom > 0) {
            conn.setRequestProperty("Range", "bytes=$rangeFrom-")
        }
        conn.connect()
        return conn
    }

    private fun streamToPart(
        conn: HttpURLConnection,
        part: File,
        deadlineAt: Long?,
        byteLimit: Long?,
        append: Boolean = false,
        onProgress: ((Long) -> Unit)?,
    ): Stream {
        val code = conn.responseCode
        if (code !in 200..299) {
            return Stream.Failed("http $code ${conn.responseMessage ?: ""}".trim())
        }
        var result: Stream? = null
        return try {
            val input: InputStream = conn.inputStream ?: return Stream.Failed("no response body")
            var bytes = if (append && part.exists()) part.length() else 0L
            FileOutputStream(part, append).use { output ->
                input.use { stream ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    loop@ while (true) {
                        if (deadlineAt != null && System.currentTimeMillis() > deadlineAt) {
                            result = Stream.Timeout(bytes)
                            break@loop
                        }
                        val read = stream.read(buffer)
                        if (read < 0) {
                            result = Stream.Complete(bytes)
                            break@loop
                        }
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            bytes += read
                            onProgress?.invoke(bytes)
                            if (byteLimit != null && bytes >= byteLimit) {
                                result = Stream.Beyond(bytes)
                                break@loop
                            }
                        }
                    }
                }
            }
            result ?: Stream.Failed("no data")
        } catch (t: Throwable) {
            Log.w(TAG, "stream failed", t)
            Stream.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ------------------------------------------------------------------ 收尾（校验 / 落名 / 解压）

    private fun finalize(
        context: Context,
        target: File,
        part: File,
        expectedSha256: String?,
        unzip: Boolean,
    ): Outcome {
        if (target.exists() && !target.delete()) {
            return Outcome.Failed("cannot replace existing file ${target.absolutePath}")
        }
        if (!part.renameTo(target)) {
            return Outcome.Failed("cannot move ${part.name} into place")
        }
        val bytes = target.length()
        val sha = sha256Hex(target)
        val expected = expectedSha256?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val verified = expected != null
        if (expected != null && !sha.equals(expected, ignoreCase = true)) {
            target.delete()
            return Outcome.Failed("sha256 mismatch: expected $expected, got $sha (bad file deleted)")
        }
        if (unzip) {
            val result = unzipInto(target)
            if (result == null) {
                return Outcome.Saved(target.absolutePath, bytes, sha, verified = verified)
            }
            if (result.error != null) {
                // 解压失败：文件本身保留，错误明说
                return Outcome.Failed(
                    "saved ${target.absolutePath} but unzip failed: ${result.error}",
                )
            }
            return Outcome.Saved(
                path = target.absolutePath,
                bytes = bytes,
                sha256 = sha,
                unzippedTo = result.dir?.absolutePath,
                unzippedCount = result.count,
                verified = verified,
            )
        }
        return Outcome.Saved(target.absolutePath, bytes, sha, verified = verified)
    }

    private data class UnzipResult(val dir: File?, val count: Int, val error: String? = null)

    /** 解压到同名文件夹（zip-slip 防护：任何逃出目标目录的条目直接跳过）。 */
    private fun unzipInto(zip: File): UnzipResult? {
        if (!zip.name.lowercase(Locale.US).endsWith(".zip")) return null
        val dirName = zip.name.dropLast(4).ifBlank { "unzipped" }
        val targetDir = File(zip.parentFile, dirName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return UnzipResult(null, 0, "cannot create ${targetDir.absolutePath}")
        }
        val canonicalRoot = targetDir.canonicalPath
        var count = 0
        return try {
            java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val out = File(targetDir, entry.name)
                    val canonical = runCatching { out.canonicalPath }.getOrNull()
                    if (canonical == null || !canonical.startsWith(canonicalRoot)) {
                        zipIn.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fos -> zipIn.copyTo(fos) }
                        count++
                    }
                    zipIn.closeEntry()
                }
            }
            UnzipResult(targetDir, count)
        } catch (t: Throwable) {
            UnzipResult(targetDir, count, t.message ?: t.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ 状态

    private fun register(task: Task) {
        tasks[task.id] = task
        taskOrder.add(task.id)
        while (taskOrder.size > MAX_TASKS) {
            val oldest = taskOrder.removeAt(0)
            tasks.remove(oldest)
        }
    }

    fun statusText(id: String?): String {
        if (!id.isNullOrBlank()) {
            val task = tasks[id.trim()]
                ?: return "error: unknown task id=$id (download tasks live in memory only; " +
                    "they are forgotten when the app process restarts)"
            return describe(task)
        }
        val all = taskOrder.toList().mapNotNull { tasks[it] }
        if (all.isEmpty()) return "no download tasks this session"
        return all.joinToString("\n") { describe(it) }
    }

    private fun describe(task: Task): String {
        val percent = if (task.totalBytes > 0) {
            " (${(task.bytesRead * 100 / task.totalBytes)}%)"
        } else ""
        val total = if (task.totalBytes > 0) "/${formatBytes(task.totalBytes)}" else ""
        val speed = if (task.state == "running" && task.speedBps > 0) {
            " speed=${formatBytes(task.speedBps)}/s"
        } else ""
        val tail = when (task.state) {
            "done" -> " path=${task.resultPath} sha256=${task.sha256Actual}"
            "error" -> " error=${task.error}"
            else -> " target=${task.targetPath}"
        }
        return "id=${task.id} state=${task.state} name=${task.name} " +
            "bytes=${formatBytes(task.bytesRead)}$total$percent$speed$tail"
    }

    private fun buildProgressText(name: String, bytes: Long, total: Long, speedBps: Long): String {
        val head = if (total > 0) {
            "${(bytes * 100 / total)}% ${formatBytes(bytes)}/${formatBytes(total)}"
        } else {
            formatBytes(bytes)
        }
        val speed = if (speedBps > 0) " - ${formatBytes(speedBps)}/s" else ""
        return "$name: $head$speed"
    }

    // ------------------------------------------------------------------ 纯函数（单测覆盖）

    /** 文件名净化：去掉路径分隔符/控制字符/危险字符，防止跳出目标目录。 */
    internal fun sanitizeFileName(raw: String): String {
        val cleaned = buildString(raw.length) {
            raw.forEach { ch ->
                when {
                    ch.code < 0x20 -> append('_')
                    ch in "\\/:*?\"<>|" -> append('_')
                    else -> append(ch)
                }
            }
        }.trim().trim('.').trim()
        return cleaned.take(160).trim()
    }

    /** 从 URL 猜文件名（取 path 的最后一段，去 query/fragment，URL 解码）。 */
    internal fun fileNameFromUrl(url: String): String {
        val noQuery = url.substringBefore('#').substringBefore('?')
        val path = runCatching { java.net.URL(noQuery).path }
            .getOrNull()
            ?: noQuery.substringAfter("://", noQuery)
        val last = path.trimEnd('/').substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        return sanitizeFileName(decoded)
    }

    /**
     * 解析请求头：JSON 对象（{"Referer":"..."}）或每行一条 "Key: value"。
     * 解析不了的条目直接忽略（不因为一个坏头把整次下载废掉）。
     */
    internal fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return runCatching {
                Json.parseToJsonElement(trimmed).jsonObject.mapNotNull { (key, value) ->
                    val text = runCatching { value.jsonPrimitive.content }.getOrNull()
                    if (text.isNullOrBlank()) null else key to text
                }.toMap()
            }.getOrDefault(emptyMap())
        }
        return trimmed.lines().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) return@mapNotNull null
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }.toMap()
    }

    internal fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1fMB", bytes / 1024.0 / 1024)
        else -> String.format(Locale.US, "%.2fGB", bytes / 1024.0 / 1024 / 1024)
    }

    internal fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 供状态页/自测使用的内存快照。 */
    fun runningCount(): Int = tasks.values.count { it.state == "running" }
}
