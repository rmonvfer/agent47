package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.app.compaction.BranchSummaryResult
import co.agentmode.agent47.app.compaction.generateBranchSummary
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.CustomMessageEntry
import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.coding.core.session.collectEntriesForBranchSummary
import co.agentmode.agent47.coding.core.session.prepareBranchEntries
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.SessionBeforeTreeEvent
import co.agentmode.agent47.ext.core.SessionTreeEvent
import co.agentmode.agent47.ext.core.TreeHookResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

public data class TreeNavigationResult(
    val newLeafId: String?,
    val editorText: String? = null,
    val aborted: Boolean = false,
    val cancelled: Boolean = false,
)

/**
 * Moves the active session's leaf pointer to a different point in the tree, optionally
 * summarizing the branch left behind. Unlike forking, navigation stays within the same session
 * file: the abandoned subtree remains in the tree, just off the active path.
 */
internal class SessionTreeService(
    private val sessionTracker: SessionTracker,
    private val client: AgentClient,
    private val extensionRuntime: KotlinExtensionRuntime,
    private val extensionContext: ExtensionContext,
    private val settings: SettingsManager,
    private val aiRuntime: AiRuntime,
    private val modelRegistry: ModelRegistry,
) {
    @Volatile
    private var activeSummary: Deferred<BranchSummaryResult>? = null

    /** Cancels an in-progress branch summary generation; a no-op when none is running. */
    fun abort() {
        activeSummary?.cancel()
    }

    suspend fun navigateTree(
        targetId: String,
        summarize: Boolean,
        customInstructions: String? = null,
    ): TreeNavigationResult {
        val sessionManager = checkNotNull(sessionTracker.current) { "No active session" }
        val oldLeafId = sessionManager.getLeafId()
        if (targetId == oldLeafId) return TreeNavigationResult(newLeafId = targetId)

        val targetEntry = checkNotNull(sessionManager.getEntry(targetId)) { "Entry $targetId not found" }
        val collected = collectEntriesForBranchSummary(sessionManager, oldLeafId, targetId)
        val hookResult = extensionRuntime.runner.prepareTree(
            SessionBeforeTreeEvent(targetId = targetId, oldLeafId = oldLeafId, entries = collected.entries),
            extensionContext,
        )

        return if (hookResult?.cancel == true) {
            TreeNavigationResult(newLeafId = oldLeafId, cancelled = true)
        } else {
            when (val summary = resolveSummary(hookResult, summarize, collected.entries, customInstructions)) {
                SummaryOutcome.Aborted -> TreeNavigationResult(newLeafId = oldLeafId, aborted = true)
                is SummaryOutcome.Text -> commitNavigation(sessionManager, targetEntry, targetId, oldLeafId, summary.value)
            }
        }
    }

    private sealed interface SummaryOutcome {
        data object Aborted : SummaryOutcome
        data class Text(val value: String?) : SummaryOutcome
    }

    private suspend fun resolveSummary(
        hookResult: TreeHookResult?,
        summarize: Boolean,
        entries: List<SessionEntry>,
        customInstructions: String?,
    ): SummaryOutcome {
        val hookSummary = hookResult?.summary
        return when {
            hookSummary != null -> SummaryOutcome.Text(hookSummary)
            !summarize || entries.isEmpty() -> SummaryOutcome.Text(null)
            else -> {
                val effectiveInstructions = hookResult?.customInstructions ?: customInstructions
                val outcome = runSummaryGeneration(entries, effectiveInstructions)
                if (outcome.aborted) {
                    SummaryOutcome.Aborted
                } else {
                    SummaryOutcome.Text(checkNotNull(outcome.summary) { outcome.error ?: "Summarization failed" })
                }
            }
        }
    }

    private suspend fun commitNavigation(
        sessionManager: SessionManager,
        targetEntry: SessionEntry,
        targetId: String,
        oldLeafId: String?,
        summaryText: String?,
    ): TreeNavigationResult {
        val (newLeafId, editorText) = resolveTarget(targetEntry, targetId)
        val summaryEntryId = applyNavigation(sessionManager, newLeafId, summaryText)

        client.abort()
        client.replaceMessages(sessionManager.buildContext().messages)

        extensionRuntime.runner.completeTree(
            SessionTreeEvent(newLeafId = sessionManager.getLeafId(), oldLeafId = oldLeafId, summaryEntryId = summaryEntryId),
            extensionContext,
        )

        return TreeNavigationResult(newLeafId = sessionManager.getLeafId(), editorText = editorText)
    }

    private fun applyNavigation(sessionManager: SessionManager, newLeafId: String?, summaryText: String?): String? {
        if (summaryText == null) {
            if (newLeafId == null) sessionManager.resetLeaf() else sessionManager.branch(newLeafId)
            return null
        }
        return sessionManager.branchWithSummary(newLeafId, summaryText).id
    }

    private fun resolveTarget(entry: SessionEntry, targetId: String): Pair<String?, String?> = when {
        entry is SessionMessageEntry && entry.message.role == "user" ->
            entry.parentId to extractText((entry.message as UserMessage).content)
        entry is CustomMessageEntry -> entry.parentId to entry.content.joinToString("") { it.text }
        else -> targetId to null
    }

    private fun extractText(content: List<ContentBlock>): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private suspend fun runSummaryGeneration(
        entries: List<SessionEntry>,
        customInstructions: String?,
    ): BranchSummaryResult = supervisorScope {
        val model = client.state.model
        val tokenBudget = model.contextWindow - settings.get().branchSummary.reserveTokens
        val prepared = prepareBranchEntries(entries, tokenBudget)
        val deferred = async {
            generateBranchSummary(prepared.messages, model, aiRuntime, modelRegistry, customInstructions)
        }
        activeSummary = deferred
        try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            // The child job (not this navigateTree coroutine) was cancelled via abort(): report a
            // graceful abort, identified by the cancellation cause, rather than letting it cross
            // the supervisorScope boundary.
            BranchSummaryResult(aborted = true, error = cancellation.message)
        } finally {
            activeSummary = null
        }
    }
}
