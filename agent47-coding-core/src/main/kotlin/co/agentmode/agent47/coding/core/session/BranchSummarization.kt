package co.agentmode.agent47.coding.core.session

import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.coding.core.compaction.estimateTokens
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
public data class BranchSummarySettings(
    val reserveTokens: Int = 16_384,
    val skipPrompt: Boolean = false,
)

public data class CollectEntriesResult(
    val entries: List<SessionEntry>,
    val commonAncestorId: String?,
)

public data class BranchPreparation(
    val messages: List<Message>,
    val totalTokens: Int,
)

/**
 * Finds the deepest entry shared by [oldLeafId]'s path and [targetId]'s path, then collects the
 * entries from [oldLeafId] back up to (excluding) that ancestor, in chronological order. This is
 * the abandoned branch: what gets summarized when navigation leaves it behind. Compaction
 * boundaries are not stop points - a compaction entry along the way is collected like any other,
 * and its summary becomes context for the branch summary.
 */
public fun collectEntriesForBranchSummary(
    sessionManager: SessionManager,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) {
        return CollectEntriesResult(entries = emptyList(), commonAncestorId = null)
    }

    val oldPath = sessionManager.getBranch(oldLeafId).map { it.id }.toSet()
    val targetPath = sessionManager.getBranch(targetId)

    // targetPath is root-first, so walk backwards to find the deepest shared entry.
    var commonAncestorId: String? = null
    for (index in targetPath.indices.reversed()) {
        if (targetPath[index].id in oldPath) {
            commonAncestorId = targetPath[index].id
            break
        }
    }

    val entries = mutableListOf<SessionEntry>()
    var current: String? = oldLeafId
    while (current != null && current != commonAncestorId) {
        val entry = sessionManager.getEntry(current) ?: break
        entries += entry
        current = entry.parentId
    }
    entries.reverse()

    return CollectEntriesResult(entries = entries, commonAncestorId = commonAncestorId)
}

/** The message an entry contributes to a summarization prompt, or null if it contributes none. */
private fun messageFromEntry(entry: SessionEntry): Message? = when (entry) {
    // Tool results are dropped: the context they carry lives in the assistant's tool call.
    is SessionMessageEntry -> entry.message.takeUnless { it is ToolResultMessage }
    is CustomMessageEntry -> CustomMessage(
        customType = entry.customType,
        content = entry.content,
        display = entry.display,
        details = entry.details,
        timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
    )
    is BranchSummaryEntry -> BranchSummaryMessage(
        fromId = entry.fromId,
        summary = entry.summary,
        timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
    )
    is CompactionEntry -> CompactionSummaryMessage(
        summary = entry.summary,
        tokensBefore = entry.tokensBefore,
        timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
    )
    is ThinkingLevelChangeEntry, is ModelChangeEntry, is CustomEntry, is LabelEntry, is SessionInfoEntry -> null
}

/**
 * Prepares [entries] for summarization under a token budget: walks from newest to oldest,
 * keeping messages until the budget is spent. Compaction and branch-summary entries are given
 * priority to fit even past the budget (up to 90% of it), since they carry condensed context that
 * would otherwise be lost entirely rather than merely truncated. A [tokenBudget] of 0 means
 * unlimited.
 */
public fun prepareBranchEntries(entries: List<SessionEntry>, tokenBudget: Int = 0): BranchPreparation {
    val messages = mutableListOf<Message>()
    var totalTokens = 0

    val candidates = entries.mapNotNull { entry -> messageFromEntry(entry)?.let { message -> entry to message } }
    for (index in candidates.indices.reversed()) {
        val (entry, message) = candidates[index]
        val tokens = estimateTokens(message)

        if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
            val isSummaryEntry = entry is CompactionEntry || entry is BranchSummaryEntry
            if (isSummaryEntry && totalTokens < (tokenBudget * 0.9).toInt()) {
                messages.add(0, message)
                totalTokens += tokens
            }
            break
        }

        messages.add(0, message)
        totalTokens += tokens
    }

    return BranchPreparation(messages = messages, totalTokens = totalTokens)
}

public const val BRANCH_SUMMARY_PREAMBLE: String =
    "The user explored a different conversation branch before returning here.\nSummary of that exploration:\n\n"

public val BRANCH_SUMMARY_PROMPT: String = """
Create a structured summary of this conversation branch for context when returning later.

Use this EXACT format:

## Goal
[What was the user trying to accomplish in this branch?]

## Constraints & Preferences
- [Any constraints, preferences, or requirements mentioned]
- [Or "(none)" if none were mentioned]

## Progress
### Done
- [x] [Completed tasks/changes]

### In Progress
- [ ] [Work that was started but not finished]

### Blocked
- [Issues preventing progress, if any]

## Key Decisions
- **[Decision]**: [Brief rationale]

## Next Steps
1. [What should happen next to continue this work]

Keep each section concise. Preserve exact file paths, function names, and error messages.
""".trimIndent()
