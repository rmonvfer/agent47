package co.agentmode.agent47.ai.core

import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.DoneEvent
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StartEvent
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.StreamOptions
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.Usage
import co.agentmode.agent47.ai.types.UsageCost
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RuntimeTest {
    @AfterTest
    fun teardown(): Unit {
        ApiRegistry.clear()
    }

    @Test
    fun `runtime resolves registered provider and returns completion`() = runTest {
        val model = createModel()
        val response = createAssistant("hello")

        ApiRegistry.register(
            object : ApiProvider {
                override val api: ApiId = KnownApis.OpenAiResponses

                override suspend fun stream(
                    model: Model,
                    context: Context,
                    options: StreamOptions?,
                ): AssistantMessageEventStream {
                    val stream = AssistantMessageEventStream()
                    stream.push(StartEvent(partial = response.copy(content = emptyList())))
                    stream.push(DoneEvent(reason = StopReason.STOP, message = response))
                    return stream
                }

                override suspend fun streamSimple(
                    model: Model,
                    context: Context,
                    options: SimpleStreamOptions?,
                ): AssistantMessageEventStream {
                    return stream(model, context, null)
                }
            },
        )

        val complete = AiRuntime.completeSimple(
            model,
            Context(
                messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L)),
            ),
        )

        assertEquals("assistant", complete.role)
        assertEquals(StopReason.STOP, complete.stopReason)
        val text = complete.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        assertEquals("hello", text)
    }

    @Test
    fun `registry supports source unregistration`() {
        val provider = object : ApiProvider {
            override val api: ApiId = ApiId("unit-test")

            override suspend fun stream(model: Model, context: Context, options: StreamOptions?): AssistantMessageEventStream {
                return AssistantMessageEventStream()
            }

            override suspend fun streamSimple(
                model: Model,
                context: Context,
                options: SimpleStreamOptions?,
            ): AssistantMessageEventStream {
                return AssistantMessageEventStream()
            }
        }

        ApiRegistry.register(provider, sourceId = "ext-a")
        assertNotNull(ApiRegistry.get(ApiId("unit-test")))

        ApiRegistry.unregisterBySource("ext-a")
        assertEquals(null, ApiRegistry.get(ApiId("unit-test")))
    }

    private fun createModel(): Model {
        return Model(
            id = "mock",
            name = "mock",
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            baseUrl = "https://example.invalid",
            reasoning = false,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8192,
            maxTokens = 2048,
        )
    }

    private fun createAssistant(text: String): AssistantMessage {
        return AssistantMessage(
            content = listOf(TextContent(text = text)),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            model = "mock",
            usage = Usage(0, 0, 0, 0, 0, UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
            stopReason = StopReason.STOP,
            timestamp = 1L,
        )
    }
}
