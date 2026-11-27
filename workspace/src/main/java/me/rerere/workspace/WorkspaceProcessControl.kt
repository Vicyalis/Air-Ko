package me.rerere.workspace

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 工作区子进程的终止策略。
 *
 * proot 把 SIGTERM 设成 SIG_IGN，所以 `Process.destroy()` 对它无效；
 * 而 `destroyForcibly()`（SIGKILL）会让 `--kill-on-exit` 的清理逻辑根本不执行，
 * 整棵 guest 进程树被放生——长时间使用后表现为"越用越慢"（残留进程 + 残留 socket）。
 *
 * 正确顺序是 **SIGQUIT → 宽限 → SIGKILL**：proot 收到 SIGQUIT 会转发给 tracee 并自己退出，
 * 从而走到它的清理路径。proot-distro 的 kill 实现也是这个顺序。
 *
 * 本类只做"尽力而为"：拿不到 pid（反射被隐藏 API 策略拦截等）或信号发送失败时，
 * 立即回落到调用方原本的 SIGKILL 行为，不引入额外等待。
 */
object WorkspaceProcessControl {

    const val SIGQUIT = 3
    const val SIGKILL = 9

    /**
     * 优雅终止一个 [Process]：先 SIGQUIT，宽限期内没退出才 SIGKILL。
     * 拿不到 pid 时保持原有行为（立即 SIGKILL），避免超时处理被拖慢。
     */
    fun terminate(process: Process, gracefulMillis: Long = 2_000) {
        val pid = pidOf(process)
        if (pid == null || !sendSignal(pid, SIGQUIT)) {
            runCatching { process.destroyForcibly() }
            return
        }
        val exited = runCatching {
            process.waitFor(gracefulMillis, TimeUnit.MILLISECONDS)
        }.getOrDefault(true)
        if (!exited) {
            runCatching { process.destroyForcibly() }
        }
    }

    /**
     * 只拿到 pid 时（Termux 的 TerminalSession）的优雅终止。
     * 异步完成：SIGQUIT 后等待宽限期，仍未退出才 SIGKILL；不阻塞调用线程。
     */
    fun terminatePid(pid: Int, gracefulMillis: Long = 2_000) {
        if (pid <= 0) return
        if (!sendSignal(pid, SIGQUIT)) {
            sendSignal(pid, SIGKILL)
            return
        }
        Thread {
            val deadline = System.currentTimeMillis() + gracefulMillis
            while (System.currentTimeMillis() < deadline && isAlive(pid)) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
            if (isAlive(pid)) sendSignal(pid, SIGKILL)
        }.apply {
            name = "workspace-terminate-$pid"
            isDaemon = true
            start()
        }
    }

    private fun isAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    private fun pidOf(process: Process): Int? {
        // Android 13+ / JDK 9+ 提供 Process.pid()
        runCatching {
            val method = process.javaClass.getMethod("pid")
            (method.invoke(process) as? Long)?.toInt()?.takeIf { it > 0 }?.let { return it }
        }
        // 旧版本回落到内部字段（Android 的 ProcessImpl 有 int pid）
        var cls: Class<*>? = process.javaClass
        while (cls != null && cls != Any::class.java) {
            runCatching {
                val field = cls!!.getDeclaredField("pid")
                field.isAccessible = true
                (field.get(process) as? Int)?.takeIf { it > 0 }?.let { return it }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun sendSignal(pid: Int, signal: Int): Boolean {
        runCatching {
            val processClass = Class.forName("android.os.Process")
            val method = processClass.getMethod(
                "sendSignal",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            method.invoke(null, pid, signal)
            return true
        }
        // 反射被隐藏 API 策略拦截时的兜底：kill 在 Android(/system/bin/kill) 与 Linux 都存在
        runCatching {
            val code = ProcessBuilder("kill", "-$signal", pid.toString())
                .redirectErrorStream(true)
                .start()
                .let { p -> if (p.waitFor(500, TimeUnit.MILLISECONDS)) p.exitValue() else -1 }
            return code == 0
        }
        return false
    }
}
