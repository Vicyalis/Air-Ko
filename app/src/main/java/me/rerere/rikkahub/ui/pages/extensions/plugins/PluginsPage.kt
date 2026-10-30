package me.rerere.rikkahub.ui.pages.extensions.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.Link
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Store01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.plugin.PluginErrorReason
import me.rerere.rikkahub.data.plugin.PluginLoadError
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel

/**
 * Air-Ko 插件页：安装、启用、删除 JSON 提示词包，并给出 DeepSeek Harness 生态插件入口。
 *
 * 商店里的原项目是 Cordis/Node 插件，Android 端不能执行；每个条目提供两种动作：
 * 打开 GitHub 仓库，或安装随 APK 打包的 Air-Ko 移植版（同样是 JSON 提示词/工作流包）。
 */
@Composable
fun PluginsPage(vm: PluginsVM = koinViewModel()) {
    val plugins by vm.plugins.collectAsStateWithLifecycle()
    val loadErrors by vm.loadErrors.collectAsStateWithLifecycle()
    val installState by vm.installState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val uriHandler = LocalUriHandler.current
    val navController = LocalNavController.current

    var url by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<InstalledPlugin?>(null) }
    var previewPlugin by remember { mutableStateOf<InstalledPlugin?>(null) }

    val openUrl: (String) -> Unit = { target ->
        if (target.isNotBlank()) {
            runCatching { uriHandler.openUri(target) }
        }
    }
    val installing = installState is PluginInstallState.Running
    val runningKey = (installState as? PluginInstallState.Running)?.key
    val allInstalled = RECOMMENDED_HARNESS_PLUGINS.all { entry ->
        plugins.any { it.manifest.id == entry.id }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.plugins_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(
                            HugeIcons.Refresh01,
                            contentDescription = stringResource(R.string.plugins_action_refresh),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "intro") {
                PluginsIntroCard()
            }

            if (loadErrors.isNotEmpty()) {
                item(key = "load_errors") {
                    PluginLoadErrorCard(
                        errors = loadErrors,
                    )
                }
            }

            item(key = "installed") {
                CardGroup(title = { Text(stringResource(R.string.plugins_installed_title)) }) {
                    if (plugins.isEmpty()) {
                        item(
                            headlineContent = { Text(stringResource(R.string.plugins_installed_empty)) },
                            supportingContent = { Text(stringResource(R.string.plugins_installed_empty_desc)) },
                            leadingContent = { CardGroupIcon(HugeIcons.Puzzle) },
                        )
                    } else {
                        plugins.forEach { plugin ->
                            installedPluginItem(
                                plugin = plugin,
                                onToggle = { enabled -> vm.setEnabled(plugin.manifest.id, enabled) },
                                onOpenHomepage = { openUrl(plugin.manifest.homepage) },
                                onPreviewPrompt = { previewPlugin = plugin },
                                onDelete = { pendingDelete = plugin },
                            )
                        }
                    }
                }
            }

            item(key = "install") {
                CardGroup(title = { Text(stringResource(R.string.plugins_install_title)) }) {
                    formItem(
                        headlineContent = { Text(stringResource(R.string.plugins_install_headline)) },
                        supportingContent = { Text(stringResource(R.string.plugins_install_desc)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Link) },
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                vm.dismissInstallState()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.plugins_install_placeholder)) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = { vm.installFromUrl(url) },
                            ),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(
                                onClick = { vm.installFromUrl(url) },
                                enabled = url.isNotBlank() && !installing,
                            ) {
                                if (runningKey == "url") {
                                    CircularWavyProgressIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                                } else {
                                    Icon(
                                        HugeIcons.Download01,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        if (runningKey == "url") R.string.plugins_install_running
                                        else R.string.plugins_install_action
                                    ),
                                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                                )
                            }
                            TextButton(
                                onClick = { vm.installSample() },
                                enabled = !installing,
                            ) {
                                if (runningKey == "sample") {
                                    CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                                    Text(
                                        text = stringResource(R.string.plugins_install_running),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                } else {
                                    Text(stringResource(R.string.plugins_install_sample))
                                }
                            }
                        }
                        when (val state = installState) {
                            is PluginInstallState.Success -> if (state.key == "url" || state.key == "sample") {
                                Text(
                                    text = stringResource(R.string.plugins_install_success, state.pluginName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            is PluginInstallState.Failed -> if (state.key == "url" || state.key == "sample") {
                                Text(
                                    text = installErrorText(state),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            }

            item(key = "store") {
                CardGroup(title = { Text(stringResource(R.string.plugins_store_title)) }) {
                    item(
                        headlineContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.plugins_store_note_title),
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Tag(type = TagType.INFO) {
                                    Text(stringResource(R.string.plugins_store_dsh_tag))
                                }
                            }
                        },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(R.string.plugins_store_note_desc))
                                val state = installState
                                if (state is PluginInstallState.Success && state.key == "all" && allInstalled) {
                                    Text(
                                        text = stringResource(R.string.plugins_store_install_all_success, state.count),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Store01) },
                        trailingContent = {
                            TextButton(
                                onClick = { vm.installAllBundled(RECOMMENDED_HARNESS_PLUGINS) },
                                enabled = !installing && !allInstalled,
                            ) {
                                if (runningKey == "all") {
                                    CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(
                                        HugeIcons.Download01,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        when {
                                            allInstalled -> R.string.plugins_store_action_all_installed
                                            runningKey == "all" -> R.string.plugins_store_installing
                                            else -> R.string.plugins_store_action_install_all
                                        }
                                    ),
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        },
                    )
                    item(
                        onClick = {
                            navController.navigate(
                                Screen.RecommendList(RecommendType.PLUGIN.wire)
                            )
                        },
                        headlineContent = {
                            Text(stringResource(R.string.plugins_store_market_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.plugins_store_market_desc))
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Store01) },
                        trailingContent = {
                            TextButton(
                                onClick = {
                                    navController.navigate(
                                        Screen.RecommendList(
                                            RecommendType.PLUGIN.wire
                                        )
                                    )
                                },
                            ) {
                                Text(stringResource(R.string.plugins_store_market_action))
                            }
                        },
                    )
                    RECOMMENDED_HARNESS_PLUGINS.forEach { entry ->
                        val installedPlugin = plugins.firstOrNull { it.manifest.id == entry.id }
                        val itemInstalling = runningKey == entry.id
                        val failure = (installState as? PluginInstallState.Failed)
                            ?.takeIf { it.key == entry.id }
                        item(
                            onClick = { openUrl(entry.homepage) },
                            headlineContent = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = entry.name,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Tag(type = TagType.INFO) {
                                        Text(stringResource(R.string.plugins_store_port_tag))
                                    }
                                }
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(text = entry.repo, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(text = stringResource(entry.descriptionRes))
                                    if (failure != null) {
                                        Text(
                                            text = installErrorText(failure),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            leadingContent = { CardGroupIcon(HugeIcons.Github) },
                            trailingContent = {
                                Column(horizontalAlignment = Alignment.End) {
                                    if (installedPlugin != null) {
                                        Tag(type = TagType.SUCCESS) {
                                            Text(stringResource(R.string.plugins_store_action_installed))
                                        }
                                    } else {
                                        TextButton(
                                            onClick = { vm.installBundled(entry.id, entry.assetPath) },
                                            enabled = !installing,
                                        ) {
                                            if (itemInstalling) {
                                                CircularWavyProgressIndicator(modifier = Modifier.size(16.dp))
                                            } else {
                                                Icon(
                                                    HugeIcons.Download01,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Text(
                                                text = stringResource(
                                                    if (itemInstalling) R.string.plugins_store_installing
                                                    else R.string.plugins_store_action_download
                                                ),
                                                modifier = Modifier.padding(start = 6.dp),
                                            )
                                        }
                                    }
                                    // 原项目是 Cordis/Node 插件，只能到 GitHub 看；移植版才在 Android 上跑
                                    TextButton(onClick = { openUrl(entry.homepage) }) {
                                        Text(stringResource(R.string.plugins_store_action_open))
                                    }
                                }
                            },
                        )
                    }
                }
            }

            item(key = "security_note") {
                Text(
                    text = stringResource(R.string.plugins_security_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    val deleting = pendingDelete
    RikkaConfirmDialog(
        show = deleting != null,
        title = stringResource(R.string.plugins_delete_title),
        confirmText = stringResource(R.string.common_delete),
        dismissText = stringResource(R.string.common_cancel),
        onConfirm = {
            deleting?.let { vm.delete(it.manifest.id) }
            pendingDelete = null
        },
        onDismiss = { pendingDelete = null },
        text = {
            Text(stringResource(R.string.plugins_delete_message, deleting?.manifest?.name.orEmpty()))
        },
    )

    val previewing = previewPlugin
    if (previewing != null) {
        AlertDialog(
            onDismissRequest = { previewPlugin = null },
            title = {
                Text(stringResource(R.string.plugins_prompt_dialog_title, previewing.manifest.name))
            },
            text = {
                if (previewing.manifest.prompt.isBlank()) {
                    Text(stringResource(R.string.plugins_prompt_empty))
                } else {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = previewing.manifest.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewPlugin = null }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
        )
    }
}

@Composable
private fun PluginsIntroCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CardGroupIcon(HugeIcons.Puzzle)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.plugins_intro_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Text(
                    text = stringResource(R.string.plugins_intro_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PluginLoadErrorCard(errors: List<PluginLoadError>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.plugins_load_errors_title, errors.size),
                style = MaterialTheme.typography.titleSmallEmphasized,
            )
            errors.take(5).forEach { error ->
                Text(
                    text = stringResource(R.string.plugins_load_errors_item, error.fileName, error.message),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun CardGroupScope.installedPluginItem(
    plugin: InstalledPlugin,
    onToggle: (Boolean) -> Unit,
    onOpenHomepage: () -> Unit,
    onPreviewPrompt: () -> Unit,
    onDelete: () -> Unit,
) {
    val manifest = plugin.manifest
    formItem(
        headlineContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = manifest.name,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Tag { Text(stringResource(R.string.plugins_version_format, manifest.version)) }
                if (manifest.minHarness) {
                    Tag(type = TagType.WARNING) {
                        Text(stringResource(R.string.plugins_min_harness_tag))
                    }
                }
            }
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (manifest.author.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.plugins_author_format, manifest.author),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (manifest.description.isNotBlank()) {
                    Text(text = manifest.description)
                }
            }
        },
        leadingContent = { CardGroupIcon(HugeIcons.Puzzle) },
        trailingContent = {
            Switch(checked = plugin.enabled, onCheckedChange = onToggle)
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPreviewPrompt) {
                Text(stringResource(R.string.plugins_action_view_prompt))
            }
            if (manifest.homepage.isNotBlank()) {
                TextButton(onClick = onOpenHomepage) {
                    Icon(
                        HugeIcons.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.plugins_action_open),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            TextButton(onClick = onDelete) {
                Icon(
                    HugeIcons.Delete02,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.plugins_action_delete),
                    modifier = Modifier.padding(start = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun installErrorText(state: PluginInstallState.Failed): String = when (state.reason) {
    PluginErrorReason.INVALID_URL -> stringResource(R.string.plugins_error_invalid_url)
    PluginErrorReason.NETWORK -> stringResource(R.string.plugins_error_network, state.detail)
    PluginErrorReason.HTTP_STATUS -> stringResource(R.string.plugins_error_http, state.detail)
    PluginErrorReason.TOO_LARGE -> stringResource(R.string.plugins_error_too_large)
    PluginErrorReason.INVALID_JSON -> stringResource(R.string.plugins_error_invalid_json)
    PluginErrorReason.INVALID_MANIFEST -> stringResource(R.string.plugins_error_invalid_manifest)
    PluginErrorReason.IO -> stringResource(R.string.plugins_error_io, state.detail)
    null -> stringResource(R.string.plugins_error_unknown, state.detail)
}
