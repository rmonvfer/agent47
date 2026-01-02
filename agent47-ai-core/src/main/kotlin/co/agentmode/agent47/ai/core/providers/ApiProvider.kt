package co.agentmode.agent47.ai.core.providers

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StreamOptions

public fun interface StreamFunction {
    public suspend fun invoke(
        model: Model,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream
}

public fun interface StreamSimpleFunction {
    public suspend fun invoke(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream
}

/**
 * Implements streaming LLM calls for a specific API format (e.g. OpenAI completions,
 * Anthropic messages, Google generative AI). Each provider registers itself in [ApiRegistry][co.agentmode.agent47.ai.core.ApiRegistry]
 * keyed by its [api] identifier; the runtime selects the right provider based on
 * the model's declared API.
 *
 * Implementations handle SSE parsing, token counting, and translating the universal
 * [Context] into provider-specific request payloads.
 */
public interface ApiProvider {
    public val api: ApiId
    public suspend fun stream(model: Model, context: Context, options: StreamOptions? = null): AssistantMessageEventStream
    public suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
    ): AssistantMessageEventStream
}
