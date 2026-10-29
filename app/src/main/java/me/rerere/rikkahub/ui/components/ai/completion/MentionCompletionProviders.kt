package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book03
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.Puzzle
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getQuickMessagesOfAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.plugin.InstalledPlugin
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import kotlin.uuid.Uuid

/**
 * `/` 与 `@` 的候选来源。
 *
 * - `/`：技能、MCP、插件（启用/绑定到当前会话）；
 * - `@`：快捷消息（插入正文）、模式注入与 Lorebook（切换当前会话绑定）；
 *   工作区文件候选仍由 WorkspaceCompletionProvider 提供，两者在同一 range 上合并。
 */

private const val MAX_ITEMS = 8

private fun mentionRange(context: ChatCompletionContext, trigger: Char): TextRange? {
    if (context.hasSelection) return null
    val text = context.text
    val cursor = context.cursor.coerceIn(0, text.length)
    if (cursor <= 0) return null
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    if (start >= cursor) return null
    if (text[start] != trigger) return null
    return TextRange(start, cursor)
}

private fun queryOf(context: ChatCompletionContext, range: TextRange): String =
    context.text.substring(range.min + 1, range.max).trim().lowercase()

class SkillCompletionProvider(
    private val skillManager: SkillManager,
    private val onSelect: (String) -> Unit,
) : ChatCompletionProvider {
    override val id: String = "mention_skills"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = mentionRange(context, '/') ?: return null
        val query = queryOf(context, range)
        val skills = withContext(Dispatchers.IO) { skillManager.listSkills() }
        val items = skills
            .asSequence()
            .filter { skill ->
                query.isEmpty() ||
                    skill.name.lowercase().contains(query) ||
                    skill.description.lowercase().contains(query)
            }
            .sortedWith(
                compareByDescending<SkillMetadata> { it.name.lowercase().startsWith(query) }
                    .thenBy { it.name.lowercase() }
            )
            .take(MAX_ITEMS)
            .map { skill ->
                ChatCompletionItem(
                    label = skill.name,
                    insertText = "/${skill.name} ",
                    detail = skill.description.takeIf { it.isNotBlank() },
                    icon = HugeIcons.Bookshelf01,
                    sortScore = if (skill.name.lowercase().startsWith(query)) 30 else 10,
                    onSelected = { onSelect(skill.name) },
                )
            }
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(id, range, items)
    }
}

class McpCompletionProvider(
    private val servers: List<McpServerConfig>,
    private val onSelect: (Uuid) -> Unit,
) : ChatCompletionProvider {
    override val id: String = "mention_mcp"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = mentionRange(context, '/') ?: return null
        val query = queryOf(context, range)
        val items = servers
            .asSequence()
            .filter { server ->
                query.isEmpty() || server.commonOptions.name.lowercase().contains(query)
            }
            .take(MAX_ITEMS)
            .map { server ->
                val transport = when (server) {
                    is McpServerConfig.SseTransportServer -> "SSE"
                    is McpServerConfig.StreamableHTTPServer -> "Streamable HTTP"
                }
                ChatCompletionItem(
                    label = server.commonOptions.name,
                    insertText = "/${server.commonOptions.name} ",
                    detail = "MCP · $transport",
                    icon = HugeIcons.McpServer,
                    sortScore = if (server.commonOptions.name.lowercase().startsWith(query)) 30 else 10,
                    onSelected = { onSelect(server.id) },
                )
            }
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(id, range, items)
    }
}

class PluginCompletionProvider(
    private val plugins: List<InstalledPlugin>,
    private val onSelect: (String) -> Unit,
) : ChatCompletionProvider {
    override val id: String = "mention_plugins"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = mentionRange(context, '/') ?: return null
        val query = queryOf(context, range)
        val items = plugins
            .asSequence()
            .filter { plugin ->
                query.isEmpty() ||
                    plugin.manifest.name.lowercase().contains(query) ||
                    plugin.manifest.description.lowercase().contains(query)
            }
            .take(MAX_ITEMS)
            .map { plugin ->
                ChatCompletionItem(
                    label = plugin.manifest.name,
                    insertText = "/${plugin.manifest.name} ",
                    detail = buildString {
                        append("Plugin")
                        if (plugin.enabled) append(" · enabled")
                    },
                    icon = HugeIcons.Puzzle,
                    sortScore = if (plugin.manifest.name.lowercase().startsWith(query)) 30 else 10,
                    onSelected = { onSelect(plugin.manifest.id) },
                )
            }
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(id, range, items)
    }
}

