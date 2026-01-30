package co.agentmode.agent47.gui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.application
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.auth.OAuthAuthorization
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.compaction.CompactionResult
import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import co.agentmode.agent47.coding.core.instructions.InstructionFile
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.gui.components.ChatPanel
import co.agentmode.agent47.gui.components.EditorPanel
import co.agentmode.agent47.gui.components.GuiOverlayHost

import co.agentmode.agent47.gui.components.GuiTaskBar
import co.agentmode.agent47.gui.components.SettingsDialog
import co.agentmode.agent47.gui.components.Sidebar
import co.agentmode.agent47.gui.theme.Agent47Theme
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.HorizontalSplitLayout
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.SplitLayoutState
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.rememberSplitLayoutState
import org.jetbrains.jewel.window.DecoratedWindow
import org.jetbrains.jewel.window.TitleBar
import org.jetbrains.jewel.window.newFullscreenControls
import java.nio.file.Path

/**
 * Entry point for the desktop GUI. Receives the same parameters as the TUI
 * so it has full access to the agent client, models, session, and settings.
 */
public fun runGui(
    client: AgentClient,
    initialUserMessage: UserMessage? = null,
    availableModels: List<Model> = emptyList(),
    sessionManager: SessionManager? = null,
    sessionsDir: Path? = null,
    cwd: Path = Path.of(System.getProperty("user.dir")),
    initialThinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    initialModel: Model? = null,
    fileCommands: List<SlashCommand> = emptyList(),
    getAllProviders: () -> List<ProviderInfo> = { emptyList() },
    storeApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit = { _, _ -> },
    refreshModels: () -> List<Model> = { availableModels },
    authorizeOAuth: suspend (provider: String) -> OAuthAuthorization? = { null },
    pollOAuthToken: suspend (provider: String) -> OAuthResult? = { null },
    onSettingsChanged: (transform: (Settings) -> Settings) -> Unit = {},
    todoState: TodoState? = null,
    instructionFiles: List<InstructionFile> = emptyList(),
    compactContext: (suspend (List<Message>, Model) -> CompactionResult?)? = null,
    compactionSettings: CompactionSettings = CompactionSettings(),
) {
    application {
        Agent47Theme(isDark = true) {
            DecoratedWindow(
                onCloseRequest = { exitApplication() },
                title = "Agent 47",
            ) {
                val scope = rememberCoroutineScope()
                val chatListState = rememberLazyListState()
                val controller = remember {
                    GuiAppController(
                        client = client,
                        initialUserMessage = initialUserMessage,
                        availableModels = availableModels,
                        sessionManager = sessionManager,
                        sessionsDir = sessionsDir,
                        cwd = cwd,
                        initialThinkingLevel = initialThinkingLevel,
                        initialModel = initialModel,
                        fileCommands = fileCommands,
                        getAllProviders = getAllProviders,
                        storeApiKey = storeApiKey,
                        storeOAuthCredential = storeOAuthCredential,
                        refreshModels = refreshModels,
                        authorizeOAuth = authorizeOAuth,
                        pollOAuthToken = pollOAuthToken,
                        onSettingsChanged = onSettingsChanged,
                        todoState = todoState,
                        instructionFiles = instructionFiles,
                        compactContext = compactContext,
                        compactionSettings = compactionSettings,
                    )
                }

                LaunchedEffect(scope) { controller.bindScope(scope) }
                LaunchedEffect(Unit) { controller.collectEvents() }
                LaunchedEffect(Unit) { controller.submitInitialMessage(scope) }

                // Spinner animation while streaming
                LaunchedEffect(controller.isStreaming) {
                    if (!controller.isStreaming) return@LaunchedEffect
                    while (true) {
                        delay(80L)
                        controller.tickSpinner()
                    }
                }

                // Title bar
                TitleBar(Modifier.newFullscreenControls()) {
                    Text(
                        text = title,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        style = JewelTheme.defaultTextStyle,
                    )
                    IconButton(
                        onClick = { controller.openSettingsOverlay() },
                        modifier = Modifier.align(Alignment.End).size(28.dp),
                    ) {
                        Icon(
                            imageVector = Tabler.Outline.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(14.dp),
                            tint = AppColors.textMuted,
                        )
                    }
                }

                val backgroundColor = AppColors.panelBackground
                val splitState = rememberSplitLayoutState(0.15f)

                // Main layout: sidebar | chat area
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                            if (controller.overlayHostState.hasOverlay) {
                                if (keyEvent.key == Key.Escape) {
                                    controller.overlayHostState.dismissTop()
                                    return@onPreviewKeyEvent true
                                }
                                return@onPreviewKeyEvent false
                            }

                            if (keyEvent.isCtrlPressed && keyEvent.key == Key.C) {
                                controller.interruptAgent()
                                return@onPreviewKeyEvent true
                            }

                            if (keyEvent.key == Key.Escape && controller.isStreaming) {
                                controller.interruptAgent()
                                return@onPreviewKeyEvent true
                            }

                            if (keyEvent.isCtrlPressed) {
                                when (keyEvent.key) {
                                    Key.T -> {
                                        val next = if (controller.thinkingLevel == AgentThinkingLevel.OFF) {
                                            AgentThinkingLevel.LOW
                                        } else {
                                            AgentThinkingLevel.OFF
                                        }
                                        controller.setThinkingLevel(next)
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.P -> { controller.cycleModel(-1); return@onPreviewKeyEvent true }
                                    Key.N -> { controller.cycleModel(1); return@onPreviewKeyEvent true }
                                    Key.O -> { controller.openSettingsOverlay(); return@onPreviewKeyEvent true }
                                    Key.G -> {
                                        controller.chatHistoryState.toggleLatestThinkingCollapsed()
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.E -> {
                                        controller.chatHistoryState.toggleLatestToolCollapsed()
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.R -> { controller.openSubAgentResultOverlay(); return@onPreviewKeyEvent true }
                                    Key.L -> { controller.chatHistoryState.entries.clear(); return@onPreviewKeyEvent true }
                                    Key.U -> {
                                        scope.launch { chatListState.animateScrollToItem(maxOf(0, chatListState.firstVisibleItemIndex - 12)) }
                                        return@onPreviewKeyEvent true
                                    }
                                    Key.D -> {
                                        scope.launch {
                                            val target = minOf(chatListState.firstVisibleItemIndex + 12, maxOf(0, chatListState.layoutInfo.totalItemsCount - 1))
                                            chatListState.animateScrollToItem(target)
                                        }
                                        return@onPreviewKeyEvent true
                                    }
                                    else -> {}
                                }
                            }

                            when (keyEvent.key) {
                                Key.PageUp -> {
                                    scope.launch { chatListState.animateScrollToItem(maxOf(0, chatListState.firstVisibleItemIndex - 12)) }
                                    true
                                }
                                Key.PageDown -> {
                                    scope.launch {
                                        val target = minOf(chatListState.firstVisibleItemIndex + 12, maxOf(0, chatListState.layoutInfo.totalItemsCount - 1))
                                        chatListState.animateScrollToItem(target)
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                ) {
                    HorizontalSplitLayout(
                        first = {
                            Sidebar(
                                onNewSession = { controller.startNewSession() },
                                onOpenSkills = { controller.openSkillsOverlay() },
                                loadSessionGroups = { controller.loadSessionGroups() },
                                onLoadSession = { path -> controller.loadSessionByPath(path) },
                                currentCwd = cwd.toString(),
                                modifier = Modifier.fillMaxHeight(),
                            )
                        },
                        second = {
                            Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    ChatPanel(
                                        state = controller.chatHistoryState,
                                        listState = chatListState,
                                        isStreaming = controller.isStreaming,
                                        activityLabel = controller.liveActivityLabel,
                                        cwd = cwd.toString().replace(System.getProperty("user.home"), "~"),
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }

                                GuiTaskBar(
                                    state = controller.taskBarState,
                                    isStreaming = controller.isStreaming,
                                    activityLabel = controller.liveActivityLabel,
                                )

                                EditorPanel(
                                    modifier = Modifier.fillMaxWidth(),
                                    onSubmit = { text -> controller.handleSubmit(text.trimEnd(), scope) },
                                    slashCommands = controller.slashCommands.map { it.command },
                                    slashCommandDetails = controller.slashCommands.associate { spec ->
                                        spec.command.removePrefix("/") to spec.description
                                    },
                                    cwd = cwd,
                                    modelLabel = controller.currentModel?.id ?: "No model",
                                    models = controller.currentModels,
                                    selectedModelIndex = controller.selectedModelIndex,
                                    onSelectModel = { model -> controller.applyModel(model) },
                                    thinkingLevel = controller.thinkingLevel,
                                    onSelectThinking = { level -> controller.setThinkingLevel(level) },
                                )
                            }
                        },
                        firstPaneMinWidth = 160.dp,
                        secondPaneMinWidth = 400.dp,
                        state = splitState,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Overlay host (renders on top of everything)
                GuiOverlayHost(state = controller.overlayHostState)

                // Settings dialog
                if (controller.settingsDialogVisible) {
                    SettingsDialog(
                        models = controller.currentModels,
                        selectedModelIndex = controller.selectedModelIndex,
                        thinkingLevel = controller.thinkingLevel,
                        providers = controller.getProviders(),
                        onSelectModel = { model -> controller.applyModel(model) },
                        onSelectThinking = { level -> controller.setThinkingLevel(level) },
                        onConnectProvider = { info -> controller.connectProvider(info) },
                        onDismiss = { controller.settingsDialogVisible = false },
                    )
                }
            }
        }
    }
}
