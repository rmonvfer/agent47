package co.agentmode.agent47.ai.providers.google

import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.http.HttpTransport
import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.core.providers.StreamAccumulator
import co.agentmode.agent47.ai.core.providers.contentOrNull
import co.agentmode.agent47.ai.core.providers.emitError
import co.agentmode.agent47.ai.core.providers.emitStreamFinale
import co.agentmode.agent47.ai.core.providers.intOrNull
import co.agentmode.agent47.ai.core.providers.mergeHeaders
import co.agentmode.agent47.ai.core.providers.streamWithCoroutine
import co.agentmode.agent47.ai.core.providers.toStreamOptions
import co.agentmode.agent47.ai.core.utils.UsageUtils
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.ai.types.AssistantMessage
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
import kotlin.math.max
import kotlin.text.StringBuilder
import kotlin.text.contains
import kotlin.text.isNotBlank
import kotlin.text.lowercase
import kotlin.text.replace
import kotlin.text.trim
import kotlin.text.trimEnd
import kotlin.text.uppercase
import co.agentmode.agent47.ai.types.AssistantMessage as AgentAssistantMessage

public fun registerGoogleProviders(transport: HttpTransport = HttpTransport()): Unit {
    ApiRegistry.register(
        GoogleFamilyProvider(
            KnownApis.GoogleGenerativeAi,
            "/models/{model}:streamGenerateContent",
            transport
        )
    )
    ApiRegistry.register(
        GoogleFamilyProvider(
            KnownApis.GoogleGeminiCli,
            "/models/{model}:streamGenerateContent",
            transport
        )
    )
    ApiRegistry.register(
        GoogleFamilyProvider(
            KnownApis.GoogleAntigravity,
            "/models/{model}:streamGenerateContent",
            transport
        )
    )
    ApiRegistry.register(
        GoogleFamilyProvider(
            KnownApis.GoogleVertex,
            "/models/{model}:streamGenerateContent",
            transport
        )
    )
}

