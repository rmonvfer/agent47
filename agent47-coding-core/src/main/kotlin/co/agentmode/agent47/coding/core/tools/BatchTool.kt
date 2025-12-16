package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Executes up to 25 independent tool calls in parallel, collecting results from each.
 * Cannot call itself or the task tool (sub-agents). Partial failures do not stop other calls.
 */
public class BatchTool(
    private val availableTools: Map<String, AgentTool<*>>,
) : AgentTool<List<BatchToolCallResult>> {

    override val label: String = "batch"

    override val definition: ToolDefinition = toolDefinition(
        "batch",
        loadToolPrompt("batch", "Execute multiple tool calls in parallel."),
    ) {
        array("invocations") {
            required = true
            description = "Array of tool invocations to execute in parallel (max 25)"
            items {
                string("tool") { required = true; description = "Name of the tool to call" }
                obj("input") { required = true; description = "Parameters to pass to the tool" }
            }
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<List<BatchToolCallResult>>?,
    ): AgentToolResult<List<BatchToolCallResult>> {
        val invocations = parameters["invocations"]?.jsonArray
            ?: error("Missing required parameter: invocations")

        if (invocations.isEmpty()) {
            return AgentToolResult(
                content = listOf(TextContent(text = "Error: invocations array is empty")),
                details = emptyList(),
            )
        }

        if (invocations.size > MAX_INVOCATIONS) {
            return AgentToolResult(
                content = listOf(TextContent(text = "Error: too many invocations (${invocations.size}). Maximum is $MAX_INVOCATIONS.")),
                details = emptyList(),
            )
        }

        val parsed = invocations.mapIndexed { index, element ->
            val obj = element.jsonObject
            val toolName = obj.string("tool")
                ?: error("Invocation at index $index missing 'tool'")
            val input = obj["input"]?.jsonObject
                ?: error("Invocation at index $index missing 'input'")

            if (toolName in FORBIDDEN_TOOLS) {
                return AgentToolResult(
                    content = listOf(TextContent(text = "Error: cannot call '$toolName' from within batch tool.")),
                    details = emptyList(),
                )
            }

            val tool = availableTools[toolName]
                ?: return AgentToolResult(
                    content = listOf(TextContent(text = "Error: unknown tool '$toolName' at index $index. Available: ${availableTools.keys.sorted()}")),
                    details = emptyList(),
                )

            Triple(index, tool, input)
        }

        val results = coroutineScope {
            parsed.map { (index, tool, input) ->
                async {
                    executeSingle(index, tool, input)
                }
            }.awaitAll()
        }

        val successCount = results.count { it.success }
        val failCount = results.size - successCount

        val outputText = buildString {
            appendLine("Batch completed: $successCount/${results.size} succeeded" +
                if (failCount > 0) ", $failCount failed" else "")
            appendLine()
            for (result in results) {
                val status = if (result.success) "OK" else "FAILED"
                appendLine("--- [${result.index}] ${result.toolName} [$status] ---")
                appendLine(result.output)
                appendLine()
            }
        }

        return AgentToolResult(
            content = listOf(TextContent(text = outputText)),
            details = results,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeSingle(
        index: Int,
        tool: AgentTool<*>,
        input: JsonObject,
    ): BatchToolCallResult {
        return try {
            val result = (tool as AgentTool<Any?>).execute(
                toolCallId = "batch_${index}",
                parameters = input,
                onUpdate = null,
            )
            val outputText = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }

            BatchToolCallResult(
                index = index,
                toolName = tool.label,
                output = outputText,
                success = true,
            )
        } catch (e: Exception) {
            BatchToolCallResult(
                index = index,
                toolName = tool.label,
                output = "Error: ${e.message}",
                success = false,
            )
        }
    }

    private companion object {
        const val MAX_INVOCATIONS = 25
        val FORBIDDEN_TOOLS = setOf("batch", "task")
    }
}

public data class BatchToolCallResult(
    val index: Int,
    val toolName: String,
    val output: String,
    val success: Boolean,
)
