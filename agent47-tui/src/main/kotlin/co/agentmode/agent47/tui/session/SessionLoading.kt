package co.agentmode.agent47.tui.session

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.session.SessionContext
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import java.nio.file.Files
import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.util.UUID

internal val SESSION_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d HH:mm")

internal fun firstUserText(session: SessionManager): String? {
    val entry = session.getEntries()
        .filterIsInstance<SessionMessageEntry>()
        .firstOrNull { it.message is UserMessage } ?: return null
    return (entry.message as UserMessage).content
        .filterIsInstance<TextContent>()
        .firstOrNull()?.text
        ?.replace("\n", " ")?.trim()?.ifBlank { null }
}

internal fun loadSession(
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

    activeSessionManagerSetter(loadedManager)
    rebuildTranscriptFrom(loadedManager, availableModels, client, chatHistoryState, applyModel, setThinkingLevel)

    appendSystemMessage("Loaded session ${resolvedPath.fileName}")
}

/**
 * Rebuilds the client and chat-history transcript from [session]'s active branch, and restores
 * whichever model and thinking level were in effect at that point. Shared by session resume,
 * tree navigation, fork, and clone, since all four land on a (possibly different) point in a
 * session's history and need the UI to reflect exactly that point.
 */
internal fun rebuildTranscriptFrom(
    session: SessionManager,
    availableModels: List<Model>,
    client: AgentClient,
    chatHistoryState: ChatHistoryState,
    applyModel: (Model) -> Unit,
    setThinkingLevel: (AgentThinkingLevel) -> Unit,
): SessionContext {
    val context = session.buildContext()
    client.replaceMessages(context.messages)
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

    return context
}

internal fun parseThinkingLevelOrNull(value: String?): AgentThinkingLevel? {
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

internal fun randomEntryId(): String = UUID.randomUUID().toString().substring(0, 8)
