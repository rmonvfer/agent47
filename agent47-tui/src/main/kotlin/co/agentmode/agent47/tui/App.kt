package co.agentmode.agent47.tui

import androidx.compose.runtime.*
import co.agentmode.agent47.agent.core.*
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.PushNotifier
import co.agentmode.agent47.coding.core.agents.RunningAgent
import co.agentmode.agent47.coding.core.compaction.estimateContextTokens
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandExpander
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.tui.components.*
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.ui.core.editor.WordWrap
import co.agentmode.agent47.ui.core.state.*
import co.agentmode.agent47.tui.input.KeyBindings
import co.agentmode.agent47.tui.input.KeyContext
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.input.SubmitDispatcher
import co.agentmode.agent47.tui.input.TuiIntent
import co.agentmode.agent47.tui.input.parseSubmission
import co.agentmode.agent47.tui.input.toKeyboardEvent
import co.agentmode.agent47.tui.layout.LayoutInputs
import co.agentmode.agent47.tui.layout.computeTuiLayout
import co.agentmode.agent47.tui.runtime.AgentEventCollector
import co.agentmode.agent47.tui.runtime.AgentTranscriptMirror
import co.agentmode.agent47.tui.runtime.PushNotificationPump
import co.agentmode.agent47.tui.runtime.ResumeHintTracker
import co.agentmode.agent47.tui.runtime.SpinnerTicker
import co.agentmode.agent47.tui.runtime.TerminalSession
import co.agentmode.agent47.tui.commands.SlashCommandSpec
import co.agentmode.agent47.tui.commands.builtinSlashCommands
import co.agentmode.agent47.tui.commands.helpText
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.rememberTuiAppState
import co.agentmode.agent47.tui.controller.CompactionController
import co.agentmode.agent47.tui.controller.ConversationController
import co.agentmode.agent47.tui.controller.ModelController
import co.agentmode.agent47.tui.controller.SessionController
import co.agentmode.agent47.tui.controller.AgentPanelController
import co.agentmode.agent47.tui.controller.ProviderAuthController
import co.agentmode.agent47.tui.extensions.BindExtensionSessionControl
import co.agentmode.agent47.tui.extensions.BindExtensionUi
import co.agentmode.agent47.tui.extensions.TuiExtensionSessionControl
import co.agentmode.agent47.tui.extensions.TuiExtensionUi
import co.agentmode.agent47.tui.overlays.OverlayNavigator
import co.agentmode.agent47.tui.overlays.openAgentsOverlay
import co.agentmode.agent47.tui.overlays.openCommandsOverlay
import co.agentmode.agent47.tui.overlays.openMemoryOverlay
import co.agentmode.agent47.tui.overlays.openModelOverlay
import co.agentmode.agent47.tui.overlays.openProviderOverlay
import co.agentmode.agent47.tui.overlays.openSessionOverlay
import co.agentmode.agent47.tui.overlays.openSettingsOverlay
import co.agentmode.agent47.tui.overlays.openThemeOverlay
import co.agentmode.agent47.tui.util.detectBranchName
import co.agentmode.agent47.tui.util.executeShell
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownTheme
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.theme.AVAILABLE_THEMES
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.dimmed
import androidx.compose.runtime.CompositionLocalProvider
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.layout.background
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.runMosaicMain
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Filler
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.isSpecifiedColor
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.ext.core.ExtensionCommandContext
import co.agentmode.agent47.ext.core.InputEvent
import co.agentmode.agent47.ext.core.InputHookResult
import co.agentmode.agent47.ext.core.InputSource
import co.agentmode.agent47.ext.core.InputStreamingBehavior
import co.agentmode.agent47.ext.core.RegisteredCommand
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.*
import kotlin.math.max
import kotlin.math.min

