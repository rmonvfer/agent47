package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.coding.core.agents.AgentDefinition
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.SubAgentOptions
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.agents.runSubAgent
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.ai.types.Model
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path

public data class TaskToolProgressState(
    val results: List<SubAgentResult>,
    val activeProgress: SubAgentProgress? = null,
    val activeProgressList: List<SubAgentProgress> = emptyList(),
)

public class TaskTool(
    private val agentRegistry: AgentRegistry,
    private val modelRegistry: ModelRegistry,
    private val settings: Settings,
    private val cwd: Path,
    private val backgroundAgents: BackgroundAgents,
    private val currentDepth: Int = 0,
    private val maxDepth: Int = 2,
    private val getApiKey: (suspend (provider: String) -> String?)? = null,
    private val sessionsDir: Path? = null,
    private val parentSessionId: String? = null,
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
        boolean("parallel") { description = "Run tasks in parallel. Only for tasks that are fully independent." }
        array("tasks") {
            required = true
            description = "Tasks to execute"
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

        val effectiveSchema = schemaOverride ?: agentDefinition.output

        if (currentDepth >= maxDepth) {
            return errorResult("Maximum recursion depth ($maxDepth) reached. Cannot spawn sub-agents at depth $currentDepth.")
        }

        val parentModel = modelRegistry.getAvailable().firstOrNull()
            ?: return errorResult("No models available")

        val definitionWithSchema = if (effectiveSchema != null && agentDefinition.output == null) {
            agentDefinition.copy(output = effectiveSchema)
        } else {
            agentDefinition
        }

        // Pre-validate all tasks before executing
        val parsedTasks = mutableListOf<ParsedTask>()
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
            parsedTasks += ParsedTask(index, taskId, assignment, description)
        }

        // Launch each task as a background agent and return immediately with their ids. The
        // orchestrator keeps working and collects results/messages later via check_inbox.
        val launched = parsedTasks.map { task ->
            val agentId = backgroundAgents.uniqueId(task.id)
            backgroundAgents.launch(
                id = agentId,
                agentName = definitionWithSchema.name,
                description = task.description,
                task = task.assignment,
            ) {
                executeTask(
                    task = task,
                    agentId = agentId,
                    definition = definitionWithSchema,
                    context = context,
                    parentModel = parentModel,
                    onProgress = { p -> backgroundAgents.updateProgress(agentId, p) },
                    onAgentReady = { a -> backgroundAgents.setAgentRef(agentId, a) },
                )
            }
            agentId
        }

        val summary = buildString {
            appendLine("Launched ${launched.size} background agent${if (launched.size != 1) "s" else ""}: ${launched.joinToString(", ")}")
            appendLine()
            append(
                "They run in the background — keep working on other things, then call check_inbox to " +
                    "collect their results and read any messages they send. Use send_message(\"<id>\", " +
                    "\"…\") to steer a running one.",
            )
        }
        return AgentToolResult(
            content = listOf(TextContent(text = summary)),
            details = emptyList(),
        )
    }

    private data class ParsedTask(val index: Int, val id: String, val assignment: String, val description: String?)

    private suspend fun executeTask(
        task: ParsedTask,
        agentId: String,
        definition: AgentDefinition,
        context: String?,
        parentModel: Model,
        onProgress: (SubAgentProgress) -> Unit,
        onAgentReady: (Agent) -> Unit,
    ): SubAgentResult {
        val childSessionManager = if (sessionsDir != null) {
            val childPath = sessionsDir.resolve("subagent-${parentSessionId ?: "none"}-${task.id}.jsonl")
            runCatching { SessionManager(childPath) }.getOrNull()
        } else {
            null
        }

        val result = runSubAgent(
            SubAgentOptions(
                agentDefinition = definition,
                task = task.assignment,
                taskId = task.id,
                description = task.description,
                context = context,
                cwd = cwd,
                parentModel = parentModel,
                modelRegistry = modelRegistry,
                settings = settings,
                currentDepth = currentDepth,
                maxDepth = maxDepth,
                agentRegistry = agentRegistry,
                getApiKey = getApiKey,
                onProgress = onProgress,
                onEvent = if (childSessionManager != null) { event ->
                    if (event is MessageEndEvent) {
                        runCatching { childSessionManager.appendMessage(event.message) }
                    }
                } else {
                    null
                },
                sessionsDir = sessionsDir,
                parentSessionId = parentSessionId,
                onAgentReady = onAgentReady,
                backgroundAgents = backgroundAgents,
                backgroundAgentId = agentId,
            ),
        )

        val sessionFilePath = childSessionManager?.let { sessionsDir?.resolve("subagent-${parentSessionId ?: "none"}-${task.id}.jsonl")?.toString() }

        return result.copy(sessionFile = sessionFilePath)
    }

    private fun errorResult(message: String): AgentToolResult<List<SubAgentResult>> {
        return AgentToolResult(
            content = listOf(TextContent(text = "Error: $message")),
            details = emptyList(),
        )
    }
}