public class GoogleFamilyProvider(
    override val api: ApiId,
    private val pathTemplate: String,
    private val transport: HttpTransport = HttpTransport(),
) : ApiProvider {
    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream = streamWithCoroutine(model) { stream ->
        val payload = buildPayload(context, options)
        val url = endpoint(model, options)
        val headers = buildHeaders(model, options)

        // Google's streamGenerateContent returns newline-delimited JSON chunks wrapped in a JSON array.
        // The response body looks like: [chunk1\n,chunk2\n,...,chunkN\n]
        // We use postJson here and parse the array, since Google doesn't use standard SSE format.
        val response = transport.postJson(
            url = url,
            payload = Json.encodeToString(JsonObject.serializer(), payload),
            headers = headers,
        )

        if (response.statusCode !in 200..299) {
            emitError(stream, model, "Google family API returned ${response.statusCode}: ${response.body}")
            return@streamWithCoroutine
        }

        val chunks = runCatching { Json.parseToJsonElement(response.body).jsonArray }.getOrNull()

        if (chunks == null) {
            // Try parsing as a single object (non-streaming response)
            val body = runCatching { Json.parseToJsonElement(response.body).jsonObject }.getOrNull()
            if (body == null) {
                emitError(stream, model, "Failed to parse Google response")
                return@streamWithCoroutine
            }
            handleSingleResponse(stream, model, body)
            return@streamWithCoroutine
        }

        val acc = StreamAccumulator(api, model)
        var textBuffer = StringBuilder()
        var textStarted = false
        var startEmitted = false
        var toolCallIndex = 0

        chunks.forEach { chunkElement ->
            val chunk = chunkElement.jsonObject
            val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject

            if (!startEmitted) {
                startEmitted = true
                stream.push(StartEvent(partial = acc.buildPartial()))
            }

            // Process usage metadata
            val usageMetadata = chunk["usageMetadata"]?.jsonObject
            if (usageMetadata != null) {
                val tokens = parseGoogleTokens(usageMetadata)
                acc.inputTokens = tokens.input
                acc.outputTokens = tokens.output
                acc.cacheReadTokens = tokens.cacheRead
            }

            if (candidate == null) return@forEach

            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@forEach

            parts.forEach { part ->
                val obj = part.jsonObject
                val text = obj["text"]?.jsonPrimitive?.contentOrNull

                if (text != null) {
                    if (!textStarted) {
                        textStarted = true
                        stream.push(TextStartEvent(contentIndex = acc.blocks.size, partial = acc.buildPartial()))
                    }
                    textBuffer.append(text)
                    stream.push(
                        TextDeltaEvent(
                            contentIndex = acc.blocks.size,
                            delta = text,
                            partial = acc.buildPartialWith(TextContent(text = textBuffer.toString()))
                        )
                    )
                }

                val functionCall = obj["functionCall"]?.jsonObject
                if (functionCall != null) {
                    // Google delivers tool calls as complete objects
                    val name = functionCall["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val args = functionCall["args"]?.jsonObject ?: buildJsonObject { }
                    val toolCall = ToolCall(
                        id = "google-call-$toolCallIndex",
                        name = name,
                        arguments = args,
                    )

                    // Finalize any open text block first
                    if (textStarted) {
                        val currentText = textBuffer.toString()
                        if (currentText.isNotBlank()) {
                            acc.blocks += TextContent(text = currentText)
                            stream.push(
                                TextEndEvent(
                                    contentIndex = acc.blocks.size - 1,
                                    content = currentText,
                                    partial = acc.buildPartial()
                                )
                            )
                        }
                        textBuffer = StringBuilder()
                        textStarted = false
                    }

                    val idx = acc.blocks.size
                    stream.push(ToolCallStartEvent(contentIndex = idx, partial = acc.buildPartial()))
                    acc.blocks += toolCall
                    stream.push(
                        ToolCallEndEvent(
                            contentIndex = idx,
                            toolCall = toolCall,
                            partial = acc.buildPartial()
                        )
                    )
                    toolCallIndex++
                }
            }

            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null) {
                acc.stopReason = mapGoogleFinishReason(finishReason)
            }
        }

        // Finalize text
        val text = textBuffer.toString()
        if (text.isNotBlank()) {
            acc.blocks += TextContent(text = text)
            stream.push(TextEndEvent(contentIndex = acc.blocks.size - 1, content = text, partial = acc.buildPartial()))
        }

        // A completed generation that produced tool calls is a tool-use turn, even
        // though Gemini reports finishReason STOP alongside the functionCall parts.
        if (acc.stopReason != StopReason.ERROR && acc.blocks.any { it is ToolCall }) {
            acc.stopReason = StopReason.TOOL_USE
        }

        emitStreamFinale(stream, acc)
    }

    private suspend fun handleSingleResponse(stream: AssistantMessageEventStream, model: Model, body: JsonObject) {
        val candidate = body["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val usageMetadata = body["usageMetadata"]?.jsonObject
        if (candidate == null) {
            emitError(stream, model, "Google response did not include candidates")
            return
        }

        val parsed = parseCandidate(candidate)
        val stopReason = if (parsed.toolCalls.isNotEmpty()) StopReason.TOOL_USE else mapFinishReason(candidate)

        val tokens = parseGoogleTokens(usageMetadata)
        val usage = UsageUtils.withTokenTotals(tokens.input, tokens.output, tokens.cacheRead, 0, model)

        val blocks = mutableListOf<ContentBlock>()
        if (parsed.text.isNotBlank()) {
            blocks += TextContent(text = parsed.text)
        }
        blocks += parsed.toolCalls

        val finalMessage = AssistantMessage(
            content = blocks,
            api = api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            timestamp = System.currentTimeMillis(),
        )

        stream.push(StartEvent(partial = finalMessage.copy(content = emptyList())))
        if (parsed.text.isNotBlank()) {
            val partial = finalMessage.copy(content = listOf(TextContent(text = parsed.text)))
            stream.push(TextStartEvent(contentIndex = 0, partial = partial))
            stream.push(TextDeltaEvent(contentIndex = 0, delta = parsed.text, partial = partial))
            stream.push(TextEndEvent(contentIndex = 0, content = parsed.text, partial = partial))
        }
        parsed.toolCalls.forEachIndexed { index, toolCall ->
            stream.push(ToolCallStartEvent(contentIndex = index, partial = finalMessage))
            stream.push(ToolCallEndEvent(contentIndex = index, toolCall = toolCall, partial = finalMessage))
        }
        stream.push(DoneEvent(reason = stopReason, message = finalMessage))
        stream.end(finalMessage)
    }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        return stream(model, context, options?.toStreamOptions())
    }

    private fun endpoint(model: Model, options: StreamOptions?): String {
        val path = pathTemplate.replace("{model}", model.id)
        val base = model.baseUrl.trimEnd('/')

        val explicitKey = options?.apiKey
            ?: model.headers?.get("x-goog-api-key")
            ?: model.headers?.get("X-Goog-Api-Key")

        val fullPath = "$base$path"

        if (explicitKey != null && !fullPath.contains("key=")) {
            val separator = if (fullPath.contains("?")) "&" else "?"
            return "$fullPath${separator}key=$explicitKey"
        }

        return fullPath
    }
}

