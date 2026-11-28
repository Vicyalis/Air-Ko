package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant

class TransformerContext(
    val context: Context,
    val model: Model,
    val assistant: Assistant,
    val settings: Settings,
    val processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    val workspaceCwd: String? = null,
)

interface MessageTransformer {
    /**
     * 消息转换器，用于对消息进行转换
     *
     * 对于输入消息，消息会转换被提供给API模块
     *
     * 对于输出消息，会对消息输出chunk进行转换
     */
    suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

interface InputMessageTransformer : MessageTransformer

interface OutputMessageTransformer : MessageTransformer {
    /**
     * 一个视觉的转换，例如转换think tag为reasoning parts
     * 但是不实际转换消息，因为流式输出需要处理消息delta chunk
     * 不能还没结束生成就transform，因此提供一个visualTransform
     */
    suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }

    /**
     * 消息生成完成后调用
     */
    suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        return messages
    }
}

suspend fun List<UIMessage>.transforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
    processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
    workspaceCwd: String? = null,
): List<UIMessage> {
    val ctx = TransformerContext(
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
        processingStatus = processingStatus,
        workspaceCwd = workspaceCwd,
    )
    return transformers.fold(this) { acc, transformer ->
        // 单个 transformer 出错不应该让整轮生成失败：记录后按原消息继续，
        // 发送失败的错误卡片对用户毫无意义，只会丢掉已经生成好的回复。
        try {
            transformer.transform(ctx, acc)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e("Transformer", "${transformer::class.java.simpleName}.transform failed", t)
            acc
        }
    }
}

suspend fun List<UIMessage>.visualTransforms(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            try {
                transformer.visualTransform(ctx, acc)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("Transformer", "${transformer::class.java.simpleName}.visualTransform failed", t)
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 流式场景的增量版 [visualTransforms]。
 *
 * 内置 transformer 的 visualTransform 都是逐条消息独立的；流式期间只有最后一条在变，
 * 历史消息的视觉结果与上一次相同。这里复用 [previous] 的历史部分，只重算最后一条，
 * 把每个 chunk 的 O(全部消息) 降到 O(1)。一旦消息数量或历史 id 变化
 * （例如追加了工具结果），退回全量计算，语义与直接调用 [visualTransforms] 一致。
 */
suspend fun List<UIMessage>.visualTransformsIncremental(
    previous: List<UIMessage>,
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    if (isEmpty() || previous.size != size) {
        return visualTransforms(transformers, context, model, assistant, settings)
    }
    for (index in 0 until lastIndex) {
        if (previous[index].id != this[index].id) {
            return visualTransforms(transformers, context, model, assistant, settings)
        }
    }
    // 注意：不能把 lastIndex 写在 buildList {} 里 —— 那里的隐式接收者是新建的
    // MutableList，lastIndex 会解析成 -1，导致 previous.subList(0, -1) 崩溃。
    val splitIndex = lastIndex
    val lastVisual = subList(splitIndex, size)
        .visualTransforms(transformers, context, model, assistant, settings)
    return buildList(size) {
        addAll(previous.subList(0, splitIndex))
        addAll(lastVisual)
    }
}

suspend fun List<UIMessage>.onGenerationFinish(
    transformers: List<MessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): List<UIMessage> {
    val ctx = TransformerContext(context, model, assistant, settings)
    return transformers.fold(this) { acc, transformer ->
        if (transformer is OutputMessageTransformer) {
            try {
                transformer.onGenerationFinish(ctx, acc)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e("Transformer", "${transformer::class.java.simpleName}.onGenerationFinish failed", t)
                acc
            }
        } else {
            acc
        }
    }
}
