package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.agent.core.AgentStreamFunction
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.DoneEvent
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.emptyUsage
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.TodoState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SubAgentTodoStateTest {

    private val model = Model(
        id = "test-model",
        name = "Test Model",
        api = ApiId("openai-completions"),
        provider = ProviderId("test-provider"),
        baseUrl = "https://api.example.com",
        reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

    private val definition = AgentDefinition(
        name = "todo-agent",
        description = "writes a todo list",
        systemPrompt = "test",
        tools = listOf("todowrite"),
        spawns = SpawnsPolicy.None,
        model = null,
        thinkingLevel = null,
        output = null,
        source = AgentSource.PROJECT,
        filePath = null,
    )

    /** Replies with a todowrite call for [taskContent], then a submitted result, then a plain stop. */
    private fun streamWriting(taskContent: String): AgentStreamFunction {
        var call = 0
        return AgentStreamFunction { _, _, _ ->
            val stream = AssistantMessageEventStream()
            when (call) {
                0 -> stream.push(toolCallEvent("todowrite", todoWriteArguments(taskContent)))
                1 -> stream.push(
                    toolCallEvent("submit_result", buildJsonObject { put("result", JsonPrimitive("wrote the list")) }),
                )
                else -> stream.push(DoneEvent(reason = StopReason.STOP, message = assistantMessage(listOf(TextContent(text = "finished")), StopReason.STOP)))
            }
            call++
            stream
        }
    }

    private fun todoWriteArguments(taskContent: String): JsonObject = buildJsonObject {
        put(
            "todos",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive("t1"))
                        put("content", JsonPrimitive(taskContent))
                        put("status", JsonPrimitive("in_progress"))
                        put("priority", JsonPrimitive("high"))
                    },
                )
            },
        )
    }

    private fun toolCallEvent(name: String, arguments: JsonObject): DoneEvent {
        val message = assistantMessage(
            listOf(ToolCall(id = "call-$name", name = name, arguments = arguments)),
            StopReason.TOOL_USE,
        )
        return DoneEvent(reason = StopReason.TOOL_USE, message = message)
    }

    private fun assistantMessage(
        content: List<co.agentmode.agent47.ai.types.ContentBlock>,
        stopReason: StopReason,
    ) = AssistantMessage(
        content = content,
        api = KnownApis.OpenAiResponses,
        provider = ProviderId("test-provider"),
        model = "test-model",
        usage = emptyUsage(),
        stopReason = stopReason,
        timestamp = 1L,
    )

    private fun options(cwd: Path, stream: AgentStreamFunction, todoState: TodoState?) = SubAgentOptions(
        streamFunction = stream,
        agentDefinition = definition,
        task = "write a todo list",
        taskId = "todo-test",
        description = null,
        context = null,
        cwd = cwd,
        parentModel = model,
        modelRegistry = ModelRegistry(AuthStorage(cwd.resolve("auth.json"), envResolver = { null })),
        settings = Settings(),
        currentDepth = 0,
        maxDepth = 1,
        agentRegistry = null,
        getApiKey = null,
        onProgress = null,
        onEvent = null,
        todoState = todoState,
    )

    @Test
    fun `a sub-agent writes into the todo list supplied for it`() = runTest {
        val cwd = createTempDirectory("subagent-todo")
        val agentTodos = TodoState()

        val result = runSubAgent(options(cwd, streamWriting("agent work"), agentTodos))

        assertEquals(0, result.exitCode, "sub-agent should finish cleanly: ${result.error}")
        assertEquals(listOf("agent work"), agentTodos.getAll().map { it.content })
    }

    @Test
    fun `todo lists of two sub-agents stay separate`() = runTest {
        val cwd = createTempDirectory("subagent-todo-isolation")
        val firstTodos = TodoState()
        val secondTodos = TodoState()

        runSubAgent(options(cwd, streamWriting("first agent work"), firstTodos))
        runSubAgent(options(cwd, streamWriting("second agent work"), secondTodos))

        assertEquals(listOf("first agent work"), firstTodos.getAll().map { it.content })
        assertEquals(listOf("second agent work"), secondTodos.getAll().map { it.content })
    }
}
