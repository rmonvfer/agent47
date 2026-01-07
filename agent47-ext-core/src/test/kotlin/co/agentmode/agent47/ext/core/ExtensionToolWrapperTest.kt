package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionToolWrapperTest {

    private fun simpleTool(name: String): AgentTool<JsonObject> = object : AgentTool<JsonObject> {
        override val label = name
        override val definition = ToolDefinition(
            name = name,
            description = "Simple $name tool",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )

        override suspend fun execute(
            toolCallId: String,
            parameters: JsonObject,
            onUpdate: AgentToolUpdateCallback<JsonObject>?,
        ): AgentToolResult<JsonObject> {
            return AgentToolResult(
                content = listOf(TextContent(text = "result from $name")),
                details = parameters,
            )
        }
    }

    @Test
    fun `wrapTool applies wrapper to tool`() = runTest {
        val runner = ExtensionRunner()
        val calls = mutableListOf<String>()

        runner.load(
            ExtensionDefinition(
                id = "wrapper-ext",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
                        calls.add(tool.definition.name)
                        return tool
                    }
                },
            ),
        )

        val tool = simpleTool("test")
        runner.wrapTool(tool)
        assertEquals(listOf("test"), calls)
    }

    @Test
    fun `multiple wrappers nest in load order`() = runTest {
        val runner = ExtensionRunner()
        val order = mutableListOf<String>()

        runner.load(
            ExtensionDefinition(
                id = "first",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
                        order.add("first")
                        return tool
                    }
                },
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "second",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
                        order.add("second")
                        return tool
                    }
                },
            ),
        )

        runner.wrapTool(simpleTool("echo"))
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun `wrapTools wraps every tool in the list`() = runTest {
        val runner = ExtensionRunner()
        val wrapped = mutableListOf<String>()

        runner.load(
            ExtensionDefinition(
                id = "list-wrapper",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
                        wrapped.add(tool.definition.name)
                        return tool
                    }
                },
            ),
        )

        val tools = listOf(simpleTool("alpha"), simpleTool("beta"), simpleTool("gamma"))
        runner.wrapTools(tools)
        assertEquals(listOf("alpha", "beta", "gamma"), wrapped)
    }

    @Test
    fun `extensions without toolWrapper do not affect wrapping`() = runTest {
        val runner = ExtensionRunner()
        val calls = mutableListOf<String>()

        runner.load(
            ExtensionDefinition(
                id = "no-wrapper",
                beforeAgent = { ctx -> ctx.messages },
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "has-wrapper",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> {
                        calls.add("wrapped")
                        return tool
                    }
                },
            ),
        )

        runner.wrapTool(simpleTool("test"))
        assertEquals(listOf("wrapped"), calls)
    }
}
