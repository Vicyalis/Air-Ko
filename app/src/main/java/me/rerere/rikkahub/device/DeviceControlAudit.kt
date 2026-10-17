/*
 * 设备控制·审计日志（对应 Reticle 的 logs/ 审计：每次工具调用一行）。
 *
 * 设计口径：不阻塞工具、不抛异常；按天一个文件，超过 1MB 换名续写；只留最近 14 天。
 * 记录内容：时间、工具名、参数摘要、结果摘要、耗时。参数与结果都会先做单行化与截断，
 * 免得一次截图/长文本把日志灌爆，也避免换行把「一行一条」的格式弄乱。
 */
package me.rerere.rikkahub.device

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceControlAudit {
    internal const val MAX_FILE_BYTES = 1_000_000L
    internal const val KEEP_DAYS = 14
    internal const val DIR_NAME = "device_control_logs"
    private const val MAX_LINE_CHARS = 500
    private const val SUMMARY_CHARS = 120

    private val lock = Any()
    @Volatile
    private var lastCleanupAt = 0L

    fun log(context: Context, tool: String, args: String, result: String, durationMs: Long) {
        runCatching {
            val now = System.currentTimeMillis()
            val dir = dirFor(context)
            synchronized(lock) {
                if (!dir.exists()) dir.mkdirs()
                var file = fileFor(dir, now)
                if (file.length() > MAX_FILE_BYTES) {
                    val rotated = File(dir, "audit-${fileStamp(now)}-${now % 100_000}.log")
                    if (file.renameTo(rotated)) {
                        file = fileFor(dir, now)
                    } else {
                        // 换名失败（极少）：直接截断太粗暴，改写到新名字
                        file = File(dir, "audit-${fileStamp(now)}-${now % 100_000}.log")
                    }
                }
                file.appendText(buildLine(tool, args, result, durationMs, now) + "\n")
                if (now - lastCleanupAt > 3_600_000L) {
                    lastCleanupAt = now
                    val keepDays = runCatching {
                        DeviceControlPrefs.auditRetentionDays(context)
                    }.getOrDefault(KEEP_DAYS)
                    cleanupOld(dir, now, keepDays)
                }
            }
        }
    }

    /** 最近 [maxLines] 行（新的在前），供设置页审计查看器使用。 */
    fun recentLines(context: Context, maxLines: Int = 400): List<String> {
        return runCatching {
            val dir = dirFor(context)
            if (!dir.exists()) return emptyList()
            val lines = ArrayList<String>(maxLines)
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    if (lines.size >= maxLines) return@forEach
                    val chunk = file.readLines().asReversed()
                    for (line in chunk) {
                        lines.add(line)
                        if (lines.size >= maxLines) break
                    }
                }
            lines
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching {
            synchronized(lock) {
                dirFor(context).listFiles()?.forEach { it.delete() }
            }
        }
    }

    fun dirFor(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 单行化 + 截断（纯函数，可单测）。 */
    internal fun sanitize(raw: String?, limit: Int = SUMMARY_CHARS): String {
        if (raw.isNullOrBlank()) return ""
        val oneLine = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val collapsed = oneLine.replace(Regex(" {2,}"), " ").trim()
        return if (collapsed.length <= limit) {
            collapsed
        } else {
            collapsed.substring(0, limit) + "…(" + collapsed.length + ")"
        }
    }

    /** 一行日志（纯函数，可单测）。 */
    internal fun buildLine(
        tool: String,
        args: String,
        result: String,
        durationMs: Long,
        timestamp: Long,
    ): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
        return "[$time] $tool ${durationMs}ms args=${sanitize(args)} result=${sanitize(result, MAX_LINE_CHARS)}"
    }

    internal fun fileStamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))

    internal fun fileFor(dir: File, timestamp: Long): File = File(dir, "audit-${fileStamp(timestamp)}.log")

    internal fun cleanupOld(dir: File, now: Long, keepDays: Int = KEEP_DAYS) {
        val cutoff = now - keepDays.coerceIn(1, 365) * 24 * 3_600_000L
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }
}
