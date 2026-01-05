package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.DoneEvent
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentLoopHooksTest {

    @Test
    fun `beforeAgent hook is called before LLM request`() = runBlocking {
        val hookCalls = mutableListOf<String>()

        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { it },
            beforeAgent = { messages ->
                hookCalls += "beforeAgent:${messages.size}"
                messages
            },
        )

        val stream = agentLoop(
            prompts = listOf(user("hello")),
            context = AgentContext("sys", mutableListOf(), emptyList()),
            config = config,
            streamFunction = { _, _, _ ->
                AssistantMessageEventStream().also { s ->
                    s.push(DoneEvent(reason = StopReason.STOP, message = assistant("hi")))
                }
            },
        )

        stream.events.toList()
        assertTrue(hookCalls.isNotEmpty(), "beforeAgent hook should have been called")
    }

    @Test
    fun `beforeAgent hook can modify messages`() = runBlocking {
        var receivedMessages: List<Message>? = null

        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { it },
            beforeAgent = { messages ->
                // Inject an extra user message before the LLM sees it
                messages + user("injected by hook")
            },
        )

        val stream = agentLoop(
            prompts = listOf(user("original")),
            context = AgentContext("sys", mutableListOf(), emptyList()),
            config = config,
            streamFunction = { _, context, _ ->
                receivedMessages = context.messages
                AssistantMessageEventStream().also { s ->
                    s.push(DoneEvent(reason = StopReason.STOP, message = assistant("response")))
                }
            },
        )

        stream.events.toList()
        // The LLM should have received the injected message
        val userTexts = receivedMessages
            ?.filterIsInstance<UserMessage>()
            ?.flatMap { it.content.filterIsInstance<TextContent>() }
            ?.map { it.text }

        assertTrue(userTexts != null && "injected by hook" in userTexts, "Hook-injected message should reach the LLM")
    }

    @Test
    fun `afterAgent hook is called after loop completes`() = runBlocking {
        val hookCalls = mutableListOf<String>()

        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { it },
            afterAgent = { messages ->
                hookCalls += "afterAgent:${messages.size}"
            },
        )

        val stream = agentLoop(
            prompts = listOf(user("hello")),
            context = AgentContext("sys", mutableListOf(), emptyList()),
            config = config,
            streamFunction = { _, _, _ ->
                AssistantMessageEventStream().also { s ->
                    s.push(DoneEvent(reason = StopReason.STOP, message = assistant("hi")))
                }
            },
        )

        stream.events.toList()
        assertTrue(hookCalls.isNotEmpty(), "afterAgent hook should have been called")
        assertTrue(hookCalls[0].startsWith("afterAgent:"), "afterAgent should receive the full message list")
    }

    @Test
    fun `afterAgent receives all messages including assistant response`() = runBlocking {
        var afterMessages: List<Message>? = null

        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { it },
            afterAgent = { messages ->
                afterMessages = messages
            },
        )

        val stream = agentLoop(
            prompts = listOf(user("hello")),
            context = AgentContext("sys", mutableListOf(), emptyList()),
            config = config,
            streamFunction = { _, _, _ ->
                AssistantMessageEventStream().also { s ->
                    s.push(DoneEvent(reason = StopReason.STOP, message = assistant("hi")))
                }
            },
        )

        stream.events.toList()
        assertTrue(afterMessages != null, "afterAgent should have received messages")
        assertTrue(afterMessages!!.any { it.role == "assistant" }, "afterAgent should include the assistant response")
    }

    private fun user(text: String): Message {
        return UserMessage(content = listOf(TextContent(text = text)), timestamp = System.currentTimeMillis())
    }

    private fun assistant(text: String): AssistantMessage {
        return AssistantMessage(
            content = listOf(TextContent(text = text)),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            model = "mock",
            usage = emptyUsage(),
            stopReason = StopReason.STOP,
            timestamp = System.currentTimeMillis(),
        )
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
            contextWindow = 8_192,
            maxTokens = 2_048,
        )
    }
}
