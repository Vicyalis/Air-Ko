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
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class ShizukuShellService : IShizukuShellService.Stub() {

    override fun exec(command: String, timeoutMs: Int): String {
        val timeout = timeoutMs.coerceIn(1_000, 300_000).toLong()
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val outThread = Thread { runCatching { process.inputStream.copyTo(stdout) } }
            val errThread = Thread { runCatching { process.errorStream.copyTo(stderr) } }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(timeout, TimeUnit.MILLISECONDS)
            if (!finished) {
                runCatching { process.destroy() }
                outThread.join(500)
                errThread.join(500)
                json(
                    ok = false,
                    exit = -1,
                    stdout = stdout.toString("UTF-8"),
                    stderr = stderr.toString("UTF-8"),
                    timedOut = true,
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
                )
            }
        } catch (t: Throwable) {
            json(ok = false, exit = -1, stdout = "", stderr = t.message ?: t.toString(), timedOut = false)
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

    /** Shizuku 解绑时调用：退出本服务进程（不退出 Shizuku 本体）。 */
    override fun destroy() {
        exitProcess(0)
    }

    private fun json(ok: Boolean, exit: Int, stdout: String, stderr: String, timedOut: Boolean): String =
        buildString {
            append("{\"ok\":").append(ok)
            append(",\"exit\":").append(exit)
            append(",\"timedOut\":").append(timedOut)
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
