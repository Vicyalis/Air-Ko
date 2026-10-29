package me.rerere.rikkahub.ui.pages.extensions.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.Shield02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.recommend.RecommendInstallResult
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.data.recommend.runtimeCommand
import me.rerere.rikkahub.data.recommend.runtimeProbeCommand
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.utils.writeClipboardText
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 安装与部署页：推荐商店里唯一真正「动手」的地方。
 *
 * 页面负责三件事：
 * 1. 安装前的安全门槛——来源与 sha256 摆出来，首次安装必须弹窗确认；
 * 2. 安装/写入动作——skill 落沙箱目录、prompt/quick_message 进设置、远程 MCP 写进 MCP 设置；
 * 3. 部署讲解——需要本机跑起来的条目（npx/uvx/docker）给出运行时检测、依赖安装、启动命令，
 *    并可直接在内置的工作区终端里执行，输出原样展示。
 */
@Composable
fun RecommendInstallPage(
    typeWire: String,
    entryId: String,
    vm: RecommendInstallVM = koinViewModel(parameters = { parametersOf(recommendTypeFromNav(typeWire), entryId) }),
) {
    val navController = LocalNavController.current
    val type = remember(typeWire) { recommendTypeFromNav(typeWire) }
    val entry by vm.entry.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val confirmed by vm.confirmed.collectAsStateWithLifecycle()
    val installState by vm.installState.collectAsStateWithLifecycle()
    val terminalState by vm.terminalState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showConfirmDialog by rememberSaveable { mutableStateOf(false) }

    val startInstall = {
        if (confirmed) {
            vm.install()
        } else {
            showConfirmDialog = true
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.wp9_install_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        val current = entry
        if (current == null) {
            Text(
                text = stringResource(R.string.wp9_detail_not_found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
            )
            return@Scaffold
        }

        val needsRuntime = current.installKind.needsRuntime
        // 可直接写地址的 remote，或需要下载配置 payload 的 MCP，都能一键写入设置；
        // 本地 npx/uvx/docker 仍然只能走部署讲解。
        val installableMcp = current.type == RecommendType.MCP &&
            (current.mcpDirectUrl.isNotBlank() || current.hasMcpConfigPayload)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = CustomColors.listItemColors.containerColor,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CardGroupIcon(recommendTypeIcon(current.type))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(text = current.name, style = MaterialTheme.typography.titleMediumEmphasized)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Tag(type = TagType.INFO) { Text(installKindLabel(current.installKind)) }
                                if (installed) {
                                    Tag(type = TagType.SUCCESS) { Text(stringResource(R.string.wp9_list_installed)) }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "security") {
                CardGroup(title = { Text(stringResource(R.string.wp9_detail_security)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.wp9_detail_source)) },
                        supportingContent = {
                            SelectionContainer {
                                Text(
                                    text = current.displaySourceUrl.ifBlank { "-" },
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Shield02) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.wp9_detail_sha256)) },
                        supportingContent = {
                            if (current.hasChecksum) {
                                SelectionContainer { Text(current.sha256, fontFamily = FontFamily.Monospace) }
                            } else {
                                Text(
                                    text = stringResource(R.string.wp9_detail_no_sha),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Shield02) },
                    )
                }
            }

            item(key = "install_button") {
                Button(
                    onClick = startInstall,
                    enabled = installState !is RecommendInstallState.Running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Download01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = when {
                            installableMcp -> stringResource(R.string.wp9_mcp_oneclick)
                            installed -> stringResource(R.string.wp9_install_reinstall)
                            else -> stringResource(R.string.wp9_install_run)
                        },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            when (val state = installState) {
                RecommendInstallState.Idle -> Unit

                is RecommendInstallState.Running -> item(key = "install_running") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.wp9_install_running, state.entryName))
                    }
                }

                is RecommendInstallState.Finished -> item(key = "install_result") {
                    InstallResultCard(
                        result = state.result,
                        onRetry = { vm.resetInstallState() },
                    )
                }
            }

            if (current.type == RecommendType.MCP && !installableMcp) {
                item(key = "mcp_local_warning") {
                    WarningCard(text = stringResource(R.string.wp9_mcp_local_warning))
                }
            }

            val snippet = mcpConfigSnippet(current)
            if (snippet.isNotBlank()) {
                item(key = "mcp_snippet") {
                    CopyableCodeBlock(
                        title = stringResource(R.string.wp9_mcp_config_title),
                        text = snippet,
                    )
                }
            }

            if (current.deployDoc.isNotBlank()) {
                item(key = "deploy_doc") {
                    CopyableCodeBlock(
                        title = stringResource(R.string.wp9_deploy_doc),
                        text = current.deployDoc.trim(),
                        maxHeight = 320.dp,
                        monospace = false,
                    )
                }
            }

            if (needsRuntime) {
                item(key = "deploy_runtime") {
                    CardGroup(title = { Text(stringResource(R.string.wp9_deploy_section)) }) {
                        item(
                            headlineContent = {
                                Text(stringResource(R.string.wp9_deploy_runtime, installKindLabel(current.installKind)))
                            },
                            supportingContent = { Text(stringResource(R.string.wp9_deploy_runtime_hint)) },
                            leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                        )
                    }
                }

                item(key = "deploy_probe") {
                    CommandBlock(
                        title = stringResource(R.string.wp9_deploy_probe),
                        command = runtimeProbeCommand(current.installKind),
                        runLabel = stringResource(R.string.wp9_run),
                        onRun = vm::runInTerminal,
                    )
                }

                if (current.deps.isNotEmpty()) {
                    item(key = "deploy_deps_title") {
                        Text(
                            text = stringResource(R.string.wp9_deploy_deps),
                            style = MaterialTheme.typography.titleSmallEmphasized,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    current.deps.forEachIndexed { index, dep ->
                        item(key = "dep_$index") {
                            CommandBlock(
                                title = dep,
                                command = dependencyCommand(current.installKind, dep),
                                runLabel = stringResource(R.string.wp9_run),
                                onRun = vm::runInTerminal,
                            )
                        }
                    }
                }

                item(key = "deploy_run") {
                    CommandBlock(
                        title = stringResource(R.string.wp9_deploy_run_command),
                        command = runtimeCommand(current),
                        runLabel = stringResource(R.string.wp9_deploy_run_in_terminal),
                        onRun = vm::runInTerminal,
                    )
                }

                item(key = "deploy_terminal") {
                    TerminalSection(
                        state = terminalState,
                        onGoWorkspaces = { navController.navigate(Screen.Workspaces) },
                    )
                }
            }
        }
    }

    if (showConfirmDialog && entry != null) {
        val current = entry!!
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.wp9_confirm_title),
            confirmText = stringResource(R.string.wp9_confirm_accept),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                showConfirmDialog = false
                vm.confirmSecurityNotice()
                vm.install()
            },
            onDismiss = { showConfirmDialog = false },
        ) {
            Text(
                stringResource(
                    R.string.wp9_confirm_message,
                    current.name,
                    current.displaySourceUrl.ifBlank { "-" },
                    current.sha256.ifBlank { stringResource(R.string.wp9_detail_no_sha) },
                )
            )
        }
    }
}

