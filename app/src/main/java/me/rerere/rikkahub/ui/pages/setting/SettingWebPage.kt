package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.os.Build

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.StopCircle
import me.rerere.hugeicons.stroke.Upload01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebServerPortMapping
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.permission.PermissionLocalNetwork
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionNotification
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.web.PortProxy
import me.rerere.rikkahub.web.WebServerManager
import org.koin.compose.koinInject

@Composable
fun SettingWebPage() {
    val webServerManager: WebServerManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val serverState by webServerManager.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val toaster = LocalToaster.current
    val copiedText = stringResource(R.string.copied)
    var portText by remember(settings.webServerPort) {
        mutableStateOf(settings.webServerPort.toString())
    }
    var accessPasswordText by remember(settings.webServerAccessPassword) {
        mutableStateOf(settings.webServerAccessPassword)
    }
    var passwordVisible by remember {
        mutableStateOf(false)
    }
    var showAddMapping by remember {
        mutableStateOf(false)
    }

    fun updateMappings(transform: (List<WebServerPortMapping>) -> List<WebServerPortMapping>) {
        scope.launch {
            settingsStore.update { it.copy(webServerPortMappings = transform(it.webServerPortMappings)) }
        }
    }

    // 端口映射的地址前缀：服务运行中才知道真实 host
    val mappingBaseUrl = if (serverState.isRunning) {
        val host = if (serverState.localhostOnly) "localhost" else (serverState.address ?: "localhost")
        "http://$host:${serverState.port}"
    } else {
        null
    }

    val permissionState = rememberPermissionState(
        permissions = buildSet {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(PermissionNotification)
            }
            if (Build.VERSION.SDK_INT >= 37 && !settings.webServerLocalhostOnly) {
                add(PermissionLocalNetwork)
            }
        },
    )
    PermissionManager(permissionState = permissionState)

    var pendingStart by remember { mutableStateOf(false) }

    fun startWebServer() {
        val intent = Intent(context, WebServerService::class.java).apply {
            action = WebServerService.ACTION_START
            putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
            putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
        }
        context.startForegroundService(intent)
        scope.launch {
            settingsStore.update { it.copy(webServerEnabled = true) }
        }
    }

    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (pendingStart && permissionState.allPermissionsGranted) {
            pendingStart = false
            startWebServer()
        }
    }

    fun copyUrl(url: String) {
        clipboardManager.setText(AnnotatedString(url))
        toaster.show(copiedText)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_page_web_server)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (serverState.isLoading) return@ExtendedFloatingActionButton
                    if (!serverState.isRunning) {
                        if (permissionState.allPermissionsGranted) {
                            startWebServer()
                        } else {
                            pendingStart = true
                            permissionState.requestPermissions()
                        }
                    } else {
                        val intent = Intent(context, WebServerService::class.java).apply {
                            action = WebServerService.ACTION_STOP
                        }
                        context.startService(intent)
                        scope.launch {
                            settingsStore.update { it.copy(webServerEnabled = false) }
                        }
                    }
                },
                icon = {
                    if (serverState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (serverState.isRunning) HugeIcons.Stop else HugeIcons.Play,
                            contentDescription = null,
                        )
                    }
                },
                text = {
                    Text(
                        if (serverState.isRunning) {
                            stringResource(R.string.setting_page_web_server_stop)
                        } else {
                            stringResource(R.string.setting_page_web_server_start)
                        }
                    )
                },
                containerColor = if (serverState.isRunning) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_port)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_desc)) },
                        trailingContent = {
                            TextField(
                                value = portText,
                                onValueChange = { value ->
                                    portText = value.filter { it.isDigit() }
                                    val port = portText.toIntOrNull()
                                    if (port != null && port in 1024..65535) {
                                        scope.launch {
                                            settingsStore.update { it.copy(webServerPort = port) }
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                isError = portText.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                                modifier = Modifier.width(100.dp),
                                enabled = !serverState.isRunning,
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_localhost_only_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.webServerLocalhostOnly,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(webServerLocalhostOnly = checked)
                                        }
                                    }
                                },
                                // 运行中不允许切换 需重启服务生效
                                enabled = !serverState.isRunning,
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_jwt_enable_desc)) },
                        trailingContent = {
                            Switch(
                                checked = settings.webServerJwtEnabled,
                                onCheckedChange = { checked ->
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(webServerJwtEnabled = checked)
                                        }
                                    }
                                },
                                enabled = settings.webServerJwtEnabled || accessPasswordText.isNotBlank(),
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_password)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_password_desc)) },
                        trailingContent = {
                            TextField(
                                value = accessPasswordText,
                                onValueChange = { value ->
                                    accessPasswordText = value
                                    scope.launch {
                                        settingsStore.update {
                                            it.copy(
                                                webServerAccessPassword = value,
                                                webServerJwtEnabled = it.webServerJwtEnabled && value.isNotBlank()
                                            )
                                        }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) HugeIcons.ViewOff else HugeIcons.View,
                                            contentDescription = null
                                        )
                                    }
                                },
                                singleLine = true,
                                isError = settings.webServerJwtEnabled && accessPasswordText.isBlank(),
                                modifier = Modifier.width(180.dp),
                                shape = CircleShape,
                                colors = TextFieldDefaults.colors(
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    errorIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                )
                            )
                        },
                    )
                    if (serverState.isRunning) {
                        val port = serverState.port
                        if (!serverState.localhostOnly) {
                            val lanUrl = "http://${serverState.address ?: "localhost"}:$port"
                            item(
                                onClick = { copyUrl(lanUrl) },
                                headlineContent = { Text(stringResource(R.string.setting_page_web_server_lan_address)) },
                                supportingContent = { Text(lanUrl) },
                            )

                            if (serverState.hostname != null) {
                                val mdnsUrl = "http://${serverState.hostname}:$port"
                                item(
                                    onClick = { copyUrl(mdnsUrl) },
                                    headlineContent = { Text(stringResource(R.string.setting_page_web_server_mdns_address)) },
                                    supportingContent = { Text(mdnsUrl) },
                                )
                            }
                        }

                        val localUrl = "http://localhost:$port"
                        item(
                            onClick = { copyUrl(localUrl) },
                            headlineContent = { Text(stringResource(R.string.setting_page_web_server_local_address)) },
                            supportingContent = { Text(localUrl) },
                        )
                    }
                    item(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.setting_page_web_server_address_note_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    if (serverState.error != null) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_web_server_error),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = serverState.error ?: "",
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_web_server_upload_page)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_upload_page_desc)) },
                        supportingContent = {
                            Text(
                                text = mappingBaseUrl?.let { "$it/upload.html" }
                                    ?: stringResource(R.string.setting_page_web_server_upload_page_offline),
                            )
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Upload01) },
                        trailingContent = {
                            IconButton(
                                onClick = { mappingBaseUrl?.let { copyUrl("$it/upload.html") } },
                                enabled = mappingBaseUrl != null,
                            ) {
                                Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.copied))
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_web_server_port_mappings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_port_mappings_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_mappings_usage)) },
                    )
                    settings.webServerPortMappings.forEach { mapping ->
                        item(
                            headlineContent = {
                                Text(
                                    text = mapping.name.ifBlank {
                                        stringResource(R.string.setting_page_web_server_port_mapping_unnamed)
                                    }
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = mappingBaseUrl?.let { PortProxy.urlFor(it, mapping.port) }
                                        ?: stringResource(
                                            R.string.setting_page_web_server_port_mapping_offline,
                                            mapping.port,
                                        )
                                )
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.Link01) },
                            trailingContent = {
                                Row {
                                    Switch(
                                        checked = mapping.enabled,
                                        onCheckedChange = { checked ->
                                            updateMappings { mappings ->
                                                mappings.map {
                                                    if (it.port == mapping.port) it.copy(enabled = checked) else it
                                                }
                                            }
                                        },
                                    )
                                    IconButton(
                                        onClick = {
                                            mappingBaseUrl?.let { copyUrl(PortProxy.urlFor(it, mapping.port)) }
                                        },
                                        enabled = mappingBaseUrl != null,
                                    ) {
                                        Icon(HugeIcons.Copy01, contentDescription = stringResource(R.string.copied))
                                    }
                                    IconButton(
                                        onClick = {
                                            updateMappings { mappings -> mappings.filterNot { it.port == mapping.port } }
                                        }
                                    ) {
                                        Icon(HugeIcons.Delete01, contentDescription = stringResource(R.string.setting_page_web_server_port_mapping_remove))
                                    }
                                }
                            },
                        )
                    }
                    item(
                        onClick = { showAddMapping = true },
                        leadingContent = { CardGroupIcon(HugeIcons.Add01) },
                        headlineContent = { Text(stringResource(R.string.setting_page_web_server_port_mapping_add)) },
                        supportingContent = { Text(stringResource(R.string.setting_page_web_server_port_mappings_suggestions)) },
                    )
                    if (!settings.webServerLocalhostOnly && settings.webServerPortMappings.any { it.enabled }) {
                        item(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_web_server_port_mapping_warning_title),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(R.string.setting_page_web_server_port_mapping_warning_desc),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    if (showAddMapping) {
        MappingEditDialog(
            initial = null,
            existingPorts = settings.webServerPortMappings.map { it.port }.toSet(),
            onDismiss = { showAddMapping = false },
            onConfirm = { mapping ->
                updateMappings { it + mapping }
                showAddMapping = false
            },
        )
    }
}

