package co.agentmode.agent47.gui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.agentmode.agent47.agent.core.AgentEndEvent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentStartEvent
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.agent.core.MessageStartEvent
import co.agentmode.agent47.agent.core.MessageUpdateEvent
import co.agentmode.agent47.agent.core.ToolExecutionEndEvent
import co.agentmode.agent47.agent.core.ToolExecutionStartEvent
import co.agentmode.agent47.agent.core.ToolExecutionUpdateEvent
import co.agentmode.agent47.agent.core.TurnEndEvent
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.Usage
import co.agentmode.agent47.ai.types.UsageCost
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.auth.AuthMethod
import co.agentmode.agent47.coding.core.auth.OAuthAuthorization
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandExpander
import co.agentmode.agent47.coding.core.compaction.CompactionResult
import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import co.agentmode.agent47.coding.core.compaction.applyCompaction
import co.agentmode.agent47.coding.core.compaction.estimateContextTokens
import co.agentmode.agent47.coding.core.compaction.findCutPoint
import co.agentmode.agent47.coding.core.compaction.shouldCompact
import co.agentmode.agent47.coding.core.instructions.InstructionFile
import co.agentmode.agent47.coding.core.instructions.InstructionSource
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.session.CompactionEntry
import co.agentmode.agent47.coding.core.session.ModelChangeEntry
import co.agentmode.agent47.coding.core.session.SessionHeader
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.ThinkingLevelChangeEntry
import kotlinx.serialization.json.Json
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.SelectItem
import co.agentmode.agent47.ui.core.state.StatusBarState
import co.agentmode.agent47.ui.core.state.TaskBarState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class SlashCommandSpec(
    val command: String,
    val description: String,
)

public data class SessionInfo(
    val path: Path,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val projectName: String,
)

public data class ProjectGroup(
    val projectName: String,
    val cwd: String,
    val sessions: List<SessionInfo>,
)

private enum class SettingsAction {
    Model,
    Provider,
    Thinking,
    Session,
    Commands,
    Help,
    Exit,
}

/**
 * Holds all mutable state and business logic for the GUI, mirroring the TUI's
 * App.kt closures. Drives the shared ui-core state classes and the agent client.
 */
