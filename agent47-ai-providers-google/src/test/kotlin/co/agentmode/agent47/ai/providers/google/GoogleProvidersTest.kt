package co.agentmode.agent47.ai.providers.google

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

class GoogleProvidersTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        ApiRegistry.clear()
        server?.stop(0)
    }

    @Test
    fun `google provider parses streamed JSON array response`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/models/gemini-2.5-pro:streamGenerateContent") { exchange ->
            val payload = """
                [
                  {
                    "candidates": [
                      {
                        "content": {
                          "parts": [
                            {"text": "hello "}
                          ]
                        }
                      }
                    ]
                  },
                  {
                    "candidates": [
                      {
                        "content": {
                          "parts": [
                            {"text": "from google"}
                          ]
                        },
                        "finishReason": "STOP"
                      }
                    ],
                    "usageMetadata": {
                      "promptTokenCount": 2,
                      "candidatesTokenCount": 3,
                      "totalTokenCount": 5
                    }
                  }
                ]
            """.trimIndent()
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
        }
        server!!.start()

        registerGoogleProviders()

        val model = Model(
            id = "gemini-2.5-pro",
            name = "Gemini",
            api = KnownApis.GoogleGenerativeAi,
            provider = ProviderId("google"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1_000_000,
            maxTokens = 4096,
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("hello from google", text)
        assertEquals(5, result.usage.totalTokens)
    }

    @Test
    fun `google provider emits error event on 401`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/models/gemini-2.5-pro:streamGenerateContent") { exchange ->
            val response = """{"error":{"code":401,"message":"API key not valid"}}"""
            exchange.sendResponseHeaders(401, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerGoogleProviders()

        val model = Model(
            id = "gemini-2.5-pro",
            name = "Gemini",
            api = KnownApis.GoogleGenerativeAi,
            provider = ProviderId("google"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1_000_000,
            maxTokens = 4096,
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
    fun `google provider emits error event on 429 rate limit`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/models/gemini-2.5-pro:streamGenerateContent") { exchange ->
            val response = """{"error":{"code":429,"message":"Resource exhausted"}}"""
            exchange.sendResponseHeaders(429, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        registerGoogleProviders()

        val model = Model(
            id = "gemini-2.5-pro",
            name = "Gemini",
            api = KnownApis.GoogleGenerativeAi,
            provider = ProviderId("google"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1_000_000,
            maxTokens = 4096,
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
    fun `google provider handles single object response`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/models/gemini-2.5-pro:streamGenerateContent") { exchange ->
            val payload = """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": "single response"}
                        ]
                      },
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {
                    "promptTokenCount": 2,
                    "candidatesTokenCount": 3,
                    "totalTokenCount": 5
                  }
                }
            """.trimIndent()
            exchange.sendResponseHeaders(200, payload.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(payload.toByteArray()) }
        }
        server!!.start()

        registerGoogleProviders()

        val model = Model(
            id = "gemini-2.5-pro",
            name = "Gemini",
            api = KnownApis.GoogleGenerativeAi,
            provider = ProviderId("google"),
            baseUrl = "http://127.0.0.1:${server!!.address.port}",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1_000_000,
            maxTokens = 4096,
        )

        val result = AiRuntime.completeSimple(
            model = model,
            context = Context(messages = listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L))),
        )

        val text = result.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("single response", text)
    }
}
