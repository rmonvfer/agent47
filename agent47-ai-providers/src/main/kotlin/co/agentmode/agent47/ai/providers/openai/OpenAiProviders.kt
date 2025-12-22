package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.http.HttpTransport
import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.core.providers.StreamAccumulator
import co.agentmode.agent47.ai.core.providers.contentOrNull
import co.agentmode.agent47.ai.core.providers.emitError
import co.agentmode.agent47.ai.core.providers.emitStreamFinale
import co.agentmode.agent47.ai.core.providers.handleSseError
import co.agentmode.agent47.ai.core.providers.intOrNull
import co.agentmode.agent47.ai.core.providers.mergeHeaders
import co.agentmode.agent47.ai.core.providers.parseToolArguments
import co.agentmode.agent47.ai.core.providers.streamWithCoroutine
import co.agentmode.agent47.ai.core.providers.toStreamOptions
import co.agentmode.agent47.ai.types.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.text.StringBuilder
import kotlin.text.endsWith
import kotlin.text.ifBlank
import kotlin.text.isEmpty
import kotlin.text.isNotBlank
import kotlin.text.isNotEmpty
import kotlin.text.trim
import kotlin.text.trimEnd

public fun registerOpenAiProviders(transport: HttpTransport = HttpTransport()): Unit {
    ApiRegistry.register(OpenAiCompletionsProvider(transport))
    ApiRegistry.register(OpenAiResponsesProvider(transport))
    ApiRegistry.register(OpenAiCodexResponsesProvider(transport))
}

public class OpenAiCompletionsProvider(
    private val transport: HttpTransport = HttpTransport(),
) : ApiProvider {
    override val api: ApiId = KnownApis.OpenAiCompletions

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream = streamWithCoroutine(model) { stream ->
        val compat = resolveOpenAiCompat(model)
        val url = endpoint(model.baseUrl, "/chat/completions")
        val payload = buildCompletionsPayload(model, context, options, compat)
        val headers = buildHeaders(model, options)
        val sseResponse = transport.streamSse(
            url = url,
            payload = Json.encodeToString(JsonObject.serializer(), payload),
            headers = headers,
        )

        if (handleSseError(sseResponse, stream, model, "OpenAI completions")) return@streamWithCoroutine

        val acc = StreamAccumulator(api, model)
        val textBuffer = StringBuilder()
        var startEmitted = false

        // Track tool calls being assembled from deltas
        data class ToolCallAccumulator(
            val index: Int,
            var id: String = "",
            var name: String = "",
            var argumentsJson: StringBuilder = StringBuilder(),
        )

        val toolAccumulators = mutableMapOf<Int, ToolCallAccumulator>()

        sseResponse.events.collect { sseEvent ->
            val data =
                runCatching { Json.parseToJsonElement(sseEvent.data).jsonObject }.getOrNull() ?: return@collect

            // Parse usage at the top level (can be in any event when stream_options.include_usage=true)
            val usage = data["usage"]?.jsonObject
            if (usage != null) {
                acc.inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: acc.inputTokens
                acc.outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: acc.outputTokens
                acc.cacheReadTokens = usage["prompt_tokens_details"]?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: acc.cacheReadTokens
            }

            val choice = data["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect

            val delta = choice["delta"]?.jsonObject

            if (!startEmitted) {
                startEmitted = true
                stream.push(StartEvent(partial = acc.buildPartial()))
            }

            // Text delta
            val textDelta = delta?.get("content")?.jsonPrimitive?.contentOrNull
            if (!textDelta.isNullOrEmpty()) {
                if (textBuffer.isEmpty()) {
                    stream.push(TextStartEvent(contentIndex = 0, partial = acc.buildPartial()))
                }
                textBuffer.append(textDelta)
                stream.push(TextDeltaEvent(contentIndex = 0, delta = textDelta, partial = acc.buildPartial()))
            }

            // Tool call deltas
            val toolCallDeltas = delta?.get("tool_calls")?.jsonArray
            toolCallDeltas?.forEach { tcElement ->
                val tc = tcElement.jsonObject
                val tcIndex = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                val tcAcc = toolAccumulators.getOrPut(tcIndex) {
                    ToolCallAccumulator(index = tcIndex).also {
                        stream.push(ToolCallStartEvent(contentIndex = tcIndex, partial = acc.buildPartial()))
                    }
                }
                tc["id"]?.jsonPrimitive?.contentOrNull?.let { tcAcc.id = it }
                tc["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { tcAcc.name = it }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { argsDelta ->
                        tcAcc.argumentsJson.append(argsDelta)
                        stream.push(
                            ToolCallDeltaEvent(
                                contentIndex = tcIndex,
                                delta = argsDelta,
                                partial = acc.buildPartial()
                            )
                        )
                    }
                }
            }

            // Finish reason
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null) {
                acc.stopReason = when (finishReason) {
                    "stop" -> StopReason.STOP
                    "length" -> StopReason.LENGTH
                    "tool_calls" -> StopReason.TOOL_USE
                    else -> StopReason.STOP
                }
            }
        }

        // Finalize text block
        val text = textBuffer.toString()
        if (text.isNotBlank()) {
            acc.blocks += TextContent(text = text)
            stream.push(TextEndEvent(contentIndex = 0, content = text, partial = acc.buildPartial()))
        }

        // Finalize tool call blocks
        toolAccumulators.values.sortedBy { it.index }.forEach { tcAcc ->
            val args = parseToolArguments(tcAcc.argumentsJson.toString())
            val toolCall = ToolCall(id = tcAcc.id, name = tcAcc.name, arguments = args)
            acc.blocks += toolCall
            stream.push(
                ToolCallEndEvent(
                    contentIndex = tcAcc.index,
                    toolCall = toolCall,
                    partial = acc.buildPartial()
                )
            )
        }

        if (toolAccumulators.isNotEmpty()) {
            acc.stopReason = StopReason.TOOL_USE
        }

        emitStreamFinale(stream, acc)
    }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        return stream(model, context, options?.toStreamOptions())
    }
}

