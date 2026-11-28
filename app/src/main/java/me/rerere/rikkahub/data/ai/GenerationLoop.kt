package me.rerere.rikkahub.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.HttpStatusException
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.handleTextGenerationResult
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompts.buildMemoryPrompt
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.plugin.PluginManager
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.transformers.visualTransformsIncremental
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.MemoryFile
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024

/**
 * 工具失败时只把异常类型、消息和前三帧写回模型。
 *
 * 之前把完整 stackTraceToString() 塞进工具结果，一次失败就能带进几千 token 的
 * 上下文（下一轮还会重复发送），既贵又容易触发上下文压缩。
 */
private const val MAX_TOOL_ERROR_CHARS = 1_200

private fun Throwable.toToolErrorText(): String {
    val frames = stackTrace.take(3).joinToString("\n") { "  at $it" }
    val head = "[$javaClass.name] $message"
    return (if (frames.isBlank()) head else "$head\n$frames").take(MAX_TOOL_ERROR_CHARS)
}
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")
private const val MAX_PROVIDER_NETWORK_RETRIES = 3
private const val INITIAL_PROVIDER_RETRY_DELAY_MS = 1_000L
private const val MAX_PROVIDER_RETRY_DELAY_MS = 30_000L

/** 429/5xx 属于可重试的服务端失败；408/409/425 也常见于网关与并发冲突。 */
private val RETRYABLE_HTTP_CODES = setOf(408, 409, 425, 429, 500, 502, 503, 504, 529)

private class StreamChunkHandlingException(cause: Throwable) : RuntimeException(cause)

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>
    ) : GenerationChunk
}

