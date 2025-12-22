package co.agentmode.agent47.ai.providers.anthropic

import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.http.HttpTransport
import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.core.providers.StreamAccumulator
import co.agentmode.agent47.ai.core.providers.contentOrNull
import co.agentmode.agent47.ai.core.providers.emitStreamFinale
import co.agentmode.agent47.ai.core.providers.handleSseError
import co.agentmode.agent47.ai.core.providers.intOrNull
import co.agentmode.agent47.ai.core.providers.mergeHeaders
import co.agentmode.agent47.ai.core.providers.normalizeToolName
import co.agentmode.agent47.ai.core.providers.parseToolArguments
import co.agentmode.agent47.ai.core.providers.streamWithCoroutine
import co.agentmode.agent47.ai.core.providers.toStreamOptions
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StartEvent
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.StreamOptions
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.TextDeltaEvent
import co.agentmode.agent47.ai.types.TextEndEvent
import co.agentmode.agent47.ai.types.TextStartEvent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ThinkingDeltaEvent
import co.agentmode.agent47.ai.types.ThinkingEndEvent
import co.agentmode.agent47.ai.types.ThinkingStartEvent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolCallDeltaEvent
import co.agentmode.agent47.ai.types.ToolCallEndEvent
import co.agentmode.agent47.ai.types.ToolCallStartEvent
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public fun registerAnthropicProviders(transport: HttpTransport = HttpTransport()) {
    ApiRegistry.register(AnthropicMessagesProvider(transport))
}

