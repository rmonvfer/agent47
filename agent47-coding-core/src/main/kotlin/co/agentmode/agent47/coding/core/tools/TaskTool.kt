package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.SubAgentOptions
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.agents.runSubAgent
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path

public class TaskTool(
    private val agentRegistry: AgentRegistry,
    private val modelRegistry: ModelRegistry,
    private val settings: Settings,
    private val cwd: Path,
    private val currentDepth: Int = 0,
    private val maxDepth: Int = 2,
    private val getApiKey: (suspend (provider: String) -> String?)? = null,
) : AgentTool<List<SubAgentResult>> {

    override val label: String = "task"

    override val definition: ToolDefinition = toolDefinition(
        "task",
        buildString {
            append(loadToolPrompt("task", "Run one or more tasks using specialized sub-agents."))
            appendLine()
            appendLine()
            append("Available agents: ")
            append(agentRegistry.getAll().joinToString(", ") { "${it.name} (${it.description})" })
        },
    ) {
        string("agent") { required = true; description = "Name of the agent to run tasks with" }
        string("context") { description = "Shared context provided to all tasks in this batch" }
        obj("schema") { description = "JTD output schema that each task result must conform to" }
        array("tasks") {
            required = true
            description = "Tasks to execute sequentially"
            items {
                string("id") { required = true; description = "Unique task identifier (alphanumeric, max 32 chars)" }
                string("description") { description = "Brief description of the task" }
                string("assignment") { required = true; description = "Detailed instructions for the agent" }
            }
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
    ): AgentToolResult<List<SubAgentResult>> {
        val agentName = parameters.string("agent")
            ?: return errorResult("Missing required parameter: agent")

        val agentDefinition = agentRegistry.get(agentName)
            ?: return errorResult("Unknown agent: $agentName. Available: ${agentRegistry.getAll().joinToString(", ") { it.name }}")

        val tasks = parameters["tasks"]?.jsonArray
            ?: return errorResult("Missing required parameter: tasks")

        if (tasks.isEmpty()) {
            return errorResult("Tasks array is empty")
        }

        val context = parameters.string("context", required = false)
        val schemaOverride = parameters["schema"]?.jsonObject

        if (schemaOverride != null && agentDefinition.output == null) {
            // Use the schema override for this batch
        }

        val effectiveSchema = schemaOverride ?: agentDefinition.output

        if (currentDepth >= maxDepth) {
            return errorResult("Maximum recursion depth ($maxDepth) reached. Cannot spawn sub-agents at depth $currentDepth.")
        }

        val parentModel = modelRegistry.getAvailable().firstOrNull()
            ?: return errorResult("No models available")

        val results = mutableListOf<SubAgentResult>()

        for ((index, taskElement) in tasks.withIndex()) {
            val taskObj = taskElement.jsonObject

            val taskId = taskObj.string("id", required = false)
                ?: return errorResult("Task at index $index missing 'id'")

            if (!taskId.matches(Regex("^[a-zA-Z0-9_-]{1,32}$"))) {
                return errorResult("Invalid task ID '$taskId': must be alphanumeric (with _ and -), max 32 chars")
            }

            val assignment = taskObj.string("assignment", required = false)
                ?: return errorResult("Task '$taskId' missing 'assignment'")

            val description = taskObj.string("description", required = false)

            val definitionWithSchema = if (effectiveSchema != null && agentDefinition.output == null) {
                agentDefinition.copy(output = effectiveSchema)
            } else {
                agentDefinition
            }

            val result = runSubAgent(
                SubAgentOptions(
                    agentDefinition = definitionWithSchema,
                    task = assignment,
                    taskId = taskId,
                    description = description,
                    context = context,
                    cwd = cwd,
                    parentModel = parentModel,
                    modelRegistry = modelRegistry,
                    settings = settings,
                    currentDepth = currentDepth,
                    maxDepth = maxDepth,
                    agentRegistry = agentRegistry,
                    getApiKey = getApiKey,
                    onProgress = { progress ->
                        val updatedProgress = progress.copy(index = index)
                        emitProgress(onUpdate, results, updatedProgress)
                    },
                    onEvent = null,
                ),
            )

            results += result

            onUpdate?.onUpdate(
                AgentToolResult(
                    content = listOf(TextContent(text = formatProgressSummary(results, tasks.size))),
                    details = results.toList(),
                ),
            )
        }

        val summary = formatFinalSummary(results)
        return AgentToolResult(
            content = listOf(TextContent(text = summary)),
            details = results,
        )
    }

    private fun emitProgress(
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        completedResults: List<SubAgentResult>,
        progress: SubAgentProgress,
    ) {
        onUpdate ?: return

        val progressText = buildString {
            appendLine("[${progress.index + 1}] ${progress.agent}/${progress.id}: ${progress.status}")
            if (progress.currentTool != null) {
                appendLine("  Running: ${progress.currentTool}")
            }
            appendLine("  Tools: ${progress.toolCount} | Tokens: ${progress.tokens}")
        }

        onUpdate.onUpdate(
            AgentToolResult(
                content = listOf(TextContent(text = progressText)),
                details = completedResults,
            ),
        )
    }

    private fun formatProgressSummary(results: List<SubAgentResult>, total: Int): String {
        return buildString {
            appendLine("Completed ${results.size}/$total tasks")
            results.forEach { result ->
                val status = if (result.exitCode == 0) "OK" else "FAILED"
                appendLine("  [${result.id}] $status (${result.durationMs}ms, ${result.tokens} tokens)")
            }
        }
    }

    private fun formatFinalSummary(results: List<SubAgentResult>): String {
        return buildString {
            appendLine("All ${results.size} task(s) completed.")
            appendLine()
            for (result in results) {
                val status = when {
                    result.aborted -> "ABORTED"
                    result.error != null -> "ERROR"
                    result.exitCode == 0 -> "SUCCESS"
                    else -> "FAILED"
                }
                appendLine("--- Task: ${result.id} [$status] ---")
                appendLine("Agent: ${result.agent} | Duration: ${result.durationMs}ms | Tokens: ${result.tokens}")
                if (result.error != null) {
                    appendLine("Error: ${result.error}")
                }
                appendLine()
                appendLine(result.output)
                appendLine()
            }
        }
    }

    private fun errorResult(message: String): AgentToolResult<List<SubAgentResult>> {
        return AgentToolResult(
            content = listOf(TextContent(text = "Error: $message")),
            details = emptyList(),
        )
    }
}
