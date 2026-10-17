package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Intent
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowTurnBackward
import me.rerere.hugeicons.stroke.Bash
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.CloudDownload
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Share08
import me.rerere.hugeicons.stroke.Tag01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Wrench01
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.tools.resolveWorkspaceToolApproval
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.PagerTab
import me.rerere.rikkahub.ui.components.ui.PagerTabs
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstallStage
import me.rerere.workspace.RootfsDistro
import me.rerere.workspace.RootfsDistroCatalog
import me.rerere.workspace.RootfsLinkKind
import me.rerere.workspace.RootfsMirror
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File

private enum class WorkspaceDetailTab(
    override val icon: ImageVector,
    @param:StringRes private val labelRes: Int,
) : PagerTab {
    BASIC(HugeIcons.Settings03, R.string.workspace_detail_tab_basic),
    FILES(HugeIcons.File02, R.string.workspace_detail_tab_files);

    override val label: String
        @Composable get() = stringResource(labelRes)
}

@Composable
fun WorkspaceDetailPage(id: String) {
    val navController = LocalNavController.current
    val vm: WorkspaceDetailVM = koinViewModel(parameters = { parametersOf(id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val installProgress by vm.installProgress.collectAsStateWithLifecycle()
    val installError by vm.installError.collectAsStateWithLifecycle()
    val settingsError by vm.settingsError.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { WorkspaceDetailTab.entries.size }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var previewImageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "imported_file"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.importFile(inputStream, fileName)
    }
    var exportTarget by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val entry = exportTarget.also { exportTarget = null } ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        val outputStream = context.contentResolver.openOutputStream(uri) ?: return@rememberLauncherForActivityResult
        vm.exportFile(entry, outputStream)
    }
    val directoryExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        vm.exportFilesToDirectory(uri, context.contentResolver)
    }

    val onFilesTab = pagerState.currentPage == WorkspaceDetailTab.FILES.ordinal

    BackHandler(enabled = onFilesTab && state.path.isNotBlank()) {
        vm.goUp()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.workspace?.name ?: stringResource(R.string.workspace_detail_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (onFilesTab) {
                        IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            Icon(
                                HugeIcons.FileImport,
                                contentDescription = stringResource(R.string.workspace_detail_import_file),
                            )
                        }
                    }
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = null)
                    }
                    if (state.workspace?.shellStatus != WorkspaceShellStatus.DISABLED.name) {
                        IconButton(onClick = { navController.navigate(Screen.WorkspaceTerminal(id)) }) {
                            Icon(HugeIcons.ComputerTerminal01, contentDescription = null)
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            PagerTabs(
                pagerState = pagerState,
                tabs = WorkspaceDetailTab.entries,
                modifier = Modifier.padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (WorkspaceDetailTab.entries[page]) {
                    WorkspaceDetailTab.BASIC -> WorkspaceBasicPage(
                        workspace = state.workspace,
                        installProgress = installProgress,
                        onInstallRootfs = { showInstallDialog = true },
                        onToolApprovalChange = vm::setToolApproval,
                        onShellCompatibilityModeChange = vm::setShellCompatibilityMode,
                        onDesktopChange = vm::updateDesktop,
                    )

                    WorkspaceDetailTab.FILES -> WorkspaceFilesPage(
                        state = state,
                        isActive = onFilesTab,
                        onSelectArea = vm::selectArea,
                        onGoUp = vm::goUp,
                        onResolveImage = { entry, area -> vm.resolveImageFile(entry, area) },
                        onOpen = { entry ->
                            when {
                                entry.isDirectory -> vm.open(entry)

                                entry.name.substringAfterLast('.').equals("svg", ignoreCase = true) ->
                                    navController.navigate(
                                        Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                    )

                                else -> when (entry.detectFileType()) {
                                    WorkspaceFileType.TEXT -> navController.navigate(
                                        Screen.WorkspaceFileEditor(id, state.area.name, entry.path)
                                    )

                                    WorkspaceFileType.IMAGE -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                        // 传绝对路径 (而非 content:// URI): Coil 可直接加载,
                                        // 预览弹窗的保存按钮 saveMessageImage 只认 "/" 开头路径, content URI 会报错
                                        previewImageUri = file.absolutePath
                                    }

                                    WorkspaceFileType.OTHER -> vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file,
                                        )
                                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                            file.extension.lowercase()
                                        ) ?: "*/*"
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(intent, null))
                                        }
                                    }
                                }
                            }
                        },
                        onDelete = { deleteTarget = it },
                        onBatchExport = { entries ->
                            if (vm.prepareBatchExport(entries)) directoryExportLauncher.launch(null)
                        },
                        onExport = { entry ->
                            exportTarget = entry
                            exportLauncher.launch(entry.name)
                        },
                        onShare = { entry ->
                            vm.exportToCacheFile(entry, context.cacheDir) { file ->
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file,
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/octet-stream"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                    )
                }
            }
        }
    }

    state.exportResult?.let { result ->
        AlertDialog(
            onDismissRequest = vm::dismissExportResult,
            title = { Text("导出结果") },
            text = { Text(result, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = vm::dismissExportResult) { Text(stringResource(R.string.common_confirm)) }
            },
        )
    }

    state.workspace?.let { workspace ->
        if (showInstallDialog) {
            InstallRootfsDialog(
                workspace = workspace,
                onDismiss = { showInstallDialog = false },
                onConfirm = { url ->
                    vm.installRootfs(url)
                    showInstallDialog = false
                },
            )
        }
    }

    installError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissInstallError,
            title = { Text(stringResource(R.string.workspace_detail_rootfs_install_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::dismissInstallError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    settingsError?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissSettingsError,
            title = { Text(stringResource(R.string.workspace_detail_settings_save_failed)) },
            text = { Text(message.ifBlank { stringResource(R.string.workspace_detail_settings_save_failed) }) },
            confirmButton = {
                TextButton(onClick = vm::dismissSettingsError) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }

    previewImageUri?.let { uri ->
        ImagePreviewDialog(
            images = listOf(uri),
            onDismissRequest = { previewImageUri = null },
        )
    }

    deleteTarget?.let { entry ->
        RikkaConfirmDialog(
            show = true,
            title = if (entry.isDirectory) stringResource(R.string.workspace_detail_delete_directory) else stringResource(R.string.workspace_detail_delete_file),
            confirmText = stringResource(R.string.common_delete),
            dismissText = stringResource(R.string.common_cancel),
            onConfirm = {
                vm.delete(entry)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.workspace_detail_will_delete, entry.path))
        }
    }
}

@Composable
private fun WorkspaceBasicPage(
    workspace: WorkspaceEntity?,
    installProgress: RootfsInstallProgress?,
    onInstallRootfs: () -> Unit,
    onToolApprovalChange: (String, Boolean) -> Unit,
    onShellCompatibilityModeChange: (Boolean) -> Unit,
    onDesktopChange: (WorkspaceDesktop, String, Int) -> Unit,
) {
    val shellStatus = workspace?.shellStatus
    val installing = installProgress != null || shellStatus == WorkspaceShellStatus.INSTALLING.name
    val rootfsReady = shellStatus == WorkspaceShellStatus.READY.name
    val installButtonText = when {
        installing -> stringResource(R.string.workspace_detail_installing)
        rootfsReady -> stringResource(R.string.workspace_detail_reinstall_rootfs)
        else -> stringResource(R.string.workspace_detail_install_rootfs)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_workspace_info)) },
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_name)) },
                    supportingContent = {
                        Text(workspace?.name ?: stringResource(R.string.workspace_detail_loading))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Tag01) },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_shell_status)) },
                    leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                    trailingContent = { ShellStatusBadge(shellStatus) },
                )
            }
        }

        item {
            CardGroup(
                title = { Text(stringResource(R.string.workspace_detail_enable_shell)) },
            ) {
                formItem(
                    headlineContent = { Text(stringResource(R.string.workspace_detail_area_rootfs)) },
                    supportingContent = { Text(stringResource(R.string.workspace_detail_enable_shell_desc)) },
                    leadingContent = { CardGroupIcon(HugeIcons.Bash) },
                ) {
                    Button(
                        onClick = onInstallRootfs,
                        enabled = workspace != null && !installing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Bash,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text(installButtonText)
                    }
                    installProgress?.let { RootfsProgress(it) }
                }
                switchItem(
                    checked = workspace?.shellCompatibilityMode ?: false,
                    onCheckedChange = onShellCompatibilityModeChange,
                    enabled = workspace != null,
                    supportingContent = {
                        Text(stringResource(R.string.workspace_detail_compatibility_mode_desc))
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Wrench01) },
                    headlineContent = { Text(stringResource(R.string.workspace_detail_compatibility_mode)) },
                )
            }
        }

        item {
            WorkspaceDesktopCard(
                workspace = workspace,
                onDesktopChange = onDesktopChange,
            )
        }

        item {
            WorkspaceToolApprovalCard(
                workspace = workspace,
                onToolApprovalChange = onToolApprovalChange,
            )
        }
    }
}

