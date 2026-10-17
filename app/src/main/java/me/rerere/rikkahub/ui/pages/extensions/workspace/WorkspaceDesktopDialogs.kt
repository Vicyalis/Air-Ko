package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.workspace.WorkspaceDesktop
import me.rerere.workspace.WorkspaceDesktopCatalog
import me.rerere.workspace.WorkspaceDesktopStatus
import org.koin.compose.koinInject

@Composable
internal fun desktopSchemeLabel(desktop: WorkspaceDesktop): String = stringResource(
    when (desktop) {
        WorkspaceDesktop.NONE -> R.string.wp5_desktop_scheme_none
        WorkspaceDesktop.CODE_SERVER -> R.string.wp5_desktop_scheme_code_server
        WorkspaceDesktop.NOVNC -> R.string.wp5_desktop_scheme_novnc
        WorkspaceDesktop.JUPYTER -> R.string.wp5_desktop_scheme_jupyter
        WorkspaceDesktop.GNOME -> R.string.wp5_desktop_scheme_gnome
        WorkspaceDesktop.NIRI -> R.string.wp5_desktop_scheme_niri
        WorkspaceDesktop.CUSTOM -> R.string.wp5_desktop_scheme_custom
    }
)

@Composable
internal fun desktopSchemeSummary(desktop: WorkspaceDesktop): String = stringResource(
    when (desktop) {
        WorkspaceDesktop.NONE -> R.string.wp5_desktop_scheme_summary_none
        WorkspaceDesktop.CODE_SERVER -> R.string.wp5_desktop_scheme_summary_code_server
        WorkspaceDesktop.NOVNC -> R.string.wp5_desktop_scheme_summary_novnc
        WorkspaceDesktop.JUPYTER -> R.string.wp5_desktop_scheme_summary_jupyter
        WorkspaceDesktop.GNOME -> R.string.wp5_desktop_scheme_summary_gnome
        WorkspaceDesktop.NIRI -> R.string.wp5_desktop_scheme_summary_niri
        WorkspaceDesktop.CUSTOM -> R.string.wp5_desktop_scheme_summary_custom
    }
)

@Composable
internal fun desktopBusyLabel(action: WorkspaceDesktopAction): String = stringResource(
    when (action) {
        WorkspaceDesktopAction.INSTALL -> R.string.wp5_desktop_busy_install
        WorkspaceDesktopAction.START -> R.string.wp5_desktop_busy_start
        WorkspaceDesktopAction.STOP -> R.string.wp5_desktop_busy_stop
    }
)

@Composable
internal fun desktopStatusLabel(status: WorkspaceDesktopStatus): String = stringResource(
    when (status) {
        WorkspaceDesktopStatus.NOT_CONFIGURED -> R.string.wp5_desktop_status_not_configured
        WorkspaceDesktopStatus.ROOTFS_MISSING -> R.string.wp5_desktop_status_rootfs_missing
        WorkspaceDesktopStatus.NOT_INSTALLED -> R.string.wp5_desktop_status_not_installed
        WorkspaceDesktopStatus.INSTALLED -> R.string.wp5_desktop_status_installed
        WorkspaceDesktopStatus.RUNNING -> R.string.wp5_desktop_status_running
    }
)

/** 方案在安装前就让用户看到的代价: 下载体积 + 大致耗时 */
@Composable
internal fun desktopEstimateText(desktop: WorkspaceDesktop): String? {
    val size = WorkspaceDesktopCatalog.sizeHintBytes(desktop) ?: return null
    val minutes = WorkspaceDesktopCatalog.installMinutes(desktop) ?: return null
    return stringResource(R.string.wp5_desktop_estimate, size.fileSizeToString(), minutes)
}

/**
 * 桌面安装的下载进度: 下载脚本每秒把「已下载 / 总量」写进安装日志, App 解析出的最后一行显示成百分比;
 * 服务器没给 Content-Length 时退化成不确定进度条 + 只报已下载量。
 */
