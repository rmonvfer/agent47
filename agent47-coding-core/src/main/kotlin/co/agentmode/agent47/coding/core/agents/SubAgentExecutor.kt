package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentEndEvent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.agent.core.ToolExecutionEndEvent
import co.agentmode.agent47.agent.core.ToolExecutionStartEvent
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.models.ModelResolver
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.SubmitResultData
import co.agentmode.agent47.coding.core.tools.SubmitResultTool
import co.agentmode.agent47.coding.core.tools.createCoreTools
import co.agentmode.agent47.coding.core.tools.truncateHead
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.nio.file.Path
import java.util.UUID
import kotlin.system.measureTimeMillis

public data class SubAgentOptions(
    val agentDefinition: AgentDefinition,
    val task: String,
    val taskId: String,
    val description: String?,
    val context: String?,
    val cwd: Path,
    val parentModel: Model,
    val modelRegistry: ModelRegistry,
    val settings: Settings,
    val currentDepth: Int,
    val maxDepth: Int,
    val agentRegistry: AgentRegistry?,
    val getApiKey: (suspend (provider: String) -> String?)?,
    val onProgress: ((SubAgentProgress) -> Unit)?,
    val onEvent: ((AgentEvent) -> Unit)?,
    val sessionsDir: Path? = null,
    val parentSessionId: String? = null,
)

public data class SubAgentResult(
    val id: String,
    val agent: String,
    val agentSource: AgentSource,
    val task: String,
    val description: String?,
    val exitCode: Int,
    val output: String,
    val truncated: Boolean,
    val durationMs: Long,
    val tokens: Long,
    val error: String?,
    val aborted: Boolean,
    val sessionFile: String? = null,
)

public data class SubAgentProgress(
    val index: Int,
    val id: String,
    val agent: String,
    val status: String,
    val currentTool: String?,
    val toolCount: Int,
    val tokens: Long,
    val durationMs: Long,
)

public suspend fun runSubAgent(options: SubAgentOptions): SubAgentResult {
    val id = UUID.randomUUID().toString().substring(0, 8)
    val definition = options.agentDefinition
    val startTime = System.currentTimeMillis()

    val model = resolveAgentModel(definition, options)

    val tools = buildToolList(definition, options, id)

    val systemPrompt = buildSubAgentSystemPrompt(definition, options)

    var submitResult: SubmitResultData? = null
    var toolCount = 0
    var currentTool: String? = null
    var totalTokens = 0L

    val submitResultTool = SubmitResultTool(
        outputSchema = definition.output,
        onSubmit = { data -> submitResult = data },
    )
    val allTools: List<AgentTool<*>> = tools + submitResultTool

    val thinkingLevel = parseAgentThinkingLevel(definition.thinkingLevel)

    val agent = Agent(
        AgentOptions(
            initialState = PartialAgentState(
                model = model,
                systemPrompt = systemPrompt,
                tools = allTools,
                thinkingLevel = thinkingLevel,
            ),
            getApiKey = options.getApiKey,
        ),
    )

    val unsubscribe = agent.subscribe { event ->
        when (event) {
            is ToolExecutionStartEvent -> {
                toolCount++
                currentTool = event.toolName
            }
            is ToolExecutionEndEvent -> {
                currentTool = null
            }
            is AgentEndEvent -> {
                val messages = agent.state.messages
                val lastAssistant = messages.lastOrNull { it is AssistantMessage } as? AssistantMessage
                if (lastAssistant != null) {
                    totalTokens += lastAssistant.usage.totalTokens
                }
            }
            else -> {}
        }

        options.onEvent?.invoke(event)
        options.onProgress?.invoke(
            SubAgentProgress(
                index = 0,
                id = id,
                agent = definition.name,
                status = "running",
                currentTool = currentTool,
                toolCount = toolCount,
                tokens = totalTokens,
                durationMs = System.currentTimeMillis() - startTime,
            ),
        )
    }

    var durationMs: Long
    var error: String? = null

    try {
        durationMs = measureTimeMillis {
            agent.prompt(options.task)
        }

        if (submitResult == null) {
            for (retry in 1..3) {
                if (submitResult != null) break
                agent.prompt("You must call submit_result to complete this task. Please call it now with your results.")
            }
        }
    } catch (e: CancellationException) {
        agent.abort()
        durationMs = System.currentTimeMillis() - startTime
        error = "Cancelled"
        throw e
    } catch (e: Exception) {
        durationMs = System.currentTimeMillis() - startTime
        error = e.message ?: e::class.simpleName ?: "Unknown error"
    } finally {
        unsubscribe()
    }

    val output = buildSubAgentOutput(submitResult, agent, error)
    val truncation = truncateHead(output)

    return SubAgentResult(
        id = id,
        agent = definition.name,
        agentSource = definition.source,
        task = options.task,
        description = options.description,
        exitCode = if (error != null || submitResult?.status == "aborted") 1 else 0,
        output = truncation.content,
        truncated = truncation.truncated,
        durationMs = durationMs,
        tokens = totalTokens,
        error = error ?: submitResult?.error,
        aborted = submitResult?.status == "aborted",
    )
}

