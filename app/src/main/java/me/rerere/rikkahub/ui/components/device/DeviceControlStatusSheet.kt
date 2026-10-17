package me.rerere.rikkahub.ui.components.device

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.R
import me.rerere.rikkahub.device.DeviceControlLevel
import me.rerere.rikkahub.device.DeviceControlStatus
import me.rerere.rikkahub.device.DeviceControlWatchdog
import me.rerere.rikkahub.device.ShizukuInfo
import me.rerere.rikkahub.device.ShizukuState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.utils.openAccessibilitySettings

/** 三色灯配色：绿=可用、橙=已授权但没连上、红=没授权。 */
@Composable
fun DeviceControlLevel.indicatorColor(): Color = when (this) {
    DeviceControlLevel.Ready -> Color(0xFF43A047)
    DeviceControlLevel.Stale -> Color(0xFFFB8C00)
    DeviceControlLevel.Disabled -> MaterialTheme.colorScheme.error
}

/**
 * 对话页顶栏的三色灯。
 *
 * 只在助手启用了「设备控制」时出现（由调用方判断）；点开是状态卡。
 */
@Composable
fun DeviceControlStatusDot(
    level: DeviceControlLevel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(level.indicatorColor(), CircleShape),
        )
    }
}

/** 点三色灯弹出的状态卡：状态 + 一键去授权 + 开关 + 设置入口。 */
@Composable
fun DeviceControlStatusSheet(
    status: DeviceControlStatus,
    autoApprove: Boolean,
    showIndicator: Boolean,
    onAutoApproveChange: (Boolean) -> Unit,
    onShowIndicatorChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val watchdog = DeviceControlWatchdog.snapshot(context)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(status.level.indicatorColor(), CircleShape),
                )
                Column {
                    Text(
                        text = stringResource(R.string.device_control_status_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            when (status.level) {
                                DeviceControlLevel.Ready -> R.string.device_control_status_ready
                                DeviceControlLevel.Stale -> R.string.device_control_status_stale
                                DeviceControlLevel.Disabled -> R.string.device_control_status_disabled
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            CardGroup {
                item(
                    headlineContent = { Text(stringResource(R.string.device_control_status_accessibility)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when (status.level) {
                                    DeviceControlLevel.Ready -> R.string.device_control_status_ready_short
                                    DeviceControlLevel.Stale -> R.string.device_control_status_stale_short
                                    DeviceControlLevel.Disabled -> R.string.device_control_status_disabled_short
                                }
                            )
                        )
                    },
                    trailingContent = if (status.connected) {
                        null
                    } else {
                        {
                            TextButton(onClick = { context.openAccessibilitySettings() }) {
                                Text(stringResource(R.string.device_control_status_open_settings))
                            }
                        }
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.device_control_status_screenshot)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (status.screenshotSupported) {
                                    R.string.device_control_status_screenshot_ready
                                } else {
                                    R.string.device_control_status_screenshot_unavailable
                                }
                            )
                        )
                    },
                )
                item(
                    headlineContent = { Text(stringResource(R.string.device_control_status_watchdog)) },
                    supportingContent = {
                        Text(
                            if (watchdog.paused) {
                                stringResource(R.string.device_control_status_watchdog_paused)
                            } else {
                                watchdogNoteText(context, watchdog.note)
                            }
                        )
                    },
                )
            }

            CardGroup {
                switchItem(
                    checked = autoApprove,
                    onCheckedChange = onAutoApproveChange,
                    headlineContent = { Text(stringResource(R.string.device_control_status_auto_approve)) },
                    supportingContent = { Text(stringResource(R.string.device_control_status_auto_approve_desc)) },
                )
                switchItem(
                    checked = showIndicator,
                    onCheckedChange = onShowIndicatorChange,
                    headlineContent = { Text(stringResource(R.string.device_control_status_show_indicator)) },
                    supportingContent = { Text(stringResource(R.string.device_control_status_show_indicator_desc)) },
                )
                if (onOpenSettings != null) {
                    item(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        },
                        headlineContent = { Text(stringResource(R.string.device_control_status_more)) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.device_control_status_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 看门狗结论 → 本地化文本（状态卡与设置页共用）。 */
fun watchdogNoteText(context: Context, note: DeviceControlWatchdog.WatchdogNote): String =
    context.getString(
        when (note) {
            DeviceControlWatchdog.WatchdogNote.NotChecked -> R.string.setting_page_device_control_note_not_checked
            DeviceControlWatchdog.WatchdogNote.Off -> R.string.setting_page_device_control_note_off
            DeviceControlWatchdog.WatchdogNote.InList -> R.string.setting_page_device_control_note_in_list
            DeviceControlWatchdog.WatchdogNote.InListNoConn -> R.string.setting_page_device_control_note_in_list_no_conn
            DeviceControlWatchdog.WatchdogNote.RepairedLocal -> R.string.setting_page_device_control_note_repaired
            DeviceControlWatchdog.WatchdogNote.RepairFailed -> R.string.setting_page_device_control_note_repair_failed
            DeviceControlWatchdog.WatchdogNote.NotAuthorized -> R.string.setting_page_device_control_note_not_authorized
            DeviceControlWatchdog.WatchdogNote.NoRead -> R.string.setting_page_device_control_note_no_read
        }
    )

/** 手动修复结论 → 本地化文本（设置页用）。 */
fun repairResultText(context: Context, result: DeviceControlWatchdog.RepairResult): String =
    context.getString(
        when (result) {
            DeviceControlWatchdog.RepairResult.FixedNow -> R.string.setting_page_device_control_repair_fixed
            DeviceControlWatchdog.RepairResult.WroteNoConn -> R.string.setting_page_device_control_repair_wrote
            DeviceControlWatchdog.RepairResult.WriteFailed -> R.string.setting_page_device_control_repair_write_failed
            DeviceControlWatchdog.RepairResult.NoRead -> R.string.setting_page_device_control_repair_no_read
            DeviceControlWatchdog.RepairResult.TooSoon -> R.string.setting_page_device_control_repair_too_soon
            DeviceControlWatchdog.RepairResult.NotAuthorized -> R.string.setting_page_device_control_note_not_authorized
        }
    )

/** Shizuku 状态 → 本地化文本（设置页与 onboarding 共用）。 */
fun shizukuStateText(context: Context, info: ShizukuInfo): String = when (info.state) {
    ShizukuState.NotRunning -> context.getString(R.string.setting_page_device_control_shizuku_not_running)
    ShizukuState.PermissionNeeded -> context.getString(R.string.setting_page_device_control_shizuku_need_permission)
    ShizukuState.Unsupported -> context.getString(R.string.setting_page_device_control_shizuku_unsupported)
    ShizukuState.Ready -> context.getString(
        R.string.setting_page_device_control_shizuku_ready,
        info.version,
        if (info.isRoot) "root" else "shell",
    )
}
