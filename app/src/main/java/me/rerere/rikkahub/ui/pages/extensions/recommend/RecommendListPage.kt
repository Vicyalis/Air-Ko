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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Store01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.recommend.RecommendEntry
import me.rerere.rikkahub.data.recommend.RecommendSort
import me.rerere.rikkahub.data.recommend.RecommendSourceResult
import me.rerere.rikkahub.data.recommend.filterEntries
import me.rerere.rikkahub.data.recommend.sortEntries
import me.rerere.rikkahub.ui.components.ai.ExtensionEmptyState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.Select
import org.koin.androidx.compose.koinViewModel

/**
 * 推荐商店的列表页：某个分类（skill / prompt / quick_message / mcp）下的所有推荐条目。
 *
 * 排序偏好在推荐源管理里持久化，来源筛选与搜索是本次会话内的临时状态。
 * 条目本身在点击后才进详情页——安装前的来源与哈希展示、首次安装确认都在后面两页完成。
 */
@Composable
fun RecommendListPage(
    typeWire: String,
    vm: RecommendListVM = koinViewModel(),
) {
    val type = remember(typeWire) { recommendTypeFromNav(typeWire) }
    val navController = LocalNavController.current
    val feed by vm.feed.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }

    val enabledSources = remember(sources) { sources.filter { it.enabled } }
    val typeEntries = remember(feed.entries, type) { feed.entriesOf(type) }
    val visibleEntries = remember(typeEntries, searchQuery, sourceFilter, sort) {
        val bySource = if (sourceFilter == null) {
            typeEntries
        } else {
            typeEntries.filter { it.originId == sourceFilter }
        }
        sortEntries(filterEntries(bySource, searchQuery), sort)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(recommendTypeTitle(type)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refresh() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.wp9_list_refresh))
                    }
                    IconButton(onClick = { navController.navigate(Screen.RecommendSources) }) {
                        Icon(
                            HugeIcons.Store01,
                            contentDescription = stringResource(R.string.wp9_list_manage_sources),
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
        PullToRefreshBox(
            isRefreshing = feed.loading && feed.loadedOnce,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (feed.loading && !feed.loadedOnce) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularWavyProgressIndicator()
                    Text(
                        text = stringResource(R.string.wp9_list_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@PullToRefreshBox
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + 72.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "search") {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.wp9_list_search_placeholder)) },
                        leadingIcon = { Icon(HugeIcons.Search01, contentDescription = null) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(HugeIcons.Cancel01, contentDescription = stringResource(R.string.wp9_clear))
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }

                if (enabledSources.size > 1) {
                    item(key = "sources") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item(key = "all") {
                                FilterChip(
                                    selected = sourceFilter == null,
                                    onClick = { sourceFilter = null },
                                    label = { Text(stringResource(R.string.wp9_list_all_sources)) },
                                )
                            }
                            items(enabledSources, key = { it.id }) { source ->
                                FilterChip(
                                    selected = sourceFilter == source.id,
                                    onClick = { sourceFilter = source.id },
                                    label = { Text(recommendSourceName(source)) },
                                )
                            }
                        }
                    }
                }

                item(key = "sort") {
                    Select(
                        options = RecommendSort.entries,
                        selectedOption = sort,
                        onOptionSelected = { value -> vm.setSort(value) },
                        optionToString = { recommendSortLabel(it) },
                        leading = {
                            Text(
                                text = stringResource(R.string.wp9_list_sort_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (feed.hasCachedData) {
                    item(key = "cached_banner") {
                        StatusBanner(
                            icon = HugeIcons.Clock01,
                            text = stringResource(R.string.wp9_list_cached_banner),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                feed.failedSources.forEach { failed ->
                    item(key = "failed_${failed.sourceId}") {
                        StatusBanner(
                            icon = HugeIcons.Cancel01,
                            text = stringResource(
                                R.string.wp9_list_source_error_format,
                                recommendSourceResultName(failed),
                                failed.error.orEmpty(),
                            ),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                if (feed.skippedCount > 0) {
                    item(key = "skipped") {
                        Text(
                            text = stringResource(R.string.wp9_list_skipped_notice, feed.skippedCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                if (visibleEntries.isEmpty()) {
                    item(key = "empty") {
                        if (searchQuery.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.wp9_list_search_no_result),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                textAlign = TextAlign.Center,
                            )
                        } else if (feed.loadedOnce && feed.failedSources.isNotEmpty() && typeEntries.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.wp9_list_failed),
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Text(
                                    text = stringResource(R.string.wp9_list_failed_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                TextButton(onClick = { vm.refresh() }) {
                                    Text(stringResource(R.string.wp9_list_retry))
                                }
                            }
                        } else {
                            ExtensionEmptyState(
                                message = stringResource(R.string.wp9_list_empty),
                                description = stringResource(R.string.wp9_list_empty_hint),
                                icon = recommendTypeIcon(type),
                            )
                        }
                    }
                }

                items(visibleEntries, key = { it.identity }) { entry ->
                    RecommendEntryCard(
                        entry = entry,
                        installed = entry.identity in installed,
                        onClick = { navController.navigate(Screen.RecommendDetail(type.wire, entry.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBanner(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RecommendEntryCard(
    entry: RecommendEntry,
    installed: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CardGroupIcon(recommendTypeIcon(entry.type))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.version.isNotBlank()) {
                        Tag { Text(stringResource(R.string.wp9_list_version_format, entry.version)) }
                    }
                    if (installed) {
                        Tag(type = TagType.SUCCESS) { Text(stringResource(R.string.wp9_list_installed)) }
                    }
                }
                if (entry.description.isNotBlank()) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tag(type = TagType.INFO) { Text(installKindLabel(entry.installKind)) }
                    entry.tags.take(2).forEach { tag ->
                        Tag { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                Text(
                    text = listOfNotNull(
                        recommendOriginLabel(entry).takeIf { it.isNotBlank() },
                        entry.author.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 源结果里带的名称可能为空（本地内置源没名字），列表页与错误提示都要把它翻成可读文本。 */
@Composable
private fun recommendSourceResultName(result: RecommendSourceResult): String =
    if (result.local) stringResource(R.string.wp9_sources_local_name) else result.sourceName.ifBlank { result.sourceId }
