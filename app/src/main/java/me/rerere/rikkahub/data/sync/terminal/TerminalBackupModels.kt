package me.rerere.rikkahub.data.sync.terminal

import kotlinx.serialization.Serializable
import me.rerere.workspace.RootfsDistroCatalog
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceDesktopCatalog
import java.io.File

/**
 * 备份里记录的终端工作区元数据。
 *
 * [distroId] 是导出时从 rootfs 的 /etc/os-release 探测到的发行版 id (见 [TerminalDistroDetector]),
 * 导入时用它选回同一发行版的下载地址; 探测不到时为 null, 恢复时退回默认发行版。
 */
@Serializable
data class TerminalWorkspaceInfo(
    val id: String,
    val name: String,
    val root: String,
    val desktop: String = WorkspaceDesktop.NONE.id,
    val desktopCustomCommand: String = "",
    val desktopCustomPort: Int = 0,
    val shellCompatibilityMode: Boolean = false,
    val distroId: String? = null,
)

/**
 * 待恢复终端的标记文件。
 *
 * 只记录相对路径和已完成的工作区, 不存绝对路径; 进程被杀后下次启动可以接着恢复。
 */
@Serializable
data class TerminalRestoreMarker(
    val version: Int = TerminalBackup.MARKER_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val workspaces: List<TerminalWorkspaceInfo> = emptyList(),
    val filesRoot: String = TerminalBackup.FILES_ROOT,
    /** 已经完整恢复 (rootfs + 桌面 + 文件) 的工作区, 重试时直接跳过 */
    val completedWorkspaceIds: List<String> = emptyList(),
    /** 由本流程新建的工作区, 用于判断重试时是否还需要安装桌面 */
    val createdWorkspaceIds: List<String> = emptyList(),
    /** 由本流程下载安装过 rootfs 的工作区, 重试时即使 rootfs 已存在也要继续安装桌面 */
    val installedRootfsIds: List<String> = emptyList(),
)

object TerminalBackup {
    const val MARKER_VERSION = 1

    /** zip 内工作区元数据路径 */
    const val ARCHIVE_WORKSPACES = "terminal/workspaces.json"

    /** zip 内用户文件前缀: terminal/files/<workspaceId>/<relative> */
    const val ARCHIVE_FILES_PREFIX = "terminal/files/"

    /** 待恢复目录内的元数据文件名 */
    const val WORKSPACES_FILE = "workspaces.json"
    const val MARKER_FILE = "terminal_restore.json"
    const val FILES_ROOT = "files"

    const val MAX_WORKSPACES = 200
    const val MAX_FILES = 50_000
    const val MAX_METADATA_BYTES = 1024L * 1024
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    val WORKSPACE_ID_REGEX = Regex("[A-Za-z0-9._-]{1,128}")
}

/** 从 rootfs 的 os-release 探测发行版 id; 探测不到返回 null。 */
object TerminalDistroDetector {
    fun detect(linuxDir: File): String? {
        val osRelease = listOf(
            File(linuxDir, "etc/os-release"),
            File(linuxDir, "usr/lib/os-release"),
        ).firstOrNull { it.isFile } ?: return null
        val values = runCatching {
            osRelease.readLines().mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null
                else line.substring(0, index).trim() to line.substring(index + 1).trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
            }.toMap()
        }.getOrNull() ?: return null

        val candidates = listOfNotNull(values["ID"], values["ID_LIKE"])
            .flatMap { it.lowercase().split(' ', '\t') }
            .filter { it.isNotBlank() }
        return candidates.firstNotNullOfOrNull { distroIdOf(it) }
    }

    private fun distroIdOf(id: String): String? = when (id) {
        RootfsDistroCatalog.ID_ALPINE -> RootfsDistroCatalog.ID_ALPINE
        RootfsDistroCatalog.ID_UBUNTU -> RootfsDistroCatalog.ID_UBUNTU
        RootfsDistroCatalog.ID_DEBIAN -> RootfsDistroCatalog.ID_DEBIAN
        "arch", "archarm", "archlinux" -> RootfsDistroCatalog.ID_ARCH
        RootfsDistroCatalog.ID_FEDORA -> RootfsDistroCatalog.ID_FEDORA
        "nixos" -> RootfsDistroCatalog.ID_NIX
        else -> null
    }
}

/** 导入时把元数据里可能出现的脏值收敛到可用范围。 */
internal fun TerminalWorkspaceInfo.normalized(): TerminalWorkspaceInfo {
    require(id.matches(TerminalBackup.WORKSPACE_ID_REGEX) && id != "." && id != "..") {
        "Invalid workspace id: $id"
    }
    val normalizedRoot = root.takeIf {
        it.matches(TerminalBackup.WORKSPACE_ID_REGEX) && it != "." && it != ".."
    } ?: id
    return copy(
        name = name.trim().ifBlank { "Workspace" }.take(128),
        root = normalizedRoot,
        desktop = WorkspaceDesktop.fromId(desktop).id,
        desktopCustomCommand = desktopCustomCommand.trim().take(4096),
        desktopCustomPort = desktopCustomPort.takeIf { WorkspaceDesktopCatalog.isValidPort(it) } ?: 0,
        distroId = distroId?.let { RootfsDistroCatalog.find(it)?.id },
    )
}
