package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.coding.core.agents.AgentDefinition
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.SubAgentOptions
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.agents.runSubAgent
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.ai.types.Model
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
        val parallel = parameters.boolean("parallel") ?: false

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

        val results = if (parallel) {
            executeParallel(parsedTasks, definitionWithSchema, context, parentModel, onUpdate, tasks.size)
        } else {
            executeSequential(parsedTasks, definitionWithSchema, context, parentModel, onUpdate, tasks.size)
        }

        val summary = formatFinalSummary(results)
        return AgentToolResult(
            content = listOf(TextContent(text = summary)),
            details = results,
        )
    }

    private data class ParsedTask(val index: Int, val id: String, val assignment: String, val description: String?)

    private suspend fun executeSequential(
        parsedTasks: List<ParsedTask>,
        definition: AgentDefinition,
        context: String?,
        parentModel: Model,
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        totalCount: Int,
    ): List<SubAgentResult> {
        val results = mutableListOf<SubAgentResult>()

        for (task in parsedTasks) {
            val result = executeTask(task, definition, context, parentModel, onUpdate) { progress ->
                val updatedProgress = progress.copy(index = task.index)
                emitProgress(onUpdate, results, updatedProgress)
            }
            results += result
            emitProgressUpdate(onUpdate, formatProgressSummary(results, totalCount), TaskToolProgressState(results.toList()))
        }

        return results
    }

    private suspend fun executeParallel(
        parsedTasks: List<ParsedTask>,
        definition: AgentDefinition,
        context: String?,
        parentModel: Model,
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        totalCount: Int,
    ): List<SubAgentResult> {
        val progressMap = ConcurrentHashMap<Int, SubAgentProgress>()
        val completedResults = Collections.synchronizedList(mutableListOf<SubAgentResult>())
        val updateLock = Any()

        fun emitParallelProgress() {
            val snapshot = synchronized(updateLock) {
                TaskToolProgressState(
                    results = completedResults.toList(),
                    activeProgressList = progressMap.values.toList().sortedBy { it.index },
                )
            }
            val progressText = buildString {
                appendLine("Completed ${snapshot.results.size}/$totalCount tasks (parallel)")
                snapshot.activeProgressList.forEach { p ->
                    appendLine("  [${p.index + 1}] ${p.agent}/${p.id}: ${p.status}")
                }
            }
            emitProgressUpdate(onUpdate, progressText, snapshot)
        }

        val allResults = supervisorScope {
            parsedTasks.map { task ->
                async {
                    runCatching {
                        executeTask(task, definition, context, parentModel, onUpdate) { progress ->
                            val updatedProgress = progress.copy(index = task.index)
                            progressMap[task.index] = updatedProgress
                            emitParallelProgress()
                        }
                    }.getOrElse { error ->
                        SubAgentResult(
                            id = task.id,
                            agent = definition.name,
                            agentSource = definition.source,
                            task = task.assignment,
                            description = task.description,
                            exitCode = 1,
                            output = "Sub-agent failed: ${error.message ?: error::class.simpleName}",
                            truncated = false,
                            durationMs = 0,
                            tokens = 0,
                            error = error.message ?: error::class.simpleName ?: "Unknown error",
                            aborted = false,
                        )
                    }.also { result ->
                        progressMap.remove(task.index)
                        completedResults += result
                        emitParallelProgress()
                    }
                }
            }.awaitAll()
        }

        return allResults
    }

    private suspend fun executeTask(
        task: ParsedTask,
        definition: AgentDefinition,
        context: String?,
        parentModel: Model,
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        onProgress: (SubAgentProgress) -> Unit,
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
            ),
        )

        val sessionFilePath = childSessionManager?.let { sessionsDir?.resolve("subagent-${parentSessionId ?: "none"}-${task.id}.jsonl")?.toString() }

        return result.copy(sessionFile = sessionFilePath)
    }

    @Suppress("UNCHECKED_CAST")
    private fun emitProgressUpdate(
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        content: String,
        progressState: TaskToolProgressState,
    ) {
        onUpdate ?: return
        val erased = onUpdate as AgentToolUpdateCallback<Any?>
        val result: AgentToolResult<Any?> = AgentToolResult(
            content = listOf(TextContent(text = content)),
            details = progressState,
        )
        erased.onUpdate(result)
    }

    private fun emitProgress(
        onUpdate: AgentToolUpdateCallback<List<SubAgentResult>>?,
        completedResults: List<SubAgentResult>,
        progress: SubAgentProgress,
    ) {
        val progressText = buildString {
            appendLine("[${progress.index + 1}] ${progress.agent}/${progress.id}: ${progress.status}")
            if (progress.currentTool != null) {
                appendLine("  Running: ${progress.currentTool}")
            }
            appendLine("  Tools: ${progress.toolCount} | Tokens: ${progress.tokens}")
        }
        emitProgressUpdate(onUpdate, progressText, TaskToolProgressState(completedResults, progress))
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
