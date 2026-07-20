package co.agentmode.agent47.ai.core.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.future.await
import kotlin.concurrent.thread

public data class HttpResult(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>>,
)

public data class SseEvent(
    val event: String?,
    val data: String,
)

public data class SseResponse(
    val statusCode: Int,
    val events: Flow<SseEvent>,
)

public class HttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
) {
    public suspend fun postJson(
        url: String,
        payload: String,
        headers: Map<String, String>,
        timeout: Duration = Duration.ofSeconds(120),
    ): HttpResult {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        headers.forEach { (name, value) ->
            builder.header(name, value)
        }

        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        return HttpResult(
            statusCode = response.statusCode(),
            body = response.body(),
            headers = response.headers().map(),
        )
    }

    public suspend fun streamSse(
        url: String,
        payload: String,
        headers: Map<String, String>,
        timeout: Duration = Duration.ofSeconds(300),
    ): SseResponse {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(payload))

        headers.forEach { (name, value) ->
            builder.header(name, value)
        }

        // ofInputStream so the reading thread can be unblocked by closing the body when the
        // collector goes away; ofLines would leave the daemon thread draining the whole response.
        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream()).await()
        val statusCode = response.statusCode()
        val body = response.body()

        val events = callbackFlow {
            val reader = body.bufferedReader()
            val parser = thread(name = "agent47-sse-parser", isDaemon = true) {
                try {
                    var currentEvent: String? = null
                    var dataLines = mutableListOf<String>()

                    while (true) {
                        val line = reader.readLine() ?: break
                        when {
                            line.startsWith("event:") -> {
                                currentEvent = line.removePrefix("event:").trim()
                            }
                            line.startsWith("data:") -> {
                                dataLines.add(line.removePrefix("data:").trimStart())
                            }
                            line.startsWith(":") -> {
                                // SSE comment, ignore
                            }
                            line.isBlank() -> {
                                if (dataLines.isNotEmpty()) {
                                    val data = dataLines.joinToString("\n")
                                    if (data != "[DONE]") {
                                        // trySendBlocking applies backpressure: the parser blocks
                                        // when the bounded buffer is full instead of buffering the
                                        // entire response in memory.
                                        trySendBlocking(SseEvent(event = currentEvent, data = data))
                                    }
                                    currentEvent = null
                                    dataLines = mutableListOf()
                                }
                            }
                            else -> {
                                // Non-SSE line (e.g. JSON error body). Capture as a raw event
                                // so handleSseError can include it in the error message.
                                trySendBlocking(SseEvent(event = null, data = line))
                            }
                        }
                    }

                    if (dataLines.isNotEmpty()) {
                        val data = dataLines.joinToString("\n")
                        if (data != "[DONE]") {
                            trySendBlocking(SseEvent(event = currentEvent, data = data))
                        }
                    }
                } catch (_: Throwable) {
                    // Reader closed or an IO error (e.g. the collector cancelled and closed the
                    // body); end the flow instead of surfacing the interruption.
                } finally {
                    close()
                }
            }

            awaitClose {
                // Collector cancelled or the flow completed: unblock the parser and release the
                // socket so an aborted request stops consuming the network stream.
                runCatching { body.close() }
                runCatching { reader.close() }
                parser.interrupt()
            }
        }

        return SseResponse(statusCode = statusCode, events = events)
    }
}