public class OpenAiResponsesProvider(
    private val transport: HttpTransport = HttpTransport(),
) : ApiProvider {
    override val api: ApiId = KnownApis.OpenAiResponses

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream = streamWithCoroutine(model) { stream ->
        val url = endpoint(model.baseUrl, "/responses")
        val payload = buildResponsesPayload(model, context, options)
        val headers = buildHeaders(model, options)
        val sseResponse = transport.streamSse(
            url = url,
            payload = Json.encodeToString(JsonObject.serializer(), payload),
            headers = headers,
        )

        if (handleSseError(sseResponse, stream, model, "OpenAI responses")) return@streamWithCoroutine

        val acc = StreamAccumulator(api, model)
        var startEmitted = false

        // Track current text and thinking accumulation
        var textBuffer = StringBuilder()
        var textStarted = false
        var thinkingBuffer = StringBuilder()
        var thinkingStarted = false

        // Track the current function call
        var currentToolId: String? = null
        var currentToolName: String? = null
        var currentToolArgs = StringBuilder()
        var toolCallStarted = false
        var toolContentIndex = 0

        sseResponse.events.collect { sseEvent ->
            val eventType = sseEvent.event ?: return@collect
            val data =
                runCatching { Json.parseToJsonElement(sseEvent.data).jsonObject }.getOrNull() ?: return@collect

            if (!startEmitted) {
                startEmitted = true
                stream.push(StartEvent(partial = acc.buildPartial()))
            }

            when (eventType) {
                "response.output_item.added" -> {
                    val item = data["item"]?.jsonObject ?: return@collect
                    val itemType = item["type"]?.jsonPrimitive?.contentOrNull

                    when (itemType) {
                        "function_call" -> {
                            currentToolId = item["call_id"]?.jsonPrimitive?.contentOrNull
                                ?: item["id"]?.jsonPrimitive?.contentOrNull
                            currentToolName = item["name"]?.jsonPrimitive?.contentOrNull
                            currentToolArgs = StringBuilder()
                            toolCallStarted = true
                            toolContentIndex = acc.blocks.size
                            stream.push(
                                ToolCallStartEvent(
                                    contentIndex = toolContentIndex,
                                    partial = acc.buildPartial()
                                )
                            )
                        }
                    }
                }

                "response.content_part.added" -> {
                    val part = data["part"]?.jsonObject
                    val partType = part?.get("type")?.jsonPrimitive?.contentOrNull
                    when (partType) {
                        "output_text" -> {
                            if (!textStarted) {
                                textStarted = true
                                stream.push(
                                    TextStartEvent(
                                        contentIndex = acc.blocks.size,
                                        partial = acc.buildPartial()
                                    )
                                )
                            }
                        }
                    }
                }

                "response.output_text.delta" -> {
                    val delta = data["delta"]?.jsonPrimitive?.contentOrNull ?: return@collect
                    if (!textStarted) {
                        textStarted = true
                        stream.push(TextStartEvent(contentIndex = acc.blocks.size, partial = acc.buildPartial()))
                    }
                    textBuffer.append(delta)
                    stream.push(
                        TextDeltaEvent(
                            contentIndex = acc.blocks.size,
                            delta = delta,
                            partial = acc.buildPartial()
                        )
                    )
                }

                "response.reasoning_summary_text.delta" -> {
                    val delta = data["delta"]?.jsonPrimitive?.contentOrNull ?: return@collect
                    if (!thinkingStarted) {
                        thinkingStarted = true
                        stream.push(ThinkingStartEvent(contentIndex = acc.blocks.size, partial = acc.buildPartial()))
                    }
                    thinkingBuffer.append(delta)
                    stream.push(
                        ThinkingDeltaEvent(
                            contentIndex = acc.blocks.size,
                            delta = delta,
                            partial = acc.buildPartial()
                        )
                    )
                }

                "response.reasoning_summary_part.done" -> {
                    thinkingBuffer.append("\n\n")
                }

                "response.function_call_arguments.delta" -> {
                    val delta = data["delta"]?.jsonPrimitive?.contentOrNull ?: return@collect
                    currentToolArgs.append(delta)
                    stream.push(
                        ToolCallDeltaEvent(
                            contentIndex = toolContentIndex,
                            delta = delta,
                            partial = acc.buildPartial()
                        )
                    )
                }

                "response.function_call_arguments.done" -> {
                    val finalArgs = data["arguments"]?.jsonPrimitive?.contentOrNull
                    if (finalArgs != null) {
                        currentToolArgs = StringBuilder(finalArgs)
                    }
                }

                "response.output_item.done" -> {
                    val item = data["item"]?.jsonObject ?: return@collect
                    val itemType = item["type"]?.jsonPrimitive?.contentOrNull

                    when (itemType) {
                        "message" -> {
                            // Finalize text
                            val text = textBuffer.toString()
                            if (text.isNotBlank()) {
                                acc.blocks += TextContent(text = text)
                                stream.push(
                                    TextEndEvent(
                                        contentIndex = acc.blocks.size - 1,
                                        content = text,
                                        partial = acc.buildPartial()
                                    )
                                )
                            }
                            textBuffer = StringBuilder()
                            textStarted = false
                        }

                        "reasoning" -> {
                            // Finalize thinking
                            val thinking = thinkingBuffer.toString().trim()
                            if (thinking.isNotBlank()) {
                                acc.blocks += ThinkingContent(thinking = thinking)
                                stream.push(
                                    ThinkingEndEvent(
                                        contentIndex = acc.blocks.size - 1,
                                        content = thinking,
                                        partial = acc.buildPartial()
                                    )
                                )
                            }
                            thinkingBuffer = StringBuilder()
                            thinkingStarted = false
                        }

                        "function_call" -> {
                            val argsStr = currentToolArgs.toString().ifBlank {
                                item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                            }
                            val args = parseToolArguments(argsStr)
                            val id = currentToolId
                                ?: item["call_id"]?.jsonPrimitive?.contentOrNull
                                ?: item["id"]?.jsonPrimitive?.contentOrNull
                                ?: "unknown"
                            val name = currentToolName
                                ?: item["name"]?.jsonPrimitive?.contentOrNull
                                ?: "unknown"
                            val toolCall = ToolCall(id = id, name = name, arguments = args)
                            acc.blocks += toolCall
                            stream.push(
                                ToolCallEndEvent(
                                    contentIndex = toolContentIndex,
                                    toolCall = toolCall,
                                    partial = acc.buildPartial()
                                )
                            )
                            currentToolId = null
                            currentToolName = null
                            currentToolArgs = StringBuilder()
                            toolCallStarted = false
                        }
                    }
                }

                "response.completed" -> {
                    val response = data["response"]?.jsonObject
                    val usage = response?.get("usage")?.jsonObject
                    if (usage != null) {
                        acc.inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: acc.inputTokens
                        acc.outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: acc.outputTokens
                        val details = usage["input_tokens_details"]?.jsonObject
                        acc.cacheReadTokens =
                            details?.get("cached_tokens")?.jsonPrimitive?.intOrNull ?: acc.cacheReadTokens
                    }

                    val status = response?.get("status")?.jsonPrimitive?.contentOrNull
                    acc.stopReason = when (status) {
                        "completed" -> if (acc.blocks.any { it is ToolCall }) StopReason.TOOL_USE else StopReason.STOP
                        "incomplete" -> StopReason.LENGTH
                        "failed", "cancelled" -> StopReason.ERROR
                        else -> StopReason.STOP
                    }

                    // Flush any remaining text that wasn't flushed by output_item.done
                    val text = textBuffer.toString()
                    if (text.isNotBlank() && acc.blocks.none { it is TextContent && it.text == text }) {
                        acc.blocks += TextContent(text = text)
                        stream.push(
                            TextEndEvent(
                                contentIndex = acc.blocks.size - 1,
                                content = text,
                                partial = acc.buildPartial()
                            )
                        )
                    }

                    emitStreamFinale(stream, acc)
                }

                "response.failed" -> {
                    acc.stopReason = StopReason.ERROR
                    emitError(stream, model, "OpenAI response failed: ${sseEvent.data}")
                }

                "error" -> {
                    val errorMessage = data["message"]?.jsonPrimitive?.contentOrNull ?: sseEvent.data
                    emitError(stream, model, errorMessage)
                }
            }
        }
    }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        return stream(model, context, options?.toStreamOptions())
    }
}

