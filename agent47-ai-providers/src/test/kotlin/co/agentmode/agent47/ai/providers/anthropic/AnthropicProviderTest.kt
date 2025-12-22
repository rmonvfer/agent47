package co.agentmode.agent47.ai.providers.anthropic

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

class AnthropicProviderTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        ApiRegistry.clear()
        server?.stop(0)
    }

    @Test
    fun `anthropic provider parses streamed SSE content`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/messages") { exchange ->
            val events = listOf(
                """event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":3,"output_tokens":0}}}""",
                """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello "}}""",
                """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"from anthropic"}}""",
                """event: content_block_stop
data: {"type":"content_block_stop","index":0}""",
                """event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}""",
                """event: message_stop
data: {"type":"message_stop"}""",
            )

            val body = events.joinToString("\n\n") + "\n\n"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        registerAnthropicProviders()

        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude",
            api = KnownApis.AnthropicMessages,
            provider = ProviderId("anthropic"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 200_000,
            maxTokens = 4096,
            headers = mapOf("x-api-key" to "test"),
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("hello from anthropic", text)
        assertEquals(7, result.usage.totalTokens)
    }

    @Test
    fun `anthropic provider emits error event on 401`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/messages") { exchange ->
            val response = """{"error":{"type":"authentication_error","message":"Invalid API key"}}"""
            exchange.sendResponseHeaders(401, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerAnthropicProviders()

        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude",
            api = KnownApis.AnthropicMessages,
            provider = ProviderId("anthropic"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 200_000,
            maxTokens = 4096,
            headers = mapOf("x-api-key" to "invalid"),
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
    fun `anthropic provider emits error event on 429 rate limit`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/messages") { exchange ->
            val response = """{"error":{"type":"rate_limit_error","message":"Rate limit exceeded"}}"""
            exchange.sendResponseHeaders(429, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerAnthropicProviders()

        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude",
            api = KnownApis.AnthropicMessages,
            provider = ProviderId("anthropic"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 200_000,
            maxTokens = 4096,
            headers = mapOf("x-api-key" to "test"),
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
    fun `anthropic provider emits error event on 500 server error`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/messages") { exchange ->
            val response = """{"error":{"type":"internal_error","message":"Internal server error"}}"""
            exchange.sendResponseHeaders(500, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerAnthropicProviders()

        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude",
            api = KnownApis.AnthropicMessages,
            provider = ProviderId("anthropic"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 200_000,
            maxTokens = 4096,
            headers = mapOf("x-api-key" to "test"),
        )

        val stream = AiRuntime.streamSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val events = stream.events.toList()
        val result = stream.result()

        assertTrue(events.any { it is ErrorEvent })
        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals(result.errorMessage?.contains("500"), true)
    }

    @Test
    fun `anthropic provider parses tool calls in stream`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/messages") { exchange ->
            val events = listOf(
                """event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":5,"output_tokens":0}}}""",
                """event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"read_file"}}""",
                """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
                """event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"/src/main.kt\"}"}}""",
                """event: content_block_stop
data: {"type":"content_block_stop","index":0}""",
                """event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":10}}""",
                """event: message_stop
data: {"type":"message_stop"}""",
            )

            val body = events.joinToString("\n\n") + "\n\n"
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        registerAnthropicProviders()

        val model = Model(
            id = "claude-sonnet-4-5",
            name = "Claude",
            api = KnownApis.AnthropicMessages,
            provider = ProviderId("anthropic"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 200_000,
            maxTokens = 4096,
            headers = mapOf("x-api-key" to "test"),
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "read file")), timestamp = 1L))),
        )

        assertEquals(StopReason.TOOL_USE, result.stopReason)
        val toolCalls = result.content.filterIsInstance<co.agentmode.agent47.ai.types.ToolCall>()
        assertEquals(1, toolCalls.size)
        assertEquals("tool-1", toolCalls[0].id)
        assertEquals("read_file", toolCalls[0].name)
        assertEquals("/src/main.kt", toolCalls[0].arguments["path"]?.toString()?.replace("\"", ""))
    }
}
