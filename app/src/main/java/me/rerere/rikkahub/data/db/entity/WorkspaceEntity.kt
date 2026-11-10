package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.Workspace
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceShellStatus

@Entity(
    tableName = "workspaces",
    indices = [
        Index(value = ["root"], unique = true),
        Index(value = ["updated_at"]),
    ],
)
data class WorkspaceEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("name")
    val name: String,
    @ColumnInfo("root")
    val root: String,
    @ColumnInfo("shell_status")
    val shellStatus: String = WorkspaceShellStatus.DISABLED.name,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("last_access_at")
    val lastAccessAt: Long? = null,
    // 工具审批的用户覆盖项 (toolName -> needsApproval)，未覆盖的工具沿用默认值
    @ColumnInfo("tool_approvals", defaultValue = "{}")
    val toolApprovals: String = "{}",
    @ColumnInfo("shell_compatibility_mode", defaultValue = "0")
    val shellCompatibilityMode: Boolean = false,
    // 工作区桌面方案 (none / code-server / novnc / jupyter / custom) 与其自定义参数。
    // 取值是 WorkspaceDesktop.id, 默认 none 表示用户没启用桌面。
    @ColumnInfo("desktop", defaultValue = "none")
    val desktop: String = WorkspaceDesktop.NONE.id,
    @ColumnInfo("desktop_custom_command", defaultValue = "")
    val desktopCustomCommand: String = "",
    @ColumnInfo("desktop_custom_port", defaultValue = "0")
    val desktopCustomPort: Int = 0,
    // Android 共享目录；空串表示仍使用 App 私有 files 目录。
    @ColumnInfo("shared_dir_path", defaultValue = "")
    val sharedDirPath: String = "",
    @ColumnInfo("shared_dir_mode", defaultValue = "bind")
    val sharedDirMode: String = "bind",
    // 工作区级“允许全部命令”：工作区工具与设备控制都不再逐次审批。
    @ColumnInfo("allow_all_tools", defaultValue = "0")
    val allowAllTools: Boolean = false,
) {
    /** 数据库里存的是 [WorkspaceDesktop.id]; 旧值/脏数据一律退回 [WorkspaceDesktop.NONE] */
    fun desktopScheme(): WorkspaceDesktop = WorkspaceDesktop.fromId(desktop)

    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    fun toWorkspace(): Workspace = Workspace(
        id = id,
        name = name,
        root = root,
        shellStatus = runCatching { WorkspaceShellStatus.valueOf(shellStatus) }
            .getOrDefault(WorkspaceShellStatus.DISABLED),
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
    )
}
