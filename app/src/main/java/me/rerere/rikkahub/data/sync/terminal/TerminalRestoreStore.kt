package me.rerere.rikkahub.data.sync.terminal

import android.content.Context
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.sync.PendingRestore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 终端恢复的 staging 目录。
 *
 * 刻意放在 backup-restore/pending 之外: applyPendingRestore 完成后会删掉自己的 pending 目录,
 * 而终端恢复要等 Koin / Room 初始化之后才能执行, 所以需要独立的生命周期。
 */
internal class TerminalRestoreStore(context: Context) {
    private val root = File(context.noBackupFilesDir, ROOT_DIR)

    val pendingDir: File
        get() = File(root, "pending")

    private val markerFile: File
        get() = File(pendingDir, TerminalBackup.MARKER_FILE)

    fun hasPending(): Boolean = markerFile.isFile

    fun createStagingDirectory(): File {
        check(root.isDirectory || root.mkdirs()) { "Cannot create terminal restore directory" }
        // 上次进程中断留下的临时目录; 当前进程还没开始恢复, 可以安全清掉。
        root.listFiles()?.filter {
            it.name.startsWith("preparing-") || it.name.startsWith("superseded-")
        }?.forEach { it.deleteRecursively() }
        return Files.createTempDirectory(root.toPath(), "preparing-").toFile()
    }

    /** 原子发布新的待恢复目录; 旧目录直接丢弃, 避免恢复上一次导入的终端数据。 */
    fun publish(staging: File) {
        if (pendingDir.exists()) {
            val superseded = File(root, "superseded-${UUID.randomUUID()}")
            runCatching {
                Files.move(
                    pendingDir.toPath(),
                    superseded.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.onFailure {
                pendingDir.deleteRecursively()
            }
            superseded.deleteRecursively()
        }
        val parent = requireNotNull(pendingDir.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create terminal restore directory" }
        Files.move(staging.toPath(), pendingDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun clearPending() {
        pendingDir.deleteRecursively()
    }

    fun readMarker(json: Json): TerminalRestoreMarker? {
        if (!markerFile.isFile) return null
        return runCatching {
            json.decodeFromString<TerminalRestoreMarker>(markerFile.readText())
        }.getOrNull()
    }

    fun writeMarker(json: Json, marker: TerminalRestoreMarker) {
        check(pendingDir.isDirectory || pendingDir.mkdirs()) { "Terminal restore staging is missing" }
        PendingRestore.writeDurably(markerFile, json.encodeToString(marker))
    }

    fun filesDirectory(filesRoot: String): File {
        require(filesRoot == TerminalBackup.FILES_ROOT) { "Invalid terminal files root: $filesRoot" }
        return File(pendingDir, filesRoot)
    }

    fun workspaceFiles(workspaceId: String): File {
        require(isValidWorkspaceId(workspaceId)) { "Invalid workspace id: $workspaceId" }
        return File(filesDirectory(TerminalBackup.FILES_ROOT), workspaceId)
    }

    companion object {
        private const val ROOT_DIR = "terminal-restore"

        /** 进程内互斥: 恢复期间拒绝新的备份导入, 避免 staging 被删掉。 */
        val isRestoring = AtomicBoolean(false)

        fun isValidWorkspaceId(id: String): Boolean =
            id.matches(TerminalBackup.WORKSPACE_ID_REGEX) && id != "." && id != ".."

        fun isTerminalEntry(name: String): Boolean =
            name == TerminalBackup.ARCHIVE_WORKSPACES || name.startsWith(TerminalBackup.ARCHIVE_FILES_PREFIX)

        /** 把 zip 条目映射到 staging 文件; 所有路径都做 zip-slip 校验。 */
        fun resolveEntry(stagingRoot: File, name: String): File {
            if (name == TerminalBackup.ARCHIVE_WORKSPACES) {
                return File(stagingRoot, TerminalBackup.WORKSPACES_FILE)
            }
            require(name.startsWith(TerminalBackup.ARCHIVE_FILES_PREFIX)) {
                "Invalid terminal backup entry: $name"
            }
            val rest = name.removePrefix(TerminalBackup.ARCHIVE_FILES_PREFIX)
            val workspaceId = rest.substringBefore('/')
            require(rest.contains('/') && isValidWorkspaceId(workspaceId)) {
                "Invalid terminal workspace id: $workspaceId"
            }
            val relative = rest.substringAfter('/')
            val workspaceRoot = File(
                File(stagingRoot, TerminalBackup.FILES_ROOT),
                workspaceId,
            )
            return PendingRestore.resolveInside(workspaceRoot, relative)
        }

        /**
         * 校验 staging 里的 workspaces.json, 生成待恢复标记。
         * 返回 null 表示备份里没有终端数据 (旧备份), 调用方应清掉旧的 pending。
         */
        fun buildMarker(stagingRoot: File, json: Json): TerminalRestoreMarker? {
            val workspacesFile = File(stagingRoot, TerminalBackup.WORKSPACES_FILE)
            val filesRoot = File(stagingRoot, TerminalBackup.FILES_ROOT)
            val workspaceDirs = filesRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (!workspacesFile.isFile) {
                require(workspaceDirs.isEmpty()) { "Terminal backup is missing workspaces.json" }
                return null
            }
            val raw = json.decodeFromString<List<TerminalWorkspaceInfo>>(workspacesFile.readText())
            require(raw.size <= TerminalBackup.MAX_WORKSPACES) {
                "Too many workspaces in terminal backup"
            }
            val workspaces = raw.map { it.normalized() }
            require(workspaces.map { it.id }.toSet().size == workspaces.size) {
                "Duplicate workspace id in terminal backup"
            }
            val ids = workspaces.map { it.id }.toSet()
            workspaceDirs.forEach { dir ->
                require(isValidWorkspaceId(dir.name) && dir.name in ids) {
                    "Terminal files for unknown workspace: ${dir.name}"
                }
            }
            if (workspaces.isEmpty() && workspaceDirs.isEmpty()) return null
            return TerminalRestoreMarker(
                workspaces = workspaces,
                filesRoot = TerminalBackup.FILES_ROOT,
            )
        }
    }
}
