/*
 * Shizuku UserService：在 Shizuku 进程里以 shell(ADB)/root 身份执行命令。
 *
 * 说明：Shizuku 13 起 `Shizuku.newProcess` 已不可用（官方要求改用 UserService），所以这里实现
 * 一个 AIDL 服务，由 Shizuku 把服务跑在它自己的进程里——`Runtime.exec` 自然就带着 shell/root
 * 权限。服务实例由 Shizuku 创建（无参构造），不要依赖 Android Context。
 *
 * 类名/包名会被客户端写进 UserServiceArgs，重命名时需要同步 ShizukuSupport 并提升 version。
 */
package me.rerere.rikkahub.device

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val MAX_STREAM_BYTES = 64 * 1024

class ShizukuShellService : IShizukuShellService.Stub() {

    override fun getUid(): Int = android.os.Process.myUid()

    override fun exec(command: String, timeoutMs: Int): String {
        val timeout = timeoutMs.coerceIn(1_000, 300_000).toLong()
        return try {
            // setsid 让 sh 成为新进程组的组长，超时才能 kill(-pid) 带走整棵子进程树；
            // 老 ROM 没有 setsid 时退回普通 sh（至少保证命令能跑）。
            val process = try {
                ProcessBuilder("setsid", "sh", "-c", command).redirectErrorStream(false).start()
            } catch (_: IOException) {
                ProcessBuilder("sh", "-c", command).redirectErrorStream(false).start()
            }
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val outTruncated = AtomicBoolean(false)
            val errTruncated = AtomicBoolean(false)
            val outThread = Thread {
                runCatching { outTruncated.set(copyLimited(process.inputStream, stdout)) }
            }
            val errThread = Thread {
                runCatching { errTruncated.set(copyLimited(process.errorStream, stderr)) }
            }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                killProcessTree(process)
                outThread.join(1_000)
                errThread.join(1_000)
                json(
                    ok = false,
                    exit = -1,
                    stdout = stdout.toString("UTF-8"),
                    stderr = stderr.toString("UTF-8"),
                    timedOut = true,
                    truncated = outTruncated.get() || errTruncated.get(),
                )
            } else {
                outThread.join(2_000)
                errThread.join(2_000)
                json(
                    ok = process.exitValue() == 0,
                    exit = process.exitValue(),
                    stdout = stdout.toString("UTF-8"),
                    stderr = stderr.toString("UTF-8"),
                    timedOut = false,
                    truncated = outTruncated.get() || errTruncated.get(),
                )
            }
        } catch (t: Throwable) {
            json(ok = false, exit = -1, stdout = "", stderr = t.message ?: t.toString(), timedOut = false)
        }
    }

    /** 读取时把输出封顶到 [MAX_STREAM_BYTES]，超出部分继续读完但不保存，避免子进程因管道写满而卡死。 */
    private fun copyLimited(input: InputStream, sink: ByteArrayOutputStream): Boolean {
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = MAX_STREAM_BYTES - sink.size()
            if (remaining > 0) {
                sink.write(buffer, 0, minOf(read, remaining))
            }
            if (read > remaining) truncated = true
        }
        return truncated
    }

    /** 先 TERM 整个进程组，再 KILL，最后才 destroy 兜底。 */
    private fun killProcessTree(process: Process) {
        val pid = runCatching { process.pid() }.getOrNull()
        if (pid != null && pid > 0) {
            runCatching {
                ProcessBuilder("sh", "-c", "kill -TERM -- -$pid 2>/dev/null").start()
                    .waitFor(1, TimeUnit.SECONDS)
            }
            runCatching {
                ProcessBuilder("sh", "-c", "kill -KILL -- -$pid 2>/dev/null").start()
            }
        }
        runCatching { process.destroy() }
        if (pid == null || pid <= 0) {
            runCatching { process.destroyForcibly() }
        }
    }

    override fun writeFile(path: String, chunk: ByteArray, append: Boolean): Boolean = try {
        val file = File(path)
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, append).use { out -> out.write(chunk) }
        true
    } catch (t: Throwable) {
        false
    }

    override fun deleteFile(path: String): Boolean = try {
        File(path).delete()
    } catch (t: Throwable) {
        false
    }

    override fun fileSize(path: String): Long = try {
        File(path).length()
    } catch (t: Throwable) {
        -1L
    }

    override fun readFile(path: String, offset: Long, length: Int): ByteArray = try {
        java.io.RandomAccessFile(path, "r").use { file ->
            if (offset < 0 || offset >= file.length() || length <= 0) {
                ByteArray(0)
            } else {
                file.seek(offset)
                val size = minOf(length.toLong(), file.length() - offset).toInt()
                val buffer = ByteArray(size)
                val read = file.read(buffer)
                if (read == size) buffer else buffer.copyOf(read)
            }
        }
    } catch (t: Throwable) {
        ByteArray(0)
    }

    /** Shizuku 解绑时调用：退出本服务进程（不退出 Shizuku 本体）。 */
    override fun destroy() {
        exitProcess(0)
    }

    private fun json(
        ok: Boolean,
        exit: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        truncated: Boolean = false,
    ): String =
        buildString {
            append("{\"ok\":").append(ok)
            append(",\"exit\":").append(exit)
            append(",\"timedOut\":").append(timedOut)
            append(",\"truncated\":").append(truncated)
            append(",\"stdout\":\"").append(escape(stdout)).append('"')
            append(",\"stderr\":\"").append(escape(stderr)).append('"')
            append('}')
        }

    private fun escape(value: String): String = buildString(value.length + 16) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append(' ') else append(ch)
            }
        }
    }
}
