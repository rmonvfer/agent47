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
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLoopTest {
    @Test
    fun `agentLoop emits core events`() = runBlocking {
        val model = createModel()
        val context = AgentContext(systemPrompt = "helpful", messages = mutableListOf(), tools = emptyList())
        val prompt = user("hello")

        val stream = agentLoop(
            prompts = listOf(prompt),
            context = context,
            config = AgentLoopConfig(model = model, convertToLlm = { it }),
            streamFunction = { _, _, _ ->
                val response = assistant("hi")
                AssistantMessageEventStream().also { s ->
                    s.push(DoneEvent(reason = StopReason.STOP, message = response))
                }
            },
        )

        val events = stream.events.toList()
        val result = stream.result()

        assertTrue(events.any { it is AgentStartEvent })
        assertTrue(events.any { it is TurnStartEvent })
        assertTrue(events.any { it is MessageStartEvent })
        assertTrue(events.any { it is MessageEndEvent })
        assertTrue(events.any { it is AgentEndEvent })

        assertEquals(2, result.size)
        assertEquals("user", result[0].role)
        assertEquals("assistant", result[1].role)
    }

    @Test
    fun `tool calls execute and continue turn`() = runBlocking {
        val model = createModel()
        val executed = mutableListOf<String>()

        val echoTool = object : AgentTool<JsonObject> {
            override val label: String = "echo"
            override val definition: ToolDefinition = ToolDefinition(
                name = "echo",
                description = "echo",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                },
            )

            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<JsonObject>?,
            ): AgentToolResult<JsonObject> {
                val value = parameters["value"]?.toString()?.replace("\"", "") ?: ""
                executed += value
                return AgentToolResult(content = listOf(TextContent(text = "echo:$value")), details = parameters)
            }
        }

        var callIndex = 0
        val streamFn = AgentStreamFunction { _, _, _ ->
            val stream = AssistantMessageEventStream()
            if (callIndex == 0) {
                val toolCallMessage = AssistantMessage(
                    content = listOf(
                        ToolCall(
                            id = "tool-1",
                            name = "echo",
                            arguments = buildJsonObject { put("value", JsonPrimitive("hello")) },
                        ),
                    ),
                    api = KnownApis.OpenAiResponses,
                    provider = ProviderId("openai"),
                    model = "mock",
                    usage = emptyUsage(),
                    stopReason = StopReason.TOOL_USE,
                    timestamp = 1L,
                )
                stream.push(DoneEvent(reason = StopReason.TOOL_USE, message = toolCallMessage))
            } else {
                stream.push(DoneEvent(reason = StopReason.STOP, message = assistant("done")))
            }
            callIndex += 1
            stream
        }

        val run = agentLoop(
            prompts = listOf(user("start")),
            context = AgentContext("", mutableListOf(), tools = listOf(echoTool)),
            config = AgentLoopConfig(model = model, convertToLlm = { it }),
            streamFunction = streamFn,
        )

        val events = run.events.toList()
        val result = run.result()

        assertEquals(listOf("hello"), executed)
        assertTrue(events.any { it is ToolExecutionStartEvent })
        assertTrue(events.any { it is ToolExecutionEndEvent && !it.isError })
        assertEquals("assistant", result.last().role)
    }

    @Test
    fun `invalid tool arguments return an error without executing the tool`() = runBlocking {
        var executed = false
        val tool = object : AgentTool<JsonObject> {
            override val label: String = "echo"
            override val definition: ToolDefinition = ToolDefinition(
                name = "echo",
                description = "echo",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("value", buildJsonObject { put("type", JsonPrimitive("string")) })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("value")) })
                },
            )

            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<JsonObject>?,
            ): AgentToolResult<JsonObject> {
                executed = true
                return AgentToolResult(content = listOf(TextContent(text = "executed")), details = parameters)
            }
        }

        var callIndex = 0
        val run = agentLoop(
            prompts = listOf(user("start")),
            context = AgentContext("", mutableListOf(), tools = listOf(tool)),
            config = AgentLoopConfig(model = createModel(), convertToLlm = { it }),
            streamFunction = { _, _, _ ->
                AssistantMessageEventStream().also { stream ->
                    if (callIndex++ == 0) {
                        val message = assistantToolCall("echo", buildJsonObject { })
                        stream.push(DoneEvent(reason = StopReason.TOOL_USE, message = message))
                    } else {
                        stream.push(DoneEvent(reason = StopReason.STOP, message = assistant("done")))
                    }
                }
            },
        )

        val events = run.events.toList()
        val result = run.result()
        val toolResult = result.filterIsInstance<co.agentmode.agent47.ai.types.ToolResultMessage>().single()

        assertFalse(executed)
        assertTrue(toolResult.isError)
        assertTrue(toolResult.content.filterIsInstance<TextContent>().single().text.contains("Invalid arguments for tool 'echo'"))
        assertTrue(events.any { it is ToolExecutionEndEvent && it.isError })
    }

    @Test
    fun `tool argument validation coerces scalar strings before execution`() = runBlocking {
        var received: JsonObject? = null
        val tool = object : AgentTool<JsonObject> {
            override val label: String = "repeat"
            override val definition: ToolDefinition = ToolDefinition(
                name = "repeat",
                description = "repeat",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("count", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("count")) })
                },
            )

            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<JsonObject>?,
            ): AgentToolResult<JsonObject> {
                received = parameters
                return AgentToolResult(content = listOf(TextContent(text = "ok")), details = parameters)
            }
        }

        var callIndex = 0
        val run = agentLoop(
            prompts = listOf(user("start")),
            context = AgentContext("", mutableListOf(), tools = listOf(tool)),
            config = AgentLoopConfig(model = createModel(), convertToLlm = { it }),
            streamFunction = { _, _, _ ->
                AssistantMessageEventStream().also { stream ->
                    if (callIndex++ == 0) {
                        val message = assistantToolCall(
                            "repeat",
                            buildJsonObject { put("count", JsonPrimitive("5")) },
                        )
                        stream.push(DoneEvent(reason = StopReason.TOOL_USE, message = message))
                    } else {
                        stream.push(DoneEvent(reason = StopReason.STOP, message = assistant("done")))
                    }
                }
            },
        )

        run.events.toList()
        run.result()

        assertEquals(JsonPrimitive(5), received?.get("count"))
    }

    private fun assistantToolCall(name: String, arguments: JsonObject): AssistantMessage {
        return AssistantMessage(
            content = listOf(ToolCall(id = "tool-1", name = name, arguments = arguments)),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("openai"),
            model = "mock",
            usage = emptyUsage(),
            stopReason = StopReason.TOOL_USE,
            timestamp = 1L,
        )
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