public class AnthropicMessagesProvider(
    private val transport: HttpTransport = HttpTransport(),
) : ApiProvider {
    override val api: ApiId = KnownApis.AnthropicMessages

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream = streamWithCoroutine(model) { stream ->
        val payload = buildPayload(model, context, options)
        val headers = buildHeaders(model, options)
        val sseResponse = transport.streamSse(
            url = endpoint(model.baseUrl),
            payload = Json.encodeToString(JsonObject.serializer(), payload),
            headers = headers,
        )

        if (handleSseError(sseResponse, stream, model, "Anthropic")) return@streamWithCoroutine

        val acc = StreamAccumulator(api, model)

        // Track the current block being streamed: type + accumulated data
        var currentBlockType: String? = null
        var currentBlockIndex = -1
        var currentToolId: String? = null
        var currentToolName: String? = null
        var currentPartialJson = StringBuilder()
        var currentText = StringBuilder()
        var currentThinking = StringBuilder()

        sseResponse.events.collect { sseEvent ->
            val eventType = sseEvent.event ?: return@collect
            val data = runCatching { Json.parseToJsonElement(sseEvent.data).jsonObject }.getOrNull() ?: return@collect

            when (eventType) {
                "message_start" -> {
                    val usage = data["message"]?.jsonObject?.get("usage")?.jsonObject
                    if (usage != null) {
                        acc.inputTokens += usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        acc.outputTokens += usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        acc.cacheReadTokens += usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                        acc.cacheWriteTokens += usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
                    }
                    stream.push(StartEvent(partial = acc.buildPartial()))
                }

                "content_block_start" -> {
                    val index = data["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val contentBlock = data["content_block"]?.jsonObject
                    val blockType = contentBlock?.get("type")?.jsonPrimitive?.contentOrNull

                    currentBlockIndex = index

                    when (blockType) {
                        "text" -> {
                            currentBlockType = "text"
                            currentText = StringBuilder()
                            stream.push(TextStartEvent(contentIndex = index, partial = acc.buildPartial()))
                        }
                        "thinking" -> {
                            currentBlockType = "thinking"
                            currentThinking = StringBuilder()
                            stream.push(ThinkingStartEvent(contentIndex = index, partial = acc.buildPartial()))
                        }
                        "tool_use" -> {
                            currentBlockType = "tool_use"
                            currentToolId = contentBlock["id"]?.jsonPrimitive?.contentOrNull
                            currentToolName = contentBlock["name"]?.jsonPrimitive?.contentOrNull
                            currentPartialJson = StringBuilder()
                            stream.push(ToolCallStartEvent(contentIndex = index, partial = acc.buildPartial()))
                        }
                    }
                }

                "content_block_delta" -> {
                    val delta = data["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.contentOrNull

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                            currentText.append(text)
                            stream.push(TextDeltaEvent(contentIndex = currentBlockIndex, delta = text, partial = acc.buildPartial()))
                        }
                        "thinking_delta" -> {
                            val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                            currentThinking.append(thinking)
                            stream.push(ThinkingDeltaEvent(contentIndex = currentBlockIndex, delta = thinking, partial = acc.buildPartial()))
                        }
                        "input_json_delta" -> {
                            val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                            currentPartialJson.append(partialJson)
                            stream.push(ToolCallDeltaEvent(contentIndex = currentBlockIndex, delta = partialJson, partial = acc.buildPartial()))
                        }
                    }
                }

                "content_block_stop" -> {
                    when (currentBlockType) {
                        "text" -> {
                            val text = currentText.toString()
                            if (text.isNotBlank()) {
                                acc.blocks += TextContent(text = text)
                            }
                            stream.push(TextEndEvent(contentIndex = currentBlockIndex, content = text, partial = acc.buildPartial()))
                        }
                        "thinking" -> {
                            val thinking = currentThinking.toString()
                            if (thinking.isNotBlank()) {
                                acc.blocks += ThinkingContent(thinking = thinking)
                            }
                            stream.push(ThinkingEndEvent(contentIndex = currentBlockIndex, content = thinking, partial = acc.buildPartial()))
                        }
                        "tool_use" -> {
                            val args = parseToolArguments(currentPartialJson.toString())
                            val toolCall = ToolCall(
                                id = currentToolId ?: "unknown",
                                name = normalizeToolName(currentToolName ?: "unknown"),
                                arguments = args,
                            )
                            acc.blocks += toolCall
                            stream.push(ToolCallEndEvent(contentIndex = currentBlockIndex, toolCall = toolCall, partial = acc.buildPartial()))
                        }
                    }
                    currentBlockType = null
                }

                "message_delta" -> {
                    val delta = data["delta"]?.jsonObject
                    val reason = delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull
                    acc.stopReason = mapStopReason(reason)

                    val usage = data["usage"]?.jsonObject
                    if (usage != null) {
                        acc.outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: acc.outputTokens
                    }
                }

                "message_stop" -> {
                    emitStreamFinale(stream, acc)
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

private fun mapStopReason(reason: String?): StopReason {
    return when (reason) {
        "end_turn" -> StopReason.STOP
        "max_tokens" -> StopReason.LENGTH
        "tool_use" -> StopReason.TOOL_USE
        "stop_sequence" -> StopReason.STOP
        else -> StopReason.STOP
    }
}

private fun endpoint(baseUrl: String): String {
    val normalized = baseUrl.trimEnd('/')
    return if (normalized.endsWith("/messages")) normalized else "$normalized/messages"
}

private fun buildHeaders(model: Model, options: StreamOptions?): Map<String, String> {
    val headers = mergeHeaders(model, options)
    headers["anthropic-version"] = "2023-06-01"
    options?.apiKey?.let { apiKey -> headers.putIfAbsent("x-api-key", apiKey) }
    return headers
}

private fun normalizeAnthropicToolId(id: String): String {
    val sanitized = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return if (sanitized.length > 40) sanitized.take(40) else sanitized
}

private fun buildPayload(model: Model, context: Context, options: StreamOptions?): JsonObject {
    val raw = buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("max_tokens", JsonPrimitive(options?.maxTokens ?: model.maxTokens))
        put("stream", JsonPrimitive(true))
        options?.temperature?.let { put("temperature", JsonPrimitive(it)) }

        context.systemPrompt?.let { put("system", JsonPrimitive(it)) }

        put(
            "messages",
            buildJsonArray {
                context.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(if (message.role == "toolResult") "user" else message.role))
                            put(
                                "content",
                                buildJsonArray {
                                    if (message is ToolResultMessage) {
                                        val text = message.content
                                            .filterIsInstance<TextContent>()
                                            .joinToString("\n") { it.text }
                                        add(buildJsonObject {
                                            put("type", JsonPrimitive("tool_result"))
                                            put("tool_use_id", JsonPrimitive(normalizeAnthropicToolId(message.toolCallId)))
                                            put("content", JsonPrimitive(text))
                                            if (message.isError) {
                                                put("is_error", JsonPrimitive(true))
                                            }
                                        })
                                    } else {
                                        val blocks = when (message) {
                                            is UserMessage -> message.content
                                            is AssistantMessage -> message.content
                                            is CustomMessage -> message.content
                                            is BashExecutionMessage -> listOf(
                                                TextContent(text = "<bash command=\"${message.command}\">\n${message.output}\n</bash>"),
                                            )
                                            is BranchSummaryMessage -> listOf(
                                                TextContent(text = message.summary),
                                            )
                                            is CompactionSummaryMessage -> listOf(
                                                TextContent(text = message.summary),
                                            )
                                            else -> emptyList()
                                        }
                                        blocks.forEach { block ->
                                            when (block) {
                                                is TextContent -> add(buildJsonObject {
                                                    put("type", JsonPrimitive("text"))
                                                    put("text", JsonPrimitive(block.text))
                                                })

                                                is ThinkingContent -> add(buildJsonObject {
                                                    put("type", JsonPrimitive("thinking"))
                                                    put("thinking", JsonPrimitive(block.thinking))
                                                })

                                                is ToolCall -> add(buildJsonObject {
                                                    put("type", JsonPrimitive("tool_use"))
                                                    put("id", JsonPrimitive(normalizeAnthropicToolId(block.id)))
                                                    put("name", JsonPrimitive(block.name))
                                                    put("input", block.arguments)
                                                })

                                                else -> {
                                                    // unsupported block for this provider
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        },
                    )
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
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(tool.description))
                                put("input_schema", tool.parameters)
                            },
                        )
                    }
                },
            )
        }
    }

    return applyCacheBreakpoints(raw)
}

private val CACHE_CONTROL = buildJsonObject {
    put("type", JsonPrimitive("ephemeral"))
}

private fun applyCacheBreakpoints(payload: JsonObject): JsonObject {
    val mutablePayload = payload.toMutableMap()
    var breakpointsRemaining = 4

    // 1. Last tool definition in the tools array
    val tools = mutablePayload["tools"]?.let { it as? JsonArray }
    if (tools != null && tools.isNotEmpty() && breakpointsRemaining > 0) {
        val lastIndex = tools.size - 1
        val lastTool = tools[lastIndex].jsonObject
        val updated = JsonObject(lastTool.toMutableMap().apply { put("cache_control", CACHE_CONTROL) })
        val updatedTools = buildJsonArray {
            tools.forEachIndexed { index, element ->
                if (index == lastIndex) add(updated) else add(element)
            }
        }
        mutablePayload["tools"] = updatedTools
        breakpointsRemaining--
    }

    // 2. System prompt: convert string to block array format with cache_control
    val system = mutablePayload["system"]
    if (system != null && breakpointsRemaining > 0) {
        when (system) {
            is JsonPrimitive -> {
                mutablePayload["system"] = buildJsonArray {
                    add(buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", system)
                        put("cache_control", CACHE_CONTROL)
                    })
                }
                breakpointsRemaining--
            }
            is JsonArray -> {
                if (system.isNotEmpty()) {
                    val lastIndex = system.size - 1
                    val lastBlock = system[lastIndex].jsonObject
                    val updated = JsonObject(lastBlock.toMutableMap().apply { put("cache_control", CACHE_CONTROL) })
                    mutablePayload["system"] = buildJsonArray {
                        system.forEachIndexed { index, element ->
                            if (index == lastIndex) add(updated) else add(element)
                        }
                    }
                    breakpointsRemaining--
                }
            }
            else -> {}
        }
    }

    // 3 & 4. Penultimate and final user messages
    val messages = mutablePayload["messages"]?.let { it as? JsonArray }
    if (messages != null && breakpointsRemaining > 0) {
        val userIndices = messages.indices.filter { i ->
            messages[i].jsonObject["role"]?.jsonPrimitive?.contentOrNull == "user"
        }

        val indicesToMark = mutableListOf<Int>()
        if (userIndices.size >= 2) {
            indicesToMark.add(userIndices[userIndices.size - 2])
        }
        if (userIndices.isNotEmpty()) {
            indicesToMark.add(userIndices.last())
        }

        if (indicesToMark.isNotEmpty()) {
            val markedSet = mutableSetOf<Int>()
            for (idx in indicesToMark) {
                if (breakpointsRemaining <= 0) break
                markedSet.add(idx)
                breakpointsRemaining--
            }

            mutablePayload["messages"] = buildJsonArray {
                messages.forEachIndexed { index, element ->
                    if (index in markedSet) {
                        add(addCacheControlToLastContentBlock(element.jsonObject))
                    } else {
                        add(element)
                    }
                }
            }
        }
    }

    return JsonObject(mutablePayload)
}

private fun addCacheControlToLastContentBlock(message: JsonObject): JsonObject {
    val content = message["content"]?.let { it as? JsonArray } ?: return message
    if (content.isEmpty()) return message

    val lastIndex = content.size - 1
    val lastBlock = content[lastIndex].jsonObject
    val updatedBlock = JsonObject(lastBlock.toMutableMap().apply { put("cache_control", CACHE_CONTROL) })

    val updatedContent = buildJsonArray {
        content.forEachIndexed { index, element ->
            if (index == lastIndex) add(updatedBlock) else add(element)
        }
    }

    return JsonObject(message.toMutableMap().apply { put("content", updatedContent) })
}
