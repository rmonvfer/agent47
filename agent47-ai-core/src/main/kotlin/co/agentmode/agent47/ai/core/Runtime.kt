package co.agentmode.agent47.ai.core

import co.agentmode.agent47.ai.types.*

public object AiRuntime {
    public suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions? = null,
    ): AssistantMessageEventStream {
        val provider = ApiRegistry.get(model.api)
            ?: error("No API provider registered for api: ${model.api.value}")
        return provider.stream(model, context, options)
    }

    public suspend fun complete(
        model: Model,
        context: Context,
        options: StreamOptions? = null,
    ): AssistantMessage {
        val stream = stream(model, context, options)
        return stream.result()
    }

    public suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
    ): AssistantMessageEventStream {
        val provider = ApiRegistry.get(model.api)
            ?: error("No API provider registered for api: ${model.api.value}")
        return provider.streamSimple(model, context, options)
    }

    public suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
    ): AssistantMessage {
        val stream = streamSimple(model, context, options)
        return stream.result()
    }
}
