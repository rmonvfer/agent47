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
import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.input.keyboardShortcutName
import co.agentmode.agent47.tui.input.toKeyboardEvent
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
 * The session the interactive app is currently in. Read by the terminal-restore shutdown hook —
 * the app exits via exitProcess(), so that hook is the only reliable place to print on the way out.
 */
private val activeResumeSession = AtomicReference<SessionManager?>(null)

/** Prints a "resume this session" hint to [out] once the terminal has been restored. */
private fun printResumeHint(out: java.io.PrintStream) {
    val session = activeResumeSession.get() ?: return
    val hasContent = runCatching { session.getEntries().any { it is SessionMessageEntry } }.getOrDefault(false)
    if (!hasContent) return
    val id = runCatching { session.getHeader().id }.getOrNull() ?: return
    runCatching {
        out.write("\nTo resume this session:  agent47 --resume $id\n".toByteArray())
        out.flush()
    }
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

    val statusHeight = 2 // ohm-style two-line footer
    val editorPromptWidth = 2 // "❯ " or "! "
    val editorContentWidth = (width - editorPromptWidth).coerceAtLeast(1)
    val visualLineCount = WordWrap.createMapping(editor.state.lines, editorContentWidth).visualLines.size
    val baseInputHeight = min(8, max(1, visualLineCount))
    val popupItemCount = editor.slashCommandPopupItemCount()
    val popupRowCount = min(8, popupItemCount)
    val popupHeight = if (popupRowCount > 0) popupRowCount + (if (popupItemCount > 8) 1 else 0) + 1 else 0
    val taskBarHeight = if (taskBarState.visible) taskBarState.lineCount else 0
    val activityHeight = if (isStreaming && !taskBarState.visible) 2 else 0
    val marginHeight = 1
    val borderHeight = 2
    val runningAgents = backgroundAgents?.runningStatus().orEmpty()
    val backgroundPanelHeight = if (runningAgents.isEmpty()) {
        0
    } else {
        val runningCount = runningAgents.count { it.status == RunningAgent.Status.RUNNING }
        val queuedCount = runningAgents.count { it.status == RunningAgent.Status.QUEUED }
        // Leading blank line + header + running rows + optional queued line (matches the panel).
        2 + runningCount + if (queuedCount > 0) 1 else 0
    }
    val historyHeight = max(1, height - statusHeight - borderHeight - popupHeight - baseInputHeight - activityHeight - taskBarHeight - marginHeight - backgroundPanelHeight)

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

    // --- Agent event collection ---

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        client.events.collect { event ->
            conversationController.onAgentEvent(event)
            compactionController.maybeAutoCompactAfter(event)
        }
    }

    // --- Refresh ticker for spinner animation ---

    LaunchedEffect(isStreaming) {
        if (!isStreaming) return@LaunchedEffect
        while (true) {
            delay(80L)
            spinnerFrame++
        }
    }

    // Rebuild the focused agent's transcript from its live messages while focus mode is active.
    LaunchedEffect(viewingAgentId) {
        val id = viewingAgentId ?: return@LaunchedEffect
        while (viewingAgentId == id) {
            val ref = backgroundAgents?.runningStatus()?.firstOrNull { it.id == id }?.agentRef
            if (ref != null) {
                viewingChatState.entries.clear()
                ref.state.messages.forEach { viewingChatState.appendMessage(it) }
            } else {
                break
            }
            delay(200L)
        }
    }

    // Keep the background-agents panel (and its elapsed times) live while agents run, even
    // when the main loop is idle between turns.
    if (backgroundAgents != null) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(100L)
                if (backgroundAgents.hasRunning()) spinnerFrame++
                conversationController.deliverPushQueue()
            }
        }
    }

    // Track the active session so the shutdown hook can print a resume hint on exit.
    LaunchedEffect(activeSessionManager) {
        activeResumeSession.set(activeSessionManager)
    }

    // --- Render the editor ---

    // Read editorVersion to trigger recomposition when the editor state changes.
    @Suppress("UNUSED_EXPRESSION")
    editorVersion
    val editorResult = editor.render(width = editorContentWidth, height = baseInputHeight)

    // --- Key event handler ---

    fun handleKeyEvent(keyEvent: KeyboardEvent): Boolean {
        // Overlay gets first priority
        // (Note: overlay key handling is done via Modifier.onKeyEvent on OverlayHost/SelectDialog)

        // Ctrl+C handling
        if (keyEvent.ctrl && keyEvent.key is Key.Character && keyEvent.key.value == 'c') {
            if (isStreaming) {
                client.abort()
                appendSystemMessage("Interrupted current response.")
                ctrlCArmed = false
                return true
            }
            if (ctrlCArmed) {
                state.quit(client)
            }
            ctrlCArmed = true
            appendSystemMessage("Press Ctrl+C again to exit.")
            return true
        }
        ctrlCArmed = false

        // ESC handling — leave agent-transcript focus, else interrupt the orchestrator when streaming.
        // Interrupting only aborts the orchestrator's own loop; background agents run on a separate
        // supervisor scope and keep going.
        if (keyEvent.key is Key.Escape) {
            if (viewingAgentId != null) {
                viewingAgentId = null
                return true
            }
            if (isStreaming) {
                client.abort()
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
                    modelController.setThinkingLevel(next)
                    return true
                }

                'p' -> {
                    modelController.cycleModel(direction = -1)
                    return true
                }

                'n' -> {
                    modelController.cycleModel(direction = 1)
                    return true
                }

                'o' -> {
                    navigator.openSettingsOverlay(activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
                    return true
                }

                'g' -> {
                    if (!chatHistoryState.toggleLatestThinkingCollapsed()) {
                        appendSystemMessage("No thinking block available to toggle.")
                    }
                    return true
                }

                'e' -> {
                    // With text in the editor, Ctrl+E is move-to-end-of-line; only act globally
                    // when the editor is empty so the line-editing shortcut isn't shadowed.
                    if (editor.text().isBlank()) {
                        if (!chatHistoryState.toggleLatestToolCollapsed()) {
                            appendSystemMessage("No tool execution available to toggle.")
                        }
                        return true
                    }
                }

                'u' -> {
                    // With text in the editor, Ctrl+U is kill-to-start-of-line; only scroll the
                    // chat globally when the editor is empty.
                    if (editor.text().isBlank()) {
                        activeChat().scrollUp(12)
                        return true
                    }
                }

                'd' -> {
                    activeChat().scrollDown(12)
                    return true
                }
            }
        }

        val shortcut = keyboardShortcutName(keyEvent)?.let { pressed ->
            extensionShortcuts.firstOrNull { it.key == pressed }
        }
        if (shortcut != null && extensionContext != null) {
            promptScope.launch {
                runCatching { shortcut.handler.run(extensionContext) }
                    .onFailure { error -> appendCommandResult(error.message ?: error.toString()) }
            }
            return true
        }

        // Scroll shortcuts
        if ((keyEvent.ctrl || keyEvent.shift) && keyEvent.key == Key.ArrowUp) {
            activeChat().scrollUp(3)
            return true
        }
        if ((keyEvent.ctrl || keyEvent.shift) && keyEvent.key == Key.ArrowDown) {
            activeChat().scrollDown(3)
            return true
        }
        if (keyEvent.key == Key.PageUp && !keyEvent.alt && !keyEvent.ctrl) {
            activeChat().scrollUp(12)
            return true
        }
        if (keyEvent.key == Key.PageDown && !keyEvent.alt && !keyEvent.ctrl) {
            activeChat().scrollDown(12)
            return true
        }
        if (keyEvent.alt && keyEvent.key == Key.PageUp) {
            activeChat().scrollUp(10)
            return true
        }
        if (keyEvent.alt && keyEvent.key == Key.PageDown) {
            activeChat().scrollDown(10)
            return true
        }

        // Arrow up/down scroll when editor is empty
        if (editor.text().isBlank()) {
            if (keyEvent.key == Key.ArrowUp && !keyEvent.ctrl && !keyEvent.alt) {
                activeChat().scrollUp(3)
                return true
            }
            if (keyEvent.key == Key.ArrowDown && !keyEvent.ctrl && !keyEvent.alt) {
                activeChat().scrollDown(3)
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
                    // Record the submission so Up-arrow / Ctrl+P recall previous prompts.
                    promptHistory.add(text)
                    editor.setHistory(promptHistory.toList())
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
                        extensionCommands = extensionCommands,
                        cwd = cwd,
                        activeSessionManager = activeSessionManager,
                        promptScope = promptScope,
                        isStreaming = isStreaming,
                        submit = conversationController::submitMessage,
                        appendSystemMessage = ::appendSystemMessage,
                        showCommandInput = ::showCommandInput,
                        appendCommandResult = ::appendCommandResult,
                        openModelOverlay = { navigator.openModelOverlay() },
                        openSessionOverlay = { navigator.openSessionOverlay() },
                        openSettingsOverlay = {
                            navigator.openSettingsOverlay(activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
                        },
                        openCommandsOverlay = { navigator.openCommandsOverlay() },
                        openThemeOverlay = { navigator.openThemeOverlay(activeTheme, themeAppearance, setActiveTheme) },
                        openProviderOverlay = { navigator.openProviderOverlay() },
                        openMemoryOverlay = { navigator.openMemoryOverlay() },
                        openAgentsOverlay = { navigator.openAgentsOverlay() },
                        setModelById = { modelId ->
                            val match = currentModels.firstOrNull { model ->
                                model.id.equals(modelId, ignoreCase = true) ||
                                        "${model.provider.value}/${model.id}".equals(modelId, ignoreCase = true)
                            }
                            if (match == null) {
                                appendCommandResult("Model not found: $modelId")
                            } else {
                                modelController.applyModel(match)
                            }
                        },
                        startNewSession = sessionController::startNewSession,
                        tryExpandFileCommand = ::tryExpandFileCommand,
                        setRunning = { value ->
                            if (!value) {
                                state.quit(client)
                            }
                        },
                        runCompaction = compactionController::runCompaction,
                        reloadExtensions = {
                            val resources = reloadExtensions()
                            extensionCommands = resources.commands
                            extensionShortcuts = resources.shortcuts
                            extensionToolRenderers = resources.toolRenderers
                            extensionMessageRenderers = resources.messageRenderers
                            "Reloaded ${resources.commands.size} extension command${if (resources.commands.size == 1) "" else "s"} " +
                                "and ${resources.shortcuts.size} shortcut${if (resources.shortcuts.size == 1) "" else "s"}."
                        },
                        processInput = processInput,
                    )
                    return@onKeyEvent true
                }

                handleKeyEvent(keyboardEvent)
            },
    ) {
        CompositionLocalProvider(LocalThemeConfig provides baseTheme) {
            Column {
                // Chat history viewport — the conversation, or a background agent's transcript in focus mode.
                val viewing = viewingAgentId
                if (viewing != null) {
                    Text("▶ Viewing agent $viewing  ·  Esc to return")
                    ChatHistory(
                        state = viewingChatState,
                        width = width,
                        height = (historyHeight - 1).coerceAtLeast(1),
                        markdownRenderer = markdownRenderer,
                        diffRenderer = diffRenderer,
                        version = viewingChatState.version,
                        spinnerFrame = spinnerFrame,
                        cwd = cwd.toString().replace(System.getProperty("user.home"), "~"),
                        toolRenderers = extensionToolRenderers,
                        messageRenderers = extensionMessageRenderers,
                    )
                } else {
                    ChatHistory(
                        state = chatHistoryState,
                        width = width,
                        height = historyHeight,
                        markdownRenderer = markdownRenderer,
                        diffRenderer = diffRenderer,
                        version = chatHistoryState.version,
                        spinnerFrame = spinnerFrame,
                        cwd = cwd.toString().replace(System.getProperty("user.home"), "~"),
                        toolRenderers = extensionToolRenderers,
                        messageRenderers = extensionMessageRenderers,
                    )
                }

                // Activity line (spinner while streaming, only when no task bar)
                if (isStreaming && !taskBarState.visible) {
                    Text("")
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

                // Live background sub-agents launched via the task tool
                BackgroundAgentsPanel(
                    agents = runningAgents,
                    width = width,
                    spinnerFrame = spinnerFrame,
                    mode = subagentsSettingsState.widgetMode,
                )

                extensionWidgets.values.flatten().forEach { line ->
                    Text(line.take(width))
                }
                val extensionStatus = buildList {
                    extensionTitle?.let(::add)
                    addAll(extensionStatuses.values)
                }.joinToString("  ")
                if (extensionStatus.isNotBlank()) {
                    Text(extensionStatus.take(width))
                }

                // Margin between chat area and editor border
                Text("")

                val bashMode = editor.text().trimStart().startsWith("!")

                // Editor top border
                EditorBorder(width, bashMode, thinkingLevel)

                // Slash command popup (between border and input)
                val autocompleteModel = editorResult.autocomplete
                if (autocompleteModel != null && autocompleteModel.items.isNotEmpty()) {
                    AutocompletePopup(
                        model = autocompleteModel,
                        maxWidth = width,
                        theme = baseTheme,
                    )
                    Text("")
                }

                // Editor view
                EditorView(
                    result = editorResult,
                    width = width,
                    height = baseInputHeight,
                )

                // Editor bottom border
                EditorBorder(width, bashMode, thinkingLevel)

                // Status bar
                StatusBar(
                    state = statusBarState,
                    width = width,
                )
            }
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

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun handleSubmit(
    rawInput: String,
    client: AgentClient,
    editor: Editor,
    chatHistoryState: ChatHistoryState,
    overlayHostState: OverlayHostState,
    slashCommands: List<SlashCommandSpec>,
    fileSlashCommands: List<SlashCommand>,
    extensionCommands: List<RegisteredCommand>,
    cwd: Path,
    activeSessionManager: SessionManager?,
    promptScope: CoroutineScope,
    isStreaming: Boolean,
    submit: (UserMessage) -> Unit,
    appendSystemMessage: (String) -> Unit,
    showCommandInput: (String) -> Unit,
    appendCommandResult: (String) -> Unit,
    openModelOverlay: () -> Unit,
    openSessionOverlay: () -> Unit,
    openSettingsOverlay: () -> Unit,
    openCommandsOverlay: () -> Unit,
    openThemeOverlay: () -> Unit,
    openProviderOverlay: () -> Unit,
    openMemoryOverlay: () -> Unit,
    openAgentsOverlay: () -> Unit,
    setModelById: (String) -> Unit,
    startNewSession: () -> Unit,
    tryExpandFileCommand: (String) -> String?,
    setRunning: (Boolean) -> Unit,
    runCompaction: () -> Unit = {},
    reloadExtensions: suspend () -> String,
    processInput: suspend (InputEvent) -> InputHookResult,
) {
    when {
        rawInput.startsWith("/") -> {
            val tokens = rawInput.trim().split(Regex("\\s+"))
            val command = tokens.firstOrNull().orEmpty()
            val args = tokens.drop(1)

            when (command) {
                "/help" -> {
                    showCommandInput(rawInput)
                    appendCommandResult(helpText(slashCommands))
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
                "/exit" -> setRunning(false)
                "/model" -> {
                    showCommandInput(rawInput)
                    if (args.isEmpty()) {
                        openModelOverlay()
                    } else {
                        setModelById(args.joinToString(" "))
                    }
                }

                "/theme" -> {
                    showCommandInput(rawInput)
                    openThemeOverlay()
                }
                "/provider" -> {
                    showCommandInput(rawInput)
                    openProviderOverlay()
                }
                "/compact" -> {
                    showCommandInput(rawInput)
                    runCompaction()
                }
                "/reload" -> {
                    showCommandInput(rawInput)
                    if (isStreaming) {
                        appendCommandResult("Wait for the current response before reloading extensions.")
                    } else {
                        promptScope.launch {
                            runCatching { reloadExtensions() }
                                .onSuccess(appendCommandResult)
                                .onFailure { error -> appendCommandResult(error.message ?: error.toString()) }
                        }
                    }
                }
                "/memory" -> {
                    showCommandInput(rawInput)
                    openMemoryOverlay()
                }
                "/settings" -> {
                    showCommandInput(rawInput)
                    openSettingsOverlay()
                }
                "/agents" -> {
                    showCommandInput(rawInput)
                    openAgentsOverlay()
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
                    val extensionCommand = extensionCommands.firstOrNull {
                        "/${it.name}".equals(command, ignoreCase = true)
                    }
                    val expanded = tryExpandFileCommand(rawInput)
                    if (extensionCommand != null) {
                        showCommandInput(rawInput)
                        if (isStreaming) {
                            appendCommandResult("Wait for the current response before running extension commands.")
                        } else {
                            val rawArgs = rawInput.trim().removePrefix(command).trimStart()
                            promptScope.launch {
                                val context = object : ExtensionCommandContext {
                                    override val cwd: Path = cwd
                                    override val hasUi: Boolean = true

                                    override fun notify(message: String) {
                                        appendCommandResult(message)
                                    }

                                    override suspend fun sendUserMessage(message: String) {
                                        val userMessage = UserMessage(
                                            content = listOf(TextContent(text = message)),
                                            timestamp = System.currentTimeMillis(),
                                        )
                                        chatHistoryState.appendMessage(userMessage)
                                        activeSessionManager?.appendMessage(userMessage)
                                        client.prompt(listOf(userMessage))
                                        client.waitForIdle()
                                    }

                                    override suspend fun reload() {
                                        notify(reloadExtensions())
                                    }
                                }
                                runCatching { extensionCommand.handler.run(rawArgs, context) }
                                    .onFailure { error -> appendCommandResult(error.message ?: error.toString()) }
                            }
                        }
                    } else if (expanded != null) {
                        val message = UserMessage(
                            content = listOf(TextContent(text = expanded)),
                            timestamp = System.currentTimeMillis(),
                        )
                        submit(message)
                    } else {
                        showCommandInput(rawInput)
                        appendCommandResult("Unknown command: $command")
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
            promptScope.launch {
                val behavior = if (isStreaming) InputStreamingBehavior.FOLLOW_UP else null
                when (val result = processInput(InputEvent(rawInput, InputSource.INTERACTIVE, behavior))) {
                    InputHookResult.Handled -> Unit
                    InputHookResult.Continue -> submit(
                        UserMessage(
                            content = listOf(TextContent(text = rawInput)),
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                    is InputHookResult.Transform -> submit(
                        UserMessage(
                            content = listOf(TextContent(text = result.text)),
                            timestamp = System.currentTimeMillis(),
                        ),
                    )
                }
            }
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
    val out = System.out
    val restoreTerminal = "\u001b[<u\u001b[?25h\u001b[?1049l"

    // Install a shutdown hook so that exitProcess() (or any JVM shutdown) restores
    // the terminal from alternate screen / kitty keyboard mode.
    val shutdownHook = Thread({
        out.write(restoreTerminal.toByteArray())
        out.flush()
        printResumeHint(out)
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

    // Fill the alternate screen with the theme background color. Mosaic renders
    // rows-1 rows of content (to avoid a scroll caused by the trailing \r\n it
    // appends after every row). Pre-filling ensures the unused last terminal row
    // matches the app background instead of showing the terminal's native color.
    if (configuration.theme.background.isSpecifiedColor) {
        val (r, g, b) = configuration.theme.background
        val red = (r * 255).toInt()
        val green = (g * 255).toInt()
        val blue = (b * 255).toInt()
        out.write("\u001b[48;2;${red};${green};${blue}m\u001b[2J\u001b[H\u001b[0m".toByteArray())
        out.flush()
    }
    try {
        runMosaicMain {
            Agent47App(
                client = client,
                configuration = configuration,
                providerServices = providerServices,
                conversationServices = conversationServices,
                subagentServices = subagentServices,
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
