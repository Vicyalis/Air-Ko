package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ComputerDesk01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FolderCode
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import me.rerere.workspace.WorkspaceDesktop
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun WorkspacePage(vm: WorkspaceVM = koinViewModel()) {
    val navController = LocalNavController.current
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var showTerminalSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.workspace_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { showTerminalSettings = true }) {
                        Icon(
                            HugeIcons.Settings03,
                            contentDescription = stringResource(R.string.workspace_terminal_settings),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(HugeIcons.Add01, contentDescription = null)
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (workspaces.isEmpty()) {
                item {
                    ExtensionEmptyState(
                        message = stringResource(R.string.workspace_page_empty),
                        description = stringResource(R.string.workspace_page_empty_desc),
                        icon = HugeIcons.FolderCode,
                    )
                }
            }

            items(workspaces, key = { it.id }) { workspace ->
                WorkspaceItem(
                    workspace = workspace,
                    onRename = { editTarget = workspace },
                    onDelete = { deleteTarget = workspace },
                    onOpen = { navController.navigate(Screen.WorkspaceDetail(workspace.id)) },
                )
            }
        }
    }

    if (showAddDialog) {
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_create),
            initialName = "",
            existingNames = workspaces.map { it.name.trim() }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { name, desktop, command, port ->
                vm.create(name, desktop, command, port)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { workspace ->
        EditWorkspaceDialog(
            title = stringResource(R.string.workspace_page_rename),
            initialName = workspace.name,
            existingNames = workspaces.filter { it.id != workspace.id }.map { it.name.trim() }.toSet(),
            initialDesktop = workspace.desktopScheme(),
            initialCommand = workspace.desktopCustomCommand,
            initialPort = workspace.desktopCustomPort,
            onDismiss = { editTarget = null },
            onConfirm = { name, desktop, command, port ->
                vm.updateWorkspace(workspace, name, desktop, command, port)
                editTarget = null
            },
        )
    }

    RikkaConfirmDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.workspace_page_delete),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleteTarget?.let { vm.delete(it) }
            deleteTarget = null
        },
        onDismiss = { deleteTarget = null },
    ) {
        Text(stringResource(R.string.workspace_page_delete_confirm))
    }

    if (showTerminalSettings) {
        WorkspaceTerminalSettingsSheet(onDismiss = { showTerminalSettings = false })
    }
}

/**
 * 终端设置。
 *
 * 预热会话数是常驻 shell 热池大小：开着的话新标签页和下一条 AI 命令省掉 1~3 秒冷启动，
 * 代价是每个会话常驻一份 rootfs 进程。0 关闭。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceTerminalSettingsSheet(onDismiss: () -> Unit) {
    val settingsStore: SettingsStore = koinInject()
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val displaySetting = settings.displaySetting
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    fun updateDisplay(transform: (DisplaySetting) -> DisplaySetting) {
        scope.launch {
            settingsStore.update { it.copy(displaySetting = transform(it.displaySetting)) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.workspace_terminal_settings),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.workspace_terminal_settings_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardGroup {
                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.workspace_terminal_settings_prewarm_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.workspace_terminal_settings_prewarm_desc))
                    },
                    trailingContent = { CardGroupValue("${settings.terminalPrewarmSessions}") },
                ) {
                    Slider(
                        value = settings.terminalPrewarmSessions.toFloat(),
                        onValueChange = { value ->
                            val sessions = value.toInt().coerceIn(0, 4)
                            scope.launch {
                                settingsStore.update { it.copy(terminalPrewarmSessions = sessions) }
                            }
                        },
                        valueRange = 0f..4f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                formItem(
                    headlineContent = {
                        Text(stringResource(R.string.workspace_terminal_settings_idle_title))
                    },
                    trailingContent = {
                        CardGroupValue(
                            stringResource(
                                R.string.workspace_terminal_settings_idle_value,
                                settings.terminalPrewarmIdleMinutes,
                            )
                        )
                    },
                ) {
                    Slider(
                        value = settings.terminalPrewarmIdleMinutes.toFloat(),
                        onValueChange = { value ->
                            val minutes = value.toInt().coerceIn(1, 60)
                            scope.launch {
                                settingsStore.update { it.copy(terminalPrewarmIdleMinutes = minutes) }
                            }
                        },
                        valueRange = 1f..60f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                switchItem(
                    checked = displaySetting.terminalPinAiSession,
                    onCheckedChange = { checked ->
                        updateDisplay { it.copy(terminalPinAiSession = checked) }
                    },
                    headlineContent = {
                        Text(stringResource(R.string.workspace_terminal_settings_pin_ai_title))
                    },
                    supportingContent = {
                        Text(stringResource(R.string.workspace_terminal_settings_pin_ai_desc))
                    },
                )
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        }
    }
}

@Composable
private fun WorkspaceItem(
    workspace: WorkspaceEntity,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(HugeIcons.FolderCode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = workspace.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = workspace.shellStatus.toShellStatusLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.common_rename),
                        icon = HugeIcons.PencilEdit01,
                        onClick = onRename,
                    ),
                    ItemAction(
                        text = stringResource(R.string.common_delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = onDelete,
                    ),
                )
            )
        }
    }
}

@Composable
private fun EditWorkspaceDialog(
    title: String,
    initialName: String,
    existingNames: Set<String>,
    initialDesktop: WorkspaceDesktop = WorkspaceDesktop.NONE,
    initialCommand: String = "",
    initialPort: Int = 0,
    onDismiss: () -> Unit,
    onConfirm: (name: String, desktop: WorkspaceDesktop, command: String, port: Int) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var desktop by rememberSaveable(initialDesktop) { mutableStateOf(initialDesktop) }
    var command by rememberSaveable(initialCommand) { mutableStateOf(initialCommand) }
    var port by rememberSaveable(initialPort) { mutableStateOf(initialPort) }
    var showDesktopPicker by rememberSaveable { mutableStateOf(false) }
    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && trimmedName in existingNames

    // 选方案占满弹窗, 选完再回到这里: 两层 AlertDialog 叠着只会互相抢焦点
    if (showDesktopPicker) {
        DesktopSchemePickerDialog(
            initialDesktop = desktop,
            initialCommand = command,
            initialPort = port,
            onDismiss = { showDesktopPicker = false },
            onConfirm = { picked, pickedCommand, pickedPort ->
                desktop = picked
                command = pickedCommand
                port = pickedPort
                showDesktopPicker = false
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.workspace_page_name)) },
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = if (isDuplicate) {
                        { Text(stringResource(R.string.workspace_page_name_duplicate)) }
                    } else null,
                )
                WorkspaceDesktopEditRow(
                    scheme = desktop,
                    onClick = { showDesktopPicker = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmedName, desktop, command, port) },
                enabled = name.isNotBlank() && !isDuplicate,
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

/** 创建/编辑弹窗里的桌面方案入口: 点开是方案选择器 */
@Composable
private fun WorkspaceDesktopEditRow(
    scheme: WorkspaceDesktop,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(HugeIcons.ComputerDesk01, contentDescription = null)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.wp5_desktop_row_scheme),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = desktopSchemeSummary(scheme),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                desktopEstimateText(scheme)?.let { estimate ->
                    Text(
                        text = estimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = desktopSchemeLabel(scheme),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
