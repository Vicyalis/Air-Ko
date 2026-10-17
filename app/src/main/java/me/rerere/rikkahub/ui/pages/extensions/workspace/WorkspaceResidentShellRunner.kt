package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import me.rerere.workspace.MAX_OUTPUT_CHARS
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 把 AI 的 shell 命令投递到常驻的「AI」终端会话里执行，而不是每条命令都冷起一个 proot。
 *
 * 这是「反应慢」的解法：原来的 [me.rerere.workspace.ProotShellRunner] 每条命令都要重新 fork
 * proot、加载动态库、起一个 login shell（1~3 秒），现在只有第一次付这个成本，之后的命令直接
 * 打进已经在跑的 shell。命令落在终端里可见，用户切到 AI 标签页就能看着 AI 干活。
 *
 * ## 输出为什么走文件，而不是读终端屏幕
 *
 * [TerminalSession] 是 final 类，没有任何输出回调；唯一能拿到字节的
 * `mProcessToTerminalIOQueue` 是包内可见，而且外部取用会抢掉会话自己的读取线程。
 * 抓 [com.termux.terminal.TerminalBuffer] 的屏幕则是**有损**的：受滚动回看行数限制、
 * 长行被折成多行（要靠 lineWrap 标志拼回去）、大输出直接丢掉开头。
 *
 * 所以这里让 shell 把输出**同时**写一份到 rootfs 的临时文件（`tee` 分流，终端照常实时显示），
 * 再从宿主侧读那个文件：字节精确、不受终端宽度与回看缓冲限制。结束标记也从文件里找，
 * 完全不必解析屏幕。
 *
 * ## 保底
 *
 * 任何一步不成立都退回 [fallback]（也就是原来的 proot 行为）。**但只在命令还没跑之前退**：
 * 命令一旦执行过就绝不重跑，否则带副作用的命令（写文件、装包）会被执行两次。
 */
