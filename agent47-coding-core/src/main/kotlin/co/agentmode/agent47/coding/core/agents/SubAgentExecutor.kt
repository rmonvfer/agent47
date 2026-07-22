package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.agent.core.MessageUpdateEvent
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.agent.core.ToolExecutionEndEvent
import co.agentmode.agent47.agent.core.ToolExecutionStartEvent
import co.agentmode.agent47.agent.core.TurnEndEvent
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.models.ModelResolver
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
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

/** Per-invocation overrides supplied by the caller (e.g. one entry in the `task` tool's tasks[]). */
public data class AgentInvocationParams(
    val model: String? = null,
    val thinking: String? = null,
    val maxTurns: Int? = null,
    val isolated: Boolean? = null,
    val inheritContext: Boolean? = null,
    val isolation: IsolationMode? = null,
    /** Task id of a prior sub-agent whose conversation should be restored before this run. */
    val resume: String? = null,
)

/** Extra system-prompt sections injected into a sub-agent (memory, preloaded skills). */
public data class PromptExtras(
    val memoryBlock: String? = null,
    val skillBlocks: List<SkillBlock> = emptyList(),
) {
    public data class SkillBlock(val name: String, val content: String)

    public val isEmpty: Boolean get() = memoryBlock == null && skillBlocks.isEmpty()
}

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
    val onAgentReady: ((Agent) -> Unit)? = null,
    val backgroundAgents: BackgroundAgents? = null,
    val backgroundAgentId: String? = null,
    val subagentsSettings: SubagentsSettings = SubagentsSettings(),
    val invocation: AgentInvocationParams = AgentInvocationParams(),
    val parentSystemPrompt: String? = null,
    val parentMessages: List<Message> = emptyList(),
    /** Prior conversation to restore into the agent when resuming (see [AgentInvocationParams.resume]). */
    val resumeMessages: List<Message> = emptyList(),
    val promptExtras: PromptExtras = PromptExtras(),
    // The project (.agent47) and global (~/.agent47) dirs, used to resolve per-agent memory.
    val memoryProjectDir: Path? = null,
    val memoryGlobalDir: Path? = null,
    // Resolves a named skill's full content (SKILL.md) for preload; null disables skill preload.
    val skillContentProvider: ((String) -> String?)? = null,
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
    val steered: Boolean = false,
    val sessionFile: String? = null,
    val outputFile: String? = null,
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
    val turnCount: Int = 0,
    /** The agent's latest streaming assistant text, shown as live activity between tool calls. */
    val streamingText: String? = null,
)

