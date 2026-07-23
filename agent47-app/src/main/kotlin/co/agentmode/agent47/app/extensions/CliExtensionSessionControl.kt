package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionSessionControl
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.SessionShutdownEvent
import co.agentmode.agent47.ext.core.SessionShutdownReason
import co.agentmode.agent47.ext.core.SessionStartEvent
import co.agentmode.agent47.ext.core.SessionStartReason
import java.nio.file.Files
import java.nio.file.Path

internal class CliExtensionSessionControl(
    private val sessionTracker: SessionTracker,
    private val extensionRuntime: KotlinExtensionRuntime,
    private val client: AgentClient,
    private val sessionsDir: Path,
    private val workingDir: Path,
    private val noSession: Boolean,
    private val contextProvider: () -> ExtensionContext,
) : ExtensionSessionControl {
    override suspend fun newSession(): String? {
        val previous = sessionTracker.current
        extensionRuntime.runner.shutdownSession(
            SessionShutdownEvent(SessionShutdownReason.NEW),
            contextProvider(),
        )
        client.abort()
        client.clearMessages()
        val manager = if (noSession) {
            null
        } else {
            SessionManager(
                sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl"),
                workingDir,
            )
        }
        sessionTracker.current = manager
        extensionRuntime.runner.startSession(
            SessionStartEvent(SessionStartReason.NEW, previous?.getSessionFile()),
            contextProvider(),
        )
        return manager?.getSessionFile()?.toString()
    }

    override suspend fun switchSession(path: Path): Boolean {
        val manager = path.takeIf(Files::exists)
            ?.let { runCatching { SessionManager(it, workingDir) }.getOrNull() }
        return if (manager == null) {
            false
        } else {
            val previous = sessionTracker.current
            extensionRuntime.runner.shutdownSession(
                SessionShutdownEvent(SessionShutdownReason.RESUME, manager.getSessionFile()),
                contextProvider(),
            )
            client.abort()
            client.replaceMessages(manager.buildContext().messages)
            sessionTracker.current = manager
            extensionRuntime.runner.startSession(
                SessionStartEvent(SessionStartReason.RESUME, previous?.getSessionFile()),
                contextProvider(),
            )
            true
        }
    }

    override suspend fun forkSession(entryId: String?): String? {
        val source = sessionTracker.current ?: return newSession()
        val context = source.buildContext(entryId ?: source.getLeafId())
        val target = SessionManager(
            sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl"),
            workingDir,
        )
        context.messages.forEach(target::appendMessage)
        extensionRuntime.runner.shutdownSession(
            SessionShutdownEvent(SessionShutdownReason.FORK, target.getSessionFile()),
            contextProvider(),
        )
        client.abort()
        client.replaceMessages(context.messages)
        sessionTracker.current = target
        extensionRuntime.runner.startSession(
            SessionStartEvent(SessionStartReason.FORK, source.getSessionFile()),
            contextProvider(),
        )
        return target.getSessionFile().toString()
    }
}
