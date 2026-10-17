package me.rerere.rikkahub.ui.pages.extensions.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Store02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.recommend.RecommendSource
import me.rerere.rikkahub.data.recommend.RecommendSourceRules
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.ItemAction
import me.rerere.rikkahub.ui.components.ui.ItemActionMenu
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.cardGroupItemShape
import me.rerere.rikkahub.ui.components.ui.longPressReorder
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.ui.components.RikkaConfirmDialog
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 推荐源管理页：增删改、启用禁用、拖动排序。
 *
 * 源上的内容会被安装进 App（技能/提示词/快捷消息/MCP 配置），所以每个源都要能看清地址；
 * 内置源可以禁用/改地址（换成镜像）但不能删，本地内置源始终启用且不可编辑——
 * 它是断网时的兜底内容。
 */
@Composable
fun RecommendSourcesPage(vm: RecommendSourcesVM = koinViewModel()) {
    val sources by vm.sources.collectAsStateWithLifecycle()
    val feedback by vm.feedback.collectAsStateWithLifecycle()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<RecommendSource?>(null) }
    var deleteTarget by remember { mutableStateOf<RecommendSource?>(null) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(feedback) {
        feedback?.let { value ->
            toaster.show(context.getString(value.messageRes()))
            vm.consumeFeedback()
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        vm.move(from.index, to.index)
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.wp9_sources_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(HugeIcons.Refresh01, contentDescription = stringResource(R.string.wp9_list_refresh))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(HugeIcons.Add01, contentDescription = stringResource(R.string.wp9_sources_add))
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
            state = lazyListState,
            contentPadding = innerPadding + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "hint") {
                Column(
                    modifier = Modifier.padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.wp9_sources_drag_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Text(
                        text = stringResource(R.string.wp9_sources_readonly_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            items(sources, key = { it.id }) { source ->
                ReorderableItem(state = reorderableState, key = source.id) { isDragging ->
                    val index = sources.indexOfFirst { it.id == source.id }
                    SourceCard(
                        source = source,
                        shape = cardGroupItemShape(index.coerceAtLeast(0), sources.size),
                        modifier = longPressReorder(isDragging),
                        onEdit = { editTarget = source },
                        onDelete = { deleteTarget = source },
                        onToggle = { enabled -> vm.setEnabled(source.id, enabled) },
                        onMoveUp = { vm.moveBy(source.id, -1) },
                        onMoveDown = { vm.moveBy(source.id, 1) },
                        canMoveUp = index > 0,
                        canMoveDown = index in 0 until sources.size - 1,
                    )
                }
            }

            item(key = "reset") {
                CardGroup(modifier = Modifier.padding(top = 24.dp)) {
                    item(
                        onClick = { showResetDialog = true },
                        headlineContent = { Text(stringResource(R.string.wp9_sources_reset)) },
                        leadingContent = { CardGroupIcon(HugeIcons.Refresh01) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        SourceEditDialog(
            title = stringResource(R.string.wp9_sources_add_title),
            initialName = "",
            initialUrl = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url ->
                vm.add(name, url)
                showAddDialog = false
            },
        )
    }

    editTarget?.let { source ->
        SourceEditDialog(
            title = stringResource(R.string.wp9_sources_edit_title),
            initialName = source.name,
            initialUrl = source.url,
            onDismiss = { editTarget = null },
            onConfirm = { name, url ->
                vm.update(source.id, name, url)
                editTarget = null
            },
        )
    }

    deleteTarget?.let { source ->
        RikkaConfirmDialog(
            show = true,
            title = stringResource(R.string.wp9_sources_delete_title),
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            onConfirm = {
                vm.remove(source.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        ) {
            Text(stringResource(R.string.wp9_sources_delete_confirm, source.name.ifBlank { source.url }))
        }
    }

    RikkaConfirmDialog(
        show = showResetDialog,
        title = stringResource(R.string.wp9_sources_reset_confirm_title),
        confirmText = stringResource(R.string.wp9_sources_reset_confirm_accept),
        dismissText = stringResource(R.string.cancel),
        onConfirm = {
            vm.resetToDefaults()
            showResetDialog = false
        },
        onDismiss = { showResetDialog = false },
    ) {
        Text(stringResource(R.string.wp9_sources_reset_confirm_message))
    }
}

@Composable
private fun SourceCard(
    source: RecommendSource,
    shape: Shape,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    val editable = !source.local
    Surface(
        onClick = { if (editable) onEdit() },
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = CustomColors.listItemColors.containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardGroupIcon(HugeIcons.Store02)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = recommendSourceName(source),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = source.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (source.builtin) {
                        Tag { Text(stringResource(R.string.wp9_sources_builtin)) }
                    }
                    if (source.local) {
                        Tag(type = TagType.INFO) { Text(stringResource(R.string.wp9_sources_local)) }
                    }
                    if (!source.enabled) {
                        Tag(type = TagType.WARNING) { Text(stringResource(R.string.wp9_sources_disabled)) }
                    }
                }
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                enabled = !source.local,
            )
            ItemActionMenu(
                actions = buildList {
                    if (editable) {
                        add(
                            ItemAction(
                                text = stringResource(R.string.wp9_sources_edit),
                                icon = HugeIcons.Edit02,
                                onClick = onEdit,
                            )
                        )
                    }
                    add(
                        ItemAction(
                            text = stringResource(R.string.wp9_sources_move_up),
                            icon = HugeIcons.ArrowUp01,
                            enabled = canMoveUp,
                            onClick = onMoveUp,
                        )
                    )
                    add(
                        ItemAction(
                            text = stringResource(R.string.wp9_sources_move_down),
                            icon = HugeIcons.ArrowDown01,
                            enabled = canMoveDown,
                            onClick = onMoveDown,
                        )
                    )
                    if (!source.builtin) {
                        add(
                            ItemAction(
                                text = stringResource(R.string.wp9_sources_delete),
                                icon = HugeIcons.Delete01,
                                destructive = true,
                                onClick = onDelete,
                            )
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun SourceEditDialog(
    title: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by rememberSaveable(initialUrl) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val urlValid = RecommendSourceRules.isValidUrl(url)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.wp9_sources_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.wp9_sources_url)) },
                    isError = url.isNotBlank() && !urlValid,
                    supportingText = {
                        Text(
                            if (url.isNotBlank() && !urlValid) {
                                stringResource(R.string.wp9_sources_feedback_invalid_url)
                            } else {
                                stringResource(R.string.wp9_sources_url_hint)
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url) },
                enabled = urlValid,
            ) {
                Text(stringResource(R.string.wp9_sources_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun RecommendSourceFeedback.messageRes(): Int = when (this) {
    RecommendSourceFeedback.ADDED -> R.string.wp9_sources_feedback_added
    RecommendSourceFeedback.UPDATED -> R.string.wp9_sources_feedback_updated
    RecommendSourceFeedback.REMOVED -> R.string.wp9_sources_feedback_removed
    RecommendSourceFeedback.RESET -> R.string.wp9_sources_feedback_reset
    RecommendSourceFeedback.INVALID_URL -> R.string.wp9_sources_feedback_invalid_url
    RecommendSourceFeedback.DUPLICATE_URL -> R.string.wp9_sources_feedback_duplicate
    RecommendSourceFeedback.NOT_FOUND -> R.string.wp9_sources_feedback_not_found
}
