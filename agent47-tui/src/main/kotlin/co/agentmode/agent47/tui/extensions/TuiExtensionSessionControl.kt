package co.agentmode.agent47.tui.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionSessionControl
import co.agentmode.agent47.ext.core.MutableExtensionSessionControl
import co.agentmode.agent47.ext.core.SessionStartReason
import co.agentmode.agent47.tui.controller.SessionController
import co.agentmode.agent47.tui.state.TuiAppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bridges the extension [ExtensionSessionControl] surface to the TUI: creating, switching, and
 * forking sessions through the [SessionController] and client.
 */
internal class TuiExtensionSessionControl(
    private val state: TuiAppState,
    private val client: AgentClient,
    private val sessions: SessionController,
    private val sessionsDir: Path?,
    private val cwd: Path,
    private val scope: CoroutineScope,
) : ExtensionSessionControl {
    override suspend fun newSession(): String? {
        val result = CompletableDeferred<String?>()
        scope.launch {
            sessions.startNewSession()
            result.complete(state.activeSessionManager?.getSessionFile()?.toString())
        }
        return result.await()
    }

    override suspend fun switchSession(path: Path): Boolean {
        val result = CompletableDeferred<Boolean>()
        scope.launch {
            if (!Files.exists(path)) {
                result.complete(false)
                return@launch
            }
            sessions.load(path)
            result.complete(true)
        }
        return result.await()
    }

    override suspend fun forkSession(entryId: String?): String? {
        val result = CompletableDeferred<String?>()
        scope.launch {
            val source = state.activeSessionManager
            val validEntryId = entryId?.takeIf { source?.getEntry(it) != null }
            if (entryId != null && validEntryId == null) {
                result.complete(null)
                return@launch
            }
            val context = source?.buildContext(validEntryId ?: source.getLeafId())
            if (context == null || sessionsDir == null) {
                result.complete(null)
                return@launch
            }
            val target = SessionManager(
                sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl"),
                cwd,
            )
            context.messages.forEach(target::appendMessage)
            sessions.transitionSession(target, SessionStartReason.FORK)
            client.replaceMessages(context.messages)
            state.chatHistory.entries.clear()
            context.messages.forEach(state.chatHistory::appendMessage)
            result.complete(target.getSessionFile().toString())
        }
        return result.await()
    }
}

/** Binds [sessionControl] to the extension context's mutable session surface for the composition. */
@Composable
internal fun BindExtensionSessionControl(
    extensionContext: ExtensionContext?,
    sessionControl: TuiExtensionSessionControl,
) {
    DisposableEffect(extensionContext) {
        val controller = extensionContext?.session as? MutableExtensionSessionControl
        controller?.bind(sessionControl)
        onDispose { controller?.reset() }
    }
}
