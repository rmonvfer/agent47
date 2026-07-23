package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinExtensionBehaviorTest {
    @Test
    fun `compiled script hooks commands tools and wrappers execute together`() = runTest {
        val afterMarker = Files.createTempFile("agent47-extension-after", ".txt")
        Files.delete(afterMarker)
        val script = Files.createTempFile("agent47-extension-behavior", ".kts")
        Files.writeString(script, behaviorScript(afterMarker))

        val loadResult = KotlinExtensionScriptLoader().load(script)
        val loaded = assertIs<ScriptLoadResult.Loaded>(
            loadResult,
            (loadResult as? ScriptLoadResult.Failed)?.failure?.diagnostics?.joinToString("\n"),
        )
        val runner = ExtensionRunner().also { it.load(loaded.extension) }
        val initial = listOf(UserMessage(content = listOf(TextContent(text = "initial")), timestamp = 1L))

        val before = runner.runBeforeAgent(initial)
        assertEquals(2, before.size)
        assertEquals("injected", assertIs<UserMessage>(before.last()).text())

        val transformed = runner.transformContext(Context(systemPrompt = "base", messages = before))
        assertEquals("base|transformed", transformed.systemPrompt)

        runner.runAfterAgent(before)
        assertEquals("after:2", Files.readString(afterMarker))

        val context = RecordingCommandContext()
        runner.commands().single { it.name == "hello" }.handler.run("Bossman", context)
        assertEquals(listOf("Hello Bossman"), context.notifications)
        assertEquals(listOf("follow-up"), context.userMessages)
        assertEquals(1, context.reloadCount)

        val tool = runner.wrapAllTools(runner.tools()).single()
        @Suppress("UNCHECKED_CAST")
        val typedTool = tool as AgentTool<JsonObject>
        val result = typedTool.execute("call-1", buildJsonObject { put("text", "value") })
        assertEquals(listOf("value", "wrapped"), result.content.filterIsInstance<TextContent>().map { it.text })
        assertEquals("value", result.details["text"].toString().trim('"'))
    }

    @Suppress("LongMethod")
    private fun behaviorScript(afterMarker: Path): String {
        val marker = afterMarker.toString().replace("\\", "\\\\")
        return """
            import co.agentmode.agent47.agent.core.AgentTool
            import co.agentmode.agent47.agent.core.AgentToolResult
            import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
            import co.agentmode.agent47.ai.types.TextContent
            import co.agentmode.agent47.ai.types.ToolDefinition
            import co.agentmode.agent47.ai.types.UserMessage
            import co.agentmode.agent47.ext.core.ToolWrapper
            import kotlinx.serialization.json.JsonObject
            import kotlinx.serialization.json.buildJsonObject
            import kotlinx.serialization.json.jsonPrimitive
            import kotlinx.serialization.json.put
            import java.nio.file.Files
            import java.nio.file.Path

            beforeAgent { messages ->
                messages + UserMessage(content = listOf(TextContent(text = "injected")), timestamp = 2L)
            }

            afterAgent { messages ->
                Files.writeString(Path.of("$marker"), "after:${'$'}{messages.size}")
            }

            transformContext { context ->
                context.copy(systemPrompt = context.systemPrompt + "|transformed")
            }

            wrapTools(object : ToolWrapper {
                override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> = object : AgentTool<T> {
                    override val definition = tool.definition
                    override val label = tool.label

                    override suspend fun execute(
                        toolCallId: String,
                        parameters: JsonObject,
                        onUpdate: AgentToolUpdateCallback<T>?,
                    ): AgentToolResult<T> {
                        val result = tool.execute(toolCallId, parameters, onUpdate)
                        return result.copy(content = result.content + TextContent(text = "wrapped"))
                    }
                }
            })

            registerTool(object : AgentTool<JsonObject> {
                override val label = "Echo"
                override val definition = ToolDefinition(
                    name = "echo",
                    description = "Echo text",
                    parameters = buildJsonObject { put("type", "object") },
                )

                override suspend fun execute(
                    toolCallId: String,
                    parameters: JsonObject,
                    onUpdate: AgentToolUpdateCallback<JsonObject>?,
                ): AgentToolResult<JsonObject> = AgentToolResult(
                    content = listOf(TextContent(text = parameters.getValue("text").jsonPrimitive.content)),
                    details = parameters,
                )
            })

            registerCommand("hello", "Say hello") { args, context ->
                context.notify("Hello ${'$'}args")
                context.sendUserMessage("follow-up")
                context.reload()
            }
        """.trimIndent()
    }

    private fun UserMessage.text(): String =
        content.filterIsInstance<TextContent>().joinToString(separator = "") { it.text }

    private class RecordingCommandContext : ExtensionCommandContext {
        override val cwd: Path = Path.of(".").toAbsolutePath().normalize()
        override val hasUi: Boolean = true
        val notifications: MutableList<String> = mutableListOf()
        val userMessages: MutableList<String> = mutableListOf()
        var reloadCount: Int = 0

        override fun notify(message: String) {
            notifications += message
        }

        override suspend fun sendUserMessage(message: String) {
            userMessages += message
        }

        override suspend fun reload() {
            reloadCount += 1
        }
    }
}
