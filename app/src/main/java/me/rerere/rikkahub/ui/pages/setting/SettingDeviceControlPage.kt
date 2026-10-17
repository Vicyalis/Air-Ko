package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import me.rerere.hugeicons.stroke.ChevronDown
import me.rerere.rikkahub.data.ai.tools.local.DEVICE_READ_ONLY_TOOLS
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryFull
import me.rerere.hugeicons.stroke.Clipboard
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.RefreshCw
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.ShieldKey
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.device.DeviceControlLevel
import me.rerere.rikkahub.device.DeviceControlMemGuard
import me.rerere.rikkahub.device.DeviceControlPrefs
import me.rerere.rikkahub.device.DeviceControlState
import me.rerere.rikkahub.device.DeviceControlWatchdog
import me.rerere.rikkahub.device.DeviceDownloader
import me.rerere.rikkahub.device.DeviceSelfTestResult
import me.rerere.rikkahub.device.deviceAppLabel
import me.rerere.rikkahub.device.runActSelfTest
import me.rerere.rikkahub.ui.components.device.repairResultText
import me.rerere.rikkahub.ui.components.device.shizukuStateText
import me.rerere.rikkahub.ui.components.device.watchdogNoteText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.openAccessibilitySettings
import androidx.compose.runtime.DisposableEffect
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.data.ai.tools.local.DeviceControlTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.device.ShizukuInfo
import me.rerere.rikkahub.device.ShizukuState
import me.rerere.rikkahub.device.ShizukuSupport
import me.rerere.rikkahub.utils.openUrl
import org.koin.compose.koinInject
import rikka.shizuku.Shizuku
import me.rerere.ui.toast.ToasterState
import me.rerere.ui.toast.ToastType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备控制设置页：连接、保活、自检、扩展权限与审计入口都在这里。
 *
 * 设计口径与 Reticle 的设置页一致：先看状态，再给「去授权」；保活相关开关旁边写清对系统权限的
 * 依赖；做不到的事（没有 WRITE_SECURE_SETTINGS 时自动修）明说，不假装。
 */