class WorkspaceResidentShellRunner(
    private val sessionManager: WorkspaceTerminalSessionManager,
    private val fallback: WorkspaceShellRunner,
) : WorkspaceShellRunner {

    /**
     * 每个工作区同时只允许一条命令：常驻会话只有一根 stdin，
     * 并发投递会让两条命令的输入互相插队。AI 在同一轮里并行发多个 shell 调用时靠这把锁排队。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** rootfs 是否具备 `tee`。不做缓存：几条 `stat` 而已，缓存反而要处理「装完 rootfs 才出现」的失效问题。 */
    private fun hasTee(linuxDir: File): Boolean =
        TEE_CANDIDATES.any { File(linuxDir, it).exists() } ||
            BUSYBOX_CANDIDATES.any { File(linuxDir, it).exists() }

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        // 需要喂 stdin 的命令退回 proot：往 PTY 里灌输入会与行编辑、回显搅在一起，
        // 而且命令文本本身也可能被前台进程当成输入读走，风险不值得担
        if (context.stdin != null) return fallback.execute(context)

        // tee 缺失时必须**在命令执行前**退回去。若等运行时才发现，pipeline 左端会被 SIGPIPE
        // 立刻打死：命令可能已经部分执行，结束标记却永远不会出现，只能干等超时，
        // 而且命令已经跑过就不能重跑补救了。见 hasTee 的注释。
        if (!hasTee(context.linuxDir)) return fallback.execute(context)

        val mutex = locks.getOrPut(context.root) { Mutex() }
        runBlocking { mutex.lock() }
        try {
            return runResident(context)
        } finally {
            mutex.unlock()
        }
    }

    private fun runResident(context: WorkspaceShellContext): WorkspaceCommandResult {
        val session = runBlocking {
            sessionManager.obtainAiSession(context.root, context.shellCompatibilityMode)
        } ?: return fallback.execute(context)

        // rootfs 的 /tmp 就是宿主上的 linuxDir/tmp。RootfsPatcher 会建它，但会话也可能走
        // 「readiness 已经是 Ready」的捷径建起来，那条路径上 patcher 不跑，这里补一次
        val rootfsTmpDir = File(context.linuxDir, "tmp").apply { mkdirs() }
        val token = newToken()
        val commandFile = File(rootfsTmpDir, COMMAND_FILE_PREFIX + token)
        val runnerFile = File(rootfsTmpDir, RUNNER_FILE_PREFIX + token)
        val outputFile = File(rootfsTmpDir, OUTPUT_FILE_PREFIX + token)
        val marker = "@@AIRKO_DONE_$token@@"
        val completion = Regex("^${Regex.escape(marker)} (-?\\d+)$", RegexOption.MULTILINE)

        try {
            commandFile.writeText(context.command, Charsets.UTF_8)
            runnerFile.writeText(
                buildRunnerScript(
                    rootfsCwd = rootfsCwd(context.cwd),
                    commandPath = tokenPath(COMMAND_FILE_PREFIX, token),
                    marker = marker,
                ),
                Charsets.UTF_8,
            )
            // tee 会用 O_TRUNC 重建它；显式删掉是为了保证「找不到标记」永远等于「还没跑完」，
            // 不会被上一个命令的残留文件骗过去
            outputFile.delete()

            inject(
                session = session,
                line = ". ${tokenPath(RUNNER_FILE_PREFIX, token)}" +
                    " 2>&1 | tee ${tokenPath(OUTPUT_FILE_PREFIX, token)}",
            )

            val outcome = awaitCompletion(session, outputFile, completion, context.timeoutMillis)
            if (outcome.sessionUnusable) {
                // shell 可能还停在被中断的命令里，不再是可信的执行环境：下一次重新建一个
                sessionManager.discardAiSession(context.root)
            }
            return readResult(outputFile, outcome, context.timeoutMillis)
        } catch (error: Throwable) {
            // 取消/中断不能被当成普通失败吞掉：runInterruptible 靠异常把线程中断翻译成
            // 协程取消，吞掉它会让「停止生成」之后命令还在后台跑。
            // 另外这两种情况下都无法确认前台进程是否真的退了，所以顺手把会话作废——
            // 复用一个状态不明的 shell，有把下一条命令喂给残留进程的风险。
            if (error is InterruptedException || error is CancellationException) {
                sendInterrupt(session)
                sessionManager.discardAiSession(context.root)
                throw error
            }
            Log.e(TAG, "Resident shell execution failed", error)
            return WorkspaceCommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to run command in the resident terminal: ${error.message}",
            )
        } finally {
            listOf(commandFile, runnerFile, outputFile).forEach { file ->
                runCatching { file.delete() }
            }
        }
    }

    /**
     * 宿主生成的脚本，在 shell 里被 source。它做四件事：
     *
     * 1. 铺上非交互执行的约定变量，与 [me.rerere.workspace.ProotShellRunner] 原来的 `env -i` 对齐。
     *    `PAGER=cat` 尤其关键，否则 `git diff` 之类会拉起分页器等输入，一直挂到超时。
     * 2. `cd` 到本次命令要求的工作目录。
     * 3. 在**子壳里** source 真正的命令文件：命令文本若有语法错误，只会让那个子壳失败，
     *    本脚本继续往下走。这一步不能省——直接 source 的话，语法错误可能把整个 pipeline
     *    左端带走，结束标记永远不会出现，只能干等到超时。
     * 4. 打印结束标记（带退出码）。它走 pipeline 右端的 `tee`，所以同时出现在终端和输出文件里。
     *
     * 脚本内容固定（只有路径和 token 是变量），不可能有语法错误。命令文本则完全以文件形式传入，
     * 不经过 PTY 的行缓冲，因此也没有 MAX_CANON（约 4096 字节）截断长命令的问题。
     */
    private fun buildRunnerScript(rootfsCwd: String, commandPath: String, marker: String): String =
        buildString {
            appendLine("#!/bin/sh")
            appendLine("# Air-Ko AI 命令投递脚本（自动生成，请勿修改）")
            appendLine("export CI=true")
            appendLine("export NO_COLOR=1")
            appendLine("export PAGER=cat")
            appendLine("export GIT_PAGER=cat")
            appendLine("export SYSTEMD_PAGER=cat")
            appendLine("cd -- ${shellLiteral(rootfsCwd)} && ( . ${shellLiteral(commandPath)} )")
            appendLine("__airko_rc=$?")
            appendLine("printf '\\n%s %s\\n' ${shellLiteral(marker)} \"\$__airko_rc\"")
            appendLine("unset __airko_rc")
        }

    /**
     * 把注入行写进 PTY。两个细节：
     * - 前置 `\x15`（Ctrl-U）：用户在 AI 标签页里敲了一半还没回车的话，注入行会和他那半行
     *   拼在一起变成一条乱命令。先把当前行清掉。
     * - 结尾用 `\r`：那才是终端里的回车（ICRNL 会把它变成 shell 看到的换行）。
     */
    private fun inject(session: TerminalSession, line: String) {
        val bytes = "\u0015$line\r".toByteArray(Charsets.UTF_8)
        session.write(bytes, 0, bytes.size)
    }

    private fun sendInterrupt(session: TerminalSession) {
        val ctrlC = byteArrayOf(0x03)
        runCatching { session.write(ctrlC, 0, 1) }
    }

    private data class Outcome(
        val exitCode: Int?,
        val timedOut: Boolean,
        val runaway: Boolean,
        val sessionUnusable: Boolean,
        /** 会话在命令结束前就退出了（proot 崩溃/被系统回收），标记不会再出现 */
        val sessionDied: Boolean = false,
    )

    /**
     * 轮询输出文件，等结束标记。
     *
     * 只读文件**尾部**：命令疯狂输出时（`yes` 之类）文件会一直涨，读全量既浪费又会让每轮
     * 轮询的耗时随文件线性增长；标记固定在最后一行，读一个常量大小的窗口就够。
     */
    private fun awaitCompletion(
        session: TerminalSession,
        outputFile: File,
        completion: Regex,
        timeoutMillis: Long,
    ): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (true) {
            lastMatch(completion, tailText(outputFile))?.let { match ->
                return Outcome(
                    exitCode = match.groupValues[1].toIntOrNull(),
                    timedOut = false,
                    runaway = false,
                    sessionUnusable = false,
                )
            }

            // 会话已经退出：结束标记永远不会再出现（proot 崩溃、被系统回收，或 shell 被
            // 内部信号带走）。这里立即失败，不让调用方干等整个超时。
            if (!session.isAlive) {
                return Outcome(
                    exitCode = null,
                    timedOut = false,
                    runaway = false,
                    sessionUnusable = true,
                    sessionDied = true,
                )
            }

            // 输出失控：继续写下去会把设备塞满，而回传给 AI 的只有开头 128KB，后面全是丢弃。
            // 与其眼睁睁看它写满，不如就在这里掐掉，并明确告诉 AI 发生了什么
            if (outputFile.length() > MAX_OUTPUT_FILE_BYTES) {
                return interruptAndSettle(session, outputFile, completion, runaway = true)
            }
            if (System.currentTimeMillis() >= deadline) break
            Thread.sleep(POLL_INTERVAL_MS)
        }

        return interruptAndSettle(session, outputFile, completion, runaway = false)
    }

    /** Ctrl-C 之后给一小段宽限期：命令可能刚好在这时收尾，标记一出现就能拿到真实的退出码。 */
    private fun interruptAndSettle(
        session: TerminalSession,
        outputFile: File,
        completion: Regex,
        runaway: Boolean,
    ): Outcome {
        sendInterrupt(session)
        val graceDeadline = System.currentTimeMillis() + INTERRUPT_GRACE_MS
        while (System.currentTimeMillis() < graceDeadline) {
            lastMatch(completion, tailText(outputFile))?.let { match ->
                // 标记出现了，说明命令确实收尾、shell 也在继续读 stdin，可以接着复用
                return Outcome(
                    exitCode = match.groupValues[1].toIntOrNull(),
                    timedOut = !runaway,
                    runaway = runaway,
                    sessionUnusable = false,
                )
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return Outcome(
            exitCode = null,
            timedOut = !runaway,
            runaway = runaway,
            sessionUnusable = true,
        )
    }

    private fun readResult(
        outputFile: File,
        outcome: Outcome,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        val head = headText(outputFile)
        val truncated = head.truncated || outcome.runaway

        // 输出文件里带着结束标记本身，去掉它再交给 AI
        val output = MARKER_TAIL_REGEX.replace(head.text, "").trimEnd('\n', '\r')

        val note = when {
            outcome.runaway ->
                "\n\n[Air-Ko] 命令输出超过 ${MAX_OUTPUT_FILE_BYTES / 1024 / 1024} MB，" +
                    "已向终端发送中断（Ctrl-C）。请缩小输出范围后重试。"
            outcome.sessionDied ->
                "\n\n[Air-Ko] 终端会话在命令结束前退出了（proot 崩溃或被系统回收），" +
                    "无法确认命令是否完成。该会话已被回收，下一条命令会使用新的会话。"
            outcome.exitCode == null ->
                "\n\n[Air-Ko] 命令在 ${timeoutMillis / 1000} 秒内没有结束，已向终端发送中断（Ctrl-C），" +
                    "但未能确认它已经退出。该终端会话已被回收，下一条命令会使用新的会话。"
            outcome.timedOut ->
                "\n\n[Air-Ko] 命令超过 ${timeoutMillis / 1000} 秒，已向终端发送中断（Ctrl-C）。" +
                    "以上是中断前捕获到的输出。"
            truncated ->
                "\n\n[Air-Ko] 输出超过 ${MAX_OUTPUT_CHARS / 1024} KB，已截断（只保留开头）。"
            else -> ""
        }

        return WorkspaceCommandResult(
            exitCode = outcome.exitCode ?: -1,
            stdout = output + note,
            // PTY 只有一根字节流，stdout 与 stderr 在这里天然合并；
            // 想让 AI 看出「这是错误输出」只能靠内容本身
            stderr = "",
            timedOut = outcome.timedOut,
            truncated = truncated,
        )
    }

    private data class CappedText(val text: String, val truncated: Boolean)

    /** 取最后一个匹配：结束标记永远在输出末尾，命令内容里也可能出现同形文本（虽然 token 随机）。 */
    private fun lastMatch(regex: Regex, text: String): MatchResult? =
        regex.findAll(text).lastOrNull()

    /** 读文件开头，最多 [MAX_OUTPUT_CHARS] 个字符。 */
    private fun headText(file: File): CappedText {
        val size = file.length()
        if (size <= 0) return CappedText("", false)

        // UTF-8 单字符最多 4 字节，按最坏情况取字节再按字符截断
        val readBytes = minOf(size, MAX_OUTPUT_CHARS.toLong() * 4).toInt()
        if (readBytes <= 0) return CappedText("", false)

        val text = String(readBytes(file, 0, readBytes), Charsets.UTF_8)
        val truncated = size > readBytes || text.length > MAX_OUTPUT_CHARS
        return CappedText(text.take(MAX_OUTPUT_CHARS), truncated)
    }

    private fun tailText(file: File): String {
        val size = file.length()
        if (size <= 0) return ""
        val readBytes = minOf(size, TAIL_READ_BYTES.toLong()).toInt()
        return String(readBytes(file, size - readBytes, readBytes), Charsets.UTF_8)
    }

    private fun readBytes(file: File, offset: Long, length: Int): ByteArray {
        val buffer = ByteArray(length)
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(buffer)
            }
            buffer
        } catch (error: Exception) {
            // 文件可能刚好被 tee 以 O_TRUNC 重建，读到一半会失败或读短；下一轮轮询重试即可，
            // 不能让这里把整个命令打断
            Log.w(TAG, "Failed to read $file at offset $offset", error)
            ByteArray(0)
        }
    }

    private fun rootfsCwd(cwd: String): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isEmpty()) {
            WorkspaceManager.ROOTFS_WORKSPACE_DIR
        } else {
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$normalized"
        }
    }

    private fun tokenPath(prefix: String, token: String): String = "/tmp/$prefix$token"

    /** 单引号字面量；路径里可能有空格甚至单引号。 */
    private fun shellLiteral(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun newToken(): String {
        val bytes = ByteArray(6)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "ResidentShellRunner"
        const val COMMAND_FILE_PREFIX = ".airko_cmd_"
        const val RUNNER_FILE_PREFIX = ".airko_run_"
        const val OUTPUT_FILE_PREFIX = ".airko_out_"

        /** 轮询间隔。100ms 对交互体感足够，又不至于让 IO 线程空转。 */
        const val POLL_INTERVAL_MS = 100L

        /** Ctrl-C 之后留给命令收尾的时间。 */
        const val INTERRUPT_GRACE_MS = 2_000L

        /** 读尾部找标记的窗口。标记连同一行结尾撑死几十字节，8KB 绰绰有余。 */
        const val TAIL_READ_BYTES = 8 * 1024

        /** 输出文件上限。超过就中断命令，避免失控输出把设备写满。 */
        const val MAX_OUTPUT_FILE_BYTES = 32L * 1024 * 1024

        /** 去掉输出里残留的结束标记（形如 `@@AIRKO_DONE_xxx@@ 0`）。 */
        val MARKER_TAIL_REGEX = Regex("(?m)^@@AIRKO_DONE_[0-9a-f]+@@ -?\\d+\\n?")

        /**
         * 找 `tee`。`tee` 是本方案唯一依赖的外部命令——命令执行本身只用 shell 内建
         * （`cd`/`export`/`printf`/`unset`），所以只要探测这一个。
         *
         * 不能只看 `File.exists()`：rootfs 里大量 applet 是指向 busybox 的**绝对**符号链接
         * （`/bin/tee -> /bin/busybox`），这种链接在宿主上解析会甩到 Android 系统的 `/bin`，
         * `exists()` 于是返回 false。因此额外认一下 busybox 本体（`tee` 是它的标准 applet）。
         */
        val TEE_CANDIDATES = listOf(
            "bin/tee",
            "usr/bin/tee",
            "sbin/tee",
            "usr/sbin/tee",
            "usr/local/bin/tee",
        )

        val BUSYBOX_CANDIDATES = listOf(
            "bin/busybox",
            "usr/bin/busybox",
            "sbin/busybox",
        )

        val secureRandom = SecureRandom()
    }
}
