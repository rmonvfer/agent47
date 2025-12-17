package co.agentmode.agent47.ai.core.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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

        val response = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines()).await()
        val statusCode = response.statusCode()
        val lines = response.body()

        val channel = Channel<SseEvent>(Channel.UNLIMITED)

        thread(name = "agent47-sse-parser", isDaemon = true) {
            try {
                var currentEvent: String? = null
                var dataLines = mutableListOf<String>()

                lines.forEach { line ->
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
                                    channel.trySend(SseEvent(event = currentEvent, data = data))
                                }
                                currentEvent = null
                                dataLines = mutableListOf()
                            }
                        }
                        else -> {
                            // Non-SSE line (e.g. JSON error body). Capture as a raw event
                            // so handleSseError can include it in the error message.
                            channel.trySend(SseEvent(event = null, data = line))
                        }
                    }
                }

                if (dataLines.isNotEmpty()) {
                    val data = dataLines.joinToString("\n")
                    if (data != "[DONE]") {
                        channel.trySend(SseEvent(event = currentEvent, data = data))
                    }
                }
            } finally {
                channel.close()
            }
        }

        return SseResponse(statusCode = statusCode, events = channel.receiveAsFlow())
    }
}
