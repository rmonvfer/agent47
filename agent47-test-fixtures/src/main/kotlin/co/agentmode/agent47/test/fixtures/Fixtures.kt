package co.agentmode.agent47.test.fixtures

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEvent
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.DoneEvent
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.emptyUsage

public fun createTestModel(api: String = "openai-responses", provider: String = "openai", id: String = "mock"): Model {
    return Model(
        id = id,
        name = id,
        api = ApiId(api),
        provider = co.agentmode.agent47.ai.types.ProviderId(provider),
        baseUrl = "https://example.invalid/v1",
        reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 8192,
        maxTokens = 2048,
    )
}

public fun createAssistantMessage(
    text: String,
    api: String = "openai-responses",
    provider: String = "openai",
    model: String = "mock",
    stopReason: StopReason = StopReason.STOP,
): AssistantMessage {
    return AssistantMessage(
        content = listOf(TextContent(text = text)),
        api = ApiId(api),
        provider = co.agentmode.agent47.ai.types.ProviderId(provider),
        model = model,
        usage = emptyUsage(),
        stopReason = stopReason,
        timestamp = System.currentTimeMillis(),
    )
}

public fun mockAssistantStream(terminalEvent: AssistantMessageEvent): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    stream.push(terminalEvent)
    return stream
}

public fun completedStream(message: AssistantMessage): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    stream.push(DoneEvent(reason = message.stopReason, message = message))
    return stream
}
