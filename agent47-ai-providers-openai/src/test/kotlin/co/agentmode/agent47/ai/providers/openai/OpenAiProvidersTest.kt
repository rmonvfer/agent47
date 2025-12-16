package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.ErrorEvent
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiProvidersTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        ApiRegistry.clear()
        server?.stop(0)
    }

    @Test
    fun `openai responses provider parses streamed SSE output`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/responses") { exchange ->
            val events = listOf(
                """event: response.output_item.added
data: {"type":"response.output_item.added","item":{"type":"message","id":"item_1"}}""",
                """event: response.content_part.added
data: {"type":"response.content_part.added","part":{"type":"output_text"}}""",
                """event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"hello "}""",
                """event: response.output_text.delta
data: {"type":"response.output_text.delta","delta":"from responses"}""",
                """event: response.output_item.done
data: {"type":"response.output_item.done","item":{"type":"message","content":[{"type":"output_text","text":"hello from responses"}]}}""",
                """event: response.completed
data: {"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":5,"output_tokens":7,"total_tokens":12}}}""",
            )

            val body = events.joinToString("\n\n") + "\n\n"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        registerOpenAiProviders()

        val model = Model(
            id = "gpt-4.1-mini",
            name = "GPT",
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 100_000,
            maxTokens = 1024,
            headers = mapOf("authorization" to "Bearer test"),
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("hello from responses", text)
        assertEquals(12, result.usage.totalTokens)
    }

    @Test
    fun `openai responses provider emits error event on 401`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/responses") { exchange ->
            val response = """{"error":{"message":"Invalid API key","type":"invalid_request_error"}}"""
            exchange.sendResponseHeaders(401, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerOpenAiProviders()

        val model = Model(
            id = "gpt-4.1-mini",
            name = "GPT",
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 100_000,
            maxTokens = 1024,
            headers = mapOf("authorization" to "Bearer invalid"),
        )

        val stream = AiRuntime.streamSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val events = stream.events.toList()
        val result = stream.result()

        assertTrue(events.any { it is ErrorEvent })
        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals(result.errorMessage?.contains("401"), true)
    }

    @Test
    fun `openai responses provider emits error event on 429 rate limit`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/responses") { exchange ->
            val response = """{"error":{"message":"Rate limit exceeded","type":"rate_limit_error"}}"""
            exchange.sendResponseHeaders(429, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerOpenAiProviders()

        val model = Model(
            id = "gpt-4.1-mini",
            name = "GPT",
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 100_000,
            maxTokens = 1024,
            headers = mapOf("authorization" to "Bearer test"),
        )

        val stream = AiRuntime.streamSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val events = stream.events.toList()
        val result = stream.result()

        assertTrue(events.any { it is ErrorEvent })
        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals(result.errorMessage?.contains("429"), true)
    }

    @Test
    fun `openai completions provider parses streamed SSE content`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/chat/completions") { exchange ->
            val events = listOf(
                """data: {"choices":[{"delta":{"content":"hello "}}]}""",
                """data: {"choices":[{"delta":{"content":"from completions"}}]}""",
                """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}""",
                """data: [DONE]""",
            )

            val body = events.joinToString("\n\n") + "\n\n"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        registerOpenAiProviders()

        val model = Model(
            id = "gpt-4.1-mini",
            name = "GPT",
            api = KnownApis.OpenAiCompletions,
            provider = ProviderId("openai"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = false,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 100_000,
            maxTokens = 1024,
            headers = mapOf("authorization" to "Bearer test"),
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("hello from completions", text)
        assertEquals(8, result.usage.totalTokens)
    }
}
