package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.migration.SettingsJsonMigrator
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.SQLiteConfiguration
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.MediaCreationFiles
import me.rerere.rikkahub.data.sync.terminal.TerminalBackup
import me.rerere.rikkahub.data.sync.terminal.TerminalDistroDetector
import me.rerere.rikkahub.data.sync.terminal.TerminalRestoreStore
import me.rerere.rikkahub.data.sync.terminal.TerminalWorkspaceInfo
import me.rerere.workspace.WorkspaceManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Shared archive format and restore lifecycle for local, WebDAV and S3 backups. */
class BackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsStore: SettingsStore,
    private val json: Json,
) {
    private val restoreMutex = Mutex()

    suspend fun createBackup(
        includeDatabase: Boolean,
        includeFiles: Boolean,
        includeTerminal: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val archive = File.createTempFile("backup_${timestamp}_", ".zip", context.cacheDir)
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "backup-").toFile()
        try {
            val settings = settingsStore.awaitLoaded()
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry("settings.json"))
                val settingsJson = json.encodeSettings(settings, settingsStore.launchCountFlow.first())
                zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                if (includeDatabase) {
                    val snapshot = File(staging, SQLiteConfiguration.DATABASE_NAME)
                    DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
                    addFile(zip, snapshot, DatabaseBackup.ARCHIVE_DATABASE)
                }
                if (includeFiles) {
                    for (folder in ATTACHMENT_FOLDERS) {
                        val directory = File(context.filesDir, folder)
                        val files = if (folder in NESTED_ATTACHMENT_FOLDERS) directory.walkTopDown().asSequence()
                        else directory.listFiles().orEmpty().asSequence()
                        for (file in files.filter { it.isFile }) {
                            currentCoroutineContext().ensureActive()
                            // A media result still being downloaded is incomplete and gets renamed when it finishes.
                            if (folder == FileFolders.MEDIA_CREATION &&
                                file.name.endsWith(MediaCreationFiles.PARTIAL_SUFFIX)
                            ) continue
                            val relative = file.relativeTo(directory).invariantSeparatorsPath
                            PendingRestore.resolveInside(directory, relative)
                            addFile(zip, file, "$folder/$relative")
                        }
                    }
                }
                if (includeTerminal) {
                    writeTerminalBackup(zip)
                }
            }
            archive
        } catch (e: Throwable) {
            archive.delete()
            throw e
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * 终端工作区元数据 + /workspace 用户文件。
     *
     * rootfs 本身不进包 (动辄几百 MB); 恢复时按 distroId 重新下载同一发行版。
     * 超过数量/体积上限时直接失败, 避免静默漏掉用户文件。
     */
    private suspend fun writeTerminalBackup(zip: ZipOutputStream) {
        val workspaces = database.workspaceDao().getAll()
            .filter { TerminalRestoreStore.isValidWorkspaceId(it.id) }
        val infos = workspaces.map { it.toTerminalInfo() }
        require(infos.size <= TerminalBackup.MAX_WORKSPACES) {
            "Too many workspaces to include in a terminal backup"
        }
        zip.putNextEntry(ZipEntry(TerminalBackup.ARCHIVE_WORKSPACES))
        zip.write(json.encodeToString(infos).toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        var fileCount = 0
        var totalBytes = 0L
        for (workspace in workspaces) {
            if (!TerminalRestoreStore.isValidWorkspaceId(workspace.id)) {
                Log.w(TAG, "Skip terminal backup for invalid workspace id: ${workspace.id}")
                continue
            }
            val filesDir = workspaceFilesDir(workspace.root) ?: continue
            if (!filesDir.isDirectory) continue
            val files = filesDir.walkTopDown()
                .onEnter { dir ->
                    dir == filesDir || (!Files.isSymbolicLink(dir.toPath()) &&
                        dir.name != TERMINAL_SKIP_DIR)
                }
                .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            for (file in files) {
                currentCoroutineContext().ensureActive()
                val length = file.length()
                require(
                    fileCount < TerminalBackup.MAX_FILES &&
                        length <= TerminalBackup.MAX_FILE_BYTES &&
                        totalBytes + length <= TerminalBackup.MAX_TOTAL_BYTES
                ) {
                    "Terminal workspace files exceed the backup limit; uncheck Terminal info or reduce workspace files"
                }
                val relative = file.relativeTo(filesDir).invariantSeparatorsPath
                PendingRestore.resolveInside(filesDir, relative)
                addFile(
                    zip,
                    file,
                    "${TerminalBackup.ARCHIVE_FILES_PREFIX}${workspace.id}/$relative",
                )
                fileCount++
                totalBytes += length
            }
        }
    }

    private fun WorkspaceEntity.toTerminalInfo(): TerminalWorkspaceInfo = TerminalWorkspaceInfo(
        id = id,
        name = name,
        root = root,
        desktop = desktop,
        desktopCustomCommand = desktopCustomCommand,
        desktopCustomPort = desktopCustomPort,
        shellCompatibilityMode = shellCompatibilityMode,
        distroId = workspaceLinuxDir(root)?.let { TerminalDistroDetector.detect(it) },
    )

    private fun workspaceBaseDir(root: String): File? {
        if (!TerminalRestoreStore.isValidWorkspaceId(root)) return null
        return File(File(context.filesDir, WorkspaceManager.BASE_DIR_NAME), root)
    }

    private fun workspaceFilesDir(root: String): File? =
        workspaceBaseDir(root)?.let { File(it, WorkspaceManager.FILES_DIR) }

    private fun workspaceLinuxDir(root: String): File? =
        workspaceBaseDir(root)?.let { File(it, WorkspaceManager.LINUX_DIR) }

    suspend fun stageRestore(
        archive: File,
        includeDatabase: Boolean,
        includeFiles: Boolean,
        includeTerminal: Boolean = false,
    ) =
        withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                check(!TerminalRestoreStore.isRestoring.get()) {
                    "A terminal restore is already running"
                }
                val restore = pendingRestore(context)
                val terminalStore = TerminalRestoreStore(context)
                val staging = restore.createStagingDirectory()
                val terminalStaging = try {
                    if (includeTerminal) terminalStore.createStagingDirectory() else null
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    throw e
                }
                try {
                    val payload = File(staging, "payload")
                    val stagedDatabase = File(payload, "database/${SQLiteConfiguration.DATABASE_NAME}")
                    val stagedWal = File(stagedDatabase.path + "-wal")
                    val seen = mutableSetOf<String>()
                    var restoredEntries = 0
                    var terminalEntries = 0
                    var terminalBytes = 0L
                    ZipFile(archive).use { zip ->
                        for (entry in zip.entries()) {
                            currentCoroutineContext().ensureActive()
                            if (entry.isDirectory) continue
                            if (includeTerminal && entry.name.startsWith("terminal/")) {
                                require(TerminalRestoreStore.isTerminalEntry(entry.name)) {
                                    "Invalid terminal backup entry: ${entry.name}"
                                }
                            }
                            val isTerminal =
                                includeTerminal && TerminalRestoreStore.isTerminalEntry(entry.name)
                            val target = when (entry.name) {
                                "settings.json" -> File(staging, "settings.json")
                                DatabaseBackup.ARCHIVE_DATABASE -> if (includeDatabase) stagedDatabase else null
                                DatabaseBackup.WAL -> if (includeDatabase) stagedWal else null
                                DatabaseBackup.SHM -> null // Rebuilt by SQLite; never restore shared-memory state.
                                else -> when {
                                    isTerminal -> TerminalRestoreStore.resolveEntry(
                                        requireNotNull(terminalStaging),
                                        entry.name,
                                    )

                                    includeFiles && isAttachment(entry.name) ->
                                        PendingRestore.resolveInside(File(payload, "files"), entry.name)

                                    else -> null
                                }
                            } ?: continue
                            require(seen.add(entry.name)) { "Duplicate backup entry: ${entry.name}" }
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs()) {
                                "Cannot create backup staging directory"
                            }
                            if (isTerminal) {
                                terminalEntries++
                                require(terminalEntries <= TerminalBackup.MAX_FILES) {
                                    "Too many terminal files in the backup"
                                }
                                val entryLimit = if (entry.name == TerminalBackup.ARCHIVE_WORKSPACES) {
                                    TerminalBackup.MAX_METADATA_BYTES
                                } else {
                                    TerminalBackup.MAX_FILE_BYTES
                                }
                                val declaredSize = entry.size
                                if (declaredSize >= 0) {
                                    require(declaredSize <= entryLimit) {
                                        "Terminal backup entry is too large: ${entry.name}"
                                    }
                                }
                                val previousBytes = terminalBytes
                                terminalBytes += declaredSize.coerceAtLeast(0)
                                require(terminalBytes <= TerminalBackup.MAX_TOTAL_BYTES) {
                                    "Terminal backup is too large"
                                }
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { output ->
                                        val copied = copyLimited(
                                            input = input,
                                            output = output,
                                            maxBytes = minOf(
                                                entryLimit,
                                                TerminalBackup.MAX_TOTAL_BYTES - previousBytes,
                                            ),
                                        )
                                        terminalBytes = previousBytes + copied
                                        require(terminalBytes <= TerminalBackup.MAX_TOTAL_BYTES) {
                                            "Terminal backup is too large"
                                        }
                                        output.fd.sync()
                                    }
                                }
                            } else {
                                zip.getInputStream(entry).use { input ->
                                    FileOutputStream(target).use { output ->
                                        input.copyTo(output)
                                        output.fd.sync()
                                    }
                                }
                            }
                            restoredEntries++
                        }
                    }
                    require(restoredEntries > 0) { "No selected data found in the backup" }
                    require(!stagedWal.exists() || stagedDatabase.exists()) { "Backup WAL has no matching database" }
                    if (stagedDatabase.exists()) {
                        DatabaseBackup.normalize(context, stagedDatabase)
                        // Reject unsupported schemas before publishing; run supported old migrations on the copy.
                        val room = AppDatabaseFactory.create(context, stagedDatabase.absolutePath)
                        try {
                            DatabaseBackup.checkpoint(room.openHelper.writableDatabase)
                        } finally {
                            room.close()
                        }
                        DatabaseBackup.removeSidecars(stagedDatabase)
                    }

                    val settingsFile = File(staging, "settings.json")
                    if (settingsFile.exists()) {
                        val migrated = SettingsJsonMigrator.migrate(settingsFile.readText())
                        val settings = json.decodeFromString<Settings>(migrated)
                        require(!settings.init) { "Backup contains uninitialized settings" }
                        // Persist the migrated value once, including generated IDs, for restart/retry consistency.
                        PendingRestore.writeDurably(settingsFile, json.encodeSettings(settings, json.launchCountOf(migrated)))
                    }
                    currentCoroutineContext().ensureActive()

                    // 终端标记独立于 PendingRestore.pending: applyPendingRestore 完成后 pending 会被删掉。
                    val terminalMarker = terminalStaging?.let {
                        TerminalRestoreStore.buildMarker(it, json)
                    }
                    if (terminalMarker != null) {
                        terminalStore.publish(terminalStaging!!)
                        try {
                            terminalStore.writeMarker(json, terminalMarker)
                        } catch (e: Throwable) {
                            terminalStore.clearPending()
                            throw e
                        }
                    }
                    try {
                        restore.publish(staging)
                    } catch (e: Throwable) {
                        if (terminalMarker != null) terminalStore.clearPending()
                        throw e
                    }
                    // 备份里没有终端数据 (旧备份 / 未勾选) 时, 清掉上一次遗留的待恢复标记。
                    if (terminalMarker == null) terminalStore.clearPending()
                } finally {
                    staging.deleteRecursively()
                    terminalStaging?.deleteRecursively()
                }
            }
        }

    private suspend fun copyLimited(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "Terminal backup entry is too large" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun isAttachment(name: String): Boolean {
        val folder = name.substringBefore('/')
        if (folder !in ATTACHMENT_FOLDERS || '/' !in name) return false
        val relative = name.substringAfter('/')
        require(relative.isNotBlank()) { "Invalid backup attachment: $name" }
        require(folder in NESTED_ATTACHMENT_FOLDERS || '/' !in relative) { "Invalid backup attachment: $name" }
        return true
    }

    private suspend fun addFile(zip: ZipOutputStream, file: File, name: String) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                zip.write(buffer, 0, read)
            }
        }
        zip.closeEntry()
    }

    companion object {
        private const val TAG = "BackupManager"

        /** 桌面安装脚本目录, 属于应用生成内容, 不随终端备份导出 */
        private const val TERMINAL_SKIP_DIR = ".airko"

        private val ATTACHMENT_FOLDERS =
            listOf(FileFolders.UPLOAD, FileFolders.SKILLS, FileFolders.FONTS, FileFolders.MEDIA_CREATION)

        /** Backed up with their subdirectories; the other folders only contain top-level files. */
        private val NESTED_ATTACHMENT_FOLDERS = setOf(FileFolders.SKILLS, FileFolders.MEDIA_CREATION)

        // 启动次数不在 Settings 里，备份文件里仍放在 settings.json 的这个字段，和旧备份保持一致
        private const val LAUNCH_COUNT_KEY = "launchCount"

        private fun Json.encodeSettings(settings: Settings, launchCount: Int): String {
            val fields = encodeToJsonElement(settings).jsonObject + (LAUNCH_COUNT_KEY to JsonPrimitive(launchCount))
            return encodeToString(JsonObject(fields))
        }

        // 更早的备份没有这个字段，按 0 恢复，和它还在 Settings 里时的默认值一致
        private fun Json.launchCountOf(settingsJson: String): Int =
            parseToJsonElement(settingsJson).jsonObject[LAUNCH_COUNT_KEY]?.jsonPrimitive?.intOrNull ?: 0

        private fun pendingRestore(context: Context) = PendingRestore(
            root = File(context.noBackupFilesDir, "backup-restore"),
            databaseFile = context.getDatabasePath(SQLiteConfiguration.DATABASE_NAME),
            filesDir = context.filesDir,
        )

        /** Must finish before Koin, Room, SettingsStore or any background consumers are initialized. */
        suspend fun applyPendingRestore(context: Context, json: Json): Boolean = withContext(Dispatchers.IO) {
            pendingRestore(context).apply { settingsJson ->
                SettingsStore.restoreBeforeInitialization(
                    context = context,
                    settings = json.decodeFromString<Settings>(settingsJson),
                    launchCount = json.launchCountOf(settingsJson),
                )
            }
        }
    }
}
