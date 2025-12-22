package co.agentmode.agent47.tui

import androidx.compose.runtime.*
import co.agentmode.agent47.agent.core.*
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.auth.AuthMethod
import co.agentmode.agent47.coding.core.auth.OAuthAuthorization
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandExpander
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.coding.core.session.ModelChangeEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.ThinkingLevelChangeEntry
import co.agentmode.agent47.tui.components.*
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.input.toKeyboardEvent
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownTheme
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.theme.AVAILABLE_THEMES
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import androidx.compose.runtime.CompositionLocalProvider
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicMain
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import co.agentmode.agent47.coding.core.tools.TodoState
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

private data class SlashCommandSpec(
    val command: String,
    val description: String,
)

private enum class SettingsAction {
    Model,
    Provider,
    Thinking,
    Theme,
    Usage,
    Session,
    Commands,
    Help,
    Exit,
}

/**
 * Top-level Mosaic composable for the interactive TUI.
 *
 * Owns the full application layout: chat history, activity spinner, editor,
 * status bar, and overlay host. Collects agent events via [LaunchedEffect],
 * dispatches keyboard input through the legacy [Editor] and overlay system,
 * and manages all mutable state through Compose snapshot-state primitives.
 *
 * @param client The agent client for sending prompts and receiving events
 * @param initialUserMessage Optional message to submit immediately on launch
 * @param availableModels Models available for selection
 * @param sessionManager Session persistence manager
 * @param sessionsDir Directory containing saved sessions
 * @param cwd Current working directory
 * @param initialThinkingLevel Starting thinking level
 * @param initialModel Starting model
 * @param theme Mosaic theme to apply
 * @param fileCommands File-based slash commands
 * @param getAllProviders Returns all providers with their connection status
 * @param storeApiKey Stores an API key for a provider and refreshes model availability
 * @param storeOAuthCredential Stores an OAuth credential for a provider and refreshes model availability
 * @param refreshModels Returns the refreshed list of available models
 * @param authorizeOAuth Initiates an OAuth device code flow for a provider, returns null if unsupported
 * @param pollOAuthToken Polls for the OAuth token after authorization is initiated
 */
@Composable
internal fun Agent47App(
    client: AgentClient,
    initialUserMessage: UserMessage? = null,
    availableModels: List<Model> = emptyList(),
    sessionManager: SessionManager? = null,
    sessionsDir: Path? = null,
    cwd: Path = Path.of(System.getProperty("user.dir")),
    initialThinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    initialModel: Model? = null,
    theme: ThemeConfig = ThemeConfig.DEFAULT,
    fileCommands: List<SlashCommand> = emptyList(),
    getAllProviders: () -> List<ProviderInfo> = { emptyList() },
    storeApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit = { _, _ -> },
    refreshModels: () -> List<Model> = { availableModels },
    authorizeOAuth: suspend (provider: String) -> OAuthAuthorization? = { null },
    pollOAuthToken: suspend (provider: String) -> OAuthResult? = { null },
    onSettingsChanged: (transform: (Settings) -> Settings) -> Unit = {},
    initialShowUsageFooter: Boolean = true,
    todoState: TodoState? = null,
) {
    var activeTheme by remember { mutableStateOf(theme) }
    CompositionLocalProvider(LocalThemeConfig provides activeTheme) {
        Agent47AppContent(
            client = client,
            initialUserMessage = initialUserMessage,
            availableModels = availableModels,
            sessionManager = sessionManager,
            sessionsDir = sessionsDir,
            cwd = cwd,
            initialThinkingLevel = initialThinkingLevel,
            initialModel = initialModel,
            fileCommands = fileCommands,
            activeTheme = activeTheme,
            setActiveTheme = { activeTheme = it },
            getAllProviders = getAllProviders,
            storeApiKey = storeApiKey,
            storeOAuthCredential = storeOAuthCredential,
            refreshModels = refreshModels,
            authorizeOAuth = authorizeOAuth,
            pollOAuthToken = pollOAuthToken,
            onSettingsChanged = onSettingsChanged,
            initialShowUsageFooter = initialShowUsageFooter,
            todoState = todoState,
        )
    }
}