class GenerationLoop(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val pluginManager: PluginManager,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        memories: List<MemoryFile>? = null,
        tools: List<Tool> = emptyList(),
        maxSteps: Int = 256,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
        // 是否把已启用插件的提示词拼进系统提示词；记忆整理这类内部生成应传 false
        includePlugins: Boolean = true,
    ): Flow<GenerationChunk> = flow {
        val provider = model.findProvider(settings.providers) ?: error("Provider not found")
        val providerImpl = providerManager.getProviderByType(provider)

        val harness = settings.harness
        val effectiveMaxSteps = if (harness.customEnabled) {
            harness.maxSteps.coerceIn(1, 512)
        } else {
            maxSteps
        }
        val toolOutputLimit = if (harness.customEnabled) {
            harness.toolOutputKb.coerceIn(4, 256) * 1024
        } else {
            MAX_TOOL_OUTPUT_CHARS
        }
        val maxRetries = if (harness.customEnabled) {
            harness.networkRetries.coerceIn(0, 5)
        } else {
            MAX_PROVIDER_NETWORK_RETRIES
        }

        var messages: List<UIMessage> = messages
        // 上一次发射给 UI 的视觉结果：流式期间历史部分的视觉转换结果不变，只重算最后一条
        var visualMessages: List<UIMessage> = emptyList()

        // 插件提示词整轮生成只读一次；没有启用插件时 buildPromptSection 直接返回空串，不碰磁盘
        val pluginPrompt = if (includePlugins) {
            try {
                pluginManager.buildPromptSection(settings.enabledPluginIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 插件读取失败不能影响正常生成
                Log.w(TAG, "Failed to build plugin prompt", e)
                ""
            }
        } else {
            ""
        }

        for (stepIndex in 0 until effectiveMaxSteps) {
            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            // Check if we have tool calls ready to continue after user interaction.
            val pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                generateInternal(
                    assistant = assistant,
                    settings = settings,
                    messages = messages,
                    onUpdateMessages = {
                        messages = it.transforms(
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings
                        )
                        // 历史消息的视觉转换结果在流式期间不变，只重算最后一条，避免每个 chunk 全量 map
                        val visible = messages.visualTransformsIncremental(
                            previous = visualMessages,
                            transformers = outputTransformers,
                            context = context,
                            model = model,
                            assistant = assistant,
                            settings = settings,
                        )
                        visualMessages = visible
                        emit(GenerationChunk.Messages(visible))
                    },
                    transformers = inputTransformers,
                    model = model,
                    providerImpl = providerImpl,
                    provider = provider,
                    tools = tools,
                    memories = memories ?: emptyList(),
                    pluginPrompt = pluginPrompt,
                    stream = assistant.streamOutput,
                    processingStatus = processingStatus,
                    conversationSystemPrompt = conversationSystemPrompt,
                    conversationId = conversationId,
                    workspaceCwd = workspaceCwd,
                )
                messages = messages.visualTransforms(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                val toolCalls = messages.last().getTools().filter { !it.isExecuted }
                if (toolCalls.isEmpty()) {
                    // no tool calls, break
                    break
                }

                // Check for tools that need approval
                var hasPendingApproval = false
                val updatedTools = toolCalls.map { tool ->
                    val toolDef = tools.find { it.name == tool.toolName }
                    when {
                        // Tool needs approval and state is Auto -> set to Pending
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != toolCalls) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(GenerationChunk.Messages(messages))
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool
                        runCatching {
                            val toolDef = tools.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = runCatching {
                                json.parseToJsonElement(tool.input.ifBlank { "{}" })
                            }.getOrElse {
                                error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                            }
                            Log.i(TAG, "generateText: executing tool ${toolDef.name} with args: $args")
                            val result = toolDef.execute(args)
                            val hasShellAccess = tools.any { it.name == "workspace_shell" }
                            executedTools += tool.copy(
                                output = maybeTruncateToolOutput(tool, result, hasShellAccess, toolOutputLimit)
                            )
                        }.onFailure {
                            // 取消必须向上传播，否则停止生成会被误报为工具执行错误
                            if (it is CancellationException) throw it
                            it.printStackTrace()
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(it.toToolErrorText())
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }.flowOn(Dispatchers.IO)

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<MemoryFile>,
        pluginPrompt: String,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationId: Uuid? = null,
        workspaceCwd: String? = null,
    ) {
        val internalMessages = buildList {
            val system = buildString {
                val effectiveSystemPrompt =
                    if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                        conversationSystemPrompt
                    } else {
                        assistant.systemPrompt
                    }
                if (effectiveSystemPrompt.isNotBlank()) {
                    append(effectiveSystemPrompt)
                }

                // 记忆
                if (assistant.enableMemory) {
                    appendLine()
                    append(buildMemoryPrompt(memories))
                }
                // 工具prompt
                tools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, messages))
                }
                // 插件提示词：空 prompt / 坏文件已在 buildPromptSection 里跳过
                if (pluginPrompt.isNotBlank()) {
                    append(pluginPrompt)
                }
            }
            if (system.isNotBlank()) {
                add(UIMessage.system(prompt = system).copy(isSynthetic = true))
            }
            addAll(messages.limitContext(assistant.contextMessageLimit))
        }.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )

        var messages: List<UIMessage> = messages
        val retryLimit = if (settings.harness.customEnabled) {
            settings.harness.networkRetries.coerceIn(0, 5)
        } else {
            MAX_PROVIDER_NETWORK_RETRIES
        }
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = assistant.maxTokens,
            tools = tools,
            reasoningLevel = assistant.reasoningLevel,
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            },
            sessionId = (conversationId ?: Uuid.random()).toString(),
        )
        try {
            if (stream) {
                // 每次重试都从本次模型调用开始前的消息快照重新合并，避免将重试响应
                // 追加到已经展示的半截回复后面。预先创建助手消息可让所有尝试复用同一 ID，
                // ChatService 因而会覆盖当前分支，而不是创建新的候选消息。
                val responseBaseMessages =
                    if (messages.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        messages
                    } else {
                        messages + UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = emptyList(),
                            modelId = model.id,
                        )
                    }
                var retryCount = 0

                while (true) {
                    val streamChunkHandler = StreamChunkHandler(model)
                    var attemptMessages = responseBaseMessages
                    // 已经开始产出 token 就不能重放：再请求一次会让用户看到内容回退，也会重复计费
                    var receivedChunk = false
                    try {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params
                        ).collect { chunk ->
                            try {
                                receivedChunk = true
                                if (retryCount > 0) {
                                    processingStatus.value = null
                                }
                                attemptMessages = streamChunkHandler.handle(attemptMessages, chunk)
                                onUpdateMessages(attemptMessages)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                // 下游消息转换或 UI 更新失败不属于网络故障，不能重放模型请求。
                                throw StreamChunkHandlingException(error)
                            }
                        }
                        messages = attemptMessages
                        break
                    } catch (error: Throwable) {
                        if (error is StreamChunkHandlingException) {
                            throw error.cause ?: error
                        }
                        retryCount = awaitNetworkRetryOrThrow(
                            error = error,
                            retryCount = retryCount,
                            processingStatus = processingStatus,
                            enabled = settings.networkSetting.enableAutoRetry,
                            maxRetries = retryLimit,
                            allowRetry = { !receivedChunk },
                        )
                    }
                }
            } else {
                val result = executeProviderRequestWithRetry(
                    processingStatus = processingStatus,
                    enabled = settings.networkSetting.enableAutoRetry,
                    maxRetries = retryLimit,
                ) {
                    providerImpl.generateText(
                        providerSetting = provider,
                        messages = internalMessages,
                        params = params,
                    )
                }
                messages = messages.handleTextGenerationResult(result = result, model = model)
                onUpdateMessages(messages)
            }
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun <T> executeProviderRequestWithRetry(
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        maxRetries: Int = MAX_PROVIDER_NETWORK_RETRIES,
        block: suspend () -> T,
    ): T {
        var retryCount = 0
        while (true) {
            try {
                return block()
            } catch (error: Throwable) {
                retryCount = awaitNetworkRetryOrThrow(
                    error = error,
                    retryCount = retryCount,
                    processingStatus = processingStatus,
                    enabled = enabled,
                    maxRetries = maxRetries,
                )
            }
        }
    }

    private suspend fun awaitNetworkRetryOrThrow(
        error: Throwable,
        retryCount: Int,
        processingStatus: MutableStateFlow<String?>,
        enabled: Boolean,
        maxRetries: Int = MAX_PROVIDER_NETWORK_RETRIES,
        allowRetry: () -> Boolean = { true },
    ): Int {
        // 用户主动停止生成时，底层连接也可能以 IOException("canceled") 收尾；
        // 先检查协程状态，确保取消不会被当作网络波动重新拉起。
        currentCoroutineContext().ensureActive()
        val retryableHttp = error is HttpStatusException && error.code in RETRYABLE_HTTP_CODES
        if (!enabled || (error !is IOException && !retryableHttp) || retryCount >= maxRetries || !allowRetry()) {
            throw error
        }

        val nextRetryCount = retryCount + 1
        // 指数退避 + 抖动，避免多个并发会话在同一秒一起重试
        val baseDelay = (INITIAL_PROVIDER_RETRY_DELAY_MS shl retryCount)
            .coerceAtMost(MAX_PROVIDER_RETRY_DELAY_MS)
        val retryDelay = baseDelay + kotlin.random.Random.nextLong(0L, 250L)
        processingStatus.value = context.getString(
            R.string.chat_generation_network_retrying,
            getNetworkErrorMessage(error),
            nextRetryCount,
            maxRetries,
        )
        Log.w(
            TAG,
            "Provider connection failed, retrying in ${retryDelay}ms " +
                    "($nextRetryCount/$maxRetries)",
            error,
        )
        delay(retryDelay)
        return nextRetryCount
    }

    private fun getNetworkErrorMessage(error: Throwable): String {
        val messageRes = when (error) {
            is UnknownHostException -> R.string.chat_generation_network_unknown_host
            is SocketTimeoutException -> R.string.chat_generation_network_timeout
            is ConnectException, is NoRouteToHostException -> R.string.chat_generation_network_unreachable
            else -> R.string.chat_generation_network_disconnected
        }
        return context.getString(messageRes)
    }

    private fun maybeTruncateToolOutput(
        tool: UIMessagePart.Tool,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
        limitChars: Int = MAX_TOOL_OUTPUT_CHARS,
    ): List<UIMessagePart> {
        if (tool.toolName in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return output

        val toolCallId = tool.toolCallId
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        if (totalChars <= limitChars || !hasShellAccess) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        // 头 + 尾：只留头部会把命令末尾的报错/退出信息切掉，模型容易误判成功
        val headChars = TOOL_OUTPUT_PREVIEW_CHARS * 3 / 4
        val tailChars = TOOL_OUTPUT_PREVIEW_CHARS - headChars
        val preview = buildString {
            append(fullText.take(headChars))
            append("\n… (truncated) …\n")
            append(fullText.takeLast(tailChars))
        }

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

}
