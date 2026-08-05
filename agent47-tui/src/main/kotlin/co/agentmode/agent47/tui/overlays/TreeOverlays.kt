package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.tui.session.rebuildTranscriptFrom
import co.agentmode.agent47.ui.core.state.SelectItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class BranchSummaryChoice { NONE, SUMMARIZE, CUSTOM }

/**
 * Opens the `/tree` session navigator over the active session, optionally re-selecting
 * [initialSelectedId] (used when returning here after cancelling the branch-summary prompt).
 */
internal fun OverlayNavigator.openTreeOverlay(initialSelectedId: String? = null) {
    val activeSession = state.activeSessionManager
    if (activeSession == null) {
        feed.appendCommandResult("No active session")
        return
    }
    if (activeSession.getTree().isEmpty()) {
        feed.appendCommandResult("No entries in session")
        return
    }
    overlays.pushTree(
        session = activeSession,
        initialSelectedId = initialSelectedId,
        onSelect = { entryId -> handleTreeSelection(activeSession, entryId) },
    )
}

private fun OverlayNavigator.handleTreeSelection(activeSession: SessionManager, entryId: String) {
    when {
        entryId == activeSession.getLeafId() -> feed.appendCommandResult("Already at this point")
        state.branchSummarySkipPrompt ->
            scope.launch { performTreeNavigation(activeSession, entryId, summarize = false, customInstructions = null) }
        // Summarization needs a model; without one the navigation still happens, it is
        // just recorded without a summary instead of failing mid-flight.
        state.currentModel == null -> {
            feed.appendSystemMessage(
                "No model selected — branch summary skipped; use /provider to connect a provider",
            )
            scope.launch { performTreeNavigation(activeSession, entryId, summarize = false, customInstructions = null) }
        }
        else -> promptBranchSummaryChoice(activeSession, entryId)
    }
}

private fun OverlayNavigator.promptBranchSummaryChoice(activeSession: SessionManager, entryId: String) {
    val options = listOf(
        SelectItem("No summary", BranchSummaryChoice.NONE),
        SelectItem("Summarize", BranchSummaryChoice.SUMMARIZE),
        SelectItem("Summarize with custom prompt", BranchSummaryChoice.CUSTOM),
    )
    overlays.push(
        title = "Summarize branch?",
        items = options,
        selectedIndex = 0,
        onSubmit = { choice ->
            when (choice) {
                BranchSummaryChoice.NONE ->
                    scope.launch { performTreeNavigation(activeSession, entryId, summarize = false, customInstructions = null) }
                BranchSummaryChoice.SUMMARIZE ->
                    scope.launch { performTreeNavigation(activeSession, entryId, summarize = true, customInstructions = null) }
                BranchSummaryChoice.CUSTOM -> overlays.pushPrompt(
                    title = "Custom summarization instructions",
                    onSubmit = { text ->
                        scope.launch {
                            performTreeNavigation(activeSession, entryId, summarize = true, customInstructions = text.ifBlank { null })
                        }
                    },
                    onClose = { promptBranchSummaryChoice(activeSession, entryId) },
                )
            }
        },
        // Escape re-opens the tree with the same selection, matching the reference flow.
        onClose = { openTreeOverlay(entryId) },
    )
}

@Suppress("TooGenericExceptionCaught")
private suspend fun OverlayNavigator.performTreeNavigation(
    activeSession: SessionManager,
    entryId: String,
    summarize: Boolean,
    customInstructions: String?,
) {
    if (summarize) {
        state.liveActivityLabel = "Summarizing branch"
        state.isStreaming = true
        state.treeSummaryAbort = { abortTreeNavigation() }
    }
    try {
        val outcome = navigateTree(entryId, summarize, customInstructions)
        when {
            outcome.aborted -> {
                feed.appendCommandResult("Branch summarization cancelled")
                openTreeOverlay(entryId)
            }
            outcome.cancelled -> feed.appendCommandResult("Navigation cancelled")
            else -> {
                rebuildTranscriptFrom(
                    session = activeSession,
                    availableModels = state.currentModels,
                    client = client,
                    chatHistoryState = state.chatHistory,
                    applyModel = { model -> models.applyModel(model, recordSessionEntry = false, announce = false) },
                    setThinkingLevel = { level -> models.setThinkingLevel(level, recordSessionEntry = false, announce = false) },
                )
                if (outcome.editorText != null && editor.text().isBlank()) {
                    editor.setText(outcome.editorText)
                    state.editorVersion++
                }
                feed.appendCommandResult("Navigated to selected point")
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        feed.appendCommandResult(error.message ?: error.toString())
    } finally {
        if (summarize) {
            state.isStreaming = false
            state.treeSummaryAbort = null
        }
    }
}
