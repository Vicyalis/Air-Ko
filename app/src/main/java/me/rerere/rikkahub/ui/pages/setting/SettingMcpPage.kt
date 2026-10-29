package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageBlocked
import me.rerere.hugeicons.stroke.Store02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.sanitizeMcpServerName
import me.rerere.rikkahub.data.ai.mcp.uniqueMcpServerName
import me.rerere.rikkahub.data.recommend.McpOAuthParseResult
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.data.recommend.parseMcpOAuthElement
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun SettingMcpPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val mcpConfigs = settings.mcpServers
    val navController = LocalNavController.current
    var showImportDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_mcp_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showImportDialog = true
                        }
                    ) {
                        Icon(HugeIcons.FileImport, null)
                    }
                    IconButton(
                        onClick = {
                            // 名称为空的配置不会连接，先加进列表，具体配置到详情页里填
                            val config = McpServerConfig.StreamableHTTPServer()
                            vm.updateSettings { latest ->
                                latest.copy(mcpServers = latest.mcpServers + config)
                            }
                            navController.navigate(Screen.SettingMcpDetail(serverId = config.id.toString()))
                        }
                    ) {
                        Icon(HugeIcons.Add01, null)
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.RecommendList(RecommendType.MCP.wire)) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Icon(HugeIcons.Store02, contentDescription = stringResource(R.string.wp9_fab_recommend))
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { innerPadding ->
        val mcpManager = koinInject<McpManager>()
        val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val state = rememberPullToRefreshState()
        val loading = status.values.any { it == McpStatus.Connecting || it is McpStatus.Reconnecting }
        val layoutDirection = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = {
                scope.launch {
                    mcpManager.syncAll()
                }
            },
            state = state,
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = innerPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = innerPadding.calculateBottomPadding() + 16.dp,
                )
            ) {
                items(mcpConfigs, key = { it.id }) { mcpConfig ->
                    McpServerItem(
                        item = mcpConfig,
                        onEdit = {
                            navController.navigate(Screen.SettingMcpDetail(serverId = mcpConfig.id.toString()))
                        },
                        onDelete = {
                            vm.updateSettings { latest ->
                                latest.copy(
                                    mcpServers = latest.mcpServers.filter { it.id != mcpConfig.id }
                                )
                            }
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (mcpConfigs.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = stringResource(R.string.setting_mcp_page_no_mcp_servers_found))
                    Text(
                        text = stringResource(R.string.setting_mcp_page_add_one_to_get_started),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (showImportDialog) {
        McpImportModal(
            onDismiss = { showImportDialog = false },
            onImport = { newConfigs ->
                // 导入的名称可能和已有 server 重名：自动追加数字后缀，
                // 而不是把整条配置静默丢掉（旧实现会直接过滤掉）。
                val usedNames = mcpConfigs.map { it.commonOptions.name }.toMutableSet()
                val toAdd = newConfigs.mapNotNull { config ->
                    if (config.commonOptions.name.isBlank()) return@mapNotNull null
                    val name = uniqueMcpServerName(config.commonOptions.name, usedNames)
                    usedNames += name
                    if (name == config.commonOptions.name) {
                        config
                    } else {
                        config.clone(commonOptions = config.commonOptions.copy(name = name))
                    }
                }
                vm.updateSettings { it.copy(mcpServers = it.mcpServers + toAdd) }
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun McpServerItem(
    item: McpServerConfig,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit,
    onEdit: (McpServerConfig) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val status by mcpManager.getStatus(item).collectAsStateWithLifecycle(McpStatus.Idle)
    var showDeleteDialog by remember { mutableStateOf(false) }
    var errorDetail by remember { mutableStateOf<McpStatus.Error?>(null) }

    errorDetail?.let { error ->
        val context = LocalContext.current
        val fullText = error.detail ?: error.message
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text(item.commonOptions.name.ifBlank { "MCP" }) },
            text = {
                SelectionContainer {
                    Text(
                        text = fullText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.writeClipboardText(fullText)
                        errorDetail = null
                    }
                ) {
                    Text(stringResource(R.string.copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { errorDetail = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    Card(
        onClick = { onEdit(item) },
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (status) {
                McpStatus.Idle -> Icon(HugeIcons.MessageBlocked, null)
                McpStatus.Connecting -> CircularProgressIndicator(
                    modifier = Modifier.size(
                        24.dp
                    )
                )

                McpStatus.Connected -> Icon(HugeIcons.McpServer, null)
                is McpStatus.Reconnecting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp)
                )
                is McpStatus.Error -> Icon(HugeIcons.AlertCircle, null)
                McpStatus.NeedsAuthorization -> Icon(HugeIcons.AlertCircle, null)
                McpStatus.Authorizing -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = item.commonOptions.name.ifBlank { "MCP" },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val dotColor =
                        if (item.commonOptions.enable) MaterialTheme.extendColors.green6 else MaterialTheme.extendColors.red6
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .drawWithContent {
                                drawCircle(
                                    color = dotColor
                                )
                            }
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Tag(type = TagType.SUCCESS) {
                        when (item) {
                            is McpServerConfig.SseTransportServer -> Text("SSE")
                            is McpServerConfig.StreamableHTTPServer -> Text("Streamable HTTP")
                        }
                    }
                }
                if (status is McpStatus.Error) {
                    val error = status as McpStatus.Error
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { errorDetail = error },
                    )
                }
                if (status == McpStatus.NeedsAuthorization) {
                    val context = LocalContext.current
                    Text(
                        text = "需要 OAuth 授权",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(
                        onClick = { mcpManager.startAuthorization(item, context) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("OAuth 授权")
                    }
                }
                if (status == McpStatus.Authorizing) {
                    Text(
                        text = "正在授权，请在浏览器中完成…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(
                        onClick = { mcpManager.cancelAuthorization(item) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text("取消授权")
                    }
                }
            }

            ItemActionMenu(
                actions = listOf(
                    ItemAction(
                        text = stringResource(R.string.delete),
                        icon = HugeIcons.Delete01,
                        destructive = true,
                        onClick = { showDeleteDialog = true },
                    ),
                )
            )
        }
    }

    RikkaConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.confirm_delete),
        confirmText = stringResource(R.string.delete),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            showDeleteDialog = false
            onDelete()
        },
        onDismiss = { showDeleteDialog = false },
    ) {
        Text(stringResource(R.string.common_delete_confirm_message, item.commonOptions.name))
    }
}

private fun parseMcpServersFromJson(json: String): List<McpServerConfig> {
    val root = Json.parseToJsonElement(json).jsonObject
    val mcpServers = root["mcpServers"]?.jsonObject ?: return emptyList()
    val usedNames = mutableSetOf<String>()
    return mcpServers.entries.mapNotNull { (rawName, element) ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        // stdio 配置没有 url，本 App 只支持远程 HTTP，导入时直接跳过
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.lowercase()?.replace('-', '_')
        val headers = when (val headersElement = obj["headers"]) {
            is JsonObject -> headersElement.entries.mapNotNull { (key, value) ->
                val headerName = key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val headerValue = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                headerName to headerValue
            }

            is JsonArray -> headersElement.mapNotNull { item ->
                val headerObj = item as? JsonObject ?: return@mapNotNull null
                val headerName = headerObj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val headerValue = headerObj["value"]?.jsonPrimitive?.contentOrNull
                    ?: headerObj["default"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                headerName to headerValue
            }

            else -> emptyList()
        }
        val oauth = when (val parsed = parseMcpOAuthElement(obj["oauth"] ?: obj["authorization"])) {
            McpOAuthParseResult.Absent -> null
            is McpOAuthParseResult.Parsed -> parsed.state
            is McpOAuthParseResult.Invalid -> null
        }
        // 设置页只接受字母数字名称，导入时直接规范化，避免导入后无法保存
        val name = uniqueMcpServerName(sanitizeMcpServerName(rawName), usedNames)
        usedNames += name
        val commonOptions = McpCommonOptions(name = name, headers = headers, oauth = oauth)
        when (type) {
            "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
            else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
        }
    }
}

@Composable
private fun McpImportModal(
    onDismiss: () -> Unit,
    onImport: (List<McpServerConfig>) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val noValidConfigMsg = stringResource(R.string.setting_mcp_page_import_no_valid_config)
    val parseErrorMsg = stringResource(R.string.setting_mcp_page_import_parse_error)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.7f)
                .padding(16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.setting_mcp_page_import_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.setting_mcp_page_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = jsonText,
                onValueChange = {
                    jsonText = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                placeholder = { Text("{ \"mcpServers\": { ... } }") },
                isError = errorMessage != null,
                supportingText = errorMessage?.let { msg -> { Text(msg, color = MaterialTheme.colorScheme.error) } }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        try {
                            val configs = parseMcpServersFromJson(jsonText.trim())
                            if (configs.isEmpty()) {
                                errorMessage = noValidConfigMsg
                            } else {
                                onImport(configs)
                            }
                        } catch (e: Exception) {
                            errorMessage = parseErrorMsg.format(e.message ?: "")
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_mcp_page_import_confirm))
                }
            }
        }
    }
}
