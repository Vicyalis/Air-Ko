package me.rerere.rikkahub.ui.pages.extensions.recommend

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.hugeicons.stroke.Shield02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.recommend.RecommendEntry
import me.rerere.rikkahub.data.recommend.RecommendType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 推荐条目详情页：把「这是什么、从哪来、能不能装」一次讲清楚。
 *
 * 真正的下载/安装动作在下一页（安装与部署），这里只提供安全信息与入口，
 * 让用户在点「安装」前就能看到来源地址与 sha256。
 */
@Composable
fun RecommendDetailPage(
    typeWire: String,
    entryId: String,
    vm: RecommendInstallVM = koinViewModel(parameters = { parametersOf(recommendTypeFromNav(typeWire), entryId) }),
) {
    val navController = LocalNavController.current
    val type = remember(typeWire) { recommendTypeFromNav(typeWire) }
    val entry by vm.entry.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(entry?.name ?: recommendTypeTitle(type), maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(recommendTypeIcon(type), contentDescription = null, modifier = Modifier.size(48.dp))
                Text(
                    text = stringResource(R.string.wp9_detail_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header") {
                EntryHeader(entry = current, installed = installed)
            }

            if (current.type == RecommendType.PLUGIN) {
                item(key = "dsh_info") {
                    DshInfoCard(entry = current)
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
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        leadingContent = { CardGroupIcon(HugeIcons.Shield02) },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.wp9_detail_sha256)) },
                        supportingContent = {
                            if (current.hasChecksum) {
                                SelectionContainer {
                                    Text(text = current.sha256, fontFamily = FontFamily.Monospace)
                                }
                            } else if (current.type == RecommendType.PLUGIN) {
                                Text(
                                    text = stringResource(R.string.wp9_dsh_no_sha),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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

            item(key = "security_hint") {
                Text(
                    text = stringResource(R.string.wp9_detail_security_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (current.deployDoc.isNotBlank()) {
                item(key = "deploy_doc") {
                    CopyableCodeBlock(
                        title = stringResource(R.string.wp9_detail_deploy_doc),
                        text = current.deployDoc.trim(),
                        maxHeight = 320.dp,
                        monospace = false,
                    )
                }
            }

            val snippet = mcpConfigSnippet(current)
            if (snippet.isNotBlank()) {
                item(key = "mcp_snippet") {
                    CopyableCodeBlock(
                        title = stringResource(R.string.wp9_detail_mcp_config),
                        text = snippet,
                    )
                }
            }

            item(key = "install") {
                Button(
                    onClick = { navController.navigate(Screen.RecommendInstall(type.wire, current.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.Download01, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(
                            when {
                                installed -> R.string.wp9_detail_reinstall
                                current.type == RecommendType.PLUGIN -> R.string.wp9_dsh_install_action
                                else -> R.string.wp9_detail_open_install
                            }
                        ),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryHeader(
    entry: RecommendEntry,
    installed: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CardGroupIcon(recommendTypeIcon(entry.type))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            entry.author.takeIf { it.isNotBlank() },
                            entry.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (entry.description.isNotBlank()) {
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (installed) {
                    Tag(type = TagType.SUCCESS) { Text(stringResource(R.string.wp9_list_installed)) }
                }
                Tag(type = TagType.INFO) { Text(installKindLabel(entry.installKind)) }
                if (entry.type == me.rerere.rikkahub.data.recommend.RecommendType.PLUGIN) {
                    Tag(type = dshKindTagType(entry.dshKind)) { Text(dshKindLabel(entry.dshKind)) }
                }
                Tag { Text(recommendOriginLabel(entry)) }
            }

            if (entry.tags.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    entry.tags.forEach { tag ->
                        Tag { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DshInfoCard(entry: RecommendEntry) {
    val uriHandler = LocalUriHandler.current
    val repo = entry.dshRepository.ifBlank { entry.dshHomepage }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardGroup(title = { Text(stringResource(R.string.wp9_dsh_info)) }) {
            item(
                headlineContent = { Text(stringResource(R.string.wp9_dsh_package)) },
                supportingContent = {
                    SelectionContainer {
                        Text(entry.dshPackage.ifBlank { "-" }, fontFamily = FontFamily.Monospace)
                    }
                },
                leadingContent = { CardGroupIcon(HugeIcons.Puzzle) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.wp9_dsh_category)) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            entry.dshCategoryLabel.ifBlank { entry.dshCategory }.takeIf { it.isNotBlank() },
                            entry.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                            entry.dshStars.takeIf { it > 0 }?.let {
                                stringResource(R.string.wp9_dsh_stars_format, compactStarCount(it))
                            },
                        ).joinToString(" · ").ifBlank { "-" }
                    )
                },
                leadingContent = { CardGroupIcon(HugeIcons.Puzzle) },
            )
            if (entry.dshEngines.isNotBlank()) {
                item(
                    headlineContent = { Text(stringResource(R.string.wp9_dsh_engines)) },
                    supportingContent = {
                        SelectionContainer {
                            Text(entry.dshEngines, fontFamily = FontFamily.Monospace)
                        }
                    },
                    leadingContent = { CardGroupIcon(HugeIcons.Shield02) },
                )
            }
            item(
                headlineContent = { Text(stringResource(R.string.wp9_dsh_compat)) },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(type = dshKindTagType(entry.dshKind)) {
                            Text(dshKindLabel(entry.dshKind))
                        }
                        Text(
                            text = dshKindDescription(entry.dshKind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (entry.dshSourceType.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.wp9_dsh_source_type, entry.dshSourceType),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingContent = { CardGroupIcon(HugeIcons.Shield02) },
            )
        }
        if (repo.isNotBlank()) {
            TextButton(onClick = { runCatching { uriHandler.openUri(repo) } }) {
                Text(stringResource(R.string.wp9_dsh_open_repo))
            }
        }
    }
}

private fun compactStarCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
    value >= 1_000 -> "%.1fk".format(value / 1_000f)
    else -> value.toString()
}