@Composable
internal fun DesktopDownloadProgress(
    progress: WorkspaceDesktopDownloadProgress,
    modifier: Modifier = Modifier,
) {
    val percent = progress.percent
    val done = progress.doneBytes.fileSizeToString()
    val label = if (percent != null) {
        stringResource(
            R.string.wp5_desktop_download_progress,
            percent,
            done,
            progress.totalBytes.fileSizeToString(),
        )
    } else {
        stringResource(R.string.wp5_desktop_download_progress_unknown, done)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun desktopFailureMessage(failure: WorkspaceDesktopFailure): String = when (failure.kind) {
    WorkspaceDesktopFailure.Kind.ROOTFS_MISSING ->
        stringResource(R.string.wp5_desktop_failure_rootfs_missing)

    WorkspaceDesktopFailure.Kind.NOT_CONFIGURED ->
        stringResource(R.string.wp5_desktop_failure_not_configured)

    WorkspaceDesktopFailure.Kind.INSTALL_FAILED -> if (failure.timedOut) {
        stringResource(R.string.wp5_desktop_failure_install_timeout)
    } else {
        stringResource(R.string.wp5_desktop_failure_install_failed, failure.exitCode ?: -1)
    }

    WorkspaceDesktopFailure.Kind.PROBE_FAILED ->
        stringResource(R.string.wp5_desktop_failure_probe_failed)

    WorkspaceDesktopFailure.Kind.START_FAILED ->
        stringResource(R.string.wp5_desktop_failure_start_failed, failure.port)

    WorkspaceDesktopFailure.Kind.STOP_FAILED ->
        stringResource(R.string.wp5_desktop_failure_stop_failed, failure.port)

    WorkspaceDesktopFailure.Kind.COMMAND_FAILED ->
        stringResource(R.string.wp5_desktop_failure_command_failed)
}

/**
 * 桌面操作的失败弹窗: 一句人话 + 脚本退出码/日志尾巴, 让用户不用再翻终端。
 */
@Composable
internal fun DesktopFailureDialog(
    failure: WorkspaceDesktopFailure?,
    onDismiss: () -> Unit,
) {
    if (failure == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HugeIcons.Alert02, contentDescription = null) },
        title = { Text(stringResource(R.string.wp5_desktop_failure_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(desktopFailureMessage(failure))
                failure.logTail?.takeIf { it.isNotBlank() }?.let { tail ->
                    Text(
                        text = stringResource(R.string.wp5_desktop_failure_log_tail),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                    LogTailBox(tail)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}

/** 等宽日志块，新日志追加在末尾所以自动贴底 */
@Composable
internal fun LogTailBox(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        val scrollState = rememberScrollState()
        LaunchedEffect(text) {
            scrollState.scrollTo(Int.MAX_VALUE)
        }
        Text(
            text = text,
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * 桌面方案选择器。
 *
 * 创建与编辑工作区共用: 选中方案后才让用户填自定义命令/端口, 顺便把体积与耗时摊开讲清楚。
 */
@Composable
internal fun DesktopSchemePickerDialog(
    initialDesktop: WorkspaceDesktop,
    initialCommand: String,
    initialPort: Int,
    onDismiss: () -> Unit,
    onConfirm: (WorkspaceDesktop, String, Int) -> Unit,
) {
    var desktop by rememberSaveable { mutableStateOf(initialDesktop) }
    var command by rememberSaveable { mutableStateOf(initialCommand) }
    var portText by rememberSaveable { mutableStateOf(initialPort.takeIf { it > 0 }?.toString().orEmpty()) }

    val parsedPort = portText.trim().toIntOrNull()
    val portInvalid = portText.isNotBlank() && (parsedPort == null || !WorkspaceDesktopCatalog.isValidPort(parsedPort))
    val commandMissing = desktop == WorkspaceDesktop.CUSTOM && command.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wp5_desktop_picker_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WorkspaceDesktop.entries.forEach { entry ->
                    DesktopSchemeOption(
                        desktop = entry,
                        selected = desktop == entry,
                        onSelect = { desktop = entry },
                    )
                }

                if (desktop == WorkspaceDesktop.CUSTOM) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.wp5_desktop_custom_command_label)) },
                        placeholder = { Text(stringResource(R.string.wp5_desktop_custom_command_placeholder)) },
                        singleLine = true,
                        isError = commandMissing,
                        supportingText = if (commandMissing) {
                            { Text(stringResource(R.string.wp5_desktop_custom_command_required)) }
                        } else null,
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.wp5_desktop_custom_port_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = portInvalid,
                        supportingText = if (portInvalid) {
                            { Text(stringResource(R.string.wp5_desktop_custom_port_error)) }
                        } else null,
                    )
                }

                desktopEstimateText(desktop)?.let { estimate ->
                    Text(
                        text = estimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(desktop, command.trim(), parsedPort ?: 0) },
                enabled = !portInvalid && !commandMissing,
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

@Composable
private fun DesktopSchemeOption(
    desktop: WorkspaceDesktop,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = desktopSchemeLabel(desktop),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = desktopSchemeSummary(desktop),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 日志弹窗: 只读 rootfs 内某个日志文件的尾部, 带一个刷新按钮。
 *
 * 日志文件可能有几十 MB, 所以直接读尾巴, 不做全量加载。
 */
@Composable
internal fun DesktopLogDialog(
    workspaceId: String,
    title: String,
    path: String,
    onDismiss: () -> Unit,
) {
    val repository: WorkspaceRepository = koinInject()
    var text by remember(workspaceId, path) { mutableStateOf<String?>(null) }
    var loading by remember(workspaceId, path) { mutableStateOf(true) }
    var reloadToken by remember { mutableStateOf(0) }

    LaunchedEffect(workspaceId, path, reloadToken) {
        loading = true
        text = runCatching { repository.readRootfsLogTail(workspaceId, path) }.getOrNull()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.wp5_desktop_log_path, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val body = text
                when {
                    loading -> Text(stringResource(R.string.wp5_desktop_log_loading))
                    body.isNullOrBlank() -> Text(stringResource(R.string.wp5_desktop_log_empty))
                    else -> LogTailBox(body)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { reloadToken++ }) {
                Text(stringResource(R.string.wp5_desktop_page_reload))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    )
}
