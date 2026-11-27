package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    /** rootfs 路径 -> 上次打补丁的时刻（nanoTime），避免每条命令都重写 /etc 下的补丁文件 */
    private val lastPatchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!WorkspaceManager.isUsableRootfs(context.linuxDir)) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        // 补丁内容（DNS/hosts/locale）只在 60s 内复用：连续命令不必每条都重写一遍，
        // 切网后又能在一个很短的时间窗内自动刷新
        val now = System.nanoTime()
        val lastPatch = lastPatchAt[context.linuxDir.absolutePath]
        if (lastPatch == null || now - lastPatch > PATCH_TTL_NANOS) {
            patcher.patch(context.linuxDir)
            lastPatchAt[context.linuxDir.absolutePath] = now
        }

        val detachedOutputPath = context.detachedOutputPath
        if (detachedOutputPath != null) {
            // 「发射后不管」: 桌面守护进程的 leader proot 要长期留守当翻译层, 不能等它退出,
            // 也不能带 --kill-on-exit (否则启动命令一结束就把刚拉起来的桌面连坐杀光)
            val logFile = File(context.linuxDir, detachedOutputPath.trimStart('/'))
            logFile.parentFile?.mkdirs()
            val process = createBuilder(context, proot, loader)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .start()
            return WorkspaceCommandResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
            )
        }

        val process = createBuilder(context, proot, loader)
            .redirectErrorStream(false)
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun createBuilder(
        context: WorkspaceShellContext,
        proot: File,
        loader: File,
    ): ProcessBuilder = ProcessBuilder(buildCommand(context, proot))
        .directory(context.filesDir)
        .apply {
            if (context.shellCompatibilityMode) {
                environment()["PROOT_NO_SECCOMP"] = "1"
            } else {
                environment().remove("PROOT_NO_SECCOMP")
            }
            environment()["PROOT_LOADER"] = loader.absolutePath
            environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
            environment()["TMPDIR"] = context.tempDir.absolutePath
            // link2symlink 的产物集中到 rootfs 内固定目录: 并发会话一个显式一个隐式会 race,
            // 且集中后备份/清理/诊断都只要看一处 (与 proot-distro 的做法一致)
            environment()["PROOT_L2S_DIR"] = File(context.linuxDir, L2S_DIR_NAME)
                .apply { mkdirs() }.absolutePath
            // 宿主的 LD_* 不该泄漏进 proot 自身, 否则偶发 "CANNOT LINK EXECUTABLE"
            environment().remove("LD_PRELOAD")
            environment().remove("LD_LIBRARY_PATH")
        }

    private fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
        )
        // 默认退出时带走所有 tracee; 桌面守护进程场景必须关掉, 让 proot 留守当翻译层
        if (context.killOnExit) {
            command += "--kill-on-exit"
        }
        command += listOf(
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        // socket 目录重挂到 App cache 短路径, 必须在 -b /dev 之后 (见 WorkspaceRuntimeBinds)
        context.runtimeDir?.let { runtimeDir ->
            WorkspaceRuntimeBinds.mounts(runtimeDir, context.linuxDir).forEach { mount ->
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // 非交互执行约定, 抑制各类 CLI 的交互行为 (确认提示/分页器/颜色转义)
            "CI=true",
            "NO_COLOR=1",
            "PAGER=cat",
            WorkspaceManager.rootfsShell(context.linuxDir),
            // 一次性命令不是登录 shell: 环境已由上面的 env -i 完全确定,
            // 再加 -l 只会让 Debian/Ubuntu 把 /etc/profile.d/* (含 bash_completion) 重跑一遍,
            // 每条命令白付 10~150ms。需要登录态的交互场景走终端会话 (它本来就没有 -l)。
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            context.prootCwd(),
            context.command,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val L2S_DIR_NAME = ".l2s"
        private const val PATCH_TTL_NANOS = 60_000_000_000L
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