@Composable
private fun Agent47AppContent(
    client: AgentClient,
    initialUserMessage: UserMessage?,
    availableModels: List<Model>,
    sessionManager: SessionManager?,
    sessionsDir: Path?,
    cwd: Path,
    initialThinkingLevel: AgentThinkingLevel,
    initialModel: Model?,
    fileCommands: List<SlashCommand>,
    activeTheme: ThemeConfig,
    setActiveTheme: (ThemeConfig) -> Unit,
    getAllProviders: () -> List<ProviderInfo>,
    storeApiKey: (provider: String, apiKey: String) -> Unit,
    storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit,
    refreshModels: () -> List<Model>,
    authorizeOAuth: suspend (provider: String) -> OAuthAuthorization?,
    pollOAuthToken: suspend (provider: String) -> OAuthResult?,
    onSettingsChanged: (transform: (Settings) -> Settings) -> Unit,
    initialShowUsageFooter: Boolean,
    todoState: TodoState? = null,
) {
    val mosaicTheme = LocalThemeConfig.current
    val terminalState = LocalTerminalState.current
    val width = terminalState.size.columns.coerceAtLeast(1)
    val height = terminalState.size.rows.coerceAtLeast(4)
    val promptScope = rememberCoroutineScope()

    // Mutable model list that updates when providers are connected
    var currentModels by remember { mutableStateOf(availableModels) }

    // --- Slash command definitions ---

    val fileSlashCommands = remember { fileCommands }
    val builtinSlashCommands = remember {
        listOf(
            SlashCommandSpec("/help", "Show help and shortcuts"),
            SlashCommandSpec("/commands", "List available slash commands"),
            SlashCommandSpec("/clear", "Clear visible chat history"),
            SlashCommandSpec("/model", "Pick or set the active model"),
            SlashCommandSpec("/provider", "Connect a provider"),
            SlashCommandSpec("/thinking", "Pick or set thinking level"),
            SlashCommandSpec("/theme", "Pick a color theme"),
            SlashCommandSpec("/usage", "Toggle usage footer on messages"),
            SlashCommandSpec("/session", "Load a saved session"),
            SlashCommandSpec("/settings", "Open interactive settings"),
            SlashCommandSpec("/exit", "Exit interactive mode"),
        )
    }
    val slashCommands = remember(builtinSlashCommands, fileSlashCommands) {
        builtinSlashCommands + fileSlashCommands.map {
            SlashCommandSpec("/${it.name}", it.description)
        }
    }

    // --- State ---

    val chatHistoryState = remember { ChatHistoryState() }
    val overlayHostState = remember { OverlayHostState() }
    val editor = remember {
        Editor(
            slashCommands = slashCommands.map { it.command },
            slashCommandDetails = slashCommands.associate { spec ->
                spec.command.removePrefix("/") to spec.description
            },
            fileCompletionRoot = cwd,
        )
    }
    val markdownRenderer = remember(mosaicTheme) {
        MarkdownRenderer(MarkdownTheme.fromTheme(mosaicTheme))
    }
    val diffRenderer = remember(mosaicTheme) { DiffRenderer(mosaicTheme) }

    var showUsageFooter by remember { mutableStateOf(initialShowUsageFooter) }
    var running by remember { mutableStateOf(true) }
    var isStreaming by remember { mutableStateOf(false) }
    var ctrlCArmed by remember { mutableStateOf(false) }
    var spinnerFrame by remember { mutableIntStateOf(0) }
    var editorVersion by remember { mutableIntStateOf(0) }
    var liveActivityLabel by remember { mutableStateOf("Thinking") }
    val taskBarState = remember { TaskBarState() }
    var thinkingLevel by remember { mutableStateOf(initialThinkingLevel) }
    var selectedModelIndex by remember {
        mutableIntStateOf(
            initialModel?.let { model ->
                currentModels.indexOfFirst { it.id == model.id && it.provider == model.provider }
                    .takeIf { it >= 0 }
            } ?: if (currentModels.isNotEmpty()) 0 else -1,
        )
    }
    var activeSessionManager by remember { mutableStateOf(sessionManager) }
    var currentPromptJob by remember { mutableStateOf<Job?>(null) }
    val toolArgumentsById = remember { mutableMapOf<String, String>() }
    var chatVersion by remember { mutableIntStateOf(0) }

    // --- Derived values ---

    val currentModel = currentModels.getOrNull(selectedModelIndex) ?: initialModel

    val statusBarState = MosaicStatusBarState(
        cwdName = cwd.fileName?.toString() ?: cwd.toString(),
        branch = remember { detectBranchName(cwd) },
        modelId = currentModel?.id,
        thinking = thinkingLevel != AgentThinkingLevel.OFF,
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        cost = null,
        contextTokens = null,
        contextWindow = currentModel?.contextWindow,
        busy = isStreaming,
        spinnerFrame = spinnerFrame,
    )

    // Status bar state is rebuilt each frame, usage stats accumulate through
    // the mutable holder, so they persist across recompositions.
    val usageHolder = remember { UsageHolder() }
    val statusBarWithUsage = statusBarState.copy(
        inputTokens = usageHolder.inputTokens,
        outputTokens = usageHolder.outputTokens,
        totalTokens = usageHolder.totalTokens,
        cost = usageHolder.cost,
        contextTokens = usageHolder.contextTokens,
    )

    // --- Layout calculations ---

    val statusHeight = 1
    val baseInputHeight = min(8, max(1, editor.text().lines().size))
    val popupItemCount = editor.slashCommandPopupItemCount()
    val popupRowCount = min(8, popupItemCount)
    val popupHeight = if (popupRowCount > 0) popupRowCount + (if (popupItemCount > 8) 1 else 0) else 0
    val taskBarHeight = if (taskBarState.visible) taskBarState.lineCount else 0
    val activityHeight = if (isStreaming && !taskBarState.visible) 1 else 0
    val marginHeight = 1
    val borderHeight = 2
    val historyHeight = max(1, height - statusHeight - borderHeight - popupHeight - baseInputHeight - activityHeight - taskBarHeight - marginHeight)

    // --- Helper lambdas (closures over state) ---

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
        chatVersion++
    }

    fun applyModel(
        model: Model,
        recordSessionEntry: Boolean = true,
        announce: Boolean = true,
    ) {
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
            appendSystemMessage("Model set to ${model.provider.value}/${model.id}")
        }
        onSettingsChanged { it.copy(defaultModel = model.id, defaultProvider = model.provider.value) }
    }

    fun setThinkingLevel(
        level: AgentThinkingLevel,
        recordSessionEntry: Boolean = true,
        announce: Boolean = true,
    ) {
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
            appendSystemMessage("Thinking set to ${level.name.lowercase()}")
        }
        onSettingsChanged { it.copy(defaultThinkingLevel = level.name.lowercase()) }
    }

    fun parseThinkingLevel(value: String): AgentThinkingLevel {
        return parseThinkingLevelOrNull(value) ?: run {
            appendSystemMessage("Unknown thinking level: $value")
            thinkingLevel
        }
    }

    fun tryExpandFileCommand(text: String): String? {
        return SlashCommandExpander.expand(text, fileSlashCommands)
    }

    // --- Overlay openers ---

    fun openModelOverlay() {
        if (currentModels.isEmpty()) {
            appendSystemMessage("No models available — use /provider to connect one")
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

    fun onProviderConnected(info: ProviderInfo) {
        currentModels = refreshModels()
        val newIndex = currentModels.indexOfFirst {
            it.provider.value == info.id
        }.takeIf { it >= 0 }
        if (newIndex != null && (selectedModelIndex < 0 || currentModel == null)) {
            applyModel(currentModels[newIndex])
        }
        appendSystemMessage("Connected ${info.name} — ${currentModels.count { it.provider.value == info.id }} models available")
    }

    fun startAuthMethod(info: ProviderInfo, method: AuthMethod) {
        when (method) {
            is AuthMethod.ApiKey -> {
                overlayHostState.pushPrompt(
                    title = "${info.name} — API Key",
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
                    title = "${info.name} — Authorizing",
                    lines = listOf(
                        "Starting authorization...",
                        "",
                        "Please wait...",
                    ),
                )
                promptScope.launch {
                    val authorization = runCatching { authorizeOAuth(info.id) }.getOrNull()
                    if (authorization == null) {
                        overlayHostState.dismissTopSilent()
                        appendSystemMessage("Failed to start OAuth for ${info.name}")
                        return@launch
                    }
                    // Update the info overlay with the verification URL and user code
                    overlayHostState.dismissTopSilent()
                    var cancelled = false
                    overlayHostState.pushInfo(
                        title = "${info.name} — Waiting for Authorization",
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
                    // Try to open the browser
                    runCatching {
                        ProcessBuilder("open", authorization.verificationUrl).start()
                    }
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
        }
    }

    fun startProviderAuth(info: ProviderInfo) {
        val methods = info.authMethods
        if (methods.size == 1) {
            startAuthMethod(info, methods.first())
        } else {
            val methodOptions = methods.map { method ->
                SelectItem(label = method.label, value = method)
            }
            overlayHostState.push(
                title = "${info.name} — Auth Method",
                items = methodOptions,
                selectedIndex = 0,
                onSubmit = { method -> startAuthMethod(info, method) },
            )
        }
    }

    fun openProviderOverlay() {
        val providers = getAllProviders()
        if (providers.isEmpty()) {
            appendSystemMessage("No providers found in model catalog")
            return
        }
        val options = providers.map { info ->
            val status = if (info.connected) "✓" else "○"
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
            onSubmit = { command ->
                if (command == "/help" || command == "/commands" || command == "/settings") {
                    // recursive, handled below via slash command dispatch
                    editor.setText(command)
                } else {
                    editor.setText(if (command == "/exit") command else "$command ")
                }
                editorVersion++
            },
        )
    }

    fun openSessionOverlay() {
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            appendSystemMessage("Session picker is unavailable: no session directory configured")
            return
        }
        val sessions = runCatching {
            Files.list(sessionsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .filter { it.fileName.toString().endsWith(".jsonl") }
                    .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
                    .limit(200)
                    .toList()
            }
        }.getOrElse {
            appendSystemMessage("Failed to list sessions: ${it.message ?: it::class.simpleName}")
            return
        }
        if (sessions.isEmpty()) {
            appendSystemMessage("No saved sessions found")
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
                    client = client,
                    chatHistoryState = chatHistoryState,
                    activeSessionManagerSetter = { activeSessionManager = it },
                    applyModel = { model -> applyModel(model, recordSessionEntry = false, announce = false) },
                    setThinkingLevel = { level ->
                        setThinkingLevel(
                            level,
                            recordSessionEntry = false,
                            announce = false
                        )
                    },
                    appendSystemMessage = ::appendSystemMessage,
                )
            },
        )
    }

    fun openThemeOverlay() {
        val options = AVAILABLE_THEMES.map { named ->
            SelectItem(label = named.name, value = named)
        }
        val currentIndex = AVAILABLE_THEMES.indexOfFirst { it.config == activeTheme }
            .coerceAtLeast(0)
        val previousTheme = activeTheme
        overlayHostState.push(
            title = "Theme",
            items = options,
            selectedIndex = currentIndex,
            onSubmit = { namedTheme ->
                setActiveTheme(namedTheme.config)
                onSettingsChanged { it.copy(theme = namedTheme.name) }
            },
            onClose = { setActiveTheme(previousTheme) },
            onSelectionChanged = { namedTheme -> setActiveTheme(namedTheme.config) },
        )
    }

    fun openSettingsOverlay() {
        val options = listOf(
            SelectItem("Model - choose model", SettingsAction.Model),
            SelectItem("Provider - connect provider", SettingsAction.Provider),
            SelectItem("Thinking - choose level", SettingsAction.Thinking),
            SelectItem("Theme - pick color theme", SettingsAction.Theme),
            SelectItem("Usage footer - toggle", SettingsAction.Usage),
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
                    SettingsAction.Theme -> openThemeOverlay()
                    SettingsAction.Usage -> {
                        showUsageFooter = !showUsageFooter
                        onSettingsChanged { it.copy(showUsageFooter = showUsageFooter) }
                        appendSystemMessage("Usage footer: ${if (showUsageFooter) "on" else "off"}")
                    }
                    SettingsAction.Session -> openSessionOverlay()
                    SettingsAction.Commands -> openCommandsOverlay()
                    SettingsAction.Help -> appendSystemMessage(helpText(slashCommands))
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

    // --- Configure agent on first composition ---

    LaunchedEffect(Unit) {
        val agent = client.rawAgent()
        agent.setThinkingLevel(initialThinkingLevel)
        initialModel?.let(agent::setModel)
        initialUserMessage?.let { message ->
            chatHistoryState.appendMessage(message)
            chatVersion++
            activeSessionManager?.appendMessage(message)
            val job = launch {
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
    }

    // --- Bind task bar to TodoState ---

    LaunchedEffect(todoState) {
        if (todoState != null) {
            taskBarState.bind(todoState)
        }
    }

    // --- Agent event collection ---

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        client.events.collect { event ->
            handleAgentEvent(
                event = event,
                client = client,
                chatHistoryState = chatHistoryState,
                toolArgumentsById = toolArgumentsById,
                activeSessionManager = activeSessionManager,
                usageHolder = usageHolder,
                setIsStreaming = { isStreaming = it },
                setLiveActivityLabel = { liveActivityLabel = it },
                setCurrentPromptJob = { currentPromptJob = it },
                bumpChatVersion = { chatVersion++ },
            )
        }
    }

    // --- Refresh ticker for spinner animation ---

    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while (true) {
            delay(80L)
            spinnerFrame = (spinnerFrame + 1) % 10
        }
    }

    // --- Render the editor ---

    // Read editorVersion to trigger recomposition when the editor state changes.
    @Suppress("UNUSED_EXPRESSION")
    editorVersion
    val editorResult = editor.render(width = width, height = baseInputHeight)

    // --- Key event handler ---

    fun handleKeyEvent(keyEvent: KeyboardEvent): Boolean {
        // Overlay gets first priority
        // (Note: overlay key handling is done via Modifier.onKeyEvent on OverlayHost/SelectDialog)

        // Ctrl+C handling
        if (keyEvent.ctrl && keyEvent.key is Key.Character && keyEvent.key.value == 'c') {
            if (isStreaming) {
                client.rawAgent().abort()
                appendSystemMessage("Interrupted current response.")
                ctrlCArmed = false
                return true
            }
            if (ctrlCArmed) {
                client.rawAgent().abort()
                currentPromptJob?.cancel(CancellationException("Exiting"))
                currentPromptJob = null
                kotlin.system.exitProcess(0)
            }
            ctrlCArmed = true
            appendSystemMessage("Press Ctrl+C again to exit.")
            return true
        }
        ctrlCArmed = false

        // ESC handling — interrupt agent when streaming
        if (keyEvent.key is Key.Escape) {
            if (isStreaming) {
                client.rawAgent().abort()
                appendSystemMessage("Interrupted current response.")
                return true
            }
            return false
        }

        // Global shortcuts
        if (keyEvent.ctrl && keyEvent.key is Key.Character) {
            when (keyEvent.key.value.lowercaseChar()) {
                'l' -> {
                    chatHistoryState.entries.clear()
                    return true
                }

                't' -> {
                    val next = if (thinkingLevel == AgentThinkingLevel.OFF) {
                        AgentThinkingLevel.LOW
                    } else {
                        AgentThinkingLevel.OFF
                    }
                    setThinkingLevel(next)
                    return true
                }

                'p' -> {
                    cycleModel(
                        direction = -1,
                        availableModels = currentModels,
                        currentIndex = selectedModelIndex,
                        setIndex = { selectedModelIndex = it },
                        applyModel = ::applyModel,
                        appendSystemMessage = ::appendSystemMessage,
                    )
                    return true
                }

                'n' -> {
                    cycleModel(
                        direction = 1,
                        availableModels = currentModels,
                        currentIndex = selectedModelIndex,
                        setIndex = { selectedModelIndex = it },
                        applyModel = ::applyModel,
                        appendSystemMessage = ::appendSystemMessage,
                    )
                    return true
                }

                'o' -> {
                    openSettingsOverlay()
                    return true
                }

                'g' -> {
                    if (!chatHistoryState.toggleLatestThinkingCollapsed()) {
                        appendSystemMessage("No thinking block available to toggle.")
                    }
                    return true
                }

                'e' -> {
                    if (!chatHistoryState.toggleLatestToolCollapsed()) {
                        appendSystemMessage("No tool execution available to toggle.")
                    }
                    return true
                }

                'u' -> {
                    chatHistoryState.scrollUp(12)
                    return true
                }

                'd' -> {
                    chatHistoryState.scrollDown(12)
                    return true
                }
            }
        }

        // Scroll shortcuts
        if ((keyEvent.ctrl || keyEvent.shift) && keyEvent.key == Key.ArrowUp) {
            chatHistoryState.scrollUp(3)
            return true
        }
        if ((keyEvent.ctrl || keyEvent.shift) && keyEvent.key == Key.ArrowDown) {
            chatHistoryState.scrollDown(3)
            return true
        }
        if (keyEvent.key == Key.PageUp && !keyEvent.alt && !keyEvent.ctrl) {
            chatHistoryState.scrollUp(12)
            return true
        }
        if (keyEvent.key == Key.PageDown && !keyEvent.alt && !keyEvent.ctrl) {
            chatHistoryState.scrollDown(12)
            return true
        }
        if (keyEvent.alt && keyEvent.key == Key.PageUp) {
            chatHistoryState.scrollUp(10)
            return true
        }
        if (keyEvent.alt && keyEvent.key == Key.PageDown) {
            chatHistoryState.scrollDown(10)
            return true
        }

        // Arrow up/down scroll when editor is empty
        if (editor.text().isBlank()) {
            if (keyEvent.key == Key.ArrowUp && !keyEvent.ctrl && !keyEvent.alt) {
                chatHistoryState.scrollUp(3)
                return true
            }
            if (keyEvent.key == Key.ArrowDown && !keyEvent.ctrl && !keyEvent.alt) {
                chatHistoryState.scrollDown(3)
                return true
            }
        }

        // Enter on autocomplete popup
        if (keyEvent.key == Key.Enter && editor.hasAutocompletePopup()) {
            editor.handle(keyEvent)
            editorVersion++
            return true
        }

        // Submit on Enter (no modifiers)
        val shouldSubmit = keyEvent.key == Key.Enter && !keyEvent.shift && !keyEvent.ctrl && !keyEvent.alt
        if (shouldSubmit) {
            return true // handled separately after the dispatch
        }

        // Everything else goes to the editor
        editor.handle(keyEvent)
        editorVersion++
        return true
    }

    // --- Rendering ---

    Box(
        modifier = Modifier
            .height(height)
            .fillMaxWidth()
            .onKeyEvent { event ->
                if (overlayHostState.hasOverlay) {
                    // Let overlay handle via its own Modifier.onKeyEvent
                    return@onKeyEvent false
                }
                val keyboardEvent = event.toKeyboardEvent()

                // Slash command auto-submit: when Enter is pressed with a
                // slash command popup visible, apply the selection and
                // immediately submit the command.
                val isSlashAutoSubmit = keyboardEvent.key == Key.Enter &&
                        !keyboardEvent.shift && !keyboardEvent.ctrl && !keyboardEvent.alt &&
                        editor.slashCommandPopupItemCount() > 0
                if (isSlashAutoSubmit) {
                    editor.handle(keyboardEvent)
                    editorVersion++
                }

                val shouldSubmit = isSlashAutoSubmit || (
                        keyboardEvent.key == Key.Enter &&
                        !keyboardEvent.shift && !keyboardEvent.ctrl && !keyboardEvent.alt &&
                        !editor.hasAutocompletePopup())
                if (shouldSubmit) {
                    val text = editor.text().trimEnd()
                    if (text.isBlank()) {
                        editor.setText("")
                        editorVersion++
                        return@onKeyEvent true
                    }
                    editor.setText("")
                    editorVersion++
                    handleSubmit(
                        rawInput = text,
                        client = client,
                        editor = editor,
                        chatHistoryState = chatHistoryState,
                        overlayHostState = overlayHostState,
                        slashCommands = slashCommands,
                        fileSlashCommands = fileSlashCommands,
                        cwd = cwd,
                        activeSessionManager = activeSessionManager,
                        promptScope = promptScope,
                        isStreaming = isStreaming,
                        currentPromptJob = currentPromptJob,
                        setCurrentPromptJob = { currentPromptJob = it },
                        appendSystemMessage = ::appendSystemMessage,
                        openModelOverlay = ::openModelOverlay,
                        openThinkingOverlay = ::openThinkingOverlay,
                        openSessionOverlay = ::openSessionOverlay,
                        openSettingsOverlay = ::openSettingsOverlay,
                        openCommandsOverlay = ::openCommandsOverlay,
                        openThemeOverlay = ::openThemeOverlay,
                        openProviderOverlay = ::openProviderOverlay,
                        toggleUsageFooter = {
                            showUsageFooter = !showUsageFooter
                            onSettingsChanged { it.copy(showUsageFooter = showUsageFooter) }
                            appendSystemMessage("Usage footer: ${if (showUsageFooter) "on" else "off"}")
                        },
                        setModelById = { modelId ->
                            val match = currentModels.firstOrNull { model ->
                                model.id.equals(modelId, ignoreCase = true) ||
                                        "${model.provider.value}/${model.id}".equals(modelId, ignoreCase = true)
                            }
                            if (match == null) {
                                appendSystemMessage("Model not found: $modelId")
                            } else {
                                applyModel(match)
                            }
                        },
                        setThinkingLevel = { value ->
                            setThinkingLevel(parseThinkingLevel(value))
                        },
                        tryExpandFileCommand = ::tryExpandFileCommand,
                        setRunning = { value ->
                            if (!value) {
                                client.rawAgent().abort()
                                currentPromptJob?.cancel(CancellationException("Exiting"))
                                currentPromptJob = null
                                kotlin.system.exitProcess(0)
                            }
                        },
                        bumpChatVersion = { chatVersion++ },
                    )
                    return@onKeyEvent true
                }

                handleKeyEvent(keyboardEvent)
            },
    ) {
        Column {
            // Chat history viewport
            ChatHistory(
                state = chatHistoryState,
                width = width,
                height = historyHeight,
                markdownRenderer = markdownRenderer,
                diffRenderer = diffRenderer,
                showUsageFooter = showUsageFooter,
                version = chatVersion,
            )

            // Activity line (spinner while streaming, only when no task bar)
            if (isStreaming && !taskBarState.visible) {
                ActivityLine(
                    spinnerFrame = spinnerFrame,
                    label = liveActivityLabel,
                    width = width,
                )
            }

            // Task bar (absorbs the activity spinner into its header)
            TaskBar(
                state = taskBarState,
                width = width,
                isStreaming = isStreaming,
                spinnerFrame = spinnerFrame,
                activityLabel = liveActivityLabel,
            )

            // Margin between chat area and editor border
            Text("")

            val bashMode = editor.text().trimStart().startsWith("!")

            // Editor top border
            EditorBorder(width, bashMode)

            // Slash command popup (between border and input)
            val autocompleteModel = editorResult.autocomplete
            if (autocompleteModel != null && autocompleteModel.items.isNotEmpty()) {
                AutocompletePopup(
                    model = autocompleteModel,
                    promptWidth = 2,
                    maxWidth = width,
                    theme = mosaicTheme,
                )
            }

            // Editor view
            EditorView(
                result = editorResult,
                width = width,
                height = baseInputHeight,
                bashMode = bashMode,
            )

            // Editor bottom border
            EditorBorder(width, bashMode)

            // Status bar
            StatusBar(
                state = statusBarWithUsage,
                width = width,
            )
        }

        // Overlay host (renders on top of everything)
        OverlayHost(
            state = overlayHostState,
            terminalWidth = width,
            terminalHeight = height,
        )
    }

    // Keep alive until running is set to false
    if (running) {
        LaunchedEffect(Unit) {
            awaitCancellation()
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun ActivityLine(
    spinnerFrame: Int,
    label: String,
    width: Int,
) {
    val theme = LocalThemeConfig.current
    val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    val frame = frames[spinnerFrame.mod(frames.size)]
    val displayLabel = label.ifBlank { "Thinking" }

    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.accent)) { append(frame) }
            append(" ")
            withStyle(SpanStyle(color = theme.colors.accentBright)) { append(displayLabel) }
            withStyle(SpanStyle(color = theme.colors.muted)) { append("...") }
            val used = 1 + 1 + displayLabel.length + 3
            val padding = (width - used).coerceAtLeast(0)
            if (padding > 0) append(" ".repeat(padding))
        },
    )
}

@Composable
private fun EditorBorder(width: Int, bashMode: Boolean = false) {
    val theme = LocalThemeConfig.current
    val color = if (bashMode) theme.colors.error else theme.colors.muted
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = color)) {
                append("─".repeat(width.coerceAtLeast(1)))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Agent event handling
// ---------------------------------------------------------------------------

private fun handleAgentEvent(
    event: AgentEvent,
    client: AgentClient,
    chatHistoryState: ChatHistoryState,
    toolArgumentsById: MutableMap<String, String>,
    activeSessionManager: SessionManager?,
    usageHolder: UsageHolder,
    setIsStreaming: (Boolean) -> Unit,
    setLiveActivityLabel: (String) -> Unit,
    setCurrentPromptJob: (Job?) -> Unit,
    bumpChatVersion: () -> Unit,
) {
    when (event) {
        is AgentStartEvent -> {
            setIsStreaming(true)
            setLiveActivityLabel("Thinking")
        }

        is AgentEndEvent -> {
            setIsStreaming(false)
            setCurrentPromptJob(null)
            setLiveActivityLabel("")
        }

        is MessageStartEvent -> {
            val message = event.message
            if (message is AssistantMessage) {
                chatHistoryState.startAssistantMessage(message)
            } else if (message !is ToolResultMessage) {
                chatHistoryState.appendMessage(message)
            }
            bumpChatVersion()
        }

        is MessageUpdateEvent -> {
            val message = event.message
            if (message is AssistantMessage) {
                chatHistoryState.updateAssistantMessage(message)
            } else if (message !is ToolResultMessage) {
                chatHistoryState.updateMessage(message)
            }
            bumpChatVersion()
        }

        is MessageEndEvent -> {
            val message = event.message
            if (message is AssistantMessage) {
                chatHistoryState.endAssistantMessage(message)
            } else if (message !is ToolResultMessage) {
                chatHistoryState.updateMessage(message)
            }
            // User messages are already persisted in submitMessage / initialUserMessage handling.
            // Only persist assistant and tool result messages from the event stream.
            if (message !is UserMessage) {
                activeSessionManager?.appendMessage(message)
            }
        }

        is ToolExecutionStartEvent -> {
            toolArgumentsById[event.toolCallId] = event.arguments.toString()
            setLiveActivityLabel("Running ${event.toolName}")
            chatHistoryState.updateToolExecution(
                ToolExecutionView(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                    output = "",
                    pending = true,
                    collapsed = defaultToolCollapsed(event.toolName),
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
            setLiveActivityLabel("Thinking")
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

        else -> {
            // no-op for TurnStartEvent and any future event types
        }
    }
}

// ---------------------------------------------------------------------------
// Submit handling
// ---------------------------------------------------------------------------

private fun handleSubmit(
    rawInput: String,
    client: AgentClient,
    editor: Editor,
    chatHistoryState: ChatHistoryState,
    overlayHostState: OverlayHostState,
    slashCommands: List<SlashCommandSpec>,
    fileSlashCommands: List<SlashCommand>,
    cwd: Path,
    activeSessionManager: SessionManager?,
    promptScope: CoroutineScope,
    isStreaming: Boolean,
    currentPromptJob: Job?,
    setCurrentPromptJob: (Job?) -> Unit,
    appendSystemMessage: (String) -> Unit,
    openModelOverlay: () -> Unit,
    openThinkingOverlay: () -> Unit,
    openSessionOverlay: () -> Unit,
    openSettingsOverlay: () -> Unit,
    openCommandsOverlay: () -> Unit,
    openThemeOverlay: () -> Unit,
    openProviderOverlay: () -> Unit,
    toggleUsageFooter: () -> Unit,
    setModelById: (String) -> Unit,
    setThinkingLevel: (String) -> Unit,
    tryExpandFileCommand: (String) -> String?,
    setRunning: (Boolean) -> Unit,
    bumpChatVersion: () -> Unit,
) {
    when {
        rawInput.startsWith("/") -> {
            val tokens = rawInput.trim().split(Regex("\\s+"))
            val command = tokens.firstOrNull().orEmpty()
            val args = tokens.drop(1)

            when (command) {
                "/help" -> appendSystemMessage(helpText(slashCommands))
                "/commands" -> openCommandsOverlay()
                "/clear" -> chatHistoryState.entries.clear()
                "/exit" -> setRunning(false)
                "/model" -> {
                    if (args.isEmpty()) {
                        openModelOverlay()
                    } else {
                        setModelById(args.joinToString(" "))
                    }
                }

                "/thinking" -> {
                    if (args.isEmpty()) {
                        openThinkingOverlay()
                    } else {
                        setThinkingLevel(args.first())
                    }
                }

                "/theme" -> openThemeOverlay()
                "/provider" -> openProviderOverlay()
                "/usage" -> toggleUsageFooter()
                "/settings" -> openSettingsOverlay()
                "/session" -> {
                    if (args.isEmpty()) {
                        openSessionOverlay()
                    } else {
                        // Direct path, not currently wired to overlay
                        appendSystemMessage("Use /session without arguments to open the session picker.")
                    }
                }

                else -> {
                    val expanded = tryExpandFileCommand(rawInput)
                    if (expanded != null) {
                        val message = UserMessage(
                            content = listOf(TextContent(text = expanded)),
                            timestamp = System.currentTimeMillis(),
                        )
                        submitMessage(
                            message = message,
                            client = client,
                            chatHistoryState = chatHistoryState,
                            activeSessionManager = activeSessionManager,
                            promptScope = promptScope,
                            isStreaming = isStreaming,
                            currentPromptJob = currentPromptJob,
                            setCurrentPromptJob = setCurrentPromptJob,
                            appendSystemMessage = appendSystemMessage,
                            bumpChatVersion = bumpChatVersion,
                        )
                    } else {
                        appendSystemMessage("Unknown command: $command")
                    }
                }
            }
        }

        rawInput.startsWith("!") -> {
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
            bumpChatVersion()
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

        else -> {
            val message = UserMessage(
                content = listOf(TextContent(text = rawInput)),
                timestamp = System.currentTimeMillis(),
            )
            submitMessage(
                message = message,
                client = client,
                chatHistoryState = chatHistoryState,
                activeSessionManager = activeSessionManager,
                promptScope = promptScope,
                isStreaming = isStreaming,
                currentPromptJob = currentPromptJob,
                setCurrentPromptJob = setCurrentPromptJob,
                appendSystemMessage = appendSystemMessage,
                bumpChatVersion = bumpChatVersion,
            )
        }
    }
}

private fun submitMessage(
    message: UserMessage,
    client: AgentClient,
    chatHistoryState: ChatHistoryState,
    activeSessionManager: SessionManager?,
    promptScope: CoroutineScope,
    isStreaming: Boolean,
    currentPromptJob: Job?,
    setCurrentPromptJob: (Job?) -> Unit,
    appendSystemMessage: (String) -> Unit,
    bumpChatVersion: () -> Unit,
) {
    chatHistoryState.appendMessage(message)
    bumpChatVersion()
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

    val job = promptScope.launch(Dispatchers.IO) {
        try {
            client.prompt(listOf(message))
        } catch (_: CancellationException) {
        } catch (error: Throwable) {
            appendSystemMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
        } finally {
            setCurrentPromptJob(null)
        }
    }
    setCurrentPromptJob(job)
}

// ---------------------------------------------------------------------------
// Session loading
// ---------------------------------------------------------------------------

private fun loadSession(
    path: Path,
    sessionsDir: Path?,
    availableModels: List<Model>,
    client: AgentClient,
    chatHistoryState: ChatHistoryState,
    activeSessionManagerSetter: (SessionManager) -> Unit,
    applyModel: (Model) -> Unit,
    setThinkingLevel: (AgentThinkingLevel) -> Unit,
    appendSystemMessage: (String) -> Unit,
) {
    val resolvedPath = when {
        path.isAbsolute -> path
        sessionsDir != null -> sessionsDir.resolve(path)
        else -> path
    }

    if (!Files.exists(resolvedPath)) {
        appendSystemMessage("Session not found: ${resolvedPath.toAbsolutePath()}")
        return
    }

    val loadedManager = runCatching { SessionManager(resolvedPath) }
        .getOrElse {
            appendSystemMessage("Failed to open session: ${it.message ?: it::class.simpleName}")
            return
        }
    val context = loadedManager.buildContext()

    activeSessionManagerSetter(loadedManager)
    client.rawAgent().replaceMessages(context.messages)
    chatHistoryState.entries.clear()
    context.messages.forEach { chatHistoryState.appendMessage(it) }

    val restoredModel = context.model?.let { (provider, id) ->
        availableModels.firstOrNull { it.provider.value == provider && it.id == id }
    }
    if (restoredModel != null) {
        applyModel(restoredModel)
    }

    parseThinkingLevelOrNull(context.thinkingLevel)?.let {
        setThinkingLevel(it)
    }

    appendSystemMessage("Loaded session ${resolvedPath.fileName}")
}

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

private fun cycleModel(
    direction: Int,
    availableModels: List<Model>,
    currentIndex: Int,
    setIndex: (Int) -> Unit,
    applyModel: (Model) -> Unit,
    appendSystemMessage: (String) -> Unit,
) {
    if (availableModels.isEmpty()) {
        appendSystemMessage("No models available")
        return
    }
    val size = availableModels.size
    val current = currentIndex.coerceIn(0, size - 1)
    val newIndex = ((current + direction) % size + size) % size
    setIndex(newIndex)
    applyModel(availableModels[newIndex])
}

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

private fun helpText(slashCommands: List<SlashCommandSpec>): String = buildString {
    appendLine("Commands:")
    slashCommands.forEach { spec ->
        appendLine("  ${spec.command.padEnd(20)} ${spec.description}")
    }
    appendLine("")
    appendLine("Shortcuts:")
    appendLine("  Enter          Submit")
    appendLine("  Shift+Enter    Insert newline (Alt+Enter also works)")
    appendLine("  Ctrl+C         Interrupt current run, then press twice to exit")
    appendLine("  Ctrl+L         Clear visible chat")
    appendLine("  Ctrl+T         Toggle thinking")
    appendLine("  Ctrl+P/Ctrl+N  Cycle models")
    appendLine("  Ctrl+O         Open settings overlay")
    appendLine("  Ctrl+G         Toggle latest thinking block")
    appendLine("  Ctrl+E         Toggle latest tool details")
    appendLine("  PgUp/PgDn      Scroll chat history")
    appendLine("  Ctrl+U/Ctrl+D  Scroll chat history")
    appendLine("  Up/Down        Scroll chat when input is empty")
    appendLine("  Alt+PgUp/PgDn  Scroll chat history")
    appendLine("  Esc            Interrupt agent or close modal")
    append("Prefix a line with ! to run local shell commands.")
}

/**
 * Mutable holder for usage stats that persists across recompositions.
 * Using a class with var fields instead of mutableStateOf for each field
 * because these are updated from the agent event handler (non-composable)
 * and read during composition to build the status bar state.
 */
private class UsageHolder {
    var inputTokens: Int? by mutableStateOf(null)
    var outputTokens: Int? by mutableStateOf(null)
    var totalTokens: Int? by mutableStateOf(null)
    var cost: Double? by mutableStateOf(null)
    var contextTokens: Int? by mutableStateOf(null)
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

/**
 * Launches the interactive TUI. This is the primary entry point
 * for the interactive mode.
 */
public fun runTui(
    client: AgentClient,
    initialUserMessage: UserMessage? = null,
    availableModels: List<Model> = emptyList(),
    sessionManager: SessionManager? = null,
    sessionsDir: Path? = null,
    cwd: Path = Path.of(System.getProperty("user.dir")),
    initialThinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    initialModel: Model? = null,
    theme: ThemeConfig = ThemeConfig.DEFAULT,
    fileCommands: List<SlashCommand> = emptyList(),
    getAllProviders: () -> List<ProviderInfo> = { emptyList() },
    storeApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit = { _, _ -> },
    refreshModels: () -> List<Model> = { availableModels },
    authorizeOAuth: suspend (provider: String) -> OAuthAuthorization? = { null },
    pollOAuthToken: suspend (provider: String) -> OAuthResult? = { null },
    onSettingsChanged: (transform: (Settings) -> Settings) -> Unit = {},
    initialShowUsageFooter: Boolean = true,
    todoState: TodoState? = null,
) {
    val out = System.out
    val restoreTerminal = "\u001b[<u\u001b[?25h\u001b[?1049l"

    // Install a shutdown hook so that exitProcess() (or any JVM shutdown) restores
    // the terminal from alternate screen / kitty keyboard mode.
    val shutdownHook = Thread({
        out.write(restoreTerminal.toByteArray())
        out.flush()
    }, "terminal-restore")
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    // Enter alternate screen buffer, hide cursor, and enable kitty keyboard protocol.
    // Kitty keyboard flags=1 (disambiguate) makes the terminal encode modifier keys
    // on Enter, Tab, Escape, and Backspace via CSI u sequences, allowing Shift+Enter
    // to be distinguished from bare Enter.
    // Non-ASCII Unicode codepoints (ñ, é, etc.) are handled by our patched CompatKt.java
    // which shadows Mosaic's broken version that throws for codepoints outside ASCII.
    out.write("\u001b[?1049h\u001b[?25l\u001b[>1u".toByteArray())
    out.flush()
    try {
        runMosaicMain {
            Agent47App(
                client = client,
                initialUserMessage = initialUserMessage,
                availableModels = availableModels,
                sessionManager = sessionManager,
                sessionsDir = sessionsDir,
                cwd = cwd,
                initialThinkingLevel = initialThinkingLevel,
                initialModel = initialModel,
                theme = theme,
                fileCommands = fileCommands,
                getAllProviders = getAllProviders,
                storeApiKey = storeApiKey,
                storeOAuthCredential = storeOAuthCredential,
                refreshModels = refreshModels,
                authorizeOAuth = authorizeOAuth,
                pollOAuthToken = pollOAuthToken,
                onSettingsChanged = onSettingsChanged,
                initialShowUsageFooter = initialShowUsageFooter,
                todoState = todoState,
            )
        }
    } catch (e: UnsupportedOperationException) {
        // Safety net: if our CompatKt shadow somehow isn't loaded and Mosaic's
        // original throws for an unrecognized codepoint, exit gracefully.
        System.err.println("Keyboard input error: ${e.message}")
        System.err.println("This is a Mosaic library bug with non-ASCII characters.")
    } finally {
        // Pop kitty keyboard flags, restore cursor, and leave alternate screen buffer
        out.write(restoreTerminal.toByteArray())
        out.flush()
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
}
