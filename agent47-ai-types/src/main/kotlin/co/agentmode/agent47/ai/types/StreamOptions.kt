package co.agentmode.agent47.ai.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public enum class CacheRetention {
    NONE,
    SHORT,
    LONG,
}

public data class StreamOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val apiKey: String? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val headers: Map<String, String>? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonObject? = null,
    val onPayload: ((Any?) -> Unit)? = null,
)

public data class SimpleStreamOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val apiKey: String? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val headers: Map<String, String>? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonObject? = null,
    val onPayload: ((Any?) -> Unit)? = null,
    val reasoning: ThinkingLevel? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
)

public fun StreamOptions.withOverrides(overrides: StreamOptions): StreamOptions = StreamOptions(
    temperature = overrides.temperature ?: temperature,
    maxTokens = overrides.maxTokens ?: maxTokens,
    apiKey = overrides.apiKey ?: apiKey,
    cacheRetention = overrides.cacheRetention ?: cacheRetention,
    sessionId = overrides.sessionId ?: sessionId,
    headers = (headers ?: emptyMap()) + (overrides.headers ?: emptyMap()),
    maxRetryDelayMs = overrides.maxRetryDelayMs ?: maxRetryDelayMs,
    metadata = overrides.metadata ?: metadata,
    onPayload = overrides.onPayload ?: onPayload,
)
