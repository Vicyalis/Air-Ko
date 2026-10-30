package me.rerere.rikkahub.ui.pages.backup.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.sync.terminal.TerminalRestoreStage
import me.rerere.rikkahub.data.sync.terminal.TerminalRestoreState

/**
 * 备份恢复后的终端重建全屏进度层。
 *
 * 由 RouteActivity 收集 TerminalRestoreManager.state 后调用; 不直接依赖 Koin, 方便预览和测试。
 */
@Composable
fun TerminalRestoreOverlay(
    state: TerminalRestoreState,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    if (!state.isVisible) return
    val finished = state.isFinished
    Dialog(
        onDismissRequest = { if (finished) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = finished,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = HugeIcons.ComputerTerminal01,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.terminal_backup_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))

                val percent = state.percent
                if (percent != null) {
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    text = terminalStageText(state.stage),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (state.workspaceName.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            R.string.terminal_backup_workspace,
                            state.workspaceName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (state.workspaceCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.terminal_backup_workspace_progress,
                            state.workspaceIndex,
                            state.workspaceCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.percent?.let { percent ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.terminal_backup_percent, percent),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                state.error?.let { error ->
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.terminal_backup_error, error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(28.dp))
                when {
                    state.stage == TerminalRestoreStage.FAILED -> Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onRetry != null) {
                            Button(onClick = onRetry) {
                                Text(stringResource(R.string.terminal_backup_retry))
                            }
                        }
                        if (onSkip != null) {
                            OutlinedButton(onClick = onSkip) {
                                Text(stringResource(R.string.terminal_backup_skip))
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.terminal_backup_close))
                        }
                    }

                    state.stage == TerminalRestoreStage.COMPLETED -> Button(onClick = onDismiss) {
                        Text(stringResource(R.string.terminal_backup_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun terminalStageText(stage: TerminalRestoreStage): String = when (stage) {
    TerminalRestoreStage.IDLE -> ""
    TerminalRestoreStage.PREPARING -> stringResource(R.string.terminal_backup_stage_preparing)
    TerminalRestoreStage.DOWNLOADING_ROOTFS ->
        stringResource(R.string.terminal_backup_stage_downloading_rootfs)

    TerminalRestoreStage.INSTALLING_DESKTOP ->
        stringResource(R.string.terminal_backup_stage_installing_desktop)

    TerminalRestoreStage.RESTORING_FILES ->
        stringResource(R.string.terminal_backup_stage_restoring_files)

    TerminalRestoreStage.COMPLETED -> stringResource(R.string.terminal_backup_stage_completed)
    TerminalRestoreStage.FAILED -> stringResource(R.string.terminal_backup_stage_failed)
}
