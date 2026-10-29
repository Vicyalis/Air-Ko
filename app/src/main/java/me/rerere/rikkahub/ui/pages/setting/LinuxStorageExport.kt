package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import java.io.File
import java.nio.file.Files

internal const val LINUX_EXPORT_FOLDER_PREF_KEY = "linuxExportFolder"
internal const val DEFAULT_LINUX_EXPORT_FOLDER = "Air-Ko!"

/**
 * 导出目录名是相对 /storage/emulated/0 的路径。
 * 只允许普通相对路径：拒绝空值、绝对路径和包含 `..` 的路径，避免写到公共存储之外。
 */
internal fun normalizeLinuxExportFolder(raw: String): String? {
    val replaced = raw.trim().replace('\\', '/')
    if (replaced.isEmpty() || replaced.startsWith('/')) return null
    val segments = replaced
        .split('/')
        .filter { it.isNotBlank() && it != "." }
    if (segments.isEmpty()) return null
    if (segments.any { it == ".." || it.contains('\u0000') }) return null
    return segments.joinToString("/")
}

internal fun Context.readLinuxExportFolder(): String =
    normalizeLinuxExportFolder(
        readStringPreference(LINUX_EXPORT_FOLDER_PREF_KEY, DEFAULT_LINUX_EXPORT_FOLDER).orEmpty()
    ) ?: DEFAULT_LINUX_EXPORT_FOLDER

internal fun hasLinuxExportStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

internal fun linuxExportTargetDirectory(folder: String): File? {
    val normalized = normalizeLinuxExportFolder(folder) ?: return null
    val root = Environment.getExternalStorageDirectory() ?: return null
    val target = File(root, normalized)
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    val canonicalTarget = runCatching { target.canonicalFile }.getOrNull() ?: return null
    val rootPath = canonicalRoot.path
    val targetPath = canonicalTarget.path
    if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return null
    return target
}

internal fun linuxExportTargetPath(folder: String): String {
    val normalized = normalizeLinuxExportFolder(folder) ?: DEFAULT_LINUX_EXPORT_FOLDER
    val root = Environment.getExternalStorageDirectory()
    return File(root, normalized).absolutePath
}

@Composable
internal fun LinuxStorageExportFolderDialog(
    currentFolder: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(currentFolder) { mutableStateOf(currentFolder) }
    val normalized = normalizeLinuxExportFolder(value)
    val invalid = normalized == null
    val preview = remember(normalized) {
        linuxExportTargetPath(normalized ?: DEFAULT_LINUX_EXPORT_FOLDER)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.linux_storage_export_folder_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.linux_storage_export_folder_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = invalid,
                    placeholder = { Text(DEFAULT_LINUX_EXPORT_FOLDER) },
                    supportingText = {
                        Text(
                            text = if (invalid) {
                                stringResource(R.string.linux_storage_export_folder_invalid)
                            } else {
                                stringResource(R.string.linux_storage_export_folder_preview, preview)
                            },
                            color = if (invalid) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onSave) },
                enabled = normalized != null,
            ) {
                Text(stringResource(R.string.linux_storage_export_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.linux_storage_page_cancel))
            }
        },
    )
}