public suspend fun runSubAgent(options: SubAgentOptions): SubAgentResult {
    val id = UUID.randomUUID().toString().substring(0, 8)
    val definition = options.agentDefinition
    val params = options.invocation
    val startTime = System.currentTimeMillis()

    // Worktree isolation: run in an isolated copy of the repo when requested. Falls back to the
    // normal cwd if the directory isn't a git repo (createWorktree returns null).
    val isolation = params.isolation ?: definition.isolation
    val worktree: WorktreeInfo? = if (isolation == IsolationMode.WORKTREE) {
        Worktree.createWorktree(options.cwd, id)
    } else {
        null
    }
    val effectiveCwd = worktree?.let { Path.of(it.workPath) } ?: options.cwd

    // Per-agent persistent memory + preloaded skills, injected into the prompt. Memory is read-only
    // for agents without write/edit tools, and resolved against the real project dir (not the worktree).
    val memoryBlock = buildMemoryBlock(definition, options)
    val skillBlocks = buildSkillBlocks(definition, options)
    val extras = options.promptExtras.copy(
        memoryBlock = memoryBlock ?: options.promptExtras.memoryBlock,
        skillBlocks = if (skillBlocks.isNotEmpty()) skillBlocks else options.promptExtras.skillBlocks,
    )
    val effectiveOptions = options.copy(cwd = effectiveCwd, promptExtras = extras)

    val model = resolveAgentModel(definition, effectiveOptions, params.model)
    val tools = buildToolList(definition, effectiveOptions, id)
    val env = AgentEnv.detect(effectiveCwd)
    val systemPrompt = buildSubAgentSystemPrompt(definition, effectiveOptions, env)

    val outputTranscriptEnabled = definition.outputTranscript ?: options.subagentsSettings.outputTranscript
    val transcript: AgentTranscript? = if (outputTranscriptEnabled) {
        val dir = Path.of(System.getProperty("java.io.tmpdir"), "agent47-subagents", options.parentSessionId ?: "none", "tasks")
        AgentTranscript(dir.resolve("$id.output"), id, effectiveCwd.toString()).also { it.writeInitial(options.task) }
    } else {
        null
    }

    // Turn budget: per-call override > definition > settings default (0 = unlimited).
    val maxTurns = normalizeMaxTurns(params.maxTurns ?: definition.maxTurns ?: options.subagentsSettings.defaultMaxTurns)
    val graceTurns = options.subagentsSettings.graceTurns

    var submitResult: SubmitResultData? = null
    var toolCount = 0
    var currentTool: String? = null
    var streamingText: String? = null
    var totalTokens = 0L
    var turnCount = 0
    var softLimitReached = false
    var turnLimitAborted = false

    val submitResultTool = SubmitResultTool(
        outputSchema = definition.output,
        onSubmit = { data -> submitResult = data },
    )
    val allTools: List<AgentTool<*>> = tools + submitResultTool

    val thinkingLevel = parseAgentThinkingLevel(params.thinking ?: definition.thinkingLevel)

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

    // Resume: restore a prior sub-agent's conversation before this run's prompt.
    if (options.resumeMessages.isNotEmpty()) {
        agent.replaceMessages(options.resumeMessages)
    }

    // Expose the live Agent so a background registry can steer it with inter-agent messages.
    options.onAgentReady?.invoke(agent)

    val unsubscribe = agent.subscribe { event ->
        when (event) {
            is ToolExecutionStartEvent -> {
                toolCount++
                currentTool = event.toolName
            }
            is ToolExecutionEndEvent -> {
                currentTool = null
            }
            is MessageUpdateEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    val text = message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }.trim()
                    if (text.isNotEmpty()) streamingText = text
                }
            }
            is MessageEndEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    // Accumulate per-message so the total survives compaction and covers every
                    // assistant turn. cacheRead is deliberately excluded (it is a cumulative prefix;
                    // summing it double-counts).
                    totalTokens += (message.usage.input + message.usage.output + message.usage.cacheWrite).toLong()
                }
                transcript?.writeMessage(message)
            }
            is TurnEndEvent -> {
                turnCount++
                if (maxTurns != null) {
                    if (!softLimitReached && turnCount >= maxTurns) {
                        softLimitReached = true
                        agent.steer(
                            UserMessage(
                                content = listOf(
                                    TextContent(
                                        text = "You have reached your turn limit. Wrap up immediately — " +
                                            "provide your final answer now and call submit_result.",
                                    ),
                                ),
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                    } else if (softLimitReached && turnCount >= maxTurns + graceTurns) {
                        turnLimitAborted = true
                        agent.abort()
                    }
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
                turnCount = turnCount,
                streamingText = streamingText,
            ),
        )
    }

    // inherit_context: prepend a text rendering of the parent conversation to the task prompt.
    val inheritContext = params.inheritContext ?: definition.inheritContext ?: false
    val effectivePrompt = if (inheritContext && options.parentMessages.isNotEmpty()) {
        buildParentContext(options.parentMessages) + options.task
    } else {
        options.task
    }

    var durationMs: Long
    var error: String? = null

    try {
        durationMs = measureTimeMillis {
            agent.prompt(effectivePrompt)
        }

        // Completion aid: if the agent finished without submitting a result (and wasn't cut off by
        // the turn limit), nudge it a few times to call submit_result.
        if (submitResult == null && !softLimitReached && !turnLimitAborted) {
            for (retry in 1..3) {
                if (submitResult != null) break
                agent.prompt("You must call submit_result to complete this task. Please call it now with your results.")
            }
        }
    } catch (e: CancellationException) {
        durationMs = System.currentTimeMillis() - startTime
        if (turnLimitAborted) {
            // Our own turn-limit abort — a controlled stop, not a propagated cancellation.
        } else {
            agent.abort()
            error = "Cancelled"
            throw e
        }
    } catch (e: Exception) {
        durationMs = System.currentTimeMillis() - startTime
        error = e.message ?: e::class.simpleName ?: "Unknown error"
    } finally {
        unsubscribe()
    }

    // Commit any changes made in the worktree to a branch and remove the worktree.
    val worktreeNote = if (worktree != null) {
        val cleanup = Worktree.cleanupWorktree(options.cwd, worktree, options.description ?: definition.name)
        if (cleanup.hasChanges && cleanup.branch != null) {
            "\n\n(changes committed to branch: ${cleanup.branch})"
        } else {
            ""
        }
    } else {
        ""
    }

    val aborted = turnLimitAborted || submitResult?.status == "aborted"
    val baseOutput = buildSubAgentOutput(submitResult, agent, error)
    val output = baseOutput +
        statusNote(aborted = aborted, steered = softLimitReached && !turnLimitAborted) +
        worktreeNote
    val truncation = truncateHead(output)

    return SubAgentResult(
        id = id,
        agent = definition.name,
        agentSource = definition.source,
        task = options.task,
        description = options.description,
        exitCode = if (error != null || aborted) 1 else 0,
        output = truncation.content,
        truncated = truncation.truncated,
        durationMs = durationMs,
        tokens = totalTokens,
        error = error ?: submitResult?.error,
        aborted = aborted,
        steered = softLimitReached,
        outputFile = transcript?.pathString(),
    )
}

