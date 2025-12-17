package co.agentmode.agent47.ai.core.providers

import co.agentmode.agent47.ai.core.http.SseResponse
import co.agentmode.agent47.ai.core.utils.UsageUtils
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.DoneEvent
import co.agentmode.agent47.ai.types.ErrorEvent
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.StreamOptions
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.emptyUsage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

public fun SimpleStreamOptions.toStreamOptions(): StreamOptions {
    return StreamOptions(
        temperature = temperature,
        maxTokens = maxTokens,
        apiKey = apiKey,
        cacheRetention = cacheRetention,
        sessionId = sessionId,
        headers = headers,
        maxRetryDelayMs = maxRetryDelayMs,
        metadata = metadata,
        onPayload = onPayload,
    )
}

public fun emitError(
    stream: AssistantMessageEventStream,
    model: Model,
    message: String,
) {
    val errorMessage = AssistantMessage(
        content = listOf(TextContent(text = message)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = emptyUsage(),
        stopReason = StopReason.ERROR,
        errorMessage = message,
        timestamp = System.currentTimeMillis(),
    )
    stream.push(ErrorEvent(reason = StopReason.ERROR, error = errorMessage))
}

public val JsonPrimitive.contentOrNull: String?
    get() = when {
        this is kotlinx.serialization.json.JsonNull -> null
        isString -> content
        else -> runCatching { content }.getOrNull()
    }

public val JsonPrimitive.intOrNull: Int?
    get() = contentOrNull?.toIntOrNull()

public val JsonPrimitive.longOrNull: Long?
    get() = contentOrNull?.toLongOrNull()

/**
 * Accumulates streamed content blocks, token counters, and stop reason
 * during SSE streaming. Shared across all SSE-based providers to eliminate
 * the duplicated mutable state + buildPartial() pattern.
 */
public class StreamAccumulator(
    private val api: ApiId,
    private val model: Model,
) {
    public val blocks: MutableList<ContentBlock> = mutableListOf()
    public var inputTokens: Int = 0
    public var outputTokens: Int = 0
    public var cacheReadTokens: Int = 0
    public var cacheWriteTokens: Int = 0
    public var stopReason: StopReason = StopReason.STOP

    public fun buildPartial(): AssistantMessage {
        val usage = UsageUtils.withTokenTotals(
            inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, model,
        )
        return AssistantMessage(
            content = blocks.toList(),
            api = api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            timestamp = System.currentTimeMillis(),
        )
    }

    public fun buildFinal(): AssistantMessage {
        if (blocks.any { it is ToolCall }) {
            stopReason = StopReason.TOOL_USE
        }
        return buildPartial().copy(stopReason = stopReason)
    }
}

/**
 * Creates an [AssistantMessageEventStream], launches a coroutine on [Dispatchers.IO],
 * and wraps the body in runCatching with automatic error emission on failure.
 */
public fun streamWithCoroutine(
    model: Model,
    body: suspend (stream: AssistantMessageEventStream) -> Unit,
): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    CoroutineScope(Dispatchers.IO).launch {
        runCatching {
            body(stream)
        }.onFailure { throwable ->
            emitError(stream, model, throwable.message ?: throwable.toString())
        }
        // If the body completed (normally or via error handler above) without pushing
        // a terminal event, the stream's channel stays open and any collector hangs
        // forever. This can happen when the SSE connection closes prematurely or the
        // final event is dropped. Push a fallback error to unblock the collector.
        // push() is a no-op if the stream is already terminated.
        if (!stream.isTerminated) {
            emitError(stream, model, "Stream ended without a complete response")
        }
    }
    return stream
}

/**
 * Handles non-2xx SSE responses by collecting the error body and emitting an error event.
 * Returns true if an error was handled (caller should return), false if status is OK.
 */
public suspend fun handleSseError(
    sseResponse: SseResponse,
    stream: AssistantMessageEventStream,
    model: Model,
    providerLabel: String,
): Boolean {
    if (sseResponse.statusCode in 200..299) return false
    val errorBody = StringBuilder()
    sseResponse.events.collect { event -> errorBody.append(event.data) }
    emitError(stream, model, "$providerLabel returned ${sseResponse.statusCode}: $errorBody")
    return true
}

/**
 * Parses a JSON string as tool call arguments, returning an empty object on failure.
 */
public fun parseToolArguments(json: String): JsonObject {
    return runCatching { Json.parseToJsonElement(json).jsonObject }.getOrElse { buildJsonObject { } }
}

/**
 * Normalizes a tool name by replacing non-alphanumeric characters (except _ and -) with underscores.
 */
public fun normalizeToolName(name: String): String {
    return name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
}

/**
 * Merges model headers and options headers into a single map.
 * Provider-specific headers (auth, version) should be added after calling this.
 */
public fun mergeHeaders(model: Model, options: StreamOptions?): MutableMap<String, String> {
    val headers = mutableMapOf<String, String>()
    model.headers?.let(headers::putAll)
    options?.headers?.let(headers::putAll)
    return headers
}

/**
 * Emits the final [DoneEvent], then ends the stream. Call this at the end of SSE processing.
 */
public suspend fun emitStreamFinale(stream: AssistantMessageEventStream, accumulator: StreamAccumulator) {
    val finalMessage = accumulator.buildFinal()
    stream.push(DoneEvent(reason = finalMessage.stopReason, message = finalMessage))
    stream.end(finalMessage)
}
