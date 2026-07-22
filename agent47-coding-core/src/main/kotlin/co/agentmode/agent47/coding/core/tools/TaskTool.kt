package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.coding.core.agents.AgentDefinition
import co.agentmode.agent47.coding.core.agents.AgentInvocationParams
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.IsolationMode
import co.agentmode.agent47.coding.core.agents.SubAgentOptions
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.agents.runSubAgent
import co.agentmode.agent47.coding.core.agents.schedule.NewJobInput
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.ai.types.Message
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
    private val subagentsSettings: SubagentsSettings = SubagentsSettings(),
    private val currentDepth: Int = 0,
    private val maxDepth: Int = 2,
    private val getApiKey: (suspend (provider: String) -> String?)? = null,
    private val sessionsDir: Path? = null,
    private val parentSessionId: String? = null,
    private val memoryProjectDir: Path? = null,
    private val memoryGlobalDir: Path? = null,
    private val scheduler: SubagentScheduler? = null,
    private val skillContentProvider: ((String) -> String?)? = null,
) : AgentTool<List<SubAgentResult>> {

    override val label: String = "task"

    override val definition: ToolDefinition = toolDefinition(
        "task",
        buildString {
            append(loadToolPrompt("task", "Run one or more tasks using specialized sub-agents."))
            appendLine()
            appendLine()
            append("Available agents: ")
            append(agentRegistry.getAvailable().joinToString(", ") { "${it.name} (${it.description})" })
        },
    ) {
        string("agent") { required = true; description = "Name of the agent to run tasks with" }
        string("context") { description = "Shared context provided to all tasks in this batch" }
        obj("schema") { description = "JTD output schema that each task result must conform to" }
        boolean("parallel") { description = "Run tasks in parallel. Only for tasks that are fully independent." }
        if (subagentsSettings.schedulingEnabled) {
            string("schedule") {
                description = "Opt-in: fire the tasks later instead of now. Formats: 6-field cron " +
                    "(\"0 0 9 * * 1\" = 9am Mondays), interval (\"5m\"/\"1h\"), or one-shot (\"+10m\" or ISO). " +
                    "Returns scheduled job ids."
            }
        }
        array("tasks") {
            required = true
            description = "Tasks to execute"
            items {
                string("id") { required = true; description = "Unique task identifier (alphanumeric, max 32 chars)" }
                string("description") { description = "Brief description of the task" }
                string("assignment") { required = true; description = "Detailed instructions for the agent" }
                string("model") { description = "Model override: \"provider/id\" or fuzzy name (e.g. \"haiku\"). Omit for the agent's default." }
                string("thinking") { description = "Thinking level override: off, minimal, low, medium, high, xhigh." }
                int("max_turns") { description = "Max agentic turns before wrap-up. Omit for the agent/settings default (0 = unlimited)." }
                boolean("inherit_context") { description = "Fork the parent conversation into the agent. Default: false (fresh context)." }
                string("isolation") { description = "Set to \"worktree\" to run in an isolated git worktree; changes land on a branch." }
                string("resume") { description = "Task id of a prior sub-agent to resume — restores its conversation before this assignment." }
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
            ?: return errorResult("Unknown agent: $agentName. Available: ${agentRegistry.getAvailable().joinToString(", ") { it.name }}")

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
            val invocation = AgentInvocationParams(
                model = taskObj.string("model", required = false),
                thinking = taskObj.string("thinking", required = false),
                maxTurns = taskObj.int("max_turns"),
                inheritContext = taskObj.boolean("inherit_context"),
                isolation = if (taskObj.string("isolation", required = false)?.equals("worktree", ignoreCase = true) == true) {
                    IsolationMode.WORKTREE
                } else {
                    null
                },
                resume = taskObj.string("resume", required = false),
            )
            parsedTasks += ParsedTask(index, taskId, assignment, description, invocation)
        }

        // Scheduled tasks: register jobs to fire later instead of launching now.
        val scheduleStr = parameters.string("schedule", required = false)
        val activeScheduler = scheduler
        if (scheduleStr != null && activeScheduler != null && subagentsSettings.schedulingEnabled) {
            val jobIds = mutableListOf<String>()
            for (task in parsedTasks) {
                val job = runCatching {
                    activeScheduler.addJob(
                        NewJobInput(
                            name = "${task.id}-${System.currentTimeMillis()}",
                            description = task.description ?: task.id,
                            schedule = scheduleStr,
                            subagentType = definitionWithSchema.name,
                            prompt = task.assignment,
                            model = task.invocation.model,
                            thinking = task.invocation.thinking,
                            maxTurns = task.invocation.maxTurns,
                            isolation = if (task.invocation.isolation == IsolationMode.WORKTREE) "worktree" else null,
                        ),
                    )
                }.getOrElse { return errorResult("Could not schedule '${task.id}': ${it.message}") }
                jobIds += job.id
            }
            val summary = "Scheduled ${jobIds.size} job${if (jobIds.size != 1) "s" else ""} (\"$scheduleStr\"): " +
                jobIds.joinToString(", ") + ". They fire while this session is running."
            return AgentToolResult(content = listOf(TextContent(text = summary)), details = emptyList())
        }

        // Snapshot the orchestrator's prompt/conversation now (at call time) so append-mode and
        // inherit_context see the context as it was when the task was requested.
        val parentSystemPrompt = backgroundAgents.orchestratorSystemPrompt()
        val parentMessages = backgroundAgents.orchestratorMessages()

        // Launch each task as a background agent and return immediately with their ids. The
        // orchestrator keeps working and collects results/messages later via check_inbox.
        val batchId = "batch-${System.currentTimeMillis()}"
        val launched = parsedTasks.map { task ->
            val agentId = backgroundAgents.uniqueId(task.id)
            backgroundAgents.launch(
                id = agentId,
                agentName = definitionWithSchema.name,
                description = task.description,
                task = task.assignment,
                groupId = batchId,
                groupSize = parsedTasks.size,
            ) {
                executeTask(
                    task = task,
                    agentId = agentId,
                    definition = definitionWithSchema,
                    context = context,
                    parentModel = parentModel,
                    parentSystemPrompt = parentSystemPrompt,
                    parentMessages = parentMessages,
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

    private data class ParsedTask(
        val index: Int,
        val id: String,
        val assignment: String,
        val description: String?,
        val invocation: AgentInvocationParams,
    )

    private suspend fun executeTask(
        task: ParsedTask,
        agentId: String,
        definition: AgentDefinition,
        context: String?,
        parentModel: Model,
        parentSystemPrompt: String?,
        parentMessages: List<Message>,
        onProgress: (SubAgentProgress) -> Unit,
        onAgentReady: (Agent) -> Unit,
    ): SubAgentResult {
        val childSessionManager = if (sessionsDir != null) {
            val childPath = sessionsDir.resolve("subagent-${parentSessionId ?: "none"}-${task.id}.jsonl")
            runCatching { SessionManager(childPath) }.getOrNull()
        } else {
            null
        }

        // Resume: reload a prior sub-agent's persisted conversation, if requested and available.
        val resumeMessages: List<Message> = task.invocation.resume?.let { resumeId ->
            if (sessionsDir == null) return@let emptyList()
            val resumePath = sessionsDir.resolve("subagent-${parentSessionId ?: "none"}-$resumeId.jsonl")
            runCatching { SessionManager(resumePath).buildContext().messages }.getOrDefault(emptyList())
        } ?: emptyList()

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
                subagentsSettings = subagentsSettings,
                invocation = task.invocation,
                parentSystemPrompt = parentSystemPrompt,
                parentMessages = parentMessages,
                resumeMessages = resumeMessages,
                memoryProjectDir = memoryProjectDir,
                memoryGlobalDir = memoryGlobalDir,
                skillContentProvider = skillContentProvider,
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