/** How far the base layer is darkened toward black while a dialog is open (1 = unchanged). */
private const val SCRIM_DIM_FACTOR = 0.5f

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
    configuration: TuiLaunchConfiguration = TuiLaunchConfiguration(),
    providerServices: TuiProviderServices = TuiProviderServices(),
    conversationServices: TuiConversationServices = TuiConversationServices(),
    subagentServices: TuiSubagentServices = TuiSubagentServices(),
) {
    var activeTheme by remember { mutableStateOf(configuration.theme) }
    var activeAppearance by remember { mutableStateOf(configuration.themeAppearance) }
    CompositionLocalProvider(LocalThemeConfig provides activeTheme) {
        Agent47AppContent(
            client = client,
            configuration = configuration,
            providerServices = providerServices,
            conversationServices = conversationServices,
            subagentServices = subagentServices,
            activeTheme = activeTheme,
            themeAppearance = activeAppearance,
            setActiveTheme = { activeTheme = it },
            setThemeAppearance = { activeAppearance = it },
        )
    }
}

@Composable
private fun Agent47AppContent(
    client: AgentClient,
    configuration: TuiLaunchConfiguration,
    providerServices: TuiProviderServices,
    conversationServices: TuiConversationServices,
    subagentServices: TuiSubagentServices,
    activeTheme: ThemeConfig,
    themeAppearance: ThemeAppearance,
    setActiveTheme: (ThemeConfig) -> Unit,
    setThemeAppearance: (ThemeAppearance) -> Unit,
) {
    val initialUserMessage = configuration.initialUserMessage
    val availableModels = configuration.availableModels
    val sessionManager = configuration.sessionManager
    val sessionsDir = configuration.sessionsDir
    val cwd = configuration.cwd
    val initialThinkingLevel = configuration.initialThinkingLevel
    val initialModel = configuration.initialModel
    val fileCommands = configuration.fileCommands
    val initialExtensionCommands = configuration.extensionCommands
    val initialExtensionShortcuts = configuration.extensionShortcuts
    val initialExtensionToolRenderers = configuration.extensionToolRenderers
    val initialExtensionMessageRenderers = configuration.extensionMessageRenderers
    val extensionContext = configuration.extensionContext
    val initialShowUsageFooter = configuration.initialShowUsageFooter
    val todoState = configuration.todoState
    val instructionFiles = configuration.instructionFiles
    val compactionSettings = configuration.compactionSettings
    val getAllProviders = providerServices.getAllProviders
    val storeApiKey = providerServices.storeApiKey
    val storeOAuthCredential = providerServices.storeOAuthCredential
    val refreshModels = providerServices.refreshModels
    val authorizeOAuth = providerServices.authorizeOAuth
    val pollOAuthToken = providerServices.pollOAuthToken
    val isUsingOAuth = providerServices.isUsingOAuth
    val onSettingsChanged = conversationServices.onSettingsChanged
    val compactContext = conversationServices.compactContext
    val onCompacted = conversationServices.onCompacted
    val onSessionChanged = conversationServices.onSessionChanged
    val onSessionTransition = conversationServices.onSessionTransition
    val processInput = conversationServices.processInput
    val reloadExtensions = conversationServices.reloadExtensions
    val backgroundAgents = subagentServices.backgroundAgents
    val subagentsSettings = subagentServices.settings
    val agentRegistry = subagentServices.agentRegistry
    val scheduler = subagentServices.scheduler
    val persistSubagentsSettings = subagentServices.persistSettings
    val mosaicTheme = LocalThemeConfig.current
    val availableThemes = configuration.availableThemes.ifEmpty { AVAILABLE_THEMES }
    val terminalState = LocalTerminalState.current
    val width = terminalState.size.columns.coerceAtLeast(1)
    // Mosaic appends \r\n after every row including the last. When content fills
    // the full terminal height, the trailing \r\n scrolls everything up by one row.
    // Using rows-1 avoids this (same approach as Mosaic's own rrtop sample).
    val height = (terminalState.size.rows - 1).coerceAtLeast(4)
    val promptScope = rememberCoroutineScope()

    val state = rememberTuiAppState(
        initialModels = availableModels,
        initialModel = initialModel,
        initialThinkingLevel = initialThinkingLevel,
        initialShowUsageFooter = initialShowUsageFooter,
        initialSessionManager = sessionManager,
        initialSubagentsSettings = subagentsSettings,
        initialExtensionCommands = initialExtensionCommands,
        initialExtensionShortcuts = initialExtensionShortcuts,
        initialExtensionToolRenderers = initialExtensionToolRenderers,
        initialExtensionMessageRenderers = initialExtensionMessageRenderers,
    )

    // Stable holder aliases keep the rest of the composable referencing familiar names.
    val chatHistoryState = state.chatHistory
    val viewingChatState = state.viewingChat
    val overlayHostState = state.overlays
    val taskBarState = state.taskBar
    val usageHolder = state.usage
    val toolArgumentsById = state.toolArgumentsById
    val promptHistory = state.promptHistory
    // Thread-safe hand-off for opt-in push notifications: PushNotifier (background thread) enqueues,
    // the background ticker below drains on the UI coroutine and delivers to the orchestrator.
    val pushQueue = state.pushQueue

    // Snapshot-state delegations forward reads and writes to the state holder.
    // Mutable model list that updates when providers are connected
    var currentModels by state::currentModels
    var extensionCommands by state::extensionCommands
    var extensionShortcuts by state::extensionShortcuts
    var extensionToolRenderers by state::extensionToolRenderers
    var extensionMessageRenderers by state::extensionMessageRenderers
    var extensionStatuses by state::extensionStatuses
    var extensionWidgets by state::extensionWidgets
    var extensionTitle by state::extensionTitle
    // Live copy of subagent settings, editable via /agents -> Settings and persisted through the callback.
    var subagentsSettingsState by state::subagentsSettings
    var showUsageFooter by state::showUsageFooter
    var running by state::running
    var isStreaming by state::isStreaming
    var ctrlCArmed by state::ctrlCArmed
    var spinnerFrame by state::spinnerFrame
    var editorVersion by state::editorVersion
    var liveActivityLabel by state::liveActivityLabel
    var thinkingLevel by state::thinkingLevel
    var selectedModelIndex by state::selectedModelIndex
    var activeSessionManager by state::activeSessionManager
    var currentPromptJob by state::currentPromptJob

    val feed = remember { TranscriptFeed(chatHistoryState, client) { state.currentModel } }
    val modelController = remember {
        ModelController(state, client, feed, onSettingsChanged, refreshModels)
    }
    val sessionController = remember {
        SessionController(
            state = state,
            client = client,
            feed = feed,
            models = modelController,
            sessionsDir = sessionsDir,
            scope = promptScope,
            backgroundAgents = backgroundAgents,
            onSessionChanged = onSessionChanged,
            onSessionTransition = onSessionTransition,
        )
    }
    val conversationController = remember {
        ConversationController(state, client, feed, promptScope)
    }
    val compactionController = remember {
        CompactionController(state, client, feed, promptScope, compactContext, onCompacted, compactionSettings)
    }
    val providerAuthController = remember {
        ProviderAuthController(
            state = state,
            feed = feed,
            models = modelController,
            scope = promptScope,
            storeApiKey = storeApiKey,
            storeOAuthCredential = storeOAuthCredential,
            authorizeOAuth = authorizeOAuth,
            pollOAuthToken = pollOAuthToken,
        )
    }
    val agentPanelController = remember {
        AgentPanelController(state, feed, backgroundAgents, persistSubagentsSettings)
    }

    // --- Slash command definitions ---

    val fileSlashCommands = remember { fileCommands }
    val slashCommands = remember(builtinSlashCommands, fileSlashCommands, extensionCommands) {
        builtinSlashCommands + extensionCommands.map {
            SlashCommandSpec("/${it.name}", it.description)
        } + fileSlashCommands.map {
            SlashCommandSpec("/${it.name}", it.description)
        }
    }

    val pushNotifier = remember(
        subagentsSettingsState.pushNotifications,
        subagentsSettingsState.defaultJoinMode,
        backgroundAgents,
    ) {
        if (subagentsSettingsState.pushNotifications && backgroundAgents != null) {
            PushNotifier(promptScope, subagentsSettingsState.defaultJoinMode, deliver = { pushQueue.add(it) })
        } else {
            null
        }
    }
    DisposableEffect(backgroundAgents, pushNotifier) {
        backgroundAgents?.setCompletionListener(pushNotifier?.let { notifier ->
            { agent -> notifier.onComplete(agent) }
        })
        onDispose {
            backgroundAgents?.setCompletionListener(null)
            pushNotifier?.close()
        }
    }
    // Focus mode: when set, the main chat area renders a background agent's live transcript (through
    // the normal ChatHistory renderer) instead of the conversation. Esc returns to the conversation.
    var viewingAgentId by state::viewingAgentId
    fun activeChat(): ChatHistoryState = if (viewingAgentId != null) viewingChatState else chatHistoryState
    val editor = remember {
        Editor(
            slashCommands = slashCommands.map { it.command },
            slashCommandDetails = slashCommands.associate { spec ->
                spec.command.removePrefix("/") to spec.description
            },
            fileCompletionRoot = cwd,
        )
    }
    LaunchedEffect(slashCommands) {
        editor.setSlashCommands(
            slashCommands.map { it.command },
            slashCommands.associate { spec -> spec.command.removePrefix("/") to spec.description },
        )
    }
    // While a dialog is open, the whole base layer is rendered with a darkened copy of the
    // theme so the (undimmed) dialog reads as a distinct layer floating above a scrim.
    // Terminals have no real alpha, so we darken every color instead of drawing a film.
    val baseTheme = if (overlayHostState.hasOverlay) {
        mosaicTheme.dimmed(SCRIM_DIM_FACTOR, themeAppearance)
    } else {
        mosaicTheme
    }
    val markdownRenderer = remember(baseTheme) {
        MarkdownRenderer(MarkdownTheme.fromTheme(baseTheme))
    }
    val diffRenderer = remember(baseTheme) { DiffRenderer(baseTheme) }

    // --- Derived values ---

    val currentModel = state.currentModel

    LaunchedEffect(activeSessionManager) {
        usageHolder.reset(
            activeSessionManager?.getEntries()
                ?.filterIsInstance<SessionMessageEntry>()
                ?.mapNotNull { it.message as? AssistantMessage }
                ?.map { it.usage }
                .orEmpty(),
        )
    }
    val contextEstimate = estimateContextTokens(client.state.messages)
    val providerCount = getAllProviders().count { it.connected }
    val statusBarState = MosaicStatusBarState(
        cwdPath = cwd.toString().replace(System.getProperty("user.home"), "~"),
        branch = remember { detectBranchName(cwd) },
        providerId = currentModel?.provider?.value,
        availableProviderCount = providerCount,
        modelId = currentModel?.id,
        modelSupportsReasoning = currentModel?.reasoning == true,
        thinkingLabel = thinkingLevel.name.lowercase(),
        inputTokens = usageHolder.inputTokens,
        outputTokens = usageHolder.outputTokens,
        cacheReadTokens = usageHolder.cacheReadTokens,
        cacheWriteTokens = usageHolder.cacheWriteTokens,
        latestCacheHitRate = usageHolder.latestCacheHitRate,
        cost = usageHolder.cost,
        usingSubscription = currentModel?.let(isUsingOAuth) == true,
        contextTokens = contextEstimate.tokens.takeIf { contextEstimate.lastUsageIndex != null },
        contextWindow = currentModel?.contextWindow ?: 0,
        autoCompactEnabled = compactionSettings.enabled && compactionSettings.auto,
        showUsage = showUsageFooter,
    )

    // --- Layout calculations ---

    val runningAgents = backgroundAgents?.runningStatus().orEmpty()
    val editorContentWidth = (width - 2).coerceAtLeast(1)
    val visualLineCount = WordWrap.createMapping(editor.state.lines, editorContentWidth).visualLines.size
    val layout = computeTuiLayout(
        width = width,
        height = height,
        inputs = LayoutInputs(
            visualLineCount = visualLineCount,
            popupItemCount = editor.slashCommandPopupItemCount(),
            taskBarVisible = taskBarState.visible,
            taskBarLineCount = taskBarState.lineCount,
            isStreaming = isStreaming,
            hasBackgroundAgents = runningAgents.isNotEmpty(),
            runningAgentCount = runningAgents.count { it.status == RunningAgent.Status.RUNNING },
            queuedAgentCount = runningAgents.count { it.status == RunningAgent.Status.QUEUED },
        ),
    )

    // --- Helper lambdas (closures over state) ---

    fun appendSystemMessage(text: String) = feed.appendSystemMessage(text)

    fun showCommandInput(text: String) = feed.showCommandInput(text)

    fun appendCommandResult(text: String) = feed.appendCommandResult(text)

    val extensionUi = remember { TuiExtensionUi(state, editor, feed, promptScope) }
    BindExtensionUi(extensionContext, extensionUi)

    fun tryExpandFileCommand(text: String): String? {
        return SlashCommandExpander.expand(text, fileSlashCommands)
    }

    val extensionSessionControl = remember {
        TuiExtensionSessionControl(state, client, sessionController, sessionsDir, cwd, promptScope)
    }
    BindExtensionSessionControl(extensionContext, extensionSessionControl)

    val navigator = remember {
        OverlayNavigator(
            state = state,
            feed = feed,
            client = client,
            models = modelController,
            providerAuth = providerAuthController,
            agentPanel = agentPanelController,
            session = sessionController,
            editor = editor,
            scope = promptScope,
            cwd = cwd,
            sessionsDir = sessionsDir,
            instructionFiles = instructionFiles,
            backgroundAgents = backgroundAgents,
            agentRegistry = agentRegistry,
            scheduler = scheduler,
            availableThemes = availableThemes,
            fileSlashCommands = fileSlashCommands,
            getAllProviders = getAllProviders,
            onSettingsChanged = onSettingsChanged,
        )
    }
    val submitDispatcher = remember {
        SubmitDispatcher(
            state = state,
            feed = feed,
            navigator = navigator,
            conversation = conversationController,
            compaction = compactionController,
            session = sessionController,
            models = modelController,
            client = client,
            cwd = cwd,
            scope = promptScope,
            reloadExtensions = reloadExtensions,
            processInput = processInput,
        )
    }

    // --- Configure agent on first composition ---

    LaunchedEffect(Unit) {
        client.setThinkingLevel(initialThinkingLevel)
        initialModel?.let(client::setModel)
        initialUserMessage?.let { message ->
            chatHistoryState.appendMessage(message)
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

    AgentEventCollector(state, client, conversationController, compactionController)
    SpinnerTicker(state)
    AgentTranscriptMirror(state, backgroundAgents)
    PushNotificationPump(state, backgroundAgents, conversationController)
    ResumeHintTracker(state)

    // --- Render the editor ---

    // Read editorVersion to trigger recomposition when the editor state changes.
    @Suppress("UNUSED_EXPRESSION")
    editorVersion
    val editorResult = editor.render(width = editorContentWidth, height = layout.baseInputHeight)

    // --- Key event handler ---

    // Apply the current slash-command popup selection (when submitting after a popup), then submit
    // the editor contents through the parser and dispatcher.
    fun submitInput(applyPopupFirst: Boolean, event: KeyboardEvent) {
        if (applyPopupFirst) {
            editor.handle(event)
            editorVersion++
        }
        val text = editor.text().trimEnd()
        if (text.isBlank()) {
            editor.setText("")
            editorVersion++
            return
        }
        // Record the submission so Up-arrow / Ctrl+P recall previous prompts.
        promptHistory.add(text)
        editor.setHistory(promptHistory.toList())
        editor.setText("")
        editorVersion++
        submitDispatcher.dispatch(
            parseSubmission(text, extensionCommands, fileSlashCommands),
            activeTheme,
            themeAppearance,
            setActiveTheme,
            setThemeAppearance,
        )
    }

    fun applyIntent(intent: TuiIntent?, event: KeyboardEvent): Boolean {
        // Every key that reaches the keymap clears the Ctrl+C armed state, except the arming key
        // itself and the submit path (which never resets it in the interactive flow).
        if (intent !is TuiIntent.Submit && intent !is TuiIntent.SubmitAfterPopup && intent !is TuiIntent.ArmCtrlC) {
            ctrlCArmed = false
        }
        when (intent) {
            null -> return false
            TuiIntent.InterruptStreaming -> {
                client.abort()
                appendSystemMessage("Interrupted current response.")
            }
            TuiIntent.ArmCtrlC -> {
                ctrlCArmed = true
                appendSystemMessage("Press Ctrl+C again to exit.")
            }
            TuiIntent.Quit -> state.quit(client)
            TuiIntent.ExitFocusMode -> viewingAgentId = null
            TuiIntent.ClearChat -> chatHistoryState.entries.clear()
            TuiIntent.ToggleThinking -> {
                val next = if (thinkingLevel == AgentThinkingLevel.OFF) {
                    AgentThinkingLevel.LOW
                } else {
                    AgentThinkingLevel.OFF
                }
                modelController.setThinkingLevel(next)
            }
            is TuiIntent.CycleModel -> modelController.cycleModel(intent.direction)
            TuiIntent.OpenSettings ->
                navigator.openSettingsOverlay(activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
            TuiIntent.ToggleThinkingBlock -> {
                if (!chatHistoryState.toggleLatestThinkingCollapsed()) {
                    appendSystemMessage("No thinking block available to toggle.")
                }
            }
            TuiIntent.ToggleToolBlock -> {
                if (!chatHistoryState.toggleLatestToolCollapsed()) {
                    appendSystemMessage("No tool execution available to toggle.")
                }
            }
            is TuiIntent.ScrollUp -> activeChat().scrollUp(intent.lines)
            is TuiIntent.ScrollDown -> activeChat().scrollDown(intent.lines)
            is TuiIntent.RunExtensionShortcut -> {
                val context = extensionContext
                if (context != null) {
                    promptScope.launch {
                        runCatching { intent.shortcut.handler.run(context) }
                            .onFailure { error -> appendCommandResult(error.message ?: error.toString()) }
                    }
                }
            }
            TuiIntent.PassToEditor -> {
                editor.handle(event)
                editorVersion++
            }
            TuiIntent.Submit -> submitInput(applyPopupFirst = false, event = event)
            TuiIntent.SubmitAfterPopup -> submitInput(applyPopupFirst = true, event = event)
        }
        return true
    }

    // --- Rendering ---

    val backgroundModifier = if (baseTheme.background.isSpecifiedColor) {
        Modifier.background(baseTheme.background)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .height(height)
            .fillMaxWidth()
            .then(backgroundModifier)
            .onKeyEvent { event ->
                if (overlayHostState.hasOverlay) {
                    // Let overlay handle via its own Modifier.onKeyEvent
                    return@onKeyEvent false
                }
                val keyboardEvent = event.toKeyboardEvent()
                val context = KeyContext(
                    isStreaming = isStreaming,
                    isViewingAgent = viewingAgentId != null,
                    ctrlCArmed = ctrlCArmed,
                    editorBlank = editor.text().isBlank(),
                    hasAutocompletePopup = editor.hasAutocompletePopup(),
                    slashPopupItemCount = editor.slashCommandPopupItemCount(),
                    extensionShortcuts = extensionShortcuts,
                    hasExtensionContext = extensionContext != null,
                )
                applyIntent(KeyBindings.resolve(keyboardEvent, context), keyboardEvent)
            },
    ) {
        CompositionLocalProvider(LocalThemeConfig provides baseTheme) {
            Agent47Screen(
                state = state,
                layout = layout,
                width = width,
                runningAgents = runningAgents,
                cwd = cwd,
                editor = editor,
                editorResult = editorResult,
                markdownRenderer = markdownRenderer,
                diffRenderer = diffRenderer,
                statusBarState = statusBarState,
                baseTheme = baseTheme,
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

/**
 * Launches the interactive TUI. This is the primary entry point
 * for the interactive mode.
 */
public fun runTui(
    client: AgentClient,
    configuration: TuiLaunchConfiguration = TuiLaunchConfiguration(),
    providerServices: TuiProviderServices = TuiProviderServices(),
    conversationServices: TuiConversationServices = TuiConversationServices(),
    subagentServices: TuiSubagentServices = TuiSubagentServices(),
) {
    TerminalSession.runInTerminalSession(configuration.theme) {
        Agent47App(
            client = client,
            configuration = configuration,
            providerServices = providerServices,
            conversationServices = conversationServices,
            subagentServices = subagentServices,
        )
    }
}
