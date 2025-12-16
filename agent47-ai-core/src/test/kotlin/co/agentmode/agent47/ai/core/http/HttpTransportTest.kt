package co.agentmode.agent47.ai.core.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpTransportTest {
    private var server: HttpServer? = null
    private val transport = HttpTransport()

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `postJson returns response body and status`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/test") { exchange ->
            val response = """{"status":"ok"}"""
            exchange.sendResponseHeaders(200, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        val result = transport.postJson(
            url = "http://127.0.0.1:${server!!.address.port}/test",
            payload = """{"request":"data"}""",
            headers = emptyMap(),
        )

        assertEquals(200, result.statusCode)
        assertEquals("""{"status":"ok"}""", result.body)
    }

    @Test
    fun `postJson returns 401 status for auth errors`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/test") { exchange ->
            val response = """{"error":"unauthorized"}"""
            exchange.sendResponseHeaders(401, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        val result = transport.postJson(
            url = "http://127.0.0.1:${server!!.address.port}/test",
            payload = """{"request":"data"}""",
            headers = mapOf("authorization" to "Bearer invalid"),
        )

        assertEquals(401, result.statusCode)
    }

    @Test
    fun `postJson returns 429 status for rate limit errors`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/test") { exchange ->
            val response = """{"error":"rate limited"}"""
            exchange.sendResponseHeaders(429, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        val result = transport.postJson(
            url = "http://127.0.0.1:${server!!.address.port}/test",
            payload = """{"request":"data"}""",
            headers = emptyMap(),
        )

        assertEquals(429, result.statusCode)
    }

    @Test
    fun `postJson returns 500 status for server errors`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/test") { exchange ->
            val response = """{"error":"internal server error"}"""
            exchange.sendResponseHeaders(500, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        val result = transport.postJson(
            url = "http://127.0.0.1:${server!!.address.port}/test",
            payload = """{"request":"data"}""",
            headers = emptyMap(),
        )

        assertEquals(500, result.statusCode)
    }

    @Test
    fun `streamSse parses events with event type`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            val body = """
                event: message_start
                data: {"type":"message_start"}

                event: content_block_delta
                data: {"type":"delta","text":"hello"}

            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        val response = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        assertEquals(200, response.statusCode)
        val events = response.events.toList()
        assertEquals(2, events.size)
        assertEquals("message_start", events[0].event)
        assertEquals("""{"type":"message_start"}""", events[0].data)
        assertEquals("content_block_delta", events[1].event)
        assertEquals("""{"type":"delta","text":"hello"}""", events[1].data)
    }

    @Test
    fun `streamSse parses events without event type`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            val body = """
                data: {"text":"first"}

                data: {"text":"second"}

            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        val response = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        assertEquals(200, response.statusCode)
        val events = response.events.toList()
        assertEquals(2, events.size)
        assertEquals(null, events[0].event)
        assertEquals("""{"text":"first"}""", events[0].data)
        assertEquals(null, events[1].event)
        assertEquals("""{"text":"second"}""", events[1].data)
    }

    @Test
    fun `streamSse filters out DONE sentinel`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            val body = """
                data: {"text":"content"}

                data: [DONE]

            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        val response = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        val events = response.events.toList()
        assertEquals(1, events.size)
        assertEquals("""{"text":"content"}""", events[0].data)
    }

    @Test
    fun `streamSse handles multiline data`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            val body = """
                event: test
                data: line1
                data: line2
                data: line3

            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server!!.start()

        val response = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        val events = response.events.toList()
        assertEquals(1, events.size)
        assertEquals("test", events[0].event)
        assertEquals("line1\nline2\nline3", events[0].data)
    }

    @Test
    fun `streamSse returns error status code`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            val response = """{"error":"invalid request"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(400, response.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server!!.start()

        val sseResponse = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        assertEquals(400, sseResponse.statusCode)
    }

    @Test
    fun `streamSse handles empty response`() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server!!.createContext("/stream") { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server!!.start()

        val response = transport.streamSse(
            url = "http://127.0.0.1:${server!!.address.port}/stream",
            payload = """{}""",
            headers = emptyMap(),
        )

        assertEquals(200, response.statusCode)
        val events = response.events.toList()
        assertEquals(0, events.size)
    }
}