private fun buildHeaders(model: Model, options: StreamOptions?): Map<String, String> {
    val headers = mergeHeaders(model, options)
    // Google headers are case-insensitive; normalize keys to lowercase
    val normalized = mutableMapOf<String, String>()
    headers.forEach { (key, value) -> normalized[key.lowercase()] = value }
    return normalized
}

private fun buildPayload(context: Context, options: StreamOptions?): JsonObject {
    return buildJsonObject {
        if (!context.systemPrompt.isNullOrBlank()) {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject {
                        put("text", JsonPrimitive(context.systemPrompt))
                    })
                })
            })
        }
        put(
            "contents",
            buildJsonArray {
                context.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive(roleForGoogle(message)))
                            put(
                                "parts",
                                buildJsonArray {
                                    if (message is ToolResultMessage) {
                                        val text = message.content
                                            .filterIsInstance<TextContent>()
                                            .joinToString("\n") { it.text }
                                        add(buildJsonObject {
                                            put("functionResponse", buildJsonObject {
                                                put("name", JsonPrimitive(message.toolName))
                                                put("response", buildJsonObject {
                                                    // Gemini has no dedicated error flag; signal failures
                                                    // through the response key so they aren't read as success.
                                                    if (message.isError) {
                                                        put("error", JsonPrimitive(text))
                                                    } else {
                                                        put("output", JsonPrimitive(text))
                                                    }
                                                })
                                            })
                                        })
                                        message.content.filterIsInstance<ImageContent>().forEach { image ->
                                            add(inlineDataPart(image))
                                        }
                                    } else {
                                        val blocks = when (message) {
                                            is UserMessage -> message.content
                                            is AgentAssistantMessage -> message.content
                                            is CustomMessage -> message.content
                                            is BashExecutionMessage -> listOf(
                                                TextContent(text = "<bash command=\"${message.command}\">\n${message.output}\n</bash>"),
                                            )
                                            is BranchSummaryMessage -> listOf(TextContent(text = message.summary))
                                            is CompactionSummaryMessage -> listOf(TextContent(text = message.summary))
                                            else -> emptyList()
                                        }
                                        val text = blocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                                        if (text.isNotBlank()) {
                                            add(buildJsonObject {
                                                put("text", JsonPrimitive(text))
                                            })
                                        }
                                        // Assistant tool calls must be sent back as functionCall parts so
                                        // Gemini can pair each functionResponse with its originating call.
                                        blocks.filterIsInstance<ToolCall>().forEach { call ->
                                            add(buildJsonObject {
                                                put("functionCall", buildJsonObject {
                                                    put("name", JsonPrimitive(call.name))
                                                    put("args", call.arguments)
                                                })
                                            })
                                        }
                                        blocks.filterIsInstance<ImageContent>().forEach { image ->
                                            add(inlineDataPart(image))
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
                    add(
                        buildJsonObject {
                            put(
                                "functionDeclarations",
                                buildJsonArray {
                                    tools.forEach { tool ->
                                        add(
                                            buildJsonObject {
                                                put("name", JsonPrimitive(tool.name))
                                                put("description", JsonPrimitive(tool.description))
                                                put("parameters", sanitizeSchemaForGoogle(tool.parameters))
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        }

        val generationConfig = buildJsonObject {
            options?.temperature?.let { put("temperature", JsonPrimitive(it)) }
            options?.maxTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
        }
        if (generationConfig.isNotEmpty()) {
            put("generationConfig", generationConfig)
        }
    }
}

private fun inlineDataPart(image: ImageContent): JsonObject = buildJsonObject {
    put("inlineData", buildJsonObject {
        put("mimeType", JsonPrimitive(image.mimeType))
        put("data", JsonPrimitive(image.data))
    })
}

private data class GoogleTokens(val input: Int, val output: Int, val cacheRead: Int)

/**
 * Extract token usage from Gemini `usageMetadata`. `promptTokenCount` already includes cached
 * tokens, so the billable input is the prompt minus the cached portion; reasoning ("thoughts")
 * tokens are added to the output count.
 */
private fun parseGoogleTokens(usageMetadata: JsonObject?): GoogleTokens {
    val prompt = usageMetadata?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
    val cached = usageMetadata?.get("cachedContentTokenCount")?.jsonPrimitive?.intOrNull ?: 0
    val candidates = usageMetadata?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
    val thoughts = usageMetadata?.get("thoughtsTokenCount")?.jsonPrimitive?.intOrNull ?: 0
    return GoogleTokens(input = max(0, prompt - cached), output = candidates + thoughts, cacheRead = cached)
}

private data class ParsedCandidate(
    val text: String,
    val toolCalls: List<ToolCall>,
)

private fun parseCandidate(candidate: JsonObject): ParsedCandidate {
    val parts = candidate["content"]
        ?.jsonObject
        ?.get("parts")
        ?.jsonArray
        ?: JsonArray(emptyList())

    val textChunks = mutableListOf<String>()
    val calls = mutableListOf<ToolCall>()

    parts.forEachIndexed { index, part ->
        val obj = part.jsonObject
        obj["text"]?.jsonPrimitive?.contentOrNull?.let(textChunks::add)

        val functionCall = obj["functionCall"]?.jsonObject
        if (functionCall != null) {
            val name = functionCall["name"]?.jsonPrimitive?.contentOrNull
            val args = functionCall["args"]?.jsonObject
            if (name != null) {
                calls += ToolCall(
                    id = "google-call-$index",
                    name = name,
                    arguments = args ?: buildJsonObject { },
                )
            }
        }
    }

    return ParsedCandidate(text = textChunks.joinToString("\n").trim(), toolCalls = calls)
}

private fun mapFinishReason(candidate: JsonObject): StopReason {
    return mapGoogleFinishReason(candidate["finishReason"]?.jsonPrimitive?.contentOrNull)
}

/**
 * Map a Gemini `finishReason` to a stop reason. Safety/recitation/blocklist and other abnormal
 * terminations become [StopReason.ERROR] instead of a silent [StopReason.STOP] so the agent loop
 * and retry logic can see that the generation did not complete normally.
 */
private fun mapGoogleFinishReason(reason: String?): StopReason {
    return when (reason?.uppercase()) {
        "STOP", null -> StopReason.STOP
        "MAX_TOKENS" -> StopReason.LENGTH
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
        "IMAGE_SAFETY", "MALFORMED_FUNCTION_CALL", "OTHER", "FINISH_REASON_UNSPECIFIED",
        -> StopReason.ERROR
        else -> StopReason.STOP
    }
}

private fun roleForGoogle(message: Message): String {
    return when (message.role) {
        "assistant" -> "model"
        else -> "user"
    }
}

private val UNSUPPORTED_SCHEMA_FIELDS = setOf(
    "\$schema", "\$ref", "\$defs",
    "examples", "default", "pattern", "patternProperties",
    "minItems", "maxItems", "minLength", "maxLength",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
    "additionalProperties", "format", "title",
)

internal fun sanitizeSchemaForGoogle(schema: JsonElement): JsonElement {
    return when (schema) {
        is JsonObject -> {
            val filtered = schema.filterKeys { it !in UNSUPPORTED_SCHEMA_FIELDS }
            JsonObject(filtered.mapValues { (_, value) -> sanitizeSchemaForGoogle(value) })
        }
        is JsonArray -> {
            JsonArray(schema.map { sanitizeSchemaForGoogle(it) })
        }
        else -> schema
    }
}
