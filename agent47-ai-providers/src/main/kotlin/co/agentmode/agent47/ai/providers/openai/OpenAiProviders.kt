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
import kotlin.math.max
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
        var startEmitted = false
        var sawFinishReason = false

        // Content blocks are assigned indices in first-appearance order so reasoning,
        // text, and tool calls never collide on contentIndex 0.
        var nextContentIndex = 0
        val thinkingBuffer = StringBuilder()
        var thinkingContentIndex = -1
        val textBuffer = StringBuilder()
        var textContentIndex = -1

        // Track tool calls being assembled from deltas, keyed by the provider's tool index.
        data class ToolCallAccumulator(
            val contentIndex: Int,
            var id: String = "",
            var name: String = "",
            val argumentsJson: StringBuilder = StringBuilder(),
        )

        val toolAccumulators = mutableMapOf<Int, ToolCallAccumulator>()

        sseResponse.events.collect { sseEvent ->
            val data =
                runCatching { Json.parseToJsonElement(sseEvent.data).jsonObject }.getOrNull() ?: return@collect

            // Parse usage at the top level (can be in any event when stream_options.include_usage=true).
            // prompt_tokens already includes cached tokens, so the billable input excludes them.
            val usage = data["usage"]?.jsonObject
            if (usage != null) {
                val cachedTokens = usage["prompt_tokens_details"]?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                if (cachedTokens != null) acc.cacheReadTokens = cachedTokens
                val promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull
                if (promptTokens != null) acc.inputTokens = max(0, promptTokens - (cachedTokens ?: acc.cacheReadTokens))
                acc.outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: acc.outputTokens
            }

            val choice = data["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect

            val delta = choice["delta"]?.jsonObject

            if (!startEmitted) {
                startEmitted = true
                stream.push(StartEvent(partial = acc.buildPartial()))
            }

            // Reasoning delta (DeepSeek `reasoning_content`, some gateways `reasoning`)
            val reasoningDelta = delta?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
                ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
            if (!reasoningDelta.isNullOrEmpty()) {
                if (thinkingContentIndex < 0) {
                    thinkingContentIndex = nextContentIndex++
                    stream.push(ThinkingStartEvent(contentIndex = thinkingContentIndex, partial = acc.buildPartial()))
                }
                thinkingBuffer.append(reasoningDelta)
                stream.push(
                    ThinkingDeltaEvent(
                        contentIndex = thinkingContentIndex,
                        delta = reasoningDelta,
                        partial = acc.buildPartialWith(ThinkingContent(thinking = thinkingBuffer.toString()))
                    )
                )
            }

            // Text delta
            val textDelta = delta?.get("content")?.jsonPrimitive?.contentOrNull
            if (!textDelta.isNullOrEmpty()) {
                if (textContentIndex < 0) {
                    textContentIndex = nextContentIndex++
                    stream.push(TextStartEvent(contentIndex = textContentIndex, partial = acc.buildPartial()))
                }
                textBuffer.append(textDelta)
                stream.push(
                    TextDeltaEvent(
                        contentIndex = textContentIndex,
                        delta = textDelta,
                        partial = acc.buildPartialWith(TextContent(text = textBuffer.toString()))
                    )
                )
            }

            // Tool call deltas
            val toolCallDeltas = delta?.get("tool_calls")?.jsonArray
            toolCallDeltas?.forEach { tcElement ->
                val tc = tcElement.jsonObject
                val tcKey = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                val tcAcc = toolAccumulators.getOrPut(tcKey) {
                    val contentIndex = nextContentIndex++
                    ToolCallAccumulator(contentIndex = contentIndex).also {
                        stream.push(ToolCallStartEvent(contentIndex = contentIndex, partial = acc.buildPartial()))
                    }
                }
                tc["id"]?.jsonPrimitive?.contentOrNull?.let { tcAcc.id = it }
                tc["function"]?.jsonObject?.let { fn ->
                    fn["name"]?.jsonPrimitive?.contentOrNull?.let { tcAcc.name = it }
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { argsDelta ->
                        tcAcc.argumentsJson.append(argsDelta)
                        stream.push(
                            ToolCallDeltaEvent(
                                contentIndex = tcAcc.contentIndex,
                                delta = argsDelta,
                                partial = acc.buildPartialWith(ToolCall(id = tcAcc.id, name = tcAcc.name))
                            )
                        )
                    }
                }
            }

            // Finish reason
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null) {
                sawFinishReason = true
                acc.stopReason = mapOpenAiFinishReason(finishReason)
            }
        }

        // Finalize content blocks, ordered so acc.blocks[contentIndex] lines up with the events.
        val finalized = mutableListOf<Pair<Int, ContentBlock>>()

        val thinking = thinkingBuffer.toString()
        if (thinkingContentIndex >= 0 && thinking.isNotBlank()) {
            finalized += thinkingContentIndex to ThinkingContent(thinking = thinking)
            stream.push(
                ThinkingEndEvent(contentIndex = thinkingContentIndex, content = thinking, partial = acc.buildPartial())
            )
        }

        val text = textBuffer.toString()
        if (textContentIndex >= 0 && text.isNotBlank()) {
            finalized += textContentIndex to TextContent(text = text)
            stream.push(TextEndEvent(contentIndex = textContentIndex, content = text, partial = acc.buildPartial()))
        }

        toolAccumulators.values.sortedBy { it.contentIndex }.forEach { tcAcc ->
            val args = parseToolArguments(tcAcc.argumentsJson.toString())
            val toolCall = ToolCall(id = tcAcc.id, name = tcAcc.name, arguments = args)
            finalized += tcAcc.contentIndex to toolCall
            stream.push(
                ToolCallEndEvent(
                    contentIndex = tcAcc.contentIndex,
                    toolCall = toolCall,
                    partial = acc.buildPartial()
                )
            )
        }

        finalized.sortBy { it.first }
        finalized.forEach { acc.blocks += it.second }

        // A stream that ended without a finish reason was truncated (severed connection).
        if (!sawFinishReason) {
            acc.stopReason = StopReason.ERROR
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
                            partial = acc.buildPartialWith(TextContent(text = textBuffer.toString()))
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
                            partial = acc.buildPartialWith(ThinkingContent(thinking = thinkingBuffer.toString()))
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
                            partial = acc.buildPartialWith(
                                ToolCall(id = currentToolId ?: "unknown", name = currentToolName ?: "unknown")
                            )
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
                        // input_tokens already includes cached tokens; exclude them from the billable input.
                        val cachedTokens = usage["input_tokens_details"]?.jsonObject
                            ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                        if (cachedTokens != null) acc.cacheReadTokens = cachedTokens
                        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull
                        if (inputTokens != null) {
                            acc.inputTokens = max(0, inputTokens - (cachedTokens ?: acc.cacheReadTokens))
                        }
                        acc.outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: acc.outputTokens
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

        // If the stream ended without response.completed/failed/error, surface the
        // accumulated content instead of relying on the generic timeout fallback.
        if (!stream.isTerminated) {
            emitStreamFinale(stream, acc)
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

private fun mapOpenAiFinishReason(reason: String): StopReason {
    return when (reason) {
        "stop" -> StopReason.STOP
        "length" -> StopReason.LENGTH
        "tool_calls", "function_call" -> StopReason.TOOL_USE
        "content_filter" -> StopReason.ERROR
        else -> StopReason.STOP
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
    if (!apiKey.isNullOrBlank() && !merged.containsKey("authorization")) {
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
                    when (message) {
                        is co.agentmode.agent47.ai.types.ToolResultMessage -> {
                            // Tool results are structured function_call_output items, paired by call_id.
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("function_call_output"))
                                    put("call_id", JsonPrimitive(message.toolCallId))
                                    put("output", JsonPrimitive(contentToText(message.content)))
                                },
                            )
                        }

                        is co.agentmode.agent47.ai.types.AssistantMessage -> {
                            val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                            if (text.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("role", JsonPrimitive("assistant"))
                                        put("content", JsonPrimitive(text))
                                    },
                                )
                            }
                            // Assistant tool calls are structured function_call items so the model can
                            // pair each function_call_output with the call that produced it.
                            message.content.filterIsInstance<ToolCall>().forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("function_call"))
                                        put("call_id", JsonPrimitive(call.id))
                                        put("name", JsonPrimitive(call.name))
                                        put("arguments", JsonPrimitive(call.arguments.toString()))
                                    },
                                )
                            }
                        }

                        else -> {
                            val contentText = when (message) {
                                is co.agentmode.agent47.ai.types.UserMessage -> contentToText(message.content)
                                is co.agentmode.agent47.ai.types.CustomMessage -> contentToText(message.content)
                                is co.agentmode.agent47.ai.types.BashExecutionMessage ->
                                    "<bash command=\"${message.command}\">\n${message.output}\n</bash>"
                                is co.agentmode.agent47.ai.types.BranchSummaryMessage -> message.summary
                                is co.agentmode.agent47.ai.types.CompactionSummaryMessage -> message.summary
                                else -> ""
                            }
                            add(
                                buildJsonObject {
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive(contentText))
                                },
                            )
                        }
                    }
                }
            },
        )
        val tools = context.tools
        if (!tools.isNullOrEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("function"))
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put("parameters", tool.parameters)
                            },
                        )
                    }
                },
            )
        }
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