/** 安装结果卡片：成功/失败 + 流水；失败时提供「重来」。 */
@Composable
private fun InstallResultCard(
    result: RecommendInstallResult,
    onRetry: () -> Unit,
) {
    val containerColor = if (result.success) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (result.success) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (result.success) HugeIcons.CheckmarkCircle02 else HugeIcons.Alert02,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(
                        if (result.success) R.string.wp9_install_success else R.string.wp9_install_failed
                    ),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                )
            }
            Text(text = result.message, style = MaterialTheme.typography.bodyMedium)
            if (result.steps.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.wp9_install_steps),
                    style = MaterialTheme.typography.labelLarge,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    SelectionContainer {
                        Text(
                            text = result.steps.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }
            if (!result.success) {
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text(stringResource(R.string.wp9_install_retry))
                }
            }
        }
    }
}

/** 命令 + 复制 + 运行：部署讲解里的每一步都用它，保证「看得见、抄得走、跑得了」。 */
@Composable
private fun CommandBlock(
    title: String,
    command: String,
    runLabel: String,
    onRun: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { context.writeClipboardText(command) }) {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = stringResource(R.string.wp9_copy),
                        modifier = Modifier.size(18.dp),
                    )
                }
                FilledTonalButton(onClick = { onRun(command) }) {
                    Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = runLabel, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = command,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}

/** 内置终端（常驻工作区）执行状态的展示区。 */
@Composable
private fun TerminalSection(
    state: RecommendTerminalState,
    onGoWorkspaces: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.wp9_deploy_terminal),
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        when (state) {
            RecommendTerminalState.Idle -> Text(
                text = stringResource(R.string.wp9_deploy_terminal_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            RecommendTerminalState.Unavailable -> Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wp9_deploy_terminal_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onGoWorkspaces) {
                        Text(stringResource(R.string.wp9_deploy_go_workspaces))
                    }
                }
            }

            is RecommendTerminalState.Running -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
                Text(
                    text = stringResource(
                        R.string.wp9_deploy_terminal_running,
                        state.command,
                        state.workspaceName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }

            is RecommendTerminalState.Finished -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = buildString {
                        append(stringResource(R.string.wp9_deploy_terminal_exit, state.exitCode))
                        if (state.timedOut) append(" ")
                        if (state.timedOut) append(stringResource(R.string.wp9_deploy_terminal_timeout))
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.exitCode == 0 && !state.timedOut) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                CopyableCodeBlock(
                    title = state.command,
                    text = state.output,
                    maxHeight = 260.dp,
                )
            }

            is RecommendTerminalState.Failed -> WarningCard(
                text = stringResource(R.string.wp9_deploy_terminal_failed, state.message)
            )
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(HugeIcons.Alert02, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
