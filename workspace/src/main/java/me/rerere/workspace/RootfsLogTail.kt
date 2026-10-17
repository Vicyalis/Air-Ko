package me.rerere.workspace

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * rootfs 内日志文件的尾部读取。
 *
 * 桌面方案的安装日志 (apt/pip 输出) 轻易就有几十 MB, 全量读进内存会直接把 App 顶爆,
 * 而用户真正要看的只是最后几屏 —— 所以只从文件末尾倒着取 [DEFAULT_MAX_BYTES] 字节。
 */
object RootfsLogTail {
    /** 日志尾巴的默认上限: 足够看清「装到哪一步 / 最后报什么错」 */
    const val DEFAULT_MAX_BYTES = 32 * 1024

    /**
     * 读取 [file] 末尾最多 [maxBytes] 字节。
     *
     * - 返回 null 表示「还没有日志」: 文件不存在、不是普通文件、或 [maxBytes] 非法
     * - 文件存在但没有内容时返回空串
     * - 从中间截断时丢掉最前面的半行, 避免 UI 上出现一条从字母中间开始的残行
     * - 按 UTF-8 解码: 文件大于 [maxBytes] 时首字符可能被切断, 会退化成替换字符
     */
    fun read(file: File, maxBytes: Int = DEFAULT_MAX_BYTES): String? {
        if (maxBytes <= 0) return null
        if (!file.isFile) return null

        val length = file.length()
        if (length <= 0L) return ""

        val start = (length - maxBytes).coerceAtLeast(0L)
        val tail = RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray((length - start).toInt())
            raf.readFully(buffer)
            buffer
        }

        val text = String(tail, StandardCharsets.UTF_8)
        val visible = if (start > 0L) {
            // 起始位置切在半行上, 丢掉这半行 (没有换行说明整段就是一个残行, 返回空串)
            text.substringAfter('\n', "")
        } else {
            text
        }
        return stripAnsi(visible)
    }

    // apt / pip / 用户自己的启动脚本在重定向时通常不会带颜色, 但 pnpm、cargo 这类工具
    // 探测不到 TTY 也可能吐控制序列; 日志是当纯文本展示的, 留着会变成一串乱码。
    private val ANSI_CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val ANSI_OSC = Regex("\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)")

    fun stripAnsi(text: String): String {
        if (text.indexOf('\u001B') < 0) return text
        return ANSI_OSC.replace(ANSI_CSI.replace(text, ""), "")
    }
}
