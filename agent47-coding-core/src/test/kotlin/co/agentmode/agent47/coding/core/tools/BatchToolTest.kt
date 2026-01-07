package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertTrue

class BatchToolTest {

    private fun echoTool(): AgentTool<JsonObject> = object : AgentTool<JsonObject> {
        override val label = "echo"
        override val definition = ToolDefinition(
            name = "echo",
            description = "Echo input",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )

        override suspend fun execute(
            toolCallId: String,
            parameters: JsonObject,
            onUpdate: AgentToolUpdateCallback<JsonObject>?,
        ): AgentToolResult<JsonObject> {
            val msg = parameters["message"]?.let { (it as? JsonPrimitive)?.content } ?: "no message"
            return AgentToolResult(content = listOf(TextContent(text = "echo: $msg")), details = parameters)
        }
    }

    private fun failingTool(): AgentTool<JsonObject> = object : AgentTool<JsonObject> {
        override val label = "fail"
        override val definition = ToolDefinition(
            name = "fail",
            description = "Always fails",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )

        override suspend fun execute(
            toolCallId: String,
            parameters: JsonObject,
            onUpdate: AgentToolUpdateCallback<JsonObject>?,
        ): AgentToolResult<JsonObject> {
            error("Intentional failure")
        }
    }

    @Test
    fun `executes multiple tools in parallel`() = runTest {
        val tools = mapOf("echo" to echoTool())
        val batch = BatchTool(tools)

        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "echo")
                    put("input", buildJsonObject { put("message", "hello") })
                })
                add(buildJsonObject {
                    put("tool", "echo")
                    put("input", buildJsonObject { put("message", "world") })
                })
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("2/2 succeeded"))
        assertTrue(text.contains("echo: hello"))
        assertTrue(text.contains("echo: world"))
    }

    @Test
    fun `rejects empty invocations`() = runTest {
        val batch = BatchTool(emptyMap())
        val params = buildJsonObject {
            put("invocations", buildJsonArray {})
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("empty"))
    }

    @Test
    fun `rejects more than 25 invocations`() = runTest {
        val tools = mapOf("echo" to echoTool())
        val batch = BatchTool(tools)
        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                repeat(26) {
                    add(buildJsonObject {
                        put("tool", "echo")
                        put("input", buildJsonObject { put("message", "item-$it") })
                    })
                }
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("too many"))
    }

    @Test
    fun `rejects forbidden tool batch`() = runTest {
        val tools = mapOf("echo" to echoTool())
        val batch = BatchTool(tools)
        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "batch")
                    put("input", buildJsonObject {})
                })
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("cannot call 'batch'"))
    }

    @Test
    fun `rejects forbidden tool task`() = runTest {
        val tools = mapOf("echo" to echoTool())
        val batch = BatchTool(tools)
        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "task")
                    put("input", buildJsonObject {})
                })
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("cannot call 'task'"))
    }

    @Test
    fun `handles partial failures without stopping others`() = runTest {
        val tools = mapOf("echo" to echoTool(), "fail" to failingTool())
        val batch = BatchTool(tools)
        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "echo")
                    put("input", buildJsonObject { put("message", "ok") })
                })
                add(buildJsonObject {
                    put("tool", "fail")
                    put("input", buildJsonObject {})
                })
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("1/2 succeeded"))
        assertTrue(text.contains("1 failed"))
    }

    @Test
    fun `rejects unknown tool name`() = runTest {
        val tools = mapOf("echo" to echoTool())
        val batch = BatchTool(tools)
        val params = buildJsonObject {
            put("invocations", buildJsonArray {
                add(buildJsonObject {
                    put("tool", "nonexistent")
                    put("input", buildJsonObject {})
                })
            })
        }

        val result = batch.execute("batch-1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("unknown tool"))
    }
}
