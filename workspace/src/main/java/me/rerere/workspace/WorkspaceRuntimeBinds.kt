package me.rerere.workspace

import java.io.File

/**
 * 桌面/服务进程要用的 socket 目录重挂载: 把 guest 侧几个固定 socket 目录换到
 * App cache 下的短宿主路径。
 *
 * proot 把 guest 路径翻译成宿主绝对路径后才交给内核, 而 AF_UNIX 的 sun_path 上限
 * 只有 108 字节。工作区目录的宿主前缀本身就有约 90 字节, guest 内稍长的 socket
 * 名字 (wayland-1、niri.wayland-1.sock、/tmp/.X11-unix/X1) 全部 bind() EINVAL。
 *
 * 重挂到短路径后:
 * - Wayland 合成器/客户端 (XDG_RUNTIME_DIR=/run)、dbus、X11 (DISPLAY=:1) 都能建成 socket;
 * - /dev/shm 有了真实、可写的 POSIX 共享内存目录;
 * - /tmp 本体留在 rootfs 内, App 从宿主读 tmp 下日志的路径不受影响。
 */
object WorkspaceRuntimeBinds {
    /** guest 侧 XDG_RUNTIME_DIR: 桌面启动脚本统一 export 到这里 */
    const val GUEST_RUNTIME_DIR = "/run"

    /** guest 侧 X11 socket 目录 (X 服务器硬编码路径) */
    const val GUEST_X11_SOCKET_DIR = "/tmp/.X11-unix"

    /** guest 侧 POSIX 共享内存目录 */
    const val GUEST_SHM_DIR = "/dev/shm"

    /**
     * 工作区运行目录, 放 App cache 下。
     *
     * 宿主路径必须短 (见类注释), 所以不挂工作区完整 uuid, 只取前 8 位。
     */
    fun runtimeDir(cacheDir: File, root: String): File =
        File(File(cacheDir, "airko-runtime"), "rt-${root.take(8).lowercase()}")

    /** proot `-b` 用的挂载表; 宿主侧与 guest 侧目录都在这里顺手建出来 */
    fun mounts(runtimeDir: File, linuxDir: File): List<WorkspaceBindMount> {
        val runDir = File(runtimeDir, "run").apply { mkdirs() }
        val x11Dir = File(runtimeDir, "x11").apply { mkdirs() }
        val shmDir = File(runtimeDir, "shm").apply { mkdirs() }
        File(linuxDir, GUEST_RUNTIME_DIR.trimStart('/')).mkdirs()
        File(linuxDir, GUEST_X11_SOCKET_DIR.trimStart('/')).mkdirs()
        File(linuxDir, GUEST_SHM_DIR.trimStart('/')).mkdirs()
        return listOf(
            WorkspaceBindMount(runDir, GUEST_RUNTIME_DIR),
            WorkspaceBindMount(x11Dir, GUEST_X11_SOCKET_DIR),
            WorkspaceBindMount(shmDir, GUEST_SHM_DIR),
        )
    }
}