private fun resolveAgentModel(definition: AgentDefinition, options: SubAgentOptions): Model {
    val patterns = definition.model ?: return options.parentModel
    val available = options.modelRegistry.getAvailable()

    for (pattern in patterns) {
        when {
            pattern.equals("pi/smol", ignoreCase = true) -> {
                val saved = options.settings.modelRoles["smol"]
                ModelResolver.findSmolModel(available, saved)?.let { return it }
            }
            pattern.equals("pi/slow", ignoreCase = true) -> {
                val saved = options.settings.modelRoles["slow"]
                ModelResolver.findSlowModel(available, saved)?.let { return it }
            }
            else -> {
                ModelResolver.tryMatchModel(pattern, available)?.let { return it }
            }
        }
    }

    return options.parentModel
}

private fun buildToolList(
    definition: AgentDefinition,
    options: SubAgentOptions,
    subAgentId: String,
): List<AgentTool<*>> {
    val toolNames = definition.tools
    val coreTools = if (toolNames != null) {
        createCoreTools(options.cwd, toolNames)
    } else {
        createCoreTools(options.cwd)
    }

    val tools = coreTools.all().toMutableList()

    if (options.currentDepth < options.maxDepth && definition.spawns !is SpawnsPolicy.None && options.agentRegistry != null) {
        val taskTool = co.agentmode.agent47.coding.core.tools.TaskTool(
            agentRegistry = options.agentRegistry,
            modelRegistry = options.modelRegistry,
            settings = options.settings,
            cwd = options.cwd,
            currentDepth = options.currentDepth + 1,
            maxDepth = options.maxDepth,
            getApiKey = options.getApiKey,
            sessionsDir = options.sessionsDir,
            parentSessionId = options.parentSessionId,
        )
        tools += taskTool
    }

    return tools
}

private fun buildSubAgentSystemPrompt(definition: AgentDefinition, options: SubAgentOptions): String {
    return buildString {
        appendLine(definition.systemPrompt)

        if (options.context != null) {
            appendLine()
            appendLine("<context>")
            appendLine(options.context)
            appendLine("</context>")
        }

        if (definition.output != null) {
            appendLine()
            appendLine("IMPORTANT: When you complete your task, you MUST call submit_result with a result that conforms to the output schema.")
            appendLine("Output schema (JTD): ${definition.output}")
        } else {
            appendLine()
            appendLine("When you complete your task, call submit_result with a text summary of what you accomplished.")
        }
    }
}

private fun buildSubAgentOutput(
    submitResult: SubmitResultData?,
    agent: Agent,
    error: String?,
): String {
    if (submitResult != null) {
        val result = submitResult.result
        return when {
            result == null || result is JsonNull -> "(no result)"
            result is kotlinx.serialization.json.JsonPrimitive && result.isString -> result.content
            else -> co.agentmode.agent47.ai.types.Agent47Json.encodeToString(JsonElement.serializer(), result)
        }
    }

    if (error != null) {
        return "Sub-agent failed: $error"
    }

    val messages = agent.state.messages
    val lastAssistant = messages.lastOrNull { it is AssistantMessage } as? AssistantMessage
    return lastAssistant?.content
        ?.filterIsInstance<TextContent>()
        ?.joinToString("\n") { it.text }
        ?.ifBlank { "(no output)" }
        ?: "(no output)"
}

private fun parseAgentThinkingLevel(level: String?): AgentThinkingLevel {
    return when (level?.lowercase()) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> AgentThinkingLevel.OFF
    }
}
