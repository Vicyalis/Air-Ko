package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.remember
import me.rerere.rikkahub.device.WorkspaceDesktopPrefs
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.ComputerDesk01
import me.rerere.hugeicons.stroke.MonitorDot
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceDesktopCatalog
import me.rerere.workspace.WorkspaceDesktopStatus
import org.koin.compose.koinInject

/**
 * 工作区详情页的「工作区桌面」卡片: 方案选择 + 安装 / 启动 / 停止 / 打开 / 日志。
 *
 * 状态来自 App 级 [WorkspaceDesktopManager], 因此安装过程中切走页面、切回来, 进度和日志都还在。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkspaceDesktopCard(
    workspace: WorkspaceEntity?,
    onDesktopChange: (WorkspaceDesktop, String, Int) -> Unit,
) {
    val manager: WorkspaceDesktopManager = koinInject()
    val navController = LocalNavController.current
    val workspaceId = workspace?.id.orEmpty()
    val state by manager.observe(workspaceId).collectAsStateWithLifecycle(
        initialValue = WorkspaceDesktopState(workspaceId = workspaceId),
    )
    val scheme = workspace?.desktopScheme() ?: WorkspaceDesktop.NONE
    val plan = state.plan
    val busy = state.busy
    val download = state.download

    var showPicker by rememberSaveable(workspaceId) { mutableStateOf(false) }
    var logPath by rememberSaveable(workspaceId) { mutableStateOf<String?>(null) }

    // 进入页面探一次; 改了方案 (实体的三个字段变化) 再探一次
    LaunchedEffect(
        workspaceId,
        workspace?.desktop,
        workspace?.desktopCustomCommand,
        workspace?.desktopCustomPort,
    ) {
        if (workspaceId.isNotEmpty()) manager.refresh(workspaceId)
    }

    CardGroup(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.wp5_desktop_card_title))
                Text(
                    text = stringResource(R.string.wp5_desktop_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        item(
            onClick = if (workspace != null) {
                { showPicker = true }
            } else null,
            headlineContent = { Text(stringResource(R.string.wp5_desktop_row_scheme)) },
            supportingContent = { Text(desktopSchemeSummary(scheme)) },
            leadingContent = { CardGroupIcon(HugeIcons.ComputerDesk01) },
            trailingContent = { CardGroupValue(desktopSchemeLabel(scheme)) },
        )

        item(
            headlineContent = { Text(stringResource(R.string.wp5_desktop_row_status)) },
            supportingContent = {
                Text(
                    when {
                        scheme == WorkspaceDesktop.NONE ->
                            stringResource(R.string.wp5_desktop_scheme_summary_none)

                        !state.loaded -> stringResource(R.string.wp5_desktop_probing)
                        else -> desktopEstimateText(scheme)
                            ?: desktopSchemeSummary(scheme)
                    }
                )
            },
            leadingContent = { CardGroupIcon(HugeIcons.MonitorDot) },
            trailingContent = { DesktopStatusBadge(state.status) },
        )

        formItem {
            if (scheme == WorkspaceDesktop.NONE) {
                Text(
                    text = stringResource(R.string.wp5_desktop_scheme_summary_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (busy != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(desktopBusyLabel(busy), style = MaterialTheme.typography.bodyMedium)
                        }
                        // 安装/启动期间给一条进度条（日志尾巴里能看到具体在做什么）;
                        // 下载阶段脚本会报「已下载 / 总量」, 这时把百分比也显示出来
                        if (busy == WorkspaceDesktopAction.INSTALL && download != null) {
                            DesktopDownloadProgress(progress = download, modifier = Modifier.fillMaxWidth())
                        } else if (busy == WorkspaceDesktopAction.INSTALL || busy == WorkspaceDesktopAction.START) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }

                val incomplete = plan == null || (plan.startScript == null && plan.installScript == null)
                if (incomplete) {
                    // 自定义方案没填命令时, 除了去改配置没有别的可做
                    Text(
                        text = stringResource(R.string.wp5_desktop_card_incomplete),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (plan?.installScript != null) {
                        if (busy == WorkspaceDesktopAction.INSTALL) {
                            FilledTonalButton(onClick = { manager.cancelInstall(workspaceId) }) {
                                Text(stringResource(R.string.wp5_desktop_action_cancel))
                            }
                        } else if (state.installed != true && state.rootfsReady) {
                            Button(
                                onClick = { manager.install(workspaceId) },
                                enabled = busy == null,
                            ) {
                                Icon(
                                    imageVector = HugeIcons.Download01,
                                    contentDescription = null,
                                    modifier = Modifier.size(ButtonDefaults.IconSize),
                                )
                                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.wp5_desktop_action_install))
                            }
                        }
                    }

                    if (busy == null && state.rootfsReady && state.installed != false &&
                        plan?.startScript != null
                    ) {
                        if (state.running) {
                            FilledTonalButton(onClick = { manager.stop(workspaceId) }) {
                                Text(stringResource(R.string.wp5_desktop_action_stop))
                            }
                        } else {
                            Button(onClick = { manager.start(workspaceId) }) {
                                Text(stringResource(R.string.wp5_desktop_action_start))
                            }
                        }
                    }

                    if (state.running && plan?.url != null) {
                        FilledTonalButton(onClick = { navController.navigate(Screen.WorkspaceDesktop(workspaceId)) }) {
                            Text(stringResource(R.string.wp5_desktop_action_open))
                        }
                    }

                    TextButton(onClick = { logPath = WorkspaceDesktopCatalog.LOG_PATH }) {
                        Text(stringResource(R.string.wp5_desktop_action_log))
                    }
                    if (state.logTail != null) {
                        TextButton(onClick = { logPath = WorkspaceDesktopCatalog.INSTALL_LOG_PATH }) {
                            Text(stringResource(R.string.wp5_desktop_action_install_log))
                        }
                    }
                }

                // 装之前先把代价摊开: 体积与耗时估计
                if (plan?.installScript != null && state.installed != true) {
                    desktopEstimateText(scheme)?.let { estimate ->
                        Text(
                            text = estimate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 还没装发行版时按钮不会出现，这里明确告诉用户下一步做什么
                if (state.loaded && !state.rootfsReady) {
                    Text(
                        text = stringResource(R.string.wp5_desktop_need_rootfs_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 下载加速（国内访问 GitHub 慢/不通时用）：装桌面之前先设好
                val context = androidx.compose.ui.platform.LocalContext.current
                var mirrorText by remember(workspaceId) {
                    mutableStateOf(WorkspaceDesktopPrefs.downloadMirror(context))
                }
                var versionText by remember(workspaceId) {
                    mutableStateOf(WorkspaceDesktopPrefs.codeServerVersion(context))
                }
                Text(
                    text = stringResource(R.string.wp5_desktop_download_options_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                OutlinedTextField(
                    value = mirrorText,
                    onValueChange = {
                        mirrorText = it
                        WorkspaceDesktopPrefs.setDownloadMirror(context, it)
                    },
                    label = { Text(stringResource(R.string.wp5_desktop_download_mirror)) },
                    placeholder = { Text("https://gh-proxy.com/") },
                    supportingText = { Text(stringResource(R.string.wp5_desktop_download_mirror_desc)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = versionText,
                    onValueChange = {
                        versionText = it
                        WorkspaceDesktopPrefs.setCodeServerVersion(context, it)
                    },
                    label = { Text(stringResource(R.string.wp5_desktop_code_server_version)) },
                    placeholder = { Text(WorkspaceDesktopCatalog.PINNED_CODE_SERVER_VERSION) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 安装进行中: 实时日志尾巴 + 取消按钮 (取消按钮在上面的按钮行里)
                if (busy == WorkspaceDesktopAction.INSTALL) {
                    state.logTail?.takeIf { it.isNotBlank() }?.let { tail ->
                        Text(
                            text = stringResource(
                                R.string.wp5_desktop_log_path,
                                WorkspaceDesktopCatalog.INSTALL_LOG_PATH,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LogTailBox(tail, maxHeight = 200.dp)
                    }
                }
            }
        }
    }

    if (showPicker) {
        DesktopSchemePickerDialog(
            initialDesktop = scheme,
            initialCommand = workspace?.desktopCustomCommand.orEmpty(),
            initialPort = workspace?.desktopCustomPort ?: 0,
            onDismiss = { showPicker = false },
            onConfirm = { desktop, command, port ->
                onDesktopChange(desktop, command, port)
                showPicker = false
            },
        )
    }

    logPath?.let { path ->
        DesktopLogDialog(
            workspaceId = workspaceId,
            title = if (path == WorkspaceDesktopCatalog.INSTALL_LOG_PATH) {
                stringResource(R.string.wp5_desktop_log_install_title)
            } else {
                stringResource(R.string.wp5_desktop_log_title)
            },
            path = path,
            onDismiss = { logPath = null },
        )
    }

    DesktopFailureDialog(
        failure = state.failure,
        onDismiss = { manager.dismissFailure(workspaceId) },
    )
}

@Composable
private fun DesktopStatusBadge(status: WorkspaceDesktopStatus) {
    val colorScheme = MaterialTheme.colorScheme
    val (containerColor, contentColor) = when (status) {
        WorkspaceDesktopStatus.RUNNING -> colorScheme.primaryContainer to colorScheme.onPrimaryContainer
        WorkspaceDesktopStatus.NOT_INSTALLED,
        WorkspaceDesktopStatus.ROOTFS_MISSING,
        -> colorScheme.errorContainer to colorScheme.onErrorContainer

        else -> colorScheme.secondaryContainer to colorScheme.onSecondaryContainer
    }
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text = desktopStatusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}
