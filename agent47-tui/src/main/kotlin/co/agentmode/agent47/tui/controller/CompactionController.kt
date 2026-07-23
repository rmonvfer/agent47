package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.agent.core.AgentEndEvent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import co.agentmode.agent47.coding.core.compaction.applyCompaction
import co.agentmode.agent47.coding.core.compaction.estimateContextTokens
import co.agentmode.agent47.coding.core.compaction.findCutPoint
import co.agentmode.agent47.coding.core.compaction.shouldCompact
import co.agentmode.agent47.coding.core.session.CompactionEntry
import co.agentmode.agent47.ext.core.AfterCompactionEvent
import co.agentmode.agent47.ext.core.CompactionReason
import co.agentmode.agent47.ext.core.PreparedCompaction
import co.agentmode.agent47.tui.session.randomEntryId
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Owns context compaction: the manual and automatic compaction runs, plus the threshold check that
 * fires an automatic run at the end of an agent turn.
 */
@Stable
internal class CompactionController(
    private val state: TuiAppState,
    private val client: AgentClient,
    private val feed: TranscriptFeed,
    private val scope: CoroutineScope,
    private val compactContext: (suspend (List<Message>, Model, CompactionReason) -> PreparedCompaction?)?,
    private val onCompacted: suspend (AfterCompactionEvent) -> Unit,
    private val compactionSettings: CompactionSettings,
) {
    @Suppress("TooGenericExceptionCaught")
    fun runCompaction(auto: Boolean = false) {
        val model = state.currentModel
        if (compactContext == null || model == null) {
            feed.appendCommandResult("Compaction unavailable")
            return
        }
        state.liveActivityLabel = if (auto) "Auto-compacting" else "Compacting context"
        state.isStreaming = true
        scope.launch(Dispatchers.IO) {
            try {
                val messages = client.state.messages
                val estimate = estimateContextTokens(messages)
                val reason = if (auto) {
                    CompactionReason.THRESHOLD
                } else {
                    CompactionReason.MANUAL
                }
                val prepared = compactContext.invoke(messages, model, reason)
                if (prepared != null) {
                    val result = prepared.compaction
                    val cutPoint = findCutPoint(messages, compactionSettings.keepRecentTokens)
                    val compacted = applyCompaction(
                        messages = messages,
                        summary = result.summary,
                        cutPointIndex = cutPoint.firstKeptEntryIndex,
                        tokensBefore = estimate.tokens,
                    )
                    client.replaceMessages(compacted)
                    state.activeSessionManager?.append(
                        CompactionEntry(
                            id = randomEntryId(),
                            parentId = state.activeSessionManager?.getLeafId(),
                            timestamp = Instant.now().toString(),
                            summary = result.summary,
                            firstKeptEntryId = result.firstKeptEntryId,
                            tokensBefore = estimate.tokens,
                        ),
                    )
                    onCompacted(
                        AfterCompactionEvent(
                            compaction = result,
                            reason = reason,
                            fromExtension = prepared.fromExtension,
                        ),
                    )
                    val after = estimateContextTokens(compacted)
                    feed.appendCommandResult("Context compacted (${estimate.tokens} → ~${after.tokens} tokens)")
                } else {
                    feed.appendCommandResult("Compaction failed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feed.appendCommandResult("Compaction error: ${e.message ?: e::class.simpleName}")
            } finally {
                state.isStreaming = false
            }
        }
    }

    fun maybeAutoCompactAfter(event: AgentEvent) {
        val settingsAllow = compactionSettings.auto && compactionSettings.enabled
        if (event !is AgentEndEvent || !settingsAllow || compactContext == null) return
        val model = state.currentModel ?: return
        val estimate = estimateContextTokens(client.state.messages)
        if (shouldCompact(estimate.tokens, model.contextWindow, compactionSettings)) {
            runCompaction(auto = true)
        }
    }
}
