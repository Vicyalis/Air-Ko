package me.rerere.workspace

/** 工作区文件区与 Android 目录的连接方式。 */
enum class WorkspaceSharedDirMode(val wire: String) {
    /** PRoot 直接绑定 Android 目录；App 文件工具也直接读写该目录。 */
    BIND("bind"),

    /** App 私有 files 目录用符号链接指向 Android 目录；PRoot 绑定真实目标目录。 */
    SYMLINK("symlink");

    companion object {
        fun fromWire(value: String?): WorkspaceSharedDirMode =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: BIND
    }
}