// Shell 状态标签，就绪和出错用不同的底色区分
@Composable
private fun ShellStatusBadge(status: String?) {
    val colorScheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (status) {
        WorkspaceShellStatus.READY.name -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        WorkspaceShellStatus.BROKEN.name -> colorScheme.errorContainer to colorScheme.onErrorContainer
        else -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = status?.toShellStatusLabel() ?: "-",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
private fun WorkspaceToolApprovalCard(
    workspace: WorkspaceEntity?,
    onToolApprovalChange: (String, Boolean) -> Unit,
) {
    val overrides = workspace?.toolApprovalOverrides().orEmpty()
    val tools = workspaceToolApprovalItems()

    CardGroup(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.workspace_detail_tool_approval))
                Text(
                    text = stringResource(R.string.workspace_detail_tool_approval_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        tools.forEach { (toolName, label, icon) ->
            switchItem(
                checked = resolveWorkspaceToolApproval(toolName, overrides),
                onCheckedChange = { onToolApprovalChange(toolName, it) },
                enabled = workspace != null,
                supportingContent = {
                    Text(
                        text = toolName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { CardGroupIcon(icon) },
                headlineContent = { Text(label) },
            )
        }
    }
}

@Composable
private fun workspaceToolApprovalItems() = listOf(
    Triple("workspace_read_file", stringResource(R.string.workspace_detail_tool_read_file), HugeIcons.FileView),
    Triple("workspace_write_file", stringResource(R.string.workspace_detail_tool_write_file), HugeIcons.PencilEdit01),
    Triple("workspace_edit_file", stringResource(R.string.workspace_detail_tool_edit_file), HugeIcons.FileEdit),
    Triple("workspace_shell", stringResource(R.string.workspace_detail_tool_shell), HugeIcons.ComputerTerminal01),
)

@Composable
private fun RootfsProgress(progress: RootfsInstallProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fraction = progress.totalBytes?.takeIf { it > 0 }?.let {
            (progress.bytesRead.toFloat() / it).coerceIn(0f, 1f)
        }
        if (fraction != null && progress.stage == RootfsInstallStage.DOWNLOADING) {
            LinearWavyProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = when (progress.stage) {
                RootfsInstallStage.DOWNLOADING -> {
                    val total = progress.totalBytes?.let { " / ${it.fileSizeToString()}" }.orEmpty()
                    stringResource(R.string.workspace_detail_downloading, progress.bytesRead.fileSizeToString(), total)
                }

                RootfsInstallStage.VERIFYING -> stringResource(R.string.workspace_detail_verifying)

                RootfsInstallStage.EXTRACTING -> {
                    val entry = progress.currentEntry?.let { " · $it" }.orEmpty()
                    stringResource(R.string.workspace_detail_extracting, progress.entriesExtracted, entry)
                }

                RootfsInstallStage.INSTALLED -> stringResource(R.string.workspace_detail_install_complete)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstallRootfsDialog(
    workspace: WorkspaceEntity,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 默认地址按设备 ABI 生成: 写死某个架构的地址在其它设备上必然 404
    val abi = remember { Build.SUPPORTED_ABIS.firstOrNull().orEmpty() }
    var url by rememberSaveable(workspace.id) { mutableStateOf(RootfsDistroCatalog.defaultUrl(abi)) }
    var showDistros by rememberSaveable(workspace.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (showDistros) showDistros = false else onDismiss() },
        title = {
            Text(
                stringResource(
                    if (showDistros) {
                        R.string.workspace_rootfs_distros_title
                    } else {
                        R.string.workspace_detail_install_rootfs
                    }
                )
            )
        },
        text = {
            if (showDistros) {
                RecommendedDistroList(
                    abi = abi,
                    onPick = { picked ->
                        url = picked
                        showDistros = false
                    },
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.workspace_detail_install_rootfs_desc, workspace.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.workspace_detail_download_url)) },
                            maxLines = 5,
                        )
                        FilledTonalIconButton(
                            onClick = { showDistros = true },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.CloudDownload,
                                contentDescription = stringResource(R.string.workspace_rootfs_distros_title),
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.workspace_rootfs_distros_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (!showDistros) {
                TextButton(
                    onClick = { onConfirm(url.trim()) },
                    enabled = url.isNotBlank(),
                ) {
                    Text(stringResource(R.string.common_install))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (showDistros) showDistros = false else onDismiss() }) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/**
 * 推荐发行版列表: 点镜像标签把地址填回输入框。
 *
 * 地址不带 sha256 校验值 —— 各镜像的校验值来源不同(上游 .sha256 文件), 写死会随上游更新失效,
 * 需要强制校验时用户可自行在地址后追加 #sha256=<hex>。
 */
@Composable
private fun RecommendedDistroList(
    abi: String,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.workspace_rootfs_distros_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RootfsDistroCatalog.entries.forEach { distro ->
            RecommendedDistroCard(
                distro = distro,
                abi = abi,
                onPick = onPick,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecommendedDistroCard(
    distro: RootfsDistro,
    abi: String,
    onPick: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = distro.name,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                distro.sizeHintBytes?.let { size ->
                    Text(
                        text = stringResource(R.string.workspace_rootfs_size_hint, size.fileSizeToString()),
                        style = MaterialTheme.typography.labelMedium,
                        // 超过 100MB 用错误色提醒: 这种下载量需要用户自己判断是否值得
                        color = if (size > HEAVY_DISTRO_BYTES) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (distro.recommended) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_rootfs_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                text = stringResource(distroSummaryRes(distro.id)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(distroDescriptionRes(distro.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (RootfsDistroCatalog.archFor(distro, abi) == null) {
                Text(
                    text = stringResource(R.string.workspace_rootfs_no_image_for_arch),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    distro.sources.forEach { source ->
                        val target = RootfsDistroCatalog.urlFor(distro, source.mirror, abi)
                        if (target != null) {
                            AssistChip(
                                onClick = { onPick(target) },
                                label = { Text(stringResource(mirrorLabelRes(source.mirror))) },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                distro.links.forEach { link ->
                    TextButton(
                        onClick = { uriHandler.openUri(link.url) },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(stringResource(linkLabelRes(link.kind)))
                    }
                }
            }
        }
    }
}

private fun distroSummaryRes(id: String): Int = when (id) {
    RootfsDistroCatalog.ID_ALPINE -> R.string.workspace_rootfs_distro_alpine_summary
    RootfsDistroCatalog.ID_UBUNTU -> R.string.workspace_rootfs_distro_ubuntu_summary
    RootfsDistroCatalog.ID_DEBIAN -> R.string.workspace_rootfs_distro_debian_summary
    RootfsDistroCatalog.ID_ARCH -> R.string.workspace_rootfs_distro_arch_summary
    RootfsDistroCatalog.ID_FEDORA -> R.string.workspace_rootfs_distro_fedora_summary
    RootfsDistroCatalog.ID_NIX -> R.string.workspace_rootfs_distro_nix_summary
    else -> R.string.workspace_rootfs_distro_arch_summary
}

private fun distroDescriptionRes(id: String): Int = when (id) {
    RootfsDistroCatalog.ID_ALPINE -> R.string.workspace_rootfs_distro_alpine_description
    RootfsDistroCatalog.ID_UBUNTU -> R.string.workspace_rootfs_distro_ubuntu_description
    RootfsDistroCatalog.ID_DEBIAN -> R.string.workspace_rootfs_distro_debian_description
    RootfsDistroCatalog.ID_ARCH -> R.string.workspace_rootfs_distro_arch_description
    RootfsDistroCatalog.ID_FEDORA -> R.string.workspace_rootfs_distro_fedora_description
    RootfsDistroCatalog.ID_NIX -> R.string.workspace_rootfs_distro_nix_description
    else -> R.string.workspace_rootfs_distro_arch_description
}

private fun mirrorLabelRes(mirror: RootfsMirror): Int = when (mirror) {
    RootfsMirror.TUNA -> R.string.workspace_rootfs_source_tuna
    RootfsMirror.USTC -> R.string.workspace_rootfs_source_ustc
    RootfsMirror.ALIYUN -> R.string.workspace_rootfs_source_aliyun
    RootfsMirror.OFFICIAL -> R.string.workspace_rootfs_source_official
}

private fun linkLabelRes(kind: RootfsLinkKind): Int = when (kind) {
    RootfsLinkKind.WEBSITE -> R.string.workspace_rootfs_link_website
    RootfsLinkKind.DOCS -> R.string.workspace_rootfs_link_docs
    RootfsLinkKind.BROWSE -> R.string.workspace_rootfs_link_browse
    RootfsLinkKind.STORE -> R.string.workspace_rootfs_link_store
}

@Composable
private fun WorkspaceFilesPage(
    state: WorkspaceDetailState,
    isActive: Boolean,
    onSelectArea: (WorkspaceStorageArea) -> Unit,
    onGoUp: () -> Unit,
    onResolveImage: suspend (WorkspaceFileEntry, WorkspaceStorageArea) -> File?,
    onOpen: (WorkspaceFileEntry) -> Unit,
    onDelete: (WorkspaceFileEntry) -> Unit,
    onExport: (WorkspaceFileEntry) -> Unit,
    onBatchExport: (List<WorkspaceFileEntry>) -> Unit,
    onShare: (WorkspaceFileEntry) -> Unit,
) {
    var selecting by remember(state.area, state.path) { mutableStateOf(false) }
    var selectedPaths by remember(state.area, state.path) { mutableStateOf(emptySet<String>()) }
    val files = state.entries.filterNot { it.isDirectory }
    val selectedFiles = files.filter { it.path in selectedPaths }
    val allSelected = files.isNotEmpty() && selectedFiles.size == files.size
    fun toggleSelection(entry: WorkspaceFileEntry) {
        selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
    }
    fun exitSelection() {
        selecting = false
        selectedPaths = emptySet()
    }
    BackHandler(enabled = selecting && isActive) {
        exitSelection()
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                // 多选时底部留出悬浮工具栏的位置
                bottom = if (selecting) 128.dp else 16.dp,
            ),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    WorkspaceAreaSelector(
                        selected = state.area,
                        onSelected = onSelectArea,
                    )
                    WorkspacePathBar(
                        path = state.path,
                        canGoUp = state.path.isNotBlank(),
                        onGoUp = onGoUp,
                    )
                    if (state.exporting) {
                        ExportProgressCard(
                            completed = state.exportCompleted,
                            total = state.exportTotal,
                        )
                    }
                    state.error?.let { ErrorCard(it) }
                }
            }

            if (!state.loading && state.entries.isEmpty() && state.error == null) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_detail_empty_directory),
                        icon = HugeIcons.FolderOpen,
                    )
                }
            }

            // 文件列表排成一个分组：首尾大圆角，项与项之间留 2dp 的缝
            itemsIndexed(state.entries, key = { _, entry -> "${state.area.name}:${entry.path}" }) { index, entry ->
                WorkspaceFileItem(
                    entry = entry,
                    area = state.area,
                    shape = cardGroupItemShape(index, state.entries.size),
                    modifier = Modifier.padding(bottom = if (index == state.entries.lastIndex) 0.dp else 2.dp),
                    onResolveImage = { onResolveImage(entry, state.area) },
                    selecting = selecting,
                    selected = entry.path in selectedPaths,
                    onLongClick = {
                        selecting = true
                        selectedPaths = selectedPaths + entry.path
                    },
                    onOpen = {
                        if (selecting && !entry.isDirectory) toggleSelection(entry) else onOpen(entry)
                    },
                    onDelete = { onDelete(entry) },
                    onExport = { onExport(entry) },
                    onShare = { onShare(entry) },
                )
            }
        }

        AnimatedVisibility(
            visible = selecting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset),
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = CustomColors.floatingToolbarColors,
                leadingContent = {
                    IconButton(onClick = { exitSelection() }) {
                        Icon(HugeIcons.Cancel01, contentDescription = "取消多选")
                    }
                },
                trailingContent = {
                    IconButton(
                        onClick = {
                            selectedPaths = if (allSelected) emptySet() else files.map { it.path }.toSet()
                        },
                    ) {
                        Icon(HugeIcons.CheckList, contentDescription = if (allSelected) "取消全选" else "全选")
                    }
                },
            ) {
                Button(
                    onClick = { onBatchExport(selectedFiles) },
                    enabled = selectedFiles.isNotEmpty() && !state.exporting,
                ) {
                    Icon(
                        imageVector = HugeIcons.FileExport,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("导出 (${selectedFiles.size})")
                }
            }
        }
    }
}

@Composable
private fun WorkspaceAreaSelector(
    selected: WorkspaceStorageArea,
    onSelected: (WorkspaceStorageArea) -> Unit,
) {
    ConnectedChoiceButtons(
        options = WorkspaceStorageArea.entries,
        selected = selected,
        onSelect = onSelected,
        // 直接放在页面背景上，用更深一档的底色才分得开
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) { area, isSelected ->
        ConnectedChoiceLabel(
            text = when (area) {
                WorkspaceStorageArea.FILES -> stringResource(R.string.workspace_detail_area_files)
                WorkspaceStorageArea.LINUX -> stringResource(R.string.workspace_detail_area_rootfs)
            },
            selected = isSelected,
            icon = HugeIcons.Tick01,
        )
    }
}

@Composable
private fun WorkspacePathBar(
    path: String,
    canGoUp: Boolean,
    onGoUp: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = CircleShape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(
                enabled = canGoUp,
                onClick = onGoUp,
            ) {
                Icon(HugeIcons.ArrowTurnBackward, contentDescription = null)
            }
            Text(
                text = path.ifBlank { "/" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                // 路径太长时省略开头，当前所在的目录始终看得到
                overflow = TextOverflow.StartEllipsis,
            )
        }
    }
}

@Composable
private fun ExportProgressCard(
    completed: Int,
    total: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "正在导出 $completed/$total",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearWavyProgressIndicator(
                progress = { if (total > 0) completed.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkspaceFileItem(
    entry: WorkspaceFileEntry,
    area: WorkspaceStorageArea,
    shape: Shape,
    onResolveImage: suspend () -> File?,
    selecting: Boolean,
    selected: Boolean,
    onLongClick: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isImage = !entry.isDirectory && entry.detectFileType() == WorkspaceFileType.IMAGE
    val imageFile by produceState<File?>(
        initialValue = null,
        key1 = if (isImage) area else null,
        key2 = if (isImage) entry.path else null,
        key3 = if (isImage) "${entry.updatedAt}:${entry.sizeBytes}" else null,
    ) {
        if (isImage) {
            value = try {
                onResolveImage()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            CustomColors.listItemColors.containerColor
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (entry.isDirectory) null else onLongClick,
                onLongClickLabel = if (entry.isDirectory) null else "选择文件",
            ),
        shape = shape,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isImage -> {
                    val context = LocalContext.current
                    val imageRequest = remember(imageFile, entry.updatedAt, entry.sizeBytes) {
                        imageFile?.let {
                            ImageRequest.Builder(context)
                                .data(it)
                                .memoryCacheKey("workspace:${it.absolutePath}:${entry.updatedAt}:${entry.sizeBytes}")
                                .build()
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = HugeIcons.File02,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        imageRequest?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }

                entry.isDirectory -> CardGroupIcon(
                    icon = HugeIcons.Folder01,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // 选中时整行换成 secondaryContainer，图标底色跟着换一档，不然会融进背景
                else -> CardGroupIcon(
                    icon = HugeIcons.File02,
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.surfaceBright
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (entry.isDirectory) entry.path else "${entry.path} · ${entry.sizeBytes.fileSizeToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                // 和菜单按钮占一样大的位置，进出多选时行高不跳；点整行切换，勾选框自己不接收点击
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!entry.isDirectory) {
                        Checkbox(checked = selected, onCheckedChange = null)
                    }
                }
            } else {
                val context = LocalContext.current
                ItemActionMenu(
                    actions = buildList {
                        add(
                            ItemAction(
                                text = stringResource(R.string.workspace_detail_copy_path),
                                icon = HugeIcons.Copy01,
                                onClick = {
                                    context.writeClipboardText(entry.rootfsPath(area))
                                    // Android 13 起系统会自己弹复制提示
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                        Toast.makeText(context, R.string.workspace_detail_path_copied, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            )
                        )
                        if (!entry.isDirectory) {
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_export),
                                    icon = HugeIcons.FileExport,
                                    onClick = onExport,
                                )
                            )
                            add(
                                ItemAction(
                                    text = stringResource(R.string.common_share),
                                    icon = HugeIcons.Share08,
                                    onClick = onShare,
                                )
                            )
                        }
                        add(
                            ItemAction(
                                text = stringResource(R.string.common_delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            )
                        )
                    },
                )
            }
        }
    }
}

// 复制出去的路径要能直接给 AI 工具和终端用，所以按 Rootfs 内的绝对路径给
private fun WorkspaceFileEntry.rootfsPath(area: WorkspaceStorageArea): String = when (area) {
    WorkspaceStorageArea.FILES -> "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/$path"
    WorkspaceStorageArea.LINUX -> "/$path"
}

@Composable
private fun ErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Alert02,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
internal fun String.toShellStatusLabel(): String = when (this) {
    WorkspaceShellStatus.DISABLED.name -> stringResource(R.string.workspace_detail_shell_disabled)
    WorkspaceShellStatus.INSTALLING.name -> stringResource(R.string.workspace_detail_shell_installing)
    WorkspaceShellStatus.READY.name -> stringResource(R.string.workspace_detail_shell_ready)
    WorkspaceShellStatus.BROKEN.name -> stringResource(R.string.workspace_detail_shell_broken)
    else -> lowercase()
}

// 超过这个体积的 rootfs 在推荐列表里用错误色提示(移动网络下需要用户自己判断)
private const val HEAVY_DISTRO_BYTES = 100L * 1024 * 1024