class QuickMessageCompletionProvider(
    private val quickMessages: List<QuickMessage>,
) : ChatCompletionProvider {
    override val id: String = "mention_quick_messages"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = mentionRange(context, '@') ?: return null
        val query = queryOf(context, range)
        val items = quickMessages
            .asSequence()
            .filter { message ->
                query.isEmpty() ||
                    message.title.lowercase().contains(query) ||
                    message.content.lowercase().contains(query)
            }
            .take(MAX_ITEMS)
            .map { message ->
                ChatCompletionItem(
                    label = message.title.ifBlank { message.content.take(24) },
                    insertText = message.content,
                    detail = message.content.take(60).replace('\n', ' '),
                    icon = HugeIcons.MessageAdd01,
                    sortScore = if (message.title.lowercase().startsWith(query)) 30 else 10,
                )
            }
            .toList()
        if (items.isEmpty()) return null
        return ChatCompletionList(id, range, items)
    }
}

class PromptCompletionProvider(
    private val modeInjections: List<PromptInjection.ModeInjection>,
    private val lorebooks: List<Lorebook>,
    private val boundModeInjectionIds: Set<Uuid>,
    private val boundLorebookIds: Set<Uuid>,
    private val onToggleModeInjection: (Uuid) -> Unit,
    private val onToggleLorebook: (Uuid) -> Unit,
) : ChatCompletionProvider {
    override val id: String = "mention_prompts"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        val range = mentionRange(context, '@') ?: return null
        val query = queryOf(context, range)

        val injections = modeInjections
            .asSequence()
            .filter { injection ->
                query.isEmpty() ||
                    injection.name.lowercase().contains(query) ||
                    injection.content.lowercase().contains(query)
            }
            .take(MAX_ITEMS)
            .map { injection ->
                val bound = injection.id in boundModeInjectionIds
                ChatCompletionItem(
                    label = injection.name,
                    insertText = "@${injection.name} ",
                    detail = buildString {
                        append("Mode injection")
                        if (bound) append(" · bound")
                    },
                    icon = HugeIcons.Book03,
                    sortScore = if (bound) 20 else 10,
                    onSelected = { onToggleModeInjection(injection.id) },
                )
            }
            .toList()

        val books = lorebooks
            .asSequence()
            .filter { book ->
                query.isEmpty() ||
                    book.name.lowercase().contains(query) ||
                    book.description.lowercase().contains(query)
            }
            .take(MAX_ITEMS)
            .map { book ->
                val bound = book.id in boundLorebookIds
                ChatCompletionItem(
                    label = book.name,
                    insertText = "@${book.name} ",
                    detail = buildString {
                        append("Lorebook")
                        if (bound) append(" · bound")
                    },
                    icon = HugeIcons.Book04,
                    sortScore = if (bound) 20 else 10,
                    onSelected = { onToggleLorebook(book.id) },
                )
            }
            .toList()

        val items = (injections + books)
            .sortedWith(
                compareByDescending<ChatCompletionItem> { it.sortScore }
                    .thenBy { it.label.lowercase() }
            )
            .take(MAX_ITEMS)
        if (items.isEmpty()) return null
        return ChatCompletionList(id, range, items)
    }
}

/** 组装 `/` 与 `@` 的候选；工作区文件候选由调用方另行追加。 */
fun buildMentionCompletionProviders(
    settings: Settings,
    assistant: Assistant,
    conversation: Conversation,
    skillManager: SkillManager,
    plugins: List<InstalledPlugin>,
    onEnableSkill: (String) -> Unit,
    onEnableMcp: (Uuid) -> Unit,
    onEnablePlugin: (String) -> Unit,
    onToggleModeInjection: (Uuid) -> Unit,
    onToggleLorebook: (Uuid) -> Unit,
): List<ChatCompletionProvider> = listOf(
    SkillCompletionProvider(skillManager, onEnableSkill),
    McpCompletionProvider(settings.mcpServers, onEnableMcp),
    PluginCompletionProvider(plugins, onEnablePlugin),
    QuickMessageCompletionProvider(settings.getQuickMessagesOfAssistant(assistant)),
    PromptCompletionProvider(
        modeInjections = settings.modeInjections,
        lorebooks = settings.lorebooks,
        boundModeInjectionIds = conversation.modeInjectionIds,
        boundLorebookIds = conversation.lorebookIds,
        onToggleModeInjection = onToggleModeInjection,
        onToggleLorebook = onToggleLorebook,
    ),
)