/**
 * Builds the per-agent memory block for the prompt, or null when the agent has no `memory:` scope
 * or the memory dirs weren't supplied. Read-only agents (no write/edit tool) get the read-only
 * variant. Resolution failures (unsafe name, symlinked dir) degrade to no memory block.
 */
private fun buildMemoryBlock(definition: AgentDefinition, options: SubAgentOptions): String? {
    val scope = definition.memory ?: return null
    val projectDir = options.memoryProjectDir ?: return null
    val globalDir = options.memoryGlobalDir ?: return null

    val disallowed = definition.disallowedTools?.map { it.lowercase() } ?: emptyList()
    val hasWriteTool = definition.tools?.any { it.equals("write", true) || it.equals("edit", true) } ?: true
    val canWrite = hasWriteTool && "write" !in disallowed && "edit" !in disallowed

    return runCatching {
        if (canWrite) {
            AgentMemory.buildMemoryBlock(definition.name, scope, projectDir, globalDir)
        } else {
            AgentMemory.buildReadOnlyMemoryBlock(definition.name, scope, projectDir, globalDir)
        }
    }.getOrNull()
}

/** Loads the full content of each skill named in the definition's `skills:` selector. */
private fun buildSkillBlocks(definition: AgentDefinition, options: SubAgentOptions): List<PromptExtras.SkillBlock> {
    val names = definition.skills ?: return emptyList()
    val provider = options.skillContentProvider ?: return emptyList()
    return names.mapNotNull { name -> provider(name)?.let { PromptExtras.SkillBlock(name, it) } }
}

/** `null`/`0` → unlimited; otherwise at least 1. */
private fun normalizeMaxTurns(n: Int?): Int? = when {
    n == null || n == 0 -> null
    else -> n.coerceAtLeast(1)
}

private fun statusNote(aborted: Boolean, steered: Boolean): String = when {
    aborted -> " (aborted — hit the turn limit before completion; output may be incomplete)"
    steered -> " (wrapped up at the turn limit — output may be partial)"
    else -> ""
}

private fun resolveAgentModel(definition: AgentDefinition, options: SubAgentOptions, override: String?): Model {
    val available = options.modelRegistry.getAvailable()
    val patterns = when {
        override != null -> listOf(override)
        definition.model != null -> definition.model
        else -> return options.parentModel
    }

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

    val bg = options.backgroundAgents
    if (bg != null) {
        // Let the sub-agent message the orchestrator and its siblings.
        tools += co.agentmode.agent47.coding.core.tools.SendMessageTool(
            backgroundAgents = bg,
            from = options.backgroundAgentId ?: options.taskId,
        )
    }
    if (options.currentDepth < options.maxDepth && definition.spawns !is SpawnsPolicy.None &&
        options.agentRegistry != null && bg != null
    ) {
        tools += co.agentmode.agent47.coding.core.tools.TaskTool(
            agentRegistry = options.agentRegistry,
            modelRegistry = options.modelRegistry,
            settings = options.settings,
            cwd = options.cwd,
            backgroundAgents = bg,
            subagentsSettings = options.subagentsSettings,
            currentDepth = options.currentDepth + 1,
            maxDepth = options.maxDepth,
            getApiKey = options.getApiKey,
            sessionsDir = options.sessionsDir,
            parentSessionId = options.parentSessionId,
            memoryProjectDir = options.memoryProjectDir,
            memoryGlobalDir = options.memoryGlobalDir,
            skillContentProvider = options.skillContentProvider,
        )
    }

    return tools
}