internal class GuiAppController(
    val client: AgentClient,
    val initialUserMessage: UserMessage?,
    availableModels: List<Model>,
    sessionManager: SessionManager?,
    private val sessionsDir: Path?,
    val cwd: Path,
    initialThinkingLevel: AgentThinkingLevel,
    initialModel: Model?,
    fileCommands: List<SlashCommand>,
    private val getAllProviders: () -> List<ProviderInfo>,
    private val storeApiKey: (provider: String, apiKey: String) -> Unit,
    private val storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit,
    private val refreshModels: () -> List<Model>,
    private val authorizeOAuth: suspend (provider: String) -> OAuthAuthorization?,
    private val pollOAuthToken: suspend (provider: String) -> OAuthResult?,
    private val onSettingsChanged: (transform: (Settings) -> Settings) -> Unit,
    todoState: TodoState?,
    val instructionFiles: List<InstructionFile>,
    private val compactContext: (suspend (List<Message>, Model) -> CompactionResult?)?,
    private val compactionSettings: CompactionSettings,
) {
    val chatHistoryState = ChatHistoryState()
    val overlayHostState = OverlayHostState()
    val taskBarState = TaskBarState()

    var isStreaming by mutableStateOf(false)
        private set
    var liveActivityLabel by mutableStateOf("Thinking")
        private set
    var spinnerFrame by mutableIntStateOf(0)
        private set
    var currentModels by mutableStateOf(availableModels)
        private set

    private var scope: CoroutineScope? = null

    fun bindScope(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

    fun tickSpinner() {
        spinnerFrame++
    }
    var selectedModelIndex by mutableIntStateOf(
        initialModel?.let { model ->
            availableModels.indexOfFirst { it.id == model.id && it.provider == model.provider }
                .takeIf { it >= 0 }
        } ?: if (availableModels.isNotEmpty()) 0 else -1,
    )
        private set
    var thinkingLevel by mutableStateOf(initialThinkingLevel)
        private set
    var activeSessionManager by mutableStateOf(sessionManager)
        private set

    private var currentPromptJob: Job? = null
    private val toolArgumentsById = mutableMapOf<String, String>()
    private val usageHolder = UsageHolder()

    val currentModel: Model?
        get() = currentModels.getOrNull(selectedModelIndex) ?: currentModels.firstOrNull()

    val fileSlashCommands: List<SlashCommand> = fileCommands

    val builtinSlashCommands: List<SlashCommandSpec> = listOf(
        SlashCommandSpec("/help", "Show help and shortcuts"),
        SlashCommandSpec("/commands", "List available slash commands"),
        SlashCommandSpec("/new", "Start a new session"),
        SlashCommandSpec("/clear", "Clear the chat display"),
        SlashCommandSpec("/model", "Pick or set the active model"),
        SlashCommandSpec("/provider", "Connect a provider"),
        SlashCommandSpec("/session", "Load a saved session"),
        SlashCommandSpec("/compact", "Compact conversation context"),
        SlashCommandSpec("/memory", "Show loaded instruction files"),
        SlashCommandSpec("/settings", "Open interactive settings"),
        SlashCommandSpec("/exit", "Exit interactive mode"),
    )

    val slashCommands: List<SlashCommandSpec> = builtinSlashCommands +
        fileSlashCommands.map { SlashCommandSpec("/${it.name}", it.description) }

    val statusBarState: StatusBarState
        get() = StatusBarState(
            cwdName = cwd.fileName?.toString() ?: cwd.toString(),
            branch = detectBranchName(cwd),
            modelId = currentModel?.id,
            thinking = thinkingLevel != AgentThinkingLevel.OFF,
            inputTokens = usageHolder.inputTokens,
            outputTokens = usageHolder.outputTokens,
            totalTokens = usageHolder.totalTokens,
            cost = usageHolder.cost,
            contextTokens = usageHolder.contextTokens,
            contextWindow = currentModel?.contextWindow,
            busy = isStreaming,
            spinnerFrame = spinnerFrame,
        )

    init {
        if (todoState != null) {
            taskBarState.bind(todoState)
        }
        val agent = client.rawAgent()
        agent.setThinkingLevel(initialThinkingLevel)
        initialModel?.let(agent::setModel)
    }

    // --- Event collection ---

    suspend fun collectEvents() {
        client.events.collect { event ->
            handleAgentEvent(event)
            if (event is AgentEndEvent && compactionSettings.auto && compactionSettings.enabled && compactContext != null) {
                val activeModel = currentModel
                if (activeModel != null) {
                    val messages = client.rawAgent().state.messages
                    val estimate = estimateContextTokens(messages)
                    if (shouldCompact(estimate.tokens, activeModel.contextWindow, compactionSettings)) {
                        runCompaction()
                    }
                }
            }
        }
    }

    fun submitInitialMessage(scope: CoroutineScope) {
        val message = initialUserMessage ?: return
        chatHistoryState.appendMessage(message)
        activeSessionManager?.appendMessage(message)
        val job = scope.launch(Dispatchers.IO) {
            try {
                client.prompt(listOf(message))
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                appendSystemMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
            } finally {
                currentPromptJob = null
            }
        }
        currentPromptJob = job
    }

    // --- Submit handling ---

    fun handleSubmit(rawInput: String, scope: CoroutineScope) {
        when {
            rawInput.startsWith("/") -> handleSlashCommand(rawInput, scope)
            rawInput.startsWith("!") -> handleBashCommand(rawInput, scope)
            else -> {
                val message = UserMessage(
                    content = listOf(TextContent(text = rawInput)),
                    timestamp = System.currentTimeMillis(),
                )
                submitMessage(message, scope)
            }
        }
    }

    private fun handleSlashCommand(rawInput: String, scope: CoroutineScope) {
        val tokens = rawInput.trim().split(Regex("\\s+"))
        val command = tokens.firstOrNull().orEmpty()
        val args = tokens.drop(1)

        when (command) {
            "/help" -> {
                showCommandInput(rawInput)
                appendCommandResult(helpText())
            }
            "/commands" -> {
                showCommandInput(rawInput)
                openCommandsOverlay()
            }
            "/new" -> {
                showCommandInput(rawInput)
                startNewSession()
            }
            "/clear" -> {
                chatHistoryState.entries.clear()
            }
            "/exit" -> {
                client.rawAgent().abort()
                currentPromptJob?.cancel(CancellationException("Exiting"))
                currentPromptJob = null
                kotlin.system.exitProcess(0)
            }
            "/model" -> {
                showCommandInput(rawInput)
                if (args.isEmpty()) {
                    openModelOverlay()
                } else {
                    setModelById(args.joinToString(" "))
                }
            }
            "/provider" -> {
                showCommandInput(rawInput)
                openProviderOverlay()
            }
            "/compact" -> {
                showCommandInput(rawInput)
                runCompaction()
            }
            "/memory" -> {
                showCommandInput(rawInput)
                openMemoryOverlay()
            }
            "/settings" -> {
                showCommandInput(rawInput)
                openSettingsOverlay()
            }
            "/session" -> {
                showCommandInput(rawInput)
                if (args.isEmpty()) {
                    openSessionOverlay()
                } else {
                    appendCommandResult("Use /session without arguments to open the session picker.")
                }
            }
            else -> {
                val expanded = tryExpandFileCommand(rawInput)
                if (expanded != null) {
                    val message = UserMessage(
                        content = listOf(TextContent(text = expanded)),
                        timestamp = System.currentTimeMillis(),
                    )
                    submitMessage(message, scope)
                } else {
                    showCommandInput(rawInput)
                    appendCommandResult("Unknown command: $command")
                }
            }
        }
    }

    private fun handleBashCommand(rawInput: String, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        val command = rawInput.removePrefix("!").trim()
        if (command.isBlank()) {
            appendSystemMessage("No command provided after !")
            return
        }
        val start = UserMessage(
            content = listOf(TextContent(text = "!$command")),
            timestamp = System.currentTimeMillis(),
        )
        chatHistoryState.appendMessage(start)
        activeSessionManager?.appendMessage(start)

        val output = executeShell(command, cwd)
        val id = "local-${System.currentTimeMillis()}"
        chatHistoryState.appendToolExecution(
            ToolExecutionView(
                toolCallId = id,
                toolName = "bash",
                arguments = command,
                output = output.first,
                isError = output.second != 0,
                pending = false,
            ),
        )
    }

    private fun submitMessage(message: UserMessage, scope: CoroutineScope) {
        chatHistoryState.appendMessage(message)
        activeSessionManager?.appendMessage(message)

        if (isStreaming) {
            runCatching {
                client.followUp(message)
                appendSystemMessage("Queued follow-up while the current response is still running.")
            }.onFailure { error ->
                appendSystemMessage("Failed to queue follow-up: ${error.message ?: error::class.simpleName}")
            }
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                client.prompt(listOf(message))
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                appendSystemMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
            } finally {
                currentPromptJob = null
            }
        }
        currentPromptJob = job
    }

    // --- Agent event handling ---

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentStartEvent -> {
                isStreaming = true
                liveActivityLabel = "Thinking"
            }
            is AgentEndEvent -> {
                isStreaming = false
                currentPromptJob = null
                liveActivityLabel = ""
            }
            is MessageStartEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.startAssistantMessage(message)
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.appendMessage(message)
                }
            }
            is MessageUpdateEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.updateAssistantMessage(message)
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.updateMessage(message)
                }
            }
            is MessageEndEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.endAssistantMessage(message)
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.updateMessage(message)
                }
                if (message !is UserMessage) {
                    activeSessionManager?.appendMessage(message)
                }
            }
            is ToolExecutionStartEvent -> {
                toolArgumentsById[event.toolCallId] = event.arguments.toString()
                liveActivityLabel = "Running ${event.toolName}"
                chatHistoryState.updateToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = "",
                        pending = true,
                        collapsed = defaultToolCollapsed(event.toolName),
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            }
            is ToolExecutionUpdateEvent -> {
                val output = event.partialResult.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                chatHistoryState.updateToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = output,
                        details = ToolDetails.from(event.toolName, event.partialResult.details),
                        pending = true,
                        collapsed = defaultToolCollapsed(event.toolName),
                    ),
                )
            }
            is ToolExecutionEndEvent -> {
                val output = event.result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                liveActivityLabel = "Thinking"
                chatHistoryState.updateToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = output,
                        isError = event.isError,
                        pending = false,
                        details = ToolDetails.from(event.toolName, event.result.details),
                        collapsed = defaultToolCollapsed(event.toolName),
                    ),
                )
                toolArgumentsById.remove(event.toolCallId)
            }
            is TurnEndEvent -> {
                val assistant = event.message as? AssistantMessage
                if (assistant != null) {
                    usageHolder.inputTokens = assistant.usage.input
                    usageHolder.outputTokens = assistant.usage.output
                    usageHolder.totalTokens = assistant.usage.totalTokens
                    usageHolder.cost = assistant.usage.cost.total
                    usageHolder.contextTokens = assistant.usage.totalTokens
                }
            }
            else -> {}
        }
    }

    // --- Helper functions ---

    fun appendSystemMessage(text: String) {
        val agent = client.rawAgent()
        val assistant = AssistantMessage(
            content = listOf(TextContent(text = text)),
            api = currentModel?.api ?: agent.state.model.api,
            provider = currentModel?.provider ?: agent.state.model.provider,
            model = currentModel?.id ?: agent.state.model.id,
            usage = emptyUsage(),
            stopReason = StopReason.STOP,
            timestamp = System.currentTimeMillis(),
        )
        chatHistoryState.appendMessage(assistant)
    }

    private fun showCommandInput(text: String) {
        chatHistoryState.appendMessage(
            UserMessage(
                content = listOf(TextContent(text = text)),
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    fun appendCommandResult(text: String) {
        chatHistoryState.appendMessage(
            CustomMessage(
                customType = "command_result",
                content = listOf(TextContent(text = text)),
                display = true,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    fun startNewSession() {
        currentPromptJob?.cancel(CancellationException("Starting new session"))
        currentPromptJob = null
        client.rawAgent().clearMessages()
        chatHistoryState.entries.clear()
        toolArgumentsById.clear()
        if (sessionsDir != null) {
            val newPath = sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl")
            val newManager = runCatching { SessionManager(newPath) }.getOrNull()
            activeSessionManager = newManager
            appendCommandResult("Started new session")
        } else {
            activeSessionManager = null
            appendCommandResult("Started new session (no persistence)")
        }
    }

    fun applyModel(model: Model, recordSessionEntry: Boolean = true, announce: Boolean = true) {
        client.rawAgent().setModel(model)
        selectedModelIndex = currentModels
            .indexOfFirst { it.id == model.id && it.provider == model.provider }
            .takeIf { it >= 0 }
            ?: selectedModelIndex
        if (recordSessionEntry) {
            activeSessionManager?.append(
                ModelChangeEntry(
                    id = randomEntryId(),
                    parentId = activeSessionManager?.getLeafId(),
                    timestamp = Instant.now().toString(),
                    provider = model.provider.value,
                    modelId = model.id,
                ),
            )
        }
        if (announce) {
            appendCommandResult("Model set to ${model.provider.value}/${model.id}")
        }
        onSettingsChanged { it.copy(defaultModel = model.id, defaultProvider = model.provider.value) }
    }

    fun setThinkingLevel(level: AgentThinkingLevel, recordSessionEntry: Boolean = true, announce: Boolean = true) {
        thinkingLevel = level
        client.rawAgent().setThinkingLevel(level)
        if (recordSessionEntry) {
            activeSessionManager?.append(
                ThinkingLevelChangeEntry(
                    id = randomEntryId(),
                    parentId = activeSessionManager?.getLeafId(),
                    timestamp = Instant.now().toString(),
                    thinkingLevel = level.name.lowercase(),
                ),
            )
        }
        if (announce) {
            appendCommandResult("Thinking set to ${level.name.lowercase()}")
        }
        onSettingsChanged { it.copy(defaultThinkingLevel = level.name.lowercase()) }
    }

    fun cycleModel(direction: Int) {
        if (currentModels.isEmpty()) {
            appendSystemMessage("No models available")
            return
        }
        val size = currentModels.size
        val current = selectedModelIndex.coerceIn(0, size - 1)
        val newIndex = ((current + direction) % size + size) % size
        selectedModelIndex = newIndex
        applyModel(currentModels[newIndex])
    }

    fun interruptAgent() {
        if (isStreaming) {
            client.rawAgent().abort()
            appendSystemMessage("Interrupted current response.")
        }
    }

    private fun setModelById(modelId: String) {
        val match = currentModels.firstOrNull { model ->
            model.id.equals(modelId, ignoreCase = true) ||
                "${model.provider.value}/${model.id}".equals(modelId, ignoreCase = true)
        }
        if (match == null) {
            appendCommandResult("Model not found: $modelId")
        } else {
            applyModel(match)
        }
    }

    private fun tryExpandFileCommand(text: String): String? {
        return SlashCommandExpander.expand(text, fileSlashCommands)
    }

    // --- Overlay openers ---

    fun openModelOverlay() {
        if (currentModels.isEmpty()) {
            appendCommandResult("No models available — use /provider to connect one")
            return
        }
        val options = currentModels.map { model ->
            SelectItem(label = "${model.provider.value}/${model.id}", value = model)
        }
        val current = currentModels.getOrNull(selectedModelIndex)
        val selIndex = current?.let { model ->
            currentModels.indexOfFirst { it.id == model.id && it.provider == model.provider }
        } ?: 0
        overlayHostState.push(
            title = "Model",
            items = options,
            selectedIndex = selIndex,
            onSubmit = { model -> applyModel(model) },
        )
    }

    fun openProviderOverlay() {
        val providers = getAllProviders()
        if (providers.isEmpty()) {
            appendSystemMessage("No providers found in model catalog")
            return
        }
        val options = providers.map { info ->
            val status = if (info.connected) "\u2713" else "\u25CB"
            val modelLabel = if (info.modelCount == 1) "1 model" else "${info.modelCount} models"
            SelectItem(
                label = "$status ${info.name} ($modelLabel)",
                value = info,
            )
        }
        overlayHostState.push(
            title = "Connect Provider",
            items = options,
            selectedIndex = 0,
            onSubmit = { info ->
                if (info.connected) {
                    appendSystemMessage("${info.name} is already connected")
                } else {
                    startProviderAuth(info)
                }
            },
        )
    }

    fun openThinkingOverlay() {
        val options = AgentThinkingLevel.entries.map {
            SelectItem(label = it.name.lowercase(), value = it)
        }
        val selIndex = AgentThinkingLevel.entries.indexOf(thinkingLevel).coerceAtLeast(0)
        overlayHostState.push(
            title = "Thinking",
            items = options,
            selectedIndex = selIndex,
            onSubmit = { level -> setThinkingLevel(level) },
        )
    }

    fun openCommandsOverlay() {
        val options = slashCommands.map { spec ->
            SelectItem(
                label = "${spec.command} - ${spec.description}",
                value = spec.command,
            )
        }
        overlayHostState.push(
            title = "Commands",
            items = options,
            selectedIndex = 0,
            onSubmit = { _ -> },
        )
    }

    fun openSkillsOverlay() {
        val skills = fileSlashCommands
        if (skills.isEmpty()) {
            appendCommandResult("No skills available")
            return
        }
        val options = skills.map { cmd ->
            SelectItem(label = "/${cmd.name} \u2014 ${cmd.description}", value = cmd)
        }
        overlayHostState.push(
            title = "Skills",
            items = options,
            selectedIndex = 0,
            onSubmit = { cmd ->
                val expanded = SlashCommandExpander.expand("/${cmd.name}", fileSlashCommands)
                if (expanded != null) {
                    val s = scope
                    if (s != null) {
                        val userMsg = UserMessage(
                            content = listOf(TextContent(text = expanded)),
                            timestamp = System.currentTimeMillis(),
                        )
                        submitMessage(userMsg, s)
                    }
                }
            },
        )
    }

    fun openSessionOverlay() {
        val dir = sessionsDir
        if (dir == null || !Files.isDirectory(dir)) {
            appendCommandResult("Session picker is unavailable: no session directory configured")
            return
        }
        val sessions = runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
                    .limit(200)
                    .toList()
            }
        }.getOrElse {
            appendCommandResult("Failed to list sessions: ${it.message ?: it::class.simpleName}")
            return
        }
        if (sessions.isEmpty()) {
            appendCommandResult("No saved sessions found")
            return
        }
        val options = sessions.map { path ->
            SelectItem(label = path.fileName.toString(), value = path)
        }
        overlayHostState.push(
            title = "Sessions",
            items = options,
            selectedIndex = 0,
            onSubmit = { path ->
                loadSession(
                    path = path,
                    sessionsDir = sessionsDir,
                    availableModels = currentModels,
                )
            },
        )
    }

    private val sessionJson = Json { ignoreUnknownKeys = true }

    fun loadSessionGroups(): List<ProjectGroup> {
        val dir = sessionsDir ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()

        val sessions = runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
                    .limit(200)
                    .toList()
            }
        }.getOrElse { return emptyList() }

        val infos = sessions.mapNotNull { path ->
            runCatching {
                val firstLine = Files.newBufferedReader(path, StandardCharsets.UTF_8).use { it.readLine() }
                    ?: return@runCatching null
                val header = sessionJson.decodeFromString<SessionHeader>(firstLine)
                SessionInfo(
                    path = path,
                    id = header.id,
                    timestamp = header.timestamp,
                    cwd = header.cwd,
                    projectName = Path.of(header.cwd).fileName?.toString() ?: header.cwd,
                )
            }.getOrNull()
        }

        return infos
            .groupBy { it.cwd }
            .map { (groupCwd, sessionList) ->
                ProjectGroup(
                    projectName = Path.of(groupCwd).fileName?.toString() ?: groupCwd,
                    cwd = groupCwd,
                    sessions = sessionList.sortedByDescending { it.timestamp },
                )
            }
            .sortedByDescending { group -> group.sessions.first().timestamp }
    }

    fun loadSessionByPath(path: Path) {
        loadSession(path = path, sessionsDir = sessionsDir, availableModels = currentModels)
    }

    fun openSettingsOverlay() {
        val options = listOf(
            SelectItem("Model - choose model", SettingsAction.Model),
            SelectItem("Provider - connect provider", SettingsAction.Provider),
            SelectItem("Thinking - choose level", SettingsAction.Thinking),
            SelectItem("Session - load session", SettingsAction.Session),
            SelectItem("Commands - list slash cmds", SettingsAction.Commands),
            SelectItem("Help - show shortcuts", SettingsAction.Help),
            SelectItem("Exit", SettingsAction.Exit),
        )
        overlayHostState.push(
            title = "Settings",
            items = options,
            selectedIndex = 0,
            keepOpenOnSubmit = true,
            onSubmit = { action ->
                when (action) {
                    SettingsAction.Model -> openModelOverlay()
                    SettingsAction.Provider -> openProviderOverlay()
                    SettingsAction.Thinking -> openThinkingOverlay()
                    SettingsAction.Session -> openSessionOverlay()
                    SettingsAction.Commands -> openCommandsOverlay()
                    SettingsAction.Help -> appendCommandResult(helpText())
                    SettingsAction.Exit -> {
                        client.rawAgent().abort()
                        currentPromptJob?.cancel(CancellationException("Exiting"))
                        currentPromptJob = null
                        kotlin.system.exitProcess(0)
                    }
                }
            },
        )
    }

    fun openMemoryOverlay() {
        if (instructionFiles.isEmpty()) {
            appendSystemMessage("No instruction files loaded")
            return
        }
        val options = instructionFiles.map { file ->
            val sourceLabel = when (file.source) {
                InstructionSource.PROJECT -> "Project"
                InstructionSource.GLOBAL -> "Global"
                InstructionSource.CLAUDE_CODE -> "Claude Code"
                InstructionSource.SETTINGS -> "Settings"
            }
            val relativePath = runCatching {
                cwd.relativize(file.path).toString()
            }.getOrElse { file.path.toString() }
            val lineCount = file.content.count { it == '\n' } + 1
            SelectItem(
                label = "$sourceLabel \u00b7 ${file.path.fileName}    $relativePath  ${lineCount}L",
                value = file,
            )
        }
        overlayHostState.push(
            title = "Instructions",
            items = options,
            keepOpenOnSubmit = true,
            onSubmit = { file ->
                overlayHostState.pushScrollableText(
                    title = "${file.path.fileName} (${file.source.name.lowercase()})",
                    lines = file.content.split("\n"),
                )
            },
        )
    }

    fun openSubAgentResultOverlay() {
        val lastTaskExecution = chatHistoryState.entries.asReversed()
            .firstOrNull { entry ->
                val details = entry.toolExecution?.details
                details is ToolDetails.SubAgent && details.results.isNotEmpty()
            }?.toolExecution

        val subAgent = lastTaskExecution?.details as? ToolDetails.SubAgent
        if (subAgent == null || subAgent.results.isEmpty()) {
            appendSystemMessage("No sub-agent results available.")
            return
        }

        val results = subAgent.results
        if (results.size == 1) {
            showSubAgentResult(results.first())
            return
        }

        val options = results.map { result ->
            val status = when {
                result.aborted -> "ABORTED"
                result.error != null -> "ERROR"
                result.exitCode == 0 -> "OK"
                else -> "FAILED"
            }
            SelectItem(
                label = "[$status] ${result.agent}/${result.id} - ${result.description ?: result.task.take(40)}",
                value = result,
            )
        }
        overlayHostState.push(
            title = "Sub-Agent Results",
            items = options,
            selectedIndex = 0,
            onSubmit = { result -> showSubAgentResult(result) },
        )
    }

    private fun showSubAgentResult(result: SubAgentResult) {
        val lines = buildList {
            add("Agent: ${result.agent}  ID: ${result.id}")
            add("Task: ${result.description ?: result.task.take(80)}")
            add("Status: ${if (result.exitCode == 0) "SUCCESS" else "FAILED"}  Duration: ${result.durationMs}ms  Tokens: ${result.tokens}")
            if (result.error != null) {
                add("Error: ${result.error}")
            }
            add("")

            val sessionPath = result.sessionFile?.let { java.nio.file.Path.of(it) }
            if (sessionPath != null && java.nio.file.Files.exists(sessionPath)) {
                add("--- Session Transcript ---")
                add("")
                val sessionMgr = runCatching { SessionManager(sessionPath) }.getOrNull()
                if (sessionMgr != null) {
                    val context = sessionMgr.buildContext()
                    context.messages.forEach { message ->
                        when (message) {
                            is AssistantMessage -> {
                                add("[assistant]")
                                message.content.filterIsInstance<TextContent>().forEach { block ->
                                    block.text.lines().forEach { line -> add("  $line") }
                                }
                                add("")
                            }
                            is UserMessage -> {
                                add("[user]")
                                message.content.filterIsInstance<TextContent>().forEach { block ->
                                    block.text.lines().forEach { line -> add("  $line") }
                                }
                                add("")
                            }
                            is ToolResultMessage -> {
                                val output = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                                val preview = output.lines().take(5).joinToString("\n")
                                add("[tool:${message.toolName}] ${if (message.isError) "ERROR" else "OK"}")
                                preview.lines().forEach { line -> add("  $line") }
                                if (output.lines().size > 5) add("  ... ${output.lines().size - 5} more lines")
                                add("")
                            }
                            else -> {}
                        }
                    }
                } else {
                    add("(failed to load session file)")
                }
            } else {
                add("--- Output ---")
                add("")
                result.output.lines().forEach { line -> add(line) }
            }
        }
        overlayHostState.pushScrollableText(
            title = "Sub-Agent: ${result.agent}/${result.id}",
            lines = lines,
        )
    }

    // --- Provider auth ---

    private fun startProviderAuth(info: ProviderInfo) {
        val methods = info.authMethods
        if (methods.size == 1) {
            startAuthMethod(info, methods.first())
        } else {
            val methodOptions = methods.map { method ->
                SelectItem(label = method.label, value = method)
            }
            overlayHostState.push(
                title = "${info.name} \u2014 Auth Method",
                items = methodOptions,
                selectedIndex = 0,
                onSubmit = { method -> startAuthMethod(info, method) },
            )
        }
    }

    private fun startAuthMethod(info: ProviderInfo, method: AuthMethod) {
        when (method) {
            is AuthMethod.ApiKey -> {
                overlayHostState.pushPrompt(
                    title = "${info.name} \u2014 API Key",
                    placeholder = "Paste your API key",
                    masked = true,
                    onSubmit = { apiKey ->
                        if (apiKey.isBlank()) {
                            appendSystemMessage("No API key provided")
                        } else {
                            storeApiKey(info.id, apiKey)
                            onProviderConnected(info)
                        }
                    },
                )
            }
            is AuthMethod.OAuth -> {
                overlayHostState.pushInfo(
                    title = "${info.name} \u2014 Authorizing",
                    lines = listOf("Starting authorization...", "", "Please wait..."),
                )
                val activeScope = scope
                if (activeScope != null) {
                    runOAuthFlow(info, activeScope)
                } else {
                    overlayHostState.dismissTopSilent()
                    appendSystemMessage("OAuth unavailable: no active scope")
                }
            }
        }
    }

    fun runOAuthFlow(info: ProviderInfo, scope: CoroutineScope) {
        scope.launch {
            val authorization = runCatching { authorizeOAuth(info.id) }.getOrNull()
            if (authorization == null) {
                overlayHostState.dismissTopSilent()
                appendSystemMessage("Failed to start OAuth for ${info.name}")
                return@launch
            }
            overlayHostState.dismissTopSilent()
            var cancelled = false
            overlayHostState.pushInfo(
                title = "${info.name} \u2014 Waiting for Authorization",
                lines = listOf(
                    "Open this URL in your browser:",
                    "",
                    "  ${authorization.verificationUrl}",
                    "",
                    "Enter code: ${authorization.userCode}",
                    "",
                    "Waiting for authorization...",
                ),
                onClose = { cancelled = true },
            )
            runCatching { ProcessBuilder("open", authorization.verificationUrl).start() }
            val result = runCatching { pollOAuthToken(info.id) }.getOrNull()
            overlayHostState.dismissTopSilent()
            if (cancelled) return@launch
            when (result) {
                is OAuthResult.Success -> {
                    storeOAuthCredential(info.id, result.credential)
                    onProviderConnected(info)
                }
                is OAuthResult.Failed -> {
                    appendSystemMessage("Authorization failed: ${result.message}")
                }
                null -> {
                    appendSystemMessage("OAuth flow was cancelled or unavailable")
                }
            }
        }
    }

    private fun onProviderConnected(info: ProviderInfo) {
        currentModels = refreshModels()
        val newIndex = currentModels.indexOfFirst {
            it.provider.value == info.id
        }.takeIf { it >= 0 }
        if (newIndex != null && (selectedModelIndex < 0 || currentModel == null)) {
            applyModel(currentModels[newIndex])
        }
        appendCommandResult("Connected ${info.name} \u2014 ${currentModels.count { it.provider.value == info.id }} models available")
    }

    // --- Compaction ---

    fun runCompaction() {
        val model = currentModel
        if (compactContext == null || model == null) {
            appendCommandResult("Compaction unavailable")
            return
        }
        liveActivityLabel = "Compacting"
        isStreaming = true
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val messages = client.rawAgent().state.messages
                val estimate = estimateContextTokens(messages)
                val result = compactContext.invoke(messages, model)
                if (result != null) {
                    val cutPoint = findCutPoint(messages, compactionSettings.keepRecentTokens)
                    val compacted = applyCompaction(
                        messages = messages,
                        summary = result.summary,
                        cutPointIndex = cutPoint.firstKeptEntryIndex,
                        tokensBefore = estimate.tokens,
                    )
                    client.rawAgent().replaceMessages(compacted)
                    activeSessionManager?.append(
                        CompactionEntry(
                            id = randomEntryId(),
                            parentId = activeSessionManager?.getLeafId(),
                            timestamp = Instant.now().toString(),
                            summary = result.summary,
                            firstKeptEntryId = result.firstKeptEntryId,
                            tokensBefore = estimate.tokens,
                        ),
                    )
                    val after = estimateContextTokens(compacted)
                    appendCommandResult("Context compacted (${estimate.tokens} \u2192 ~${after.tokens} tokens)")
                } else {
                    appendCommandResult("Compaction failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendCommandResult("Compaction error: ${e.message ?: e::class.simpleName}")
            } finally {
                isStreaming = false
            }
        }
    }

    // --- Session loading ---

    private fun loadSession(path: Path, sessionsDir: Path?, availableModels: List<Model>) {
        val resolvedPath = when {
            path.isAbsolute -> path
            sessionsDir != null -> sessionsDir.resolve(path)
            else -> path
        }
        if (!Files.exists(resolvedPath)) {
            appendSystemMessage("Session not found: ${resolvedPath.toAbsolutePath()}")
            return
        }
        val loadedManager = runCatching { SessionManager(resolvedPath) }.getOrElse {
            appendSystemMessage("Failed to open session: ${it.message ?: it::class.simpleName}")
            return
        }
        val context = loadedManager.buildContext()

        activeSessionManager = loadedManager
        client.rawAgent().replaceMessages(context.messages)
        chatHistoryState.entries.clear()
        context.messages.forEach { chatHistoryState.appendMessage(it) }

        val restoredModel = context.model?.let { (provider, id) ->
            availableModels.firstOrNull { it.provider.value == provider && it.id == id }
        }
        if (restoredModel != null) {
            applyModel(restoredModel, recordSessionEntry = false, announce = false)
        }

        parseThinkingLevelOrNull(context.thinkingLevel)?.let {
            setThinkingLevel(it, recordSessionEntry = false, announce = false)
        }

        appendSystemMessage("Loaded session ${resolvedPath.fileName}")
    }

    // --- Help text ---

    private fun helpText(): String = buildString {
        appendLine("Commands:")
        slashCommands.forEach { spec ->
            appendLine("  ${spec.command.padEnd(20)} ${spec.description}")
        }
        appendLine("")
        appendLine("Shortcuts:")
        appendLine("  Enter          Submit")
        appendLine("  Shift+Enter    Insert newline")
        appendLine("  Ctrl+C         Interrupt current run, then press twice to exit")
        appendLine("  Ctrl+L         Clear visible chat")
        appendLine("  Ctrl+T         Toggle thinking")
        appendLine("  Ctrl+P/Ctrl+N  Cycle models")
        appendLine("  Ctrl+O         Open settings overlay")
        appendLine("  Ctrl+G         Toggle latest thinking block")
        appendLine("  Ctrl+E         Toggle latest tool details")
        appendLine("  Ctrl+R         View sub-agent results")
        appendLine("  PgUp/PgDn      Scroll chat history")
        appendLine("  Ctrl+U/Ctrl+D  Scroll chat history")
        appendLine("  Esc            Interrupt agent or close modal")
        append("Prefix a line with ! to run local shell commands.")
    }
}

// --- Utilities ---

private fun defaultToolCollapsed(toolName: String): Boolean = when (toolName.lowercase()) {
    "read", "grep", "find", "ls" -> true
    else -> false
}

private fun emptyUsage(): Usage = Usage(
    input = 0,
    output = 0,
    cacheRead = 0,
    cacheWrite = 0,
    totalTokens = 0,
    cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
)

private fun randomEntryId(): String = UUID.randomUUID().toString().substring(0, 8)

private fun detectBranchName(path: Path): String? {
    return runCatching {
        val process = ProcessBuilder("git", "-C", path.toString(), "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true)
            .start()
        val ok = process.waitFor(1, TimeUnit.SECONDS)
        if (!ok || process.exitValue() != 0) return null
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim().ifBlank { null }
    }.getOrNull()
}

private fun executeShell(command: String, cwd: Path): Pair<String, Int> {
    return runCatching {
        val process = ProcessBuilder("/bin/sh", "-lc", command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "Command timed out after 60s" to 124
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        output.ifBlank { "(no output)" } to process.exitValue()
    }.getOrElse { error ->
        "Command failed: ${error.message ?: error::class.simpleName}" to 1
    }
}

private fun parseThinkingLevelOrNull(value: String?): AgentThinkingLevel? {
    if (value == null) return null
    return when (value.lowercase()) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> null
    }
}

/**
 * Mutable holder for usage stats that persists across recompositions.
 */
internal class UsageHolder {
    var inputTokens: Int? by mutableStateOf(null)
    var outputTokens: Int? by mutableStateOf(null)
    var totalTokens: Int? by mutableStateOf(null)
    var cost: Double? by mutableStateOf(null)
    var contextTokens: Int? by mutableStateOf(null)
}
