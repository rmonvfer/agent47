package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.ext.core.SessionStartReason
import co.agentmode.agent47.tui.session.loadSession
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Owns session lifecycle: transitioning between sessions, starting a fresh one, and loading a saved
 * session into the client, chat, and model/thinking state.
 */
@Stable
internal class SessionController(
    private val state: TuiAppState,
    private val client: AgentClient,
    private val feed: TranscriptFeed,
    private val models: ModelController,
    private val sessionsDir: Path?,
    private val scope: CoroutineScope,
    private val backgroundAgents: BackgroundAgents?,
    private val onSessionChanged: (SessionManager?) -> Unit,
    private val onSessionTransition: suspend (SessionManager?, SessionManager?, SessionStartReason) -> Unit,
) {
    fun transitionSession(next: SessionManager?, reason: SessionStartReason) {
        val previous = state.activeSessionManager
        state.activeSessionManager = next
        onSessionChanged(next)
        scope.launch {
            onSessionTransition(previous, next, reason)
        }
    }

    fun startNewSession() {
        state.currentPromptJob?.cancel(CancellationException("Starting new session"))
        state.currentPromptJob = null
        backgroundAgents?.cancelAll()
        client.clearMessages()
        state.chatHistory.entries.clear()
        state.toolArgumentsById.clear()
        if (sessionsDir != null) {
            val newPath = sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl")
            val newManager = runCatching { SessionManager(newPath) }.getOrNull()
            transitionSession(newManager, SessionStartReason.NEW)
            feed.appendCommandResult("Started new session")
        } else {
            transitionSession(null, SessionStartReason.NEW)
            feed.appendCommandResult("Started new session (no persistence)")
        }
    }

    fun load(path: Path) {
        loadSession(
            path = path,
            sessionsDir = sessionsDir,
            availableModels = state.currentModels,
            client = client,
            chatHistoryState = state.chatHistory,
            activeSessionManagerSetter = {
                transitionSession(it, SessionStartReason.RESUME)
            },
            applyModel = { model -> models.applyModel(model, recordSessionEntry = false, announce = false) },
            setThinkingLevel = { level ->
                models.setThinkingLevel(level, recordSessionEntry = false, announce = false)
            },
            appendSystemMessage = feed::appendCommandResult,
        )
    }
}
