package co.agentmode.agent47.tui

import androidx.compose.runtime.Composable
import co.agentmode.agent47.coding.core.agents.RunningAgent
import co.agentmode.agent47.tui.components.ActivityLine
import co.agentmode.agent47.tui.components.AutocompletePopup
import co.agentmode.agent47.tui.components.BackgroundAgentsPanel
import co.agentmode.agent47.tui.components.ChatHistory
import co.agentmode.agent47.tui.components.EditorBorder
import co.agentmode.agent47.tui.components.EditorView
import co.agentmode.agent47.tui.components.MosaicStatusBarState
import co.agentmode.agent47.tui.components.StartupSummary
import co.agentmode.agent47.tui.components.StatusBar
import co.agentmode.agent47.tui.components.TaskBar
import co.agentmode.agent47.tui.components.renderStartupSummary
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.tui.editor.EditorRenderResult
import co.agentmode.agent47.tui.layout.TuiLayout
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import java.nio.file.Path

/**
 * Stateless render tree for the interactive TUI. Reads [TuiAppState] and the per-frame layout,
 * renderers, and editor result; performs no state mutation of its own.
 */
@Composable
internal fun Agent47Screen(
    state: TuiAppState,
    layout: TuiLayout,
    width: Int,
    runningAgents: List<RunningAgent>,
    cwd: Path,
    editor: Editor,
    editorResult: EditorRenderResult,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    statusBarState: MosaicStatusBarState,
    baseTheme: ThemeConfig,
    startupSummary: StartupSummary,
) {
    Column(modifier = Modifier.padding(horizontal = layout.horizontalPadding, vertical = 0)) {
        ChatPane(state, width, layout.historyHeight, markdownRenderer, diffRenderer, cwd, startupSummary)
        FooterPane(state, width, runningAgents)
        EditorPane(
            state,
            editor,
            editorResult,
            width,
            layout.baseInputHeight,
            layout.editorTopMarginHeight,
            baseTheme,
            statusBarState,
        )
    }
}

@Composable
private fun ChatPane(
    state: TuiAppState,
    width: Int,
    historyHeight: Int,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    cwd: Path,
    startupSummary: StartupSummary,
) {
    val theme = LocalThemeConfig.current
    val cwdDisplay = cwd.toString().replace(System.getProperty("user.home"), "~")
    // Chat history viewport - the conversation, or a background agent's transcript in focus mode.
    val viewing = state.viewingAgentId
    if (viewing != null) {
        Text("▶ Viewing agent $viewing  ·  Esc to return", color = theme.markdownText)
        ChatHistory(
            state = state.viewingChat,
            width = width,
            height = (historyHeight - 1).coerceAtLeast(1),
            markdownRenderer = markdownRenderer,
            diffRenderer = diffRenderer,
            version = state.viewingChat.version,
            spinnerFrame = state.spinnerFrame,
            cwd = cwdDisplay,
            toolRenderers = state.extensionToolRenderers,
            messageRenderers = state.extensionMessageRenderers,
        )
    } else {
        ChatHistory(
            state = state.chatHistory,
            width = width,
            height = historyHeight,
            markdownRenderer = markdownRenderer,
            diffRenderer = diffRenderer,
            version = state.chatHistory.version,
            spinnerFrame = state.spinnerFrame,
            cwd = cwdDisplay,
            toolRenderers = state.extensionToolRenderers,
            messageRenderers = state.extensionMessageRenderers,
            introLines = renderStartupSummary(
                summary = startupSummary,
                width = width,
                expanded = state.startupExpanded,
                theme = theme,
            ),
        )
    }
}

@Composable
private fun FooterPane(
    state: TuiAppState,
    width: Int,
    runningAgents: List<RunningAgent>,
) {
    val theme = LocalThemeConfig.current
    // Activity line (spinner while streaming, only when no task bar)
    if (state.isStreaming && !state.taskBar.visible) {
        Text("")
        ActivityLine(
            spinnerFrame = state.spinnerFrame,
            label = state.liveActivityLabel,
            width = width,
        )
    }

    // Task bar (absorbs the activity spinner into its header)
    TaskBar(
        state = state.taskBar,
        width = width,
        isStreaming = state.isStreaming,
        spinnerFrame = state.spinnerFrame,
        activityLabel = state.liveActivityLabel,
    )

    // Live background sub-agents launched via the task tool
    BackgroundAgentsPanel(
        agents = runningAgents,
        width = width,
        spinnerFrame = state.spinnerFrame,
        mode = state.subagentsSettings.widgetMode,
    )

    state.extensionWidgets.values.flatten().forEach { line ->
        Text(line.take(width), color = theme.markdownText)
    }
    val extensionStatus = buildList {
        state.extensionTitle?.let(::add)
        addAll(state.extensionStatuses.values)
    }.joinToString("  ")
    if (extensionStatus.isNotBlank()) {
        Text(extensionStatus.take(width), color = theme.markdownText)
    }
}

@Composable
private fun EditorPane(
    state: TuiAppState,
    editor: Editor,
    editorResult: EditorRenderResult,
    width: Int,
    baseInputHeight: Int,
    topMarginHeight: Int,
    baseTheme: ThemeConfig,
    statusBarState: MosaicStatusBarState,
) {
    if (topMarginHeight > 0) {
        Text("")
    }

    val bashMode = editor.text().trimStart().startsWith("!")

    // Editor top border
    EditorBorder(width, bashMode, state.thinkingLevel)

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
    EditorBorder(width, bashMode, state.thinkingLevel)

    // Status bar
    StatusBar(
        state = statusBarState,
        width = width,
    )
}
