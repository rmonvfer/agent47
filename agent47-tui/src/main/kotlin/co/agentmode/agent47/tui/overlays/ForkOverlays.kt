package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.ext.core.SessionStartReason
import co.agentmode.agent47.tui.session.rebuildTranscriptFrom
import co.agentmode.agent47.ui.core.state.UserMessageItem
import java.nio.file.Path

/**
 * Opens the `/fork` overlay: every user message in the session, in chronological order, newest
 * preselected. Selecting one copies the active path up to that message's parent into a new
 * session file (structure-preserving, unlike replaying messages) and switches to it.
 */
@Suppress("ReturnCount")
internal fun OverlayNavigator.openForkOverlay() {
    val activeSession = state.activeSessionManager
    if (activeSession == null) {
        feed.appendCommandResult("No active session")
        return
    }
    if (state.isStreaming) {
        feed.appendCommandResult("Wait for the current response before forking.")
        return
    }
    val messages = activeSession.getEntries()
        .filterIsInstance<SessionMessageEntry>()
        .mapNotNull { entry ->
            val message = entry.message as? UserMessage ?: return@mapNotNull null
            val text = extractMessageText(message.content)
            if (text.isBlank()) null else UserMessageItem(entry.id, text)
        }
    if (messages.isEmpty()) {
        feed.appendCommandResult("No messages to fork from")
        return
    }
    overlays.pushUserMessages(
        items = messages,
        initialSelectedId = messages.last().id,
        onSelect = { entryId -> forkFromMessage(activeSession, entryId) },
    )
}

@Suppress("ReturnCount")
private fun OverlayNavigator.forkFromMessage(activeSession: SessionManager, entryId: String) {
    val targetDir = sessionsDir
    if (targetDir == null) {
        feed.appendCommandResult("Forking is unavailable: no session directory configured")
        return
    }
    val selected = activeSession.getEntry(entryId) as? SessionMessageEntry ?: return
    val message = selected.message as? UserMessage ?: return
    val text = extractMessageText(message.content)

    // A message with no parent is the very first entry in the session: forking "before" it means
    // starting a brand-new, empty session rather than branching (there is nothing to branch from).
    val forked = selected.parentId?.let { parentId ->
        SessionManager(activeSession.createBranchedSession(parentId, newSessionFile(targetDir)), cwd)
    } ?: SessionManager(newSessionFile(targetDir), cwd)

    session.transitionSession(forked, SessionStartReason.FORK)
    rebuildTranscriptForkedTo(forked)
    editor.setText(text)
    state.editorVersion++
    feed.appendCommandResult("Forked to new session")
}

/** Copies the active path at the current leaf into a new session file and switches to it. */
@Suppress("ReturnCount")
internal fun OverlayNavigator.cloneSession() {
    val activeSession = state.activeSessionManager
    if (activeSession == null) {
        feed.appendCommandResult("No active session")
        return
    }
    if (state.isStreaming) {
        feed.appendCommandResult("Wait for the current response before cloning.")
        return
    }
    val leafId = activeSession.getLeafId()
    if (leafId == null) {
        feed.appendCommandResult("Nothing to clone yet")
        return
    }
    val targetDir = sessionsDir
    if (targetDir == null) {
        feed.appendCommandResult("Cloning is unavailable: no session directory configured")
        return
    }
    val targetFile = newSessionFile(targetDir)
    val cloned = SessionManager(activeSession.createBranchedSession(leafId, targetFile), cwd)

    session.transitionSession(cloned, SessionStartReason.FORK)
    rebuildTranscriptForkedTo(cloned)
    feed.appendCommandResult("Cloned to ${targetFile.fileName}")
}

private fun OverlayNavigator.rebuildTranscriptForkedTo(target: SessionManager) {
    rebuildTranscriptFrom(
        session = target,
        availableModels = state.currentModels,
        client = client,
        chatHistoryState = state.chatHistory,
        applyModel = { model -> models.applyModel(model, recordSessionEntry = false, announce = false) },
        setThinkingLevel = { level -> models.setThinkingLevel(level, recordSessionEntry = false, announce = false) },
    )
}

private fun newSessionFile(sessionsDir: Path): Path =
    sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl")

private fun extractMessageText(content: List<ContentBlock>): String =
    content.filterIsInstance<TextContent>().joinToString("") { it.text }
