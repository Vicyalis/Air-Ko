package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.device.DeviceControlAudit
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.ui.toast.ToastType

/**
 * 设备控制·审计日志查看页：按天文件里的最近记录（倒序展示，最新在最上面）。
 *
 * 每条一行的格式由 DeviceControlAudit 保证（参数与结果都单行化、截断），所以这里直接等宽展示。
 */
@Composable
fun SettingDeviceControlAuditPage() {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val clipboard = LocalClipboardManager.current
    var lines by remember { mutableStateOf(DeviceControlAudit.recentLines(context, 400)) }
    var showClearConfirm by remember { mutableStateOf(false) }

    fun reload() {
        lines = DeviceControlAudit.recentLines(context, 400)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_device_control_audit_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(HugeIcons.Refresh, contentDescription = stringResource(R.string.setting_page_device_control_audit_refresh))
                    }
                    IconButton(
                        onClick = {
                            if (lines.isNotEmpty()) {
                                clipboard.setText(AnnotatedString(lines.asReversed().joinToString("\n")))
                                toaster.show(
                                    message = context.getString(R.string.setting_page_device_control_audit_copied),
                                    type = ToastType.Success,
                                )
                            }
                        },
                    ) {
                        Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.setting_page_device_control_audit_copy))
                    }
                    IconButton(
                        onClick = {
                            if (lines.isNotEmpty()) showClearConfirm = true
                        },
                    ) {
                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_page_device_control_audit_clear))
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (lines.isEmpty()) {
            Text(
                text = stringResource(R.string.setting_page_device_control_audit_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(innerPadding).padding(horizontal = 20.dp, vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + 32.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(lines.asReversed()) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.setting_page_device_control_audit_clear)) },
            text = { Text(stringResource(R.string.setting_page_device_control_audit_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        DeviceControlAudit.clear(context)
                        reload()
                        showClearConfirm = false
                        toaster.show(
                            message = context.getString(R.string.setting_page_device_control_audit_cleared),
                            type = ToastType.Success,
                        )
                    },
                ) {
                    Text(stringResource(R.string.setting_page_device_control_audit_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
