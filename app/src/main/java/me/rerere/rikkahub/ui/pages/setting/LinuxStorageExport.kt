package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import java.io.File
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
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea

/**
 * 存储管理里的共享目录设置。
 *
 * 这里只做两件事：
 * 1. 让用户选一个 Android 真实目录作为工作区文件区（需要所有文件访问权限）；
 * 2. 选择绑定挂载或符号链接方式。
 *
 * 真正的文件迁移在 WorkspaceRepository 中完成；这个文件不再保存固定导出目录。
 */
@Composable
internal fun LinuxSharedDirectoryDialogs(
    workspaceId: String,
    repository: WorkspaceRepository,
    currentPath: String,
    currentMode: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var mode by remember(currentMode) {
        mutableStateOf(me.rerere.workspace.WorkspaceSharedDirMode.fromWire(currentMode))
    }
    var path by remember(currentPath) { mutableStateOf(currentPath) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val treeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val picked = LinuxDirectoryPicker.treeUriToDirectory(context, uri)
        if (picked == null) {
            error = context.getString(R.string.linux_storage_shared_dir_invalid)
        } else {
            path = picked
            error = null
        }
    }
    val allFilesLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasLinuxExportStorageAccess(context)) {
            treeLauncher.launch(null)
        } else {
            error = context.getString(R.string.linux_storage_export_permission_denied)
        }
    }
    val legacyPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) treeLauncher.launch(null)
        else error = context.getString(R.string.linux_storage_export_permission_denied)
    }

    fun pickDirectory() {
        if (hasLinuxExportStorageAccess(context)) {
            treeLauncher.launch(null)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        } else {
            legacyPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    fun save() {
        if (saving) return
        val target = path.trim()
        if (target.isBlank()) {
            error = context.getString(R.string.linux_storage_shared_dir_required)
            return
        }
        saving = true
        error = null
        scope.launch {
            val result = repository.updateSharedDir(workspaceId, target, mode)
            saving = false
            result.fold(
                onSuccess = {
                    toaster.show(
                        context.getString(R.string.linux_storage_shared_dir_saved),
                        type = ToastType.Success,
                    )
                    onSaved()
                },
                onFailure = {
                    error = it.message ?: context.getString(R.string.linux_storage_shared_dir_failed)
                },
            )
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.linux_storage_shared_dir_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.linux_storage_shared_dir_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = path.ifBlank { stringResource(R.string.linux_storage_shared_dir_not_set) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = ::pickDirectory,
                        enabled = !saving,
                    ) {
                        Text(stringResource(R.string.linux_storage_shared_dir_pick))
                    }
                }
                Text(
                    text = stringResource(R.string.linux_storage_shared_mode_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(
                        me.rerere.workspace.WorkspaceSharedDirMode.BIND to R.string.linux_storage_shared_mode_bind,
                        me.rerere.workspace.WorkspaceSharedDirMode.SYMLINK to R.string.linux_storage_shared_mode_symlink,
                    ).forEach { (option, label) ->
                        Row(
                            modifier = Modifier
                                .clickable(enabled = !saving) { mode = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = mode == option,
                                onClick = { mode = option },
                                enabled = !saving,
                            )
                            Text(stringResource(label))
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.linux_storage_shared_mode_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (saving) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.linux_storage_shared_dir_migrating),
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::save, enabled = !saving && path.isNotBlank()) {
                Text(stringResource(R.string.linux_storage_export_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.linux_storage_page_cancel))
            }
        },
    )
}

/** 选目录并转成真实文件路径；只支持本机 primary storage。 */
internal object LinuxDirectoryPicker {
    fun treeUriToDirectory(context: Context, uri: Uri?): String? {
        if (uri == null) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val relative = when {
            documentId == "primary:" -> ""
            documentId.startsWith("primary:") -> documentId.removePrefix("primary:").trim('/')
            else -> return null
        }
        val root = Environment.getExternalStorageDirectory() ?: return null
        val target = if (relative.isBlank()) root else File(root, relative)
        return runCatching { target.canonicalPath }.getOrNull()
    }
}

internal fun hasLinuxExportStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

@Composable
internal fun LinuxStorageExportDialog(
    workspaceId: String,
    repository: WorkspaceRepository,
    onDismiss: () -> Unit,
    onExportSelected: (List<WorkspaceFileEntry>) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<WorkspaceFileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Map<String, WorkspaceFileEntry>>(emptyMap()) }

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
            error = failure.message
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                Text(
                    text = stringResource(R.string.linux_storage_export_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.linux_storage_export_pick_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (path.isNotBlank()) path = path.substringBeforeLast('/', "")
                            },
                            enabled = path.isNotBlank(),
                        ) {
                            Icon(HugeIcons.ArrowTurnBackward, stringResource(R.string.linux_storage_page_up))
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
                        TextButton(onClick = { toggleAll() }, enabled = entries.isNotEmpty()) {
                            Icon(HugeIcons.CheckList, null, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(
                                    if (allSelected) R.string.linux_storage_export_deselect_all
                                    else R.string.linux_storage_export_select_all
                                ),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.size(28.dp).align(Alignment.Center),
                        )
                        error != null -> Text(
                            text = stringResource(R.string.linux_storage_export_load_failed, error.orEmpty()),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                        )
                        entries.isEmpty() -> Text(
                            text = stringResource(R.string.linux_storage_export_empty),
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
                        text = stringResource(R.string.linux_storage_export_selected_count, selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.linux_storage_page_cancel))
                    }
                    Button(
                        onClick = { onExportSelected(selected.values.toList()) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Icon(HugeIcons.FileExport, null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.linux_storage_export_pick_target),
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
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (entry.isDirectory) onOpen() else onToggle() },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
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
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (entry.isDirectory) stringResource(R.string.linux_storage_page_folder)
                    else entry.sizeBytes.fileSizeToString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.isDirectory) {
                Icon(
                    HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

private data class ExportFile(
    val sourcePath: String,
    val destination: File,
)

internal suspend fun exportWorkspaceSelection(
    repository: WorkspaceRepository,
    workspaceId: String,
    selected: List<WorkspaceFileEntry>,
    targetRoot: File,
    onProgress: suspend (completed: Int, total: Int, name: String?) -> Unit,
): Pair<Int, Int> = withContext(Dispatchers.IO) {
    targetRoot.mkdirs()
    val files = mutableListOf<ExportFile>()
    var failed = 0

    suspend fun collect(entry: WorkspaceFileEntry, destination: File) {
        currentCoroutineContext().ensureActive()
        if (entry.isSymbolicLink) return
        if (!entry.isDirectory) {
            files += ExportFile(entry.path, destination)
            return
        }
        destination.mkdirs()
        val children = runCatching {
            repository.listFiles(
                id = workspaceId,
                area = WorkspaceStorageArea.FILES,
                path = entry.path,
            )
        }.getOrElse {
            failed++
            emptyList()
        }
        children.forEach { child ->
            collect(child, File(destination, child.name))
        }
    }

    selected.forEach { entry ->
        collect(entry, uniqueTarget(targetRoot, entry.name))
    }

    var copied = 0
    files.forEachIndexed { index, item ->
        currentCoroutineContext().ensureActive()
        runCatching {
            item.destination.parentFile?.mkdirs()
            item.destination.outputStream().use { output ->
                repository.exportFile(
                    id = workspaceId,
                    area = WorkspaceStorageArea.FILES,
                    path = item.sourcePath,
                    outputStream = output,
                )
            }
            copied++
        }.onFailure {
            failed++
            runCatching { item.destination.delete() }
        }
        onProgress(index + 1, files.size, item.sourcePath)
    }
    copied to failed
}

private fun uniqueTarget(root: File, name: String): File {
    var candidate = File(root, name)
    var index = 1
    while (candidate.exists()) {
        candidate = File(root, "$name ($index)")
        index++
    }
    return candidate
}
