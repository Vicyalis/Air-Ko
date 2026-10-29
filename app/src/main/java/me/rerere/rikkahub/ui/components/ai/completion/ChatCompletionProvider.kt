package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange

data class ChatCompletionContext(
    val text: String,
    val selection: TextRange,
) {
    val cursor: Int = selection.max
    val hasSelection: Boolean = selection.min != selection.max
}

data class ChatCompletionList(
    val providerId: String,
    val replacementRange: TextRange,
    val items: List<ChatCompletionItem>,
)

data class ChatCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val sortScore: Int = 0,
    /** 选中后的副作用（启用技能/MCP/插件、切换注入等）；插入文本仍由 [insertText] 完成 */
    val onSelected: (() -> Unit)? = null,
)

interface ChatCompletionProvider {
    val id: String

    suspend fun complete(context: ChatCompletionContext): ChatCompletionList?
}