@Composable
internal fun LinuxStorageExportDialog(
    workspaceId: String,
    repository: WorkspaceRepository,
    folder: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val targetPath = remember(folder) { linuxExportTargetPath(folder) }

    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Map<String, WorkspaceFileEntry>>(emptyMap()) }
    var exporting by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var currentName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workspaceId, path) {
        loading = true
        error = null
        entries = emptyList()
        try {
            entries = repository.listFiles(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                path = path,
            ).filterNot { it.isSymbolicLink }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            error = failure.message ?: context.getString(R.string.linux_storage_export_load_failed_generic)
        } finally {
            loading = false
        }
    }

    val allSelected = entries.isNotEmpty() && entries.all { it.path in selected }

    fun toggle(entry: WorkspaceFileEntry) {
        selected = if (entry.path in selected) {
            selected - entry.path
        } else {
            selected + (entry.path to entry)
        }
    }

    fun toggleAll() {
        val currentPaths = entries.map { it.path }.toSet()
        selected = if (allSelected) {
            selected.filterKeys { it !in currentPaths }
        } else {
            selected + entries.associateBy { it.path }
        }
    }

    fun goUp() {
        if (path.isNotBlank()) {
            path = path.substringBeforeLast('/', missingDelimiterValue = "")
        }
    }

    fun startExport() {
        if (exporting || selected.isEmpty()) return
        val selection = selected.values.toList()
        exporting = true
        completed = 0
        total = 0
        currentName = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    copySelectionToPublicFolder(
                        context = context,
                        repository = repository,
                        workspaceId = workspaceId,
                        selected = selection,
                        targetFolder = folder,
                        onProgress = { done, all, name ->
                            withContext(Dispatchers.Main) {
                                completed = done
                                total = all
                                currentName = name
                            }
                        },
                    )
                }
                exporting = false
                if (result.failed > 0) {
                    toaster.show(
                        message = context.getString(
                            R.string.linux_storage_export_done_with_failures,
                            result.copied,
                            result.targetPath,
                            result.failed,
                        ),
                        type = ToastType.Warning,
                    )
                } else {
                    toaster.show(
                        message = context.getString(
                            R.string.linux_storage_export_done,
                            result.copied,
                            result.targetPath,
                        ),
                        type = ToastType.Success,
                    )
                }
                onDismiss()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                exporting = false
                toaster.show(
                    message = context.getString(
                        R.string.linux_storage_export_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    type = ToastType.Error,
                )
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !exporting,
            dismissOnClickOutside = !exporting,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.linux_storage_export_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.linux_storage_export_target, targetPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = { goUp() },
                            enabled = path.isNotBlank() && !exporting,
                        ) {
                            Icon(
                                imageVector = HugeIcons.ArrowTurnBackward,
                                contentDescription = stringResource(R.string.linux_storage_page_up),
                            )
                        }
                        Text(
                            text = buildString {
                                append("/workspace")
                                if (path.isNotBlank()) append('/').append(path)
                            },
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { toggleAll() },
                            enabled = entries.isNotEmpty() && !exporting,
                        ) {
                            Icon(
                                imageVector = HugeIcons.CheckList,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(
                                    if (allSelected) {
                                        R.string.linux_storage_export_deselect_all
                                    } else {
                                        R.string.linux_storage_export_select_all
                                    }
                                ),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                if (exporting) {
                    LinuxStorageExportProgress(
                        completed = completed,
                        total = total,
                        currentName = currentName,
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .align(Alignment.Center),
                        )

                        error != null -> Text(
                            text = stringResource(
                                R.string.linux_storage_export_load_failed,
                                error.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )

                        entries.isEmpty() -> Text(
                            text = stringResource(R.string.linux_storage_export_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(entries, key = { it.path }) { entry ->
                                LinuxStorageExportRow(
                                    entry = entry,
                                    selected = entry.path in selected,
                                    enabled = !exporting,
                                    onToggle = { toggle(entry) },
                                    onOpen = { if (entry.isDirectory) path = entry.path },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.linux_storage_export_selected_count,
                            selected.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onDismiss,
                        enabled = !exporting,
                    ) {
                        Text(stringResource(R.string.linux_storage_page_cancel))
                    }
                    Button(
                        onClick = { startExport() },
                        enabled = selected.isNotEmpty() && !exporting,
                    ) {
                        Icon(
                            imageVector = HugeIcons.FileExport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(R.string.linux_storage_export_start),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinuxStorageExportRow(
    entry: WorkspaceFileEntry,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                if (entry.isDirectory) onOpen() else onToggle()
            },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File02,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) {
                        stringResource(R.string.linux_storage_page_folder)
                    } else {
                        formatBytes(entry.sizeBytes)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.isDirectory) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun LinuxStorageExportProgress(
    completed: Int,
    total: Int,
    currentName: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (total > 0) {
                    stringResource(R.string.linux_storage_export_progress, completed, total)
                } else {
                    stringResource(R.string.linux_storage_export_preparing)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { completed.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            currentName?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class LinuxExportResult(
    val copied: Int,
    val failed: Int,
    val targetPath: String,
)

private data class LinuxExportFile(
    val sourcePath: String,
    val destination: File,
)

private data class LinuxExportPlan(
    val files: List<LinuxExportFile>,
    val failed: Int,
)

private suspend fun copySelectionToPublicFolder(
    context: Context,
    repository: WorkspaceRepository,
    workspaceId: String,
    selected: List<WorkspaceFileEntry>,
    targetFolder: String,
    onProgress: suspend (completed: Int, total: Int, name: String?) -> Unit,
): LinuxExportResult {
    val targetRoot = linuxExportTargetDirectory(targetFolder)
        ?: error(context.getString(R.string.linux_storage_export_error_invalid_folder, targetFolder))
    if (targetRoot.exists() && !targetRoot.isDirectory) {
        error(
            context.getString(
                R.string.linux_storage_export_error_not_directory,
                targetRoot.absolutePath,
            )
        )
    }
    if (!targetRoot.exists() && !targetRoot.mkdirs() && !targetRoot.isDirectory) {
        error(
            context.getString(
                R.string.linux_storage_export_error_create_failed,
                targetRoot.absolutePath,
            )
        )
    }

    val plan = buildLinuxExportPlan(
        repository = repository,
        workspaceId = workspaceId,
        selected = selected,
        targetRoot = targetRoot,
    )
    onProgress(0, plan.files.size, null)

    var copied = 0
    var failed = plan.failed
    plan.files.forEachIndexed { index, item ->
        currentCoroutineContext().ensureActive()
        var created: File? = null
        try {
            val destination = uniqueLinuxExportFile(item.destination)
            created = destination
            destination.parentFile?.mkdirs()
            destination.outputStream().use { output ->
                repository.exportFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    path = item.sourcePath,
                    outputStream = output,
                )
            }
            copied++
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed++
            runCatching { created?.delete() }
        }
        onProgress(index + 1, plan.files.size, item.sourcePath)
    }

    return LinuxExportResult(
        copied = copied,
        failed = failed,
        targetPath = targetRoot.absolutePath,
    )
}

private suspend fun buildLinuxExportPlan(
    repository: WorkspaceRepository,
    workspaceId: String,
    selected: List<WorkspaceFileEntry>,
    targetRoot: File,
): LinuxExportPlan {
    val files = mutableListOf<LinuxExportFile>()
    var failed = 0
    val selectedDirectories = selected
        .filter { it.isDirectory && !it.isSymbolicLink }
        .map { it.path }
        .toSet()
    val roots = selected.filter { entry ->
        !entry.isSymbolicLink &&
            selectedDirectories.none { directory ->
                entry.path != directory && entry.path.startsWith("$directory/")
            }
    }
    val pendingDirectories = ArrayDeque<Pair<String, File>>()

    roots.forEach { entry ->
        when {
            entry.isDirectory -> {
                val destination = ensureLinuxExportDirectory(File(targetRoot, entry.name))
                if (destination == null) {
                    failed++
                } else {
                    pendingDirectories.addLast(entry.path to destination)
                }
            }

            !entry.isSymbolicLink -> {
                files += LinuxExportFile(
                    sourcePath = entry.path,
                    destination = File(targetRoot, entry.name),
                )
            }
        }
    }

    while (pendingDirectories.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val (sourceDirectory, destinationDirectory) = pendingDirectories.removeFirst()
        val children = try {
            repository.listFiles(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                path = sourceDirectory,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            failed++
            continue
        }
        children.forEach { child ->
            if (child.isSymbolicLink) return@forEach
            when {
                child.isDirectory -> {
                    val destination = ensureLinuxExportDirectory(
                        File(destinationDirectory, child.name),
                    )
                    if (destination == null) {
                        failed++
                    } else {
                        pendingDirectories.addLast(child.path to destination)
                    }
                }

                !child.isSymbolicLink -> {
                    files += LinuxExportFile(
                        sourcePath = child.path,
                        destination = File(destinationDirectory, child.name),
                    )
                }
            }
        }
    }

    return LinuxExportPlan(files = files, failed = failed)
}

private fun ensureLinuxExportDirectory(directory: File): File? {
    if (directory.isDirectory && !directory.isSymbolicLink()) return directory
    if (!directory.exists() && !directory.isSymbolicLink()) {
        return if (directory.mkdirs() || directory.isDirectory) directory else null
    }
    val alternative = uniqueLinuxExportFile(directory)
    return if (alternative.mkdirs() || alternative.isDirectory) alternative else null
}

private fun uniqueLinuxExportFile(file: File): File {
    if (!file.exists() && !file.isSymbolicLink()) return file
    val name = file.name
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: -1
    val stem = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var index = 1
    while (true) {
        val candidate = File(file.parentFile, "$stem ($index)$extension")
        if (!candidate.exists() && !candidate.isSymbolicLink()) return candidate
        index++
    }
}

private fun File.isSymbolicLink(): Boolean =
    runCatching { Files.isSymbolicLink(toPath()) }.getOrDefault(false)