public class OpenAiCodexResponsesProvider(
    private val transport: HttpTransport = HttpTransport(),
) : ApiProvider {
    override val api: ApiId = KnownApis.OpenAiCodexResponses

    private val delegate: OpenAiResponsesProvider = OpenAiResponsesProvider(transport)

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream {
        return delegate.stream(
            model.copy(api = KnownApis.OpenAiResponses, provider = KnownProviders.OpenAiCodex),
            context,
            options
        )
    }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        return stream(model, context, options?.toStreamOptions())
    }
}

private fun endpoint(baseUrl: String, path: String): String {
    val normalized = baseUrl.trimEnd('/')
    return if (normalized.endsWith(path)) {
        normalized
    } else {
        "$normalized$path"
    }
}

private fun buildHeaders(model: Model, options: StreamOptions?): Map<String, String> {
    val merged = mergeHeaders(model, options)
    val apiKey = options?.apiKey
    if (apiKey != null && !merged.containsKey("authorization")) {
        merged["authorization"] = "Bearer $apiKey"
    }
    return merged
}

private fun buildCompletionsPayload(model: Model, context: Context, options: StreamOptions?, compat: OpenAiCompat): JsonObject {
    return buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("messages", buildOpenAiMessages(context, model, compat))
        put("stream", JsonPrimitive(true))
        if (compat.supportsStreamOptions) {
            put("stream_options", buildJsonObject { put("include_usage", JsonPrimitive(true)) })
        }
        options?.temperature?.let { put("temperature", JsonPrimitive(it)) }
        options?.maxTokens?.let { put(compat.maxTokensField, JsonPrimitive(it)) }
        val tools = context.tools
        if (!tools.isNullOrEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", JsonPrimitive(tool.name))
                                        put("description", JsonPrimitive(tool.description))
                                        put("parameters", tool.parameters)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }
}

