package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Pin
import me.rerere.hugeicons.stroke.PinOff
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.ui.components.ai.ChatInput
import me.rerere.rikkahub.ui.components.ai.SearchMode
import me.rerere.rikkahub.ui.components.ai.completion.buildMentionCompletionProviders
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.chat.ChatList
import me.rerere.rikkahub.ui.pages.chat.ChatVM
import me.rerere.ui.toast.ToastType
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

/**
 * 桌面上的下拉式悬浮聊天（类似 PC QQ 的贴边聊天）。
 *
 * - 标题：点击弹出历史对话列表，选择后切换；
 * - Pin：默认按下，锁定后操作桌面不会收起；取消后点击桌面即收起；
 * - 输入框与主聊天界面共用 ChatInput，消息列表共用 ChatList。
 */
@Composable
fun DesktopFloatingChatPanel(
    conversationId: String,
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalSettings.current
    val repository: ConversationRepository = koinInject()
    val conversations by remember(settings.assistantId) {
        repository.getConversationsOfAssistant(settings.assistantId)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var activeId by remember(conversationId) { mutableStateOf(conversationId) }
    var showPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.pointerInput(Unit) { detectTapGestures { } },
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        if (activeId.isBlank()) {
            ConversationChooser(
                conversations = conversations,
                onSelect = { activeId = it },
                onDismiss = onDismiss,
            )
        } else {
            key(activeId) {
                FloatingChatContent(
                    conversationId = activeId,
                    pinned = pinned,
                    onPinnedChange = onPinnedChange,
                    onPickConversation = { showPicker = true },
                    onDismiss = onDismiss,
                    showPicker = showPicker,
                    onShowPickerChange = { showPicker = it },
                    conversations = conversations,
                    onSelectConversation = {
                        activeId = it
                        showPicker = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationChooser(
    conversations: List<me.rerere.rikkahub.data.model.Conversation>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.desktop_floating_pick_conversation),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            IconButton(onClick = onDismiss) {
                Icon(HugeIcons.ArrowDown01, contentDescription = null)
            }
        }
        HorizontalDivider()
        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.desktop_floating_no_conversations),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(conversations, key = { it.id.toString() }) { conversation ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(conversation.id.toString()) },
                        headlineContent = {
                            Text(
                                text = conversation.title.ifBlank {
                                    stringResource(R.string.chat_page_new_chat)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingChatContent(
    conversationId: String,
    pinned: Boolean,
    onPinnedChange: (Boolean) -> Unit,
    onPickConversation: () -> Unit,
    onDismiss: () -> Unit,
    showPicker: Boolean,
    onShowPickerChange: (Boolean) -> Unit,
    conversations: List<me.rerere.rikkahub.data.model.Conversation>,
    onSelectConversation: (String) -> Unit,
) {
    val vm: ChatVM = koinViewModel(
        key = "desktop-floating-$conversationId",
        parameters = { parametersOf(conversationId, false) },
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val loadingJob by vm.conversationJob.collectAsStateWithLifecycle()
    val processingStatus by vm.processingStatus.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val chatModel by vm.currentChatModel.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val messageQueue by vm.messageQueue.collectAsStateWithLifecycle()

    val inputState = vm.inputState
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val selectModelMessage = stringResource(R.string.desktop_floating_select_model)
    val attachmentsHint = stringResource(R.string.desktop_floating_attachments_hint)
    val skillManager: SkillManager = koinInject()
    val pluginManager: PluginManager = koinInject()
    val installedPlugins by pluginManager.installed.collectAsStateWithLifecycle()
    val completionProviders = remember(
        settings.mcpServers,
        settings.modeInjections,
        settings.lorebooks,
        settings.quickMessages,
        assistant.quickMessageIds,
        assistant.enabledSkills,
        assistant.mcpServers,
        conversation.modeInjectionIds,
        conversation.lorebookIds,
        installedPlugins,
    ) {
        buildMentionCompletionProviders(
            settings = settings,
            assistant = assistant,
            conversation = conversation,
            skillManager = skillManager,
            plugins = installedPlugins,
            onEnableSkill = { name ->
                vm.updateAssistant(assistant.copy(enabledSkills = assistant.enabledSkills + name))
            },
            onEnableMcp = { serverId ->
                vm.updateAssistant(assistant.copy(mcpServers = assistant.mcpServers + serverId))
            },
            onEnablePlugin = { pluginId ->
                vm.updateSettings { it.copy(enabledPluginIds = it.enabledPluginIds + pluginId) }
            },
            onToggleModeInjection = { injectionId ->
                val ids = if (injectionId in conversation.modeInjectionIds) {
                    conversation.modeInjectionIds - injectionId
                } else {
                    conversation.modeInjectionIds + injectionId
                }
                vm.updateConversation(conversation.copy(modeInjectionIds = ids))
                vm.saveConversationAsync()
            },
            onToggleLorebook = { lorebookId ->
                val ids = if (lorebookId in conversation.lorebookIds) {
                    conversation.lorebookIds - lorebookId
                } else {
                    conversation.lorebookIds + lorebookId
                }
                vm.updateConversation(conversation.copy(lorebookIds = ids))
                vm.saveConversationAsync()
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = conversation.title.ifBlank {
                        stringResource(R.string.chat_page_new_chat)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onShowPickerChange(true) }
                        .padding(vertical = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                DropdownMenu(
                    expanded = showPicker,
                    onDismissRequest = { onShowPickerChange(false) },
                ) {
                    conversations.forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = item.title.ifBlank {
                                        stringResource(R.string.chat_page_new_chat)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = { onSelectConversation(item.id.toString()) },
                        )
                    }
                }
            }
            IconButton(onClick = { onPinnedChange(!pinned) }) {
                Icon(
                    imageVector = if (pinned) HugeIcons.Pin else HugeIcons.PinOff,
                    contentDescription = stringResource(
                        if (pinned) R.string.desktop_floating_unpin
                        else R.string.desktop_floating_pin
                    ),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(HugeIcons.ArrowDown01, contentDescription = null)
            }
        }
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            ChatList(
                innerPadding = PaddingValues(0.dp),
                conversation = conversation,
                state = listState,
                loading = loadingJob != null,
                processingStatus = processingStatus,
                previewMode = false,
                settings = settings,
                hazeState = hazeState,
                errors = errors,
                onDismissError = vm::dismissError,
                onClearAllErrors = vm::clearAllErrors,
                onRegenerate = vm::regenerateAtMessage,
                onEdit = { message ->
                    inputState.editingMessage = message.id
                    inputState.setContents(message.parts)
                },
                onForkMessage = { message ->
                    scope.launch { vm.forkMessage(message) }
                },
                onDelete = { message ->
                    if (loadingJob != null) {
                        vm.showDeleteBlockedWhileGeneratingError()
                    } else {
                        vm.deleteMessage(message)
                    }
                },
                onUpdateMessage = { newNode ->
                    vm.updateConversation(
                        conversation.copy(
                            messageNodes = conversation.messageNodes.map { node ->
                                if (node.id == newNode.id) newNode else node
                            }
                        )
                    )
                    vm.saveConversationAsync()
                },
                onClickSuggestion = { suggestion ->
                    inputState.editingMessage = null
                    inputState.setMessageText(suggestion)
                },
                onToolApproval = vm::handleToolApproval,
                onToolAnswer = vm::handleToolAnswer,
                onToggleFavorite = vm::toggleMessageFavorite,
            )
        }

        ChatInput(
            state = inputState,
            loading = loadingJob != null,
            settings = settings,
            assistant = assistant,
            chatModel = chatModel,
            hazeState = hazeState,
            completionProviders = completionProviders,
            messageQueue = messageQueue,
            onRemoveQueuedMessage = vm::removeQueuedMessage,
            onBeginEditQueuedMessage = vm::beginEditQueuedMessage,
            onFinishEditQueuedMessage = vm::finishEditQueuedMessage,
            onResumeMessageQueue = vm::resumeMessageQueue,
            onCancelClick = vm::stopGeneration,
            onUpdateSearchMode = { mode ->
                vm.updateSearch(
                    enableWebSearch = mode == SearchMode.LOCAL,
                    builtInSearch = mode == SearchMode.BUILT_IN,
                )
            },
            onSendClick = {
                if (chatModel == null) {
                    toaster.show(selectModelMessage, type = ToastType.Error)
                    return@ChatInput
                }
                if (inputState.isEditing()) {
                    vm.handleMessageEdit(
                        parts = inputState.getContents(),
                        messageId = inputState.editingMessage!!,
                    )
                } else {
                    vm.handleMessageSend(inputState.getContents())
                    scope.launch { listState.requestScrollToItem(conversation.currentMessages.size + 5) }
                }
                inputState.clearInput()
            },
            onLongSendClick = {
                if (inputState.isEditing()) {
                    vm.handleMessageEdit(
                        parts = inputState.getContents(),
                        messageId = inputState.editingMessage!!,
                    )
                } else {
                    vm.handleMessageSend(inputState.getContents(), answer = false)
                    scope.launch { listState.requestScrollToItem(conversation.currentMessages.size + 5) }
                }
                inputState.clearInput()
            },
            onUpdateChatModel = vm::setChatModel,
            onUpdateAssistant = vm::updateAssistant,
            onUpdateSearchService = { index ->
                vm.updateSettings { it.copy(searchServiceSelected = index) }
            },
            onMoreClick = {
                toaster.show(attachmentsHint, type = ToastType.Info)
            },
        )
    }
}
