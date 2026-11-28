package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.TextQuote
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.copyMessageToClipboard

@Composable
fun ChatMessageCopySheet(
    message: UIMessage,
    onDismissRequest: () -> Unit,
    /** 把「选中的文字」作为引用带回输入框；没有选区时引用整条消息 */
    onQuote: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val fullText = remember(message) {
        message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { it.text }
    }
    // 只读 TextField：相比 SelectionContainer，它能拿到真实的选区范围，
    // 这是「选中一段内容并追问」的前提。
    val textState = remember(message) { TextFieldState(fullText) }
    val quoteSelection = {
        val selection = textState.selection
        val selected = if (!selection.collapsed) {
            textState.text.subSequence(selection.min, selection.max).toString()
        } else {
            fullText
        }
        onQuote?.invoke(selected)
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                    }
                ) {
                    Icon(HugeIcons.Cancel01, null)
                }

                Text(
                    text = stringResource(R.string.select_and_copy),
                    style = MaterialTheme.typography.headlineSmall,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onQuote != null) {
                        TextButton(
                            onClick = {
                                quoteSelection()
                                onDismissRequest()
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.TextQuote,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.chat_message_quote))
                        }
                    }
                    TextButton(
                        onClick = {
                            context.copyMessageToClipboard(message)
                            onDismissRequest()
                        }
                    ) {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.copy_all))
                    }
                }
            }

            // Content
            val textParts =
                message.parts.filterIsInstance<UIMessagePart.Text>().filter { it.text.isNotBlank() }

            if (textParts.isEmpty()) {
                // No text content available
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_text_content_to_copy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            } else if (onQuote != null) {
                // 只读 TextField 能直接读出选区，不需要经过剪贴板
                val quoteToolbar = remember(textState, onQuote) {
                    QuoteTextToolbar(
                        context = context,
                        selectionText = {
                            val selection = textState.selection
                            if (selection.collapsed) {
                                null
                            } else {
                                textState.text.subSequence(selection.min, selection.max).toString()
                            }
                        },
                        onQuote = onQuote,
                    )
                }
                CompositionLocalProvider(LocalTextToolbar provides quoteToolbar) {
                    BasicTextField(
                        state = textState,
                        readOnly = true,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            } else {
                BasicTextField(
                    state = textState,
                    readOnly = true,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
    }
}