@Composable
fun SettingDeviceControlPage() {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val status by DeviceControlState.status.collectAsStateWithLifecycle()

    var refreshTick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        DeviceControlState.refresh(context)
        refreshTick++
    }

    var watchdogEnabled by remember { mutableStateOf(DeviceControlPrefs.watchdogEnabled(context)) }
    var bootEnabled by remember { mutableStateOf(DeviceControlPrefs.bootAutoStartEnabled(context)) }
    var selfTestRunning by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<DeviceSelfTestResult?>(null) }

    val watchdog = remember(refreshTick, status.level, watchdogEnabled) {
        DeviceControlWatchdog.snapshot(context)
    }
    val batteryExempt = remember(refreshTick) { isIgnoringBatteryOptimizations(context) }
    val allFiles = remember(refreshTick) { DeviceDownloader.hasAllFilesAccess(context) }
    val memoryLevel = DeviceControlMemGuard.level
    val settingsStore: SettingsStore = koinInject()
    val shizukuInfo = remember(refreshTick) { ShizukuSupport.info() }
    var shizukuSelfTestRunning by remember { mutableStateOf(false) }
    var shizukuSelfTestOutput by remember { mutableStateOf<String?>(null) }
    val deviceTools = remember(refreshTick) {
        DeviceControlTools(context, settingsStore).getTools()
    }
    val approvalOverrides = remember(refreshTick) { DeviceControlPrefs.toolApprovalOverrides(context) }
    val watchdogIntervalMs = remember(refreshTick) { DeviceControlPrefs.watchdogIntervalMs(context) }
    val auditRetentionDays = remember(refreshTick) { DeviceControlPrefs.auditRetentionDays(context) }
    val downloadAppDirOnly = remember(refreshTick) { DeviceControlPrefs.downloadToAppDirOnly(context) }
    var showIntervalMenu by remember { mutableStateOf(false) }
    var showRetentionMenu by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshTick++ }
        ShizukuSupport.addPermissionListener(listener)
        onDispose { ShizukuSupport.removePermissionListener(listener) }
    }

    val containerColor = MaterialTheme.colorScheme.secondaryContainer
    val contentColor = MaterialTheme.colorScheme.onSecondaryContainer

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_device_control)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("status") {
                CardGroup(
                    title = { Text(stringResource(R.string.device_control_status_title)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.device_control_status_accessibility)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when (status.level) {
                                        DeviceControlLevel.Ready -> R.string.device_control_status_ready
                                        DeviceControlLevel.Stale -> R.string.device_control_status_stale
                                        DeviceControlLevel.Disabled -> R.string.device_control_status_disabled
                                    }
                                )
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Shield01, containerColor = containerColor, contentColor = contentColor)
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
                    status.foregroundPackage?.let { pkg ->
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_page_device_control_foreground)) },
                            supportingContent = { Text("${context.deviceAppLabel(pkg)} · $pkg") },
                            leadingContent = {
                                CardGroupIcon(HugeIcons.SmartPhone01, containerColor = containerColor, contentColor = contentColor)
                            },
                        )
                    }
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
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Cpu, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_memory)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    when (memoryLevel) {
                                        DeviceControlMemGuard.Level.Normal -> R.string.device_control_mem_normal
                                        DeviceControlMemGuard.Level.Ease -> R.string.device_control_mem_ease
                                        DeviceControlMemGuard.Level.Trim -> R.string.device_control_mem_trim
                                        DeviceControlMemGuard.Level.Yield -> R.string.device_control_mem_yield
                                    }
                                )
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Cpu, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                }
            }

            item("selftest") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_connect_section)) },
                ) {
                    item(
                        onClick = {
                            if (selfTestRunning) return@item
                            val service = DeviceControlState.service
                            if (service == null) {
                                toaster.show(
                                    message = context.getString(R.string.setting_page_device_control_selftest_need_service),
                                    type = ToastType.Warning,
                                )
                                return@item
                            }
                            selfTestRunning = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    service.runActSelfTest(context)
                                }
                                selfTestResult = result
                                selfTestRunning = false
                                refreshTick++
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_selftest)) },
                        supportingContent = {
                            val result = selfTestResult
                            when {
                                selfTestRunning -> Text(stringResource(R.string.setting_page_device_control_selftest_running))
                                result == null -> Text(stringResource(R.string.setting_page_device_control_selftest_desc))
                                !result.windowShown -> Text(stringResource(R.string.setting_page_device_control_selftest_blocked))
                                result.ok -> Text(stringResource(R.string.setting_page_device_control_selftest_ok))
                                else -> Text(
                                    stringResource(
                                        R.string.setting_page_device_control_selftest_failed,
                                        result.steps.joinToString(" · "),
                                    )
                                )
                            }
                        },
                        leadingContent = {
                            if (selfTestRunning) {
                                Row {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                                }
                            } else {
                                CardGroupIcon(HugeIcons.CursorPointer01, containerColor = containerColor, contentColor = contentColor)
                            }
                        },
                    )
                    item(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val result = DeviceControlWatchdog.repair(context, force = true)
                                withContext(Dispatchers.Main) {
                                    refreshTick++
                                    toaster.show(
                                        message = context.getString(
                                            R.string.setting_page_device_control_repair_result,
                                            repairResultText(context, result),
                                        ),
                                        type = if (result == DeviceControlWatchdog.RepairResult.FixedNow) {
                                            ToastType.Success
                                        } else {
                                            ToastType.Warning
                                        },
                                    )
                                }
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_repair_now)) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append(watchdogNoteText(context, watchdog.note))
                                    append(" · ")
                                    append(
                                        context.getString(
                                            R.string.setting_page_device_control_last_check,
                                            formatTime(watchdog.lastCheckAt)
                                                ?: context.getString(R.string.setting_page_device_control_never),
                                        )
                                    )
                                }
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Refresh, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_audit)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_audit_desc)) },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Clipboard, containerColor = containerColor, contentColor = contentColor)
                        },
                        onClick = { navController.navigate(Screen.SettingDeviceControlAudit) },
                    )
                }
            }

            item("shizuku") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_shizuku_section)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_shizuku)) },
                        supportingContent = { Text(shizukuStateText(context, shizukuInfo)) },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.ServerStack01, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    if (shizukuInfo.state == ShizukuState.NotRunning || shizukuInfo.state == ShizukuState.Unsupported) {
                        item(
                            onClick = { openShizuku(context, toaster) },
                            headlineContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_get)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_get_desc)) },
                        )
                    }
                    if (shizukuInfo.state == ShizukuState.PermissionNeeded) {
                        item(
                            onClick = { ShizukuSupport.requestPermission() },
                            headlineContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_grant)) },
                            supportingContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_grant_desc)) },
                        )
                    }
                    if (shizukuInfo.state == ShizukuState.Ready) {
                        item(
                            onClick = {
                                if (!shizukuSelfTestRunning) {
                                    shizukuSelfTestRunning = true
                                    scope.launch {
                                        val result = ShizukuSupport.exec("id")
                                        shizukuSelfTestOutput = result.describe()
                                        shizukuSelfTestRunning = false
                                    }
                                }
                            },
                            headlineContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_selftest)) },
                            supportingContent = {
                                Text(
                                    stringResource(
                                        if (shizukuSelfTestRunning) {
                                            R.string.setting_page_device_control_shizuku_selftest_running
                                        } else {
                                            R.string.setting_page_device_control_shizuku_selftest_desc
                                        }
                                    )
                                )
                            },
                        )
                    }
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_what)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_shizuku_what_desc)) },
                    )
                }
            }

            item("capabilities") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_capabilities)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_capabilities_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_capabilities_approval_hint)) },
                    )
                    deviceTools.forEach { tool ->
                        item(
                            headlineContent = { Text(tool.name) },
                            supportingContent = { Text(deviceToolSummary(tool.name, toolDescriptionSummary(tool.description))) },
                            trailingContent = {
                                Switch(
                                    checked = approvalOverrides[tool.name]
                                        ?: (tool.name !in DEVICE_READ_ONLY_TOOLS),
                                    onCheckedChange = { value ->
                                        DeviceControlPrefs.setToolApprovalOverride(context, tool.name, value)
                                        refreshTick++
                                    },
                                )
                            },
                        )
                    }
                }
            }

            item("options") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_options)) },
                ) {
                    item(
                        onClick = { showIntervalMenu = true },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_watchdog_interval)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.setting_page_device_control_watchdog_interval_value,
                                    (watchdogIntervalMs / 1000).toInt(),
                                )
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ChevronDown, null) },
                    )
                    item(
                        onClick = { showRetentionMenu = true },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_audit_retention)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_device_control_audit_retention_value, auditRetentionDays))
                        },
                        trailingContent = { Icon(HugeIcons.ChevronDown, null) },
                    )
                    switchItem(
                        checked = downloadAppDirOnly,
                        onCheckedChange = { value ->
                            DeviceControlPrefs.setDownloadToAppDirOnly(context, value)
                            refreshTick++
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_download_app_dir)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_download_app_dir_desc)) },
                    )
                }
            }

            item("optionMenus") {
                DropdownMenu(expanded = showIntervalMenu, onDismissRequest = { showIntervalMenu = false }) {
                    listOf(10_000L, 30_000L, 60_000L).forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.setting_page_device_control_watchdog_interval_value,
                                        (option / 1000).toInt(),
                                    )
                                )
                            },
                            onClick = {
                                showIntervalMenu = false
                                DeviceControlPrefs.setWatchdogIntervalMs(context, option)
                                refreshTick++
                            },
                        )
                    }
                }
                DropdownMenu(expanded = showRetentionMenu, onDismissRequest = { showRetentionMenu = false }) {
                    listOf(7, 14, 30, 90).forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(R.string.setting_page_device_control_audit_retention_value, option))
                            },
                            onClick = {
                                showRetentionMenu = false
                                DeviceControlPrefs.setAuditRetentionDays(context, option)
                                refreshTick++
                            },
                        )
                    }
                }
            }

            item("keepalive") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_keepalive_section)) },
                ) {
                    switchItem(
                        checked = watchdogEnabled,
                        onCheckedChange = { value ->
                            watchdogEnabled = value
                            DeviceControlPrefs.setWatchdogEnabled(context, value)
                            if (value) {
                                DeviceControlPrefs.setUserStopped(context, false)
                                DeviceControlWatchdog.start(context)
                            } else {
                                DeviceControlWatchdog.stop(context)
                            }
                            refreshTick++
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_watchdog)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (watchdogEnabled) {
                                        R.string.setting_page_device_control_watchdog_desc_on
                                    } else {
                                        R.string.setting_page_device_control_watchdog_desc_off
                                    }
                                )
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.ShieldKey, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_fixes)) },
                        supportingContent = {
                            Text(
                                context.getString(
                                    R.string.setting_page_device_control_fixes_desc,
                                    watchdog.fixes,
                                    formatTime(watchdog.lastFixAt)
                                        ?: context.getString(R.string.setting_page_device_control_never),
                                )
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.RefreshCw, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    switchItem(
                        checked = bootEnabled,
                        onCheckedChange = { value ->
                            bootEnabled = value
                            DeviceControlPrefs.setBootAutoStartEnabled(context, value)
                            refreshTick++
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_boot)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_boot_desc)) },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Refresh, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        onClick = {
                            if (!openAutoStartSettings(context)) {
                                toaster.show(
                                    message = context.getString(R.string.setting_page_device_control_autostart_missing),
                                    type = ToastType.Warning,
                                )
                            }
                        },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_autostart)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_device_control_autostart_desc)) },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Settings03, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        onClick = { requestIgnoreBatteryOptimizations(context, toaster) },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_battery)) },
                        supportingContent = {
                            Text(
                                if (batteryExempt) {
                                    stringResource(R.string.setting_page_device_control_battery_granted)
                                } else {
                                    stringResource(R.string.setting_page_device_control_battery_desc)
                                }
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.BatteryFull, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                }
            }

            item("permissions") {
                CardGroup(
                    title = { Text(stringResource(R.string.setting_page_device_control_permissions_section)) },
                ) {
                    item(
                        onClick = { openAllFilesAccessSettings(context) },
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_all_files)) },
                        supportingContent = {
                            Text(
                                if (allFiles) {
                                    stringResource(R.string.setting_page_device_control_granted)
                                } else {
                                    stringResource(R.string.setting_page_device_control_all_files_desc)
                                }
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Download01, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_device_control_securesettings)) },
                        supportingContent = {
                            Text(
                                if (watchdog.canWriteSecureSettings) {
                                    stringResource(R.string.setting_page_device_control_granted)
                                } else {
                                    stringResource(R.string.setting_page_device_control_securesettings_desc)
                                }
                            )
                        },
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Cpu, containerColor = containerColor, contentColor = contentColor)
                        },
                    )
                }
            }

            item("doc") {
                Text(
                    text = stringResource(R.string.setting_page_device_control_doc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    shizukuSelfTestOutput?.let { output ->
        AlertDialog(
            onDismissRequest = { shizukuSelfTestOutput = null },
            title = { Text(stringResource(R.string.setting_page_device_control_shizuku_selftest_title)) },
            text = {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { shizukuSelfTestOutput = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

/** 时间戳 → 短格式（MM-dd HH:mm）；0 表示从未发生。 */
private fun formatTime(timestamp: Long): String? =
    if (timestamp <= 0L) {
        null
    } else {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

private fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    power.isIgnoringBatteryOptimizations(context.packageName)
}.getOrDefault(false)

private fun requestIgnoreBatteryOptimizations(context: Context, toaster: ToasterState) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    val opened = runCatching { context.startActivity(direct) }.isSuccess
    if (!opened) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val ok = runCatching { context.startActivity(list) }.isSuccess
        if (!ok) {
            toaster.show(
                message = context.getString(R.string.setting_page_device_control_battery_missing),
                type = ToastType.Warning,
            )
        }
    }
}

/** 所有文件访问（系统「下载」目录的前置条件）。 */
private fun openAllFilesAccessSettings(context: Context) {
    val permissionIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
    }
    val opened = runCatching { context.startActivity(permissionIntent) }.isSuccess
    if (!opened) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        }
    }
}

private fun openAutoStartSettings(context: Context): Boolean =
    me.rerere.rikkahub.device.DeviceControlBootReceiver.openAutoStartSettings(context)

/** 打开 Shizuku 应用；没装就跳到下载页。 */
private fun openShizuku(context: Context, toaster: ToasterState) {
    val launched = runCatching {
        context.startActivity(context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
    }.isSuccess
    if (!launched) {
        val opened = runCatching { context.openUrl("https://shizuku.rikka.app/download/") }.isSuccess
        if (!opened) {
            toaster.show(
                message = context.getString(R.string.setting_page_device_control_shizuku_get_desc),
                type = ToastType.Warning,
            )
        }
    }
}

/** 工具描述取第一句，用于设置页的能力清单。 */
private fun toolDescriptionSummary(description: String): String {
    val dot = description.indexOf(". ")
    return if (dot in 1 until description.length) description.take(dot + 1) else description
}

/** 工具名 → 本地化的一句话说明（找不到对应文案时回退英文描述首句）。 */
@Composable
private fun deviceToolSummary(name: String, fallback: String): String {
    val key = deviceToolSummaryKey(name) ?: return fallback
    return stringResource(key)
}

private fun deviceToolSummaryKey(name: String): Int? = when (name) {
    "device_status" -> R.string.device_tool_summary_status
    "device_ui" -> R.string.device_tool_summary_ui
    "device_tap" -> R.string.device_tool_summary_tap
    "device_swipe" -> R.string.device_tool_summary_swipe
    "device_type" -> R.string.device_tool_summary_type
    "device_key" -> R.string.device_tool_summary_key
    "device_apps" -> R.string.device_tool_summary_apps
    "device_screenshot" -> R.string.device_tool_summary_screenshot
    "device_find" -> R.string.device_tool_summary_find
    "device_wait" -> R.string.device_tool_summary_wait
    "device_scroll" -> R.string.device_tool_summary_scroll
    "device_ping" -> R.string.device_tool_summary_ping
    "device_ocr" -> R.string.device_tool_summary_ocr
    "device_windows" -> R.string.device_tool_summary_windows
    "device_long_press" -> R.string.device_tool_summary_long_press
    "device_tap_text" -> R.string.device_tool_summary_tap_text
    "device_scroll_to_text" -> R.string.device_tool_summary_scroll_to_text
    "device_paste" -> R.string.device_tool_summary_paste
    "device_notify" -> R.string.device_tool_summary_notify
    "device_buzz" -> R.string.device_tool_summary_buzz
    "device_download" -> R.string.device_tool_summary_download
    "device_download_status" -> R.string.device_tool_summary_download_status
    "device_help" -> R.string.device_tool_summary_help
    "device_acttest" -> R.string.device_tool_summary_acttest
    "device_do_chain" -> R.string.device_tool_summary_do_chain
    "device_shell" -> R.string.device_tool_summary_shell
    "device_install" -> R.string.device_tool_summary_install
    "device_uninstall" -> R.string.device_tool_summary_uninstall
    "device_force_stop" -> R.string.device_tool_summary_force_stop
    "device_settings" -> R.string.device_tool_summary_settings
    "device_input" -> R.string.device_tool_summary_input
    "device_screencap" -> R.string.device_tool_summary_screencap
    else -> null
}