private fun buildResponsesPayload(model: Model, context: Context, options: StreamOptions?): JsonObject {
    return buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("stream", JsonPrimitive(true))
        if (!context.systemPrompt.isNullOrBlank()) {
            put("instructions", JsonPrimitive(context.systemPrompt))
        }
        put(
            "input",
            buildJsonArray {
                context.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            val role = when (message) {
                                is co.agentmode.agent47.ai.types.AssistantMessage -> "assistant"
                                else -> "user"
                            }
                            put("role", JsonPrimitive(role))
                            val contentText = when (message) {
                                is co.agentmode.agent47.ai.types.ToolResultMessage -> {
                                    val errorTag = if (message.isError) " error=\"true\"" else ""
                                    "<tool_result name=\"${message.toolName}\" call_id=\"${message.toolCallId}\"$errorTag>\n${contentToText(message.content)}\n</tool_result>"
                                }
                                is co.agentmode.agent47.ai.types.UserMessage -> contentToText(message.content)
                                is co.agentmode.agent47.ai.types.AssistantMessage -> contentToText(message.content)
                                is co.agentmode.agent47.ai.types.CustomMessage -> contentToText(message.content)
                                is co.agentmode.agent47.ai.types.BashExecutionMessage ->
                                    "<bash command=\"${message.command}\">\n${message.output}\n</bash>"
                                is co.agentmode.agent47.ai.types.BranchSummaryMessage -> message.summary
                                is co.agentmode.agent47.ai.types.CompactionSummaryMessage -> message.summary
                            }
                            put("content", JsonPrimitive(contentText))
                        },
                    )
                }
            },
        )
        options?.temperature?.let { put("temperature", JsonPrimitive(it)) }
        options?.maxTokens?.let { put("max_output_tokens", JsonPrimitive(it)) }
    }
}

private fun extractResponsesText(output: JsonArray): String {
    val chunks = mutableListOf<String>()
    output.forEach { item ->
        val obj = item.jsonObject
        val content = obj["content"]?.jsonArray ?: return@forEach
        content.forEach { block ->
            val blockObj = block.jsonObject
            if (blockObj["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
                blockObj["text"]?.jsonPrimitive?.contentOrNull?.let(chunks::add)
            }
        }
    }
    return chunks.joinToString("\n").trim()
}

private fun extractResponsesToolCalls(output: JsonArray): List<ToolCall> {
    val calls = mutableListOf<ToolCall>()
    output.forEach { item ->
        val obj = item.jsonObject
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "function_call") {
            return@forEach
        }

        val id = obj["call_id"]?.jsonPrimitive?.contentOrNull
            ?: obj["id"]?.jsonPrimitive?.contentOrNull
            ?: return@forEach
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        val argsText = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
        val args = parseToolArguments(argsText)
        calls += ToolCall(id = id, name = name, arguments = args)
    }
    return calls
}