/**
 * Builds a sub-agent's system prompt:
 * - `replace`: env header + the definition's body (full control, no parent identity).
 * - `append`: parent prompt (verbatim, cacheable prefix) + sub-agent bridge + `<active_agent>` tag +
 *   env block + the definition body wrapped in `<agent_instructions>`.
 *
 * Our additions (superset over pi): the shared `<context>` block from the `task` tool and the
 * submit_result instruction are appended after the base prompt.
 */
internal fun buildSubAgentSystemPrompt(definition: AgentDefinition, options: SubAgentOptions, env: EnvInfo): String {
    val activeAgentTag = "<active_agent name=\"${definition.name}\"/>\n\n"
    val envBlock = buildString {
        append("# Environment\n")
        append("Working directory: ${options.cwd}\n")
        if (env.isGitRepo) {
            append("Git repository: yes\nBranch: ${env.branch}\n")
        } else {
            append("Not a git repository\n")
        }
        append("Platform: ${env.platform}")
    }

    val extras = options.promptExtras
    val extrasSuffix = buildString {
        if (extras.memoryBlock != null) {
            append("\n\n")
            append(extras.memoryBlock)
        }
        for (skill in extras.skillBlocks) {
            append("\n\n# Preloaded Skill: ${skill.name}\n")
            append(skill.content)
        }
    }

    val base = if (definition.promptMode == PromptMode.APPEND) {
        val identity = options.parentSystemPrompt?.takeIf { it.isNotBlank() } ?: GENERIC_BASE
        val bridge = SUB_AGENT_BRIDGE
        val customSection = if (definition.systemPrompt.isNotBlank()) {
            "\n\n<agent_instructions>\n${definition.systemPrompt}\n</agent_instructions>"
        } else {
            ""
        }
        identity + "\n\n" + bridge + "\n\n" + activeAgentTag + envBlock + customSection + extrasSuffix
    } else {
        val replaceHeader = "You are an agent47 coding agent sub-agent.\n" +
            "You have been invoked to handle a specific task autonomously.\n\n" +
            envBlock
        activeAgentTag + replaceHeader + "\n\n" + definition.systemPrompt + extrasSuffix
    }

    return buildString {
        append(base)
        if (options.context != null) {
            append("\n\n<context>\n")
            append(options.context)
            append("\n</context>")
        }
        if (definition.output != null) {
            append("\n\nIMPORTANT: When you complete your task, you MUST call submit_result with a result that conforms to the output schema.")
            append("\nOutput schema (JTD): ${definition.output}")
        } else {
            append("\n\nWhen you complete your task, call submit_result with a text summary of what you accomplished.")
        }
    }
}

/** Renders the parent conversation into a prompt prefix, prepended when inherit_context is set. */
internal fun buildParentContext(messages: List<Message>): String {
    val parts = buildList {
        for (message in messages) {
            when (message) {
                is UserMessage -> {
                    val text = extractText(message.content).trim()
                    if (text.isNotEmpty()) add("[User]: $text")
                }
                is AssistantMessage -> {
                    val text = extractText(message.content).trim()
                    if (text.isNotEmpty()) add("[Assistant]: $text")
                }
                else -> {} // Skip tool results — too verbose for context.
            }
        }
    }
    if (parts.isEmpty()) return ""

    return buildString {
        append("# Parent Conversation Context\n")
        append("The following is the conversation history from the parent session that spawned you.\n")
        append("Use this context to understand what has been discussed and decided so far.\n\n")
        append(parts.joinToString("\n\n"))
        append("\n\n---\n# Your Task (below)\n")
    }
}

private fun extractText(content: List<co.agentmode.agent47.ai.types.ContentBlock>): String =
    content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

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

private const val SUB_AGENT_BRIDGE = """<sub_agent_context>
You are operating as a sub-agent invoked to handle a specific task.
- Use the read tool instead of cat/head/tail
- Use the edit tool instead of sed/awk
- Use the write tool instead of echo/heredoc
- Use the find tool instead of bash find/ls for file search
- Use the grep tool instead of bash grep/rg for content search
- Make independent tool calls in parallel
- Use absolute file paths
- Do not use emojis
- Be concise but complete
</sub_agent_context>"""

private const val GENERIC_BASE = """# Role
You are a general-purpose coding agent for complex, multi-step tasks.
You have full access to read, write, edit files, and execute commands.
Do what has been asked; nothing more, nothing less."""