@Composable
private fun MappingEditDialog(
    initial: WebServerPortMapping?,
    existingPorts: Set<Int>,
    onDismiss: () -> Unit,
    onConfirm: (WebServerPortMapping) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var portText by remember {
        mutableStateOf(initial?.port?.takeIf { it > 0 }?.toString().orEmpty())
    }
    val port = portText.toIntOrNull()
    val portValid = port != null && PortProxy.isValidPort(port)
    val duplicate = port != null && port in existingPorts
    val titleRes = if (initial == null) {
        R.string.setting_page_web_server_port_mapping_add
    } else {
        R.string.setting_page_web_server_port_mapping_edit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.setting_page_web_server_port_mapping_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { value -> portText = value.filter { it.isDigit() }.take(5) },
                    label = { Text(stringResource(R.string.setting_page_web_server_port_mapping_port)) },
                    singleLine = true,
                    isError = portText.isNotEmpty() && (!portValid || duplicate),
                    supportingText = if (duplicate) {
                        { Text(stringResource(R.string.setting_page_web_server_port_mapping_duplicate)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.setting_page_web_server_port_mappings_suggestions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        WebServerPortMapping(
                            name = name.trim(),
                            port = port ?: 0,
                            enabled = initial?.enabled ?: true,
                        )
                    )
                },
                enabled = portValid && !duplicate,
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
