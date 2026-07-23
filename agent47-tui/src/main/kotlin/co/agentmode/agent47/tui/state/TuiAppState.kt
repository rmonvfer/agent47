package co.agentmode.agent47.tui.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.ext.core.RegisteredCommand
import co.agentmode.agent47.ext.core.RegisteredMessageRenderer
import co.agentmode.agent47.ext.core.RegisteredShortcut
import co.agentmode.agent47.ext.core.RegisteredToolRenderer
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.TaskBarState
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Owns the cross-cutting mutable UI state and the stable sub-holders of the interactive TUI.
 * Snapshot-state fields drive recomposition; the stable holders (chat, overlays, task bar, usage)
 * persist for the lifetime of the app. Feature controllers read and write this state directly.
 */
@Stable
internal class TuiAppState(
    initialModels: List<Model>,
    private val initialModel: Model?,
    initialThinkingLevel: AgentThinkingLevel,
    initialShowUsageFooter: Boolean,
    initialSessionManager: SessionManager?,
    initialSubagentsSettings: SubagentsSettings,
    initialExtensionCommands: List<RegisteredCommand>,
    initialExtensionShortcuts: List<RegisteredShortcut>,
    initialExtensionToolRenderers: List<RegisteredToolRenderer>,
    initialExtensionMessageRenderers: List<RegisteredMessageRenderer>,
) {
    val chatHistory: ChatHistoryState = ChatHistoryState()
    val viewingChat: ChatHistoryState = ChatHistoryState()
    val overlays: OverlayHostState = OverlayHostState()
    val taskBar: TaskBarState = TaskBarState()
    val usage: UsageState = UsageState()
    val toolArgumentsById: MutableMap<String, String> = mutableMapOf()
    val promptHistory: MutableList<String> = mutableListOf()
    val pushQueue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()

    var currentModels: List<Model> by mutableStateOf(initialModels)
    var selectedModelIndex: Int by mutableIntStateOf(
        initialModel?.let { model ->
            initialModels.indexOfFirst { it.id == model.id && it.provider == model.provider }
                .takeIf { it >= 0 }
        } ?: if (initialModels.isNotEmpty()) 0 else -1,
    )
    var thinkingLevel: AgentThinkingLevel by mutableStateOf(initialThinkingLevel)
    var activeSessionManager: SessionManager? by mutableStateOf(initialSessionManager)
    var currentPromptJob: Job? by mutableStateOf(null)
    var running: Boolean by mutableStateOf(true)
    var isStreaming: Boolean by mutableStateOf(false)
    var ctrlCArmed: Boolean by mutableStateOf(false)
    var spinnerFrame: Int by mutableIntStateOf(0)
    var editorVersion: Int by mutableIntStateOf(0)
    var liveActivityLabel: String by mutableStateOf("Thinking")
    var viewingAgentId: String? by mutableStateOf(null)
    var showUsageFooter: Boolean by mutableStateOf(initialShowUsageFooter)
    var subagentsSettings: SubagentsSettings by mutableStateOf(initialSubagentsSettings)
    var extensionCommands: List<RegisteredCommand> by mutableStateOf(initialExtensionCommands)
    var extensionShortcuts: List<RegisteredShortcut> by mutableStateOf(initialExtensionShortcuts)
    var extensionToolRenderers: List<RegisteredToolRenderer> by mutableStateOf(initialExtensionToolRenderers)
    var extensionMessageRenderers: List<RegisteredMessageRenderer> by mutableStateOf(initialExtensionMessageRenderers)
    var extensionStatuses: Map<String, String> by mutableStateOf(emptyMap())
    var extensionWidgets: Map<String, List<String>> by mutableStateOf(emptyMap())
    var extensionTitle: String? by mutableStateOf(null)

    val currentModel: Model?
        get() = currentModels.getOrNull(selectedModelIndex) ?: initialModel

    fun activeChat(): ChatHistoryState = if (viewingAgentId != null) viewingChat else chatHistory
}

@Composable
internal fun rememberTuiAppState(
    initialModels: List<Model>,
    initialModel: Model?,
    initialThinkingLevel: AgentThinkingLevel,
    initialShowUsageFooter: Boolean,
    initialSessionManager: SessionManager?,
    initialSubagentsSettings: SubagentsSettings,
    initialExtensionCommands: List<RegisteredCommand>,
    initialExtensionShortcuts: List<RegisteredShortcut>,
    initialExtensionToolRenderers: List<RegisteredToolRenderer>,
    initialExtensionMessageRenderers: List<RegisteredMessageRenderer>,
): TuiAppState = remember {
    TuiAppState(
        initialModels = initialModels,
        initialModel = initialModel,
        initialThinkingLevel = initialThinkingLevel,
        initialShowUsageFooter = initialShowUsageFooter,
        initialSessionManager = initialSessionManager,
        initialSubagentsSettings = initialSubagentsSettings,
        initialExtensionCommands = initialExtensionCommands,
        initialExtensionShortcuts = initialExtensionShortcuts,
        initialExtensionToolRenderers = initialExtensionToolRenderers,
        initialExtensionMessageRenderers = initialExtensionMessageRenderers,
    )
}
