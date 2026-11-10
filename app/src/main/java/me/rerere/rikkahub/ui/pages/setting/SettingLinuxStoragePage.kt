package me.rerere.rikkahub.ui.pages.setting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.hugeicons.stroke.ChevronUp
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Upload01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceDetailVM
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceSharedDirMode
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 存储管理（数据设置）——管理内置 Linux 的 `/workspace` 文件区。
 *
 * 文件区可以指向 Android 真实目录（绑定挂载 / 符号链接），也可以继续使用 App 私有目录。
 * 页面只保留：选目录、选连接方式、选择文件导出、打开完整工作区、聊天附件清理。
 */
@Composable
fun SettingLinuxStoragePage() {
    val repository: WorkspaceRepository = koinInject()
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val workspaces by repository.listFlow().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val current = workspaces.firstOrNull { it.id == selectedId } ?: workspaces.firstOrNull()

    LaunchedEffect(workspaces) {
        if (selectedId == null || workspaces.none { it.id == selectedId }) {
            selectedId = workspaces.firstOrNull()?.id
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.linux_storage_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val workspace = current
        if (workspace == null) {
            Text(
                text = stringResource(R.string.linux_storage_page_no_workspace),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            WorkspaceFilesContent(
                workspace = workspace,
                workspaces = workspaces,
                repository = repository,
                onSelectWorkspace = { selectedId = it },
                onOpenWorkspace = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                onOpenChatFiles = { navController.navigate(Screen.SettingFiles) },
                innerPadding = innerPadding,
            )
        }
    }
}

@Composable
private fun WorkspaceFilesContent(
    workspace: WorkspaceEntity,
    workspaces: List<WorkspaceEntity>,
    repository: WorkspaceRepository,
    onSelectWorkspace: (String) -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenChatFiles: () -> Unit,
    innerPadding: PaddingValues,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val vm: WorkspaceDetailVM = koinViewModel(
        key = workspace.id,
        parameters = { parametersOf(workspace.id) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showWorkspaceMenu by remember { mutableStateOf(false) }
    var showSharedDirDialog by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<List<WorkspaceFileEntry>?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(0) }
    var exportTotal by remember { mutableStateOf(0) }
    var exportName by remember { mutableStateOf<String?>(null) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var imported = 0
        uris.forEach { uri ->
            val name = queryDisplayName(context, uri) ?: "file"
            val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            if (input != null) {
                vm.importFile(input, name)
                imported++
            }
        }
        if (imported > 0) {
            toaster.show(
                message = context.getString(R.string.linux_storage_page_uploaded, imported),
                type = ToastType.Success,
            )
        }
    }

    val exportTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val selection = pendingExport.also { pendingExport = null } ?: return@rememberLauncherForActivityResult
        val targetPath = LinuxDirectoryPicker.treeUriToDirectory(context, uri)
        if (targetPath == null) {
            toaster.show(
                message = context.getString(R.string.linux_storage_export_pick_invalid),
                type = ToastType.Warning,
            )
            return@rememberLauncherForActivityResult
        }
        exporting = true
        exportDone = 0
        exportTotal = 0
        exportName = null
        scope.launch {
            try {
                val (copied, failed) = withContext(Dispatchers.IO) {
                    exportWorkspaceSelection(
                        repository = repository,
                        workspaceId = workspace.id,
                        selected = selection,
                        targetRoot = java.io.File(targetPath),
                        onProgress = { done, total, name ->
                            withContext(Dispatchers.Main) {
                                exportDone = done
                                exportTotal = total
                                exportName = name
                            }
                        },
                    )
                }
                toaster.show(
                    message = context.getString(
                        if (failed > 0) R.string.linux_storage_export_done_with_failures
                        else R.string.linux_storage_export_done,
                        copied,
                        targetPath,
                        failed,
                    ),
                    type = if (failed > 0) ToastType.Warning else ToastType.Success,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                toaster.show(
                    message = context.getString(
                        R.string.linux_storage_export_failed,
                        failure.message ?: failure.javaClass.simpleName,
                    ),
                    type = ToastType.Error,
                )
            } finally {
                exporting = false
            }
        }
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (hasLinuxExportStorageAccess(context)) {
            exportTreeLauncher.launch(null)
        } else {
            pendingExport = null
            toaster.show(
                message = context.getString(R.string.linux_storage_export_permission_denied),
                type = ToastType.Warning,
            )
        }
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            exportTreeLauncher.launch(null)
        } else {
            pendingExport = null
            toaster.show(
                message = context.getString(R.string.linux_storage_export_permission_denied),
                type = ToastType.Warning,
            )
        }
    }

    fun startExport(selection: List<WorkspaceFileEntry>) {
        pendingExport = selection
        if (hasLinuxExportStorageAccess(context)) {
            exportTreeLauncher.launch(null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            allFilesLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        } else {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val containerColor = MaterialTheme.colorScheme.tertiaryContainer
    val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    val sharedPath = workspace.sharedDirPath
    val mode = WorkspaceSharedDirMode.fromWire(workspace.sharedDirMode)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = innerPadding.calculateTopPadding(),
            bottom = innerPadding.calculateBottomPadding() + 32.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("hint") {
            CardGroup {
                if (workspaces.size > 1) {
                    item(
                        onClick = { showWorkspaceMenu = true },
                        headlineContent = { Text(stringResource(R.string.linux_storage_page_workspace)) },
                        supportingContent = { Text(workspace.name) },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.FolderAdd, containerColor = containerColor, contentColor = contentColor)
                        },
                        trailingContent = { Icon(HugeIcons.ChevronDown, null) },
                    )
                }
                item(
                    onClick = { showSharedDirDialog = true },
                    headlineContent = { Text(stringResource(R.string.linux_storage_shared_dir_title)) },
                    supportingContent = {
                        Text(
                            text = sharedPath.ifBlank {
                                stringResource(R.string.linux_storage_shared_dir_default)
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(HugeIcons.Folder01, containerColor = containerColor, contentColor = contentColor)
                    },
                )
                item(
                    onClick = {
                        if (sharedPath.isBlank()) {
                            toaster.show(
                                context.getString(R.string.linux_storage_shared_dir_required),
                                type = ToastType.Warning,
                            )
                        } else {
                            showModeDialog = true
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.linux_storage_shared_mode_title)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (mode == WorkspaceSharedDirMode.SYMLINK) {
                                    R.string.linux_storage_shared_mode_symlink
                                } else {
                                    R.string.linux_storage_shared_mode_bind
                                }
                            )
                        )
                    },
                    leadingContent = {
                        CardGroupIcon(HugeIcons.Link01, containerColor = containerColor, contentColor = contentColor)
                    },
                )
                item(
                    onClick = { showExportDialog = true },
                    headlineContent = { Text(stringResource(R.string.linux_storage_export_entry_title)) },
                    supportingContent = {
                        Text(stringResource(R.string.linux_storage_export_entry_desc))
                    },
                    leadingContent = {
                        CardGroupIcon(HugeIcons.FileExport, containerColor = containerColor, contentColor = contentColor)
                    },
                )
                item(
                    onClick = onOpenWorkspace,
                    headlineContent = { Text(stringResource(R.string.linux_storage_page_open_workspace)) },
                    supportingContent = { Text(stringResource(R.string.linux_storage_page_open_workspace_desc)) },
                    leadingContent = {
                        CardGroupIcon(HugeIcons.File02, containerColor = containerColor, contentColor = contentColor)
                    },
                )
                item(
                    onClick = onOpenChatFiles,
                    headlineContent = { Text(stringResource(R.string.linux_storage_page_chat_files)) },
                    supportingContent = { Text(stringResource(R.string.linux_storage_page_chat_files_desc)) },
                    leadingContent = {
                        CardGroupIcon(HugeIcons.Delete01, containerColor = containerColor, contentColor = contentColor)
                    },
                )
            }
        }

        item("workspaceMenu") {
            if (workspaces.size > 1) {
                DropdownMenu(
                    expanded = showWorkspaceMenu,
                    onDismissRequest = { showWorkspaceMenu = false },
                ) {
                    workspaces.forEach { workspaceItem ->
                        DropdownMenuItem(
                            text = { Text(workspaceItem.name) },
                            onClick = {
                                showWorkspaceMenu = false
                                onSelectWorkspace(workspaceItem.id)
                            },
                        )
                    }
                }
            }
        }

        item("toolbar") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(
                    onClick = { vm.goUp() },
                    enabled = state.path.isNotBlank(),
                ) {
                    Icon(HugeIcons.ChevronUp, stringResource(R.string.linux_storage_page_up))
                }
                Text(
                    text = buildString {
                        append("/workspace")
                        if (state.path.isNotBlank()) append('/').append(state.path)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                    Icon(HugeIcons.Upload01, null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.linux_storage_page_upload),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                TextButton(onClick = { showNewFolderDialog = true }) {
                    Icon(HugeIcons.FolderAdd, null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.linux_storage_page_new_folder),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        if (state.loading) {
            item("loading") {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }

        state.error?.let { error ->
            item("error") {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (!state.loading && state.entries.isEmpty()) {
            item("empty") {
                Text(
                    text = stringResource(R.string.linux_storage_page_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        items(state.entries, key = { it.path }) { entry ->
            WorkspaceFileRow(
                entry = entry,
                onOpen = { vm.open(entry) },
                onDelete = { deleteTarget = entry },
            )
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.linux_storage_page_delete_title)) },
            text = { Text(stringResource(R.string.linux_storage_page_delete_confirm, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(target)
                        deleteTarget = null
                    },
                ) {
                    Text(stringResource(R.string.linux_storage_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.linux_storage_page_cancel))
                }
            },
        )
    }

    if (showNewFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.linux_storage_page_new_folder)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.linux_storage_page_folder_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createDirectory(name)
                        showNewFolderDialog = false
                    },
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.linux_storage_page_create))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.linux_storage_page_cancel))
                }
            },
        )
    }

    if (showSharedDirDialog) {
        LinuxSharedDirectoryDialogs(
            workspaceId = workspace.id,
            repository = repository,
            currentPath = sharedPath,
            currentMode = mode.wire,
            onDismiss = { showSharedDirDialog = false },
            onSaved = { showSharedDirDialog = false },
        )
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text(stringResource(R.string.linux_storage_shared_mode_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceSharedDirMode.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showModeDialog = false
                                    scope.launch {
                                        repository.updateSharedDir(workspace.id, sharedPath, option)
                                            .onSuccess {
                                                toaster.show(
                                                    context.getString(R.string.linux_storage_shared_mode_saved),
                                                    type = ToastType.Success,
                                                )
                                            }
                                            .onFailure {
                                                toaster.show(
                                                    it.message ?: context.getString(R.string.linux_storage_shared_dir_failed),
                                                    type = ToastType.Error,
                                                )
                                            }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == mode, onClick = null)
                            Text(
                                text = stringResource(
                                    if (option == WorkspaceSharedDirMode.SYMLINK) {
                                        R.string.linux_storage_shared_mode_symlink
                                    } else {
                                        R.string.linux_storage_shared_mode_bind
                                    }
                                ),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showModeDialog = false }) {
                    Text(stringResource(R.string.linux_storage_page_cancel))
                }
            },
        )
    }

    if (showExportDialog) {
        LinuxStorageExportDialog(
            workspaceId = workspace.id,
            repository = repository,
            onDismiss = { showExportDialog = false },
            onExportSelected = { selection ->
                showExportDialog = false
                startExport(selection)
            },
        )
    }

    if (exporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.linux_storage_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (exportTotal > 0) {
                            stringResource(R.string.linux_storage_export_progress, exportDone, exportTotal)
                        } else {
                            stringResource(R.string.linux_storage_export_preparing)
                        }
                    )
                    if (exportTotal > 0) {
                        LinearProgressIndicator(
                            progress = { exportDone.toFloat() / exportTotal },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    exportName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = null,
        )
    }
}

@Composable
private fun WorkspaceFileRow(
    entry: WorkspaceFileEntry,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.linux_storage_page_delete),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
    }
}.getOrNull()

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
