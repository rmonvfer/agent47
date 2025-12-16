package co.agentmode.agent47.coding.core.compaction

import co.agentmode.agent47.ai.types.*
import kotlinx.serialization.Serializable

@Serializable
public data class CompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Int = 16_384,
    val keepRecentTokens: Int = 20_000,
)

public data class ContextUsageEstimate(
    val tokens: Int,
    val usageTokens: Int,
    val trailingTokens: Int,
    val lastUsageIndex: Int?,
)

public data class CutPointResult(
    val firstKeptEntryIndex: Int,
    val turnStartIndex: Int,
    val isSplitTurn: Boolean,
)

public data class CompactionResult(
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
)

public fun calculateContextTokens(usage: Usage): Int {
    return if (usage.totalTokens > 0) {
        usage.totalTokens
    } else {
        usage.input + usage.output + usage.cacheRead + usage.cacheWrite
    }
}

public fun estimateTokens(message: Message): Int {
    return when (message) {
        is UserMessage -> message.content.filterIsInstance<TextContent>().sumOf { it.text.length } / 4
        is AssistantMessage -> {
            val chars = message.content.sumOf { block ->
                when (block) {
                    is TextContent -> block.text.length
                    is ThinkingContent -> block.thinking.length
                    is ToolCall -> block.name.length + block.arguments.toString().length
                    else -> 0
                }
            }
            chars / 4
        }

        is ToolResultMessage -> message.content.filterIsInstance<TextContent>().sumOf { it.text.length } / 4
        is CustomMessage -> message.content.filterIsInstance<TextContent>().sumOf { it.text.length } / 4
        is BashExecutionMessage -> (message.command.length + message.output.length) / 4
        is BranchSummaryMessage -> message.summary.length / 4
        is CompactionSummaryMessage -> message.summary.length / 4
    }
}

public fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate {
    var usageIndex: Int? = null
    var usageTokens = 0

    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (message is AssistantMessage && message.stopReason != StopReason.ERROR && message.stopReason != StopReason.ABORTED) {
            usageIndex = index
            usageTokens = calculateContextTokens(message.usage)
            break
        }
    }

    if (usageIndex == null) {
        val estimate = messages.sumOf(::estimateTokens)
        return ContextUsageEstimate(
            tokens = estimate,
            usageTokens = 0,
            trailingTokens = estimate,
            lastUsageIndex = null
        )
    }

    val trailing = messages.drop(usageIndex + 1).sumOf(::estimateTokens)
    return ContextUsageEstimate(
        tokens = usageTokens + trailing,
        usageTokens = usageTokens,
        trailingTokens = trailing,
        lastUsageIndex = usageIndex,
    )
}

public fun shouldCompact(contextTokens: Int, contextWindow: Int, settings: CompactionSettings): Boolean {
    if (!settings.enabled) {
        return false
    }
    return contextTokens > (contextWindow - settings.reserveTokens)
}

public fun findTurnStartIndex(messages: List<Message>, index: Int, startIndex: Int): Int {
    for (cursor in index downTo startIndex) {
        val role = messages[cursor].role
        if (role == "user" || role == "bashExecution" || role == "branchSummary") {
            return cursor
        }
    }
    return -1
}

public fun findCutPoint(messages: List<Message>, keepRecentTokens: Int): CutPointResult {
    if (messages.isEmpty()) {
        return CutPointResult(firstKeptEntryIndex = 0, turnStartIndex = -1, isSplitTurn = false)
    }

    var kept = 0
    var firstKept = messages.lastIndex

    for (index in messages.indices.reversed()) {
        val estimate = estimateTokens(messages[index])
        if (kept + estimate > keepRecentTokens) {
            break
        }
        kept += estimate
        firstKept = index
    }

    val turnStart = findTurnStartIndex(messages, firstKept, 0)
    val isSplitTurn = turnStart != firstKept && turnStart >= 0

    return CutPointResult(
        firstKeptEntryIndex = if (turnStart >= 0) turnStart else firstKept,
        turnStartIndex = turnStart,
        isSplitTurn = isSplitTurn,
    )
}

public fun compact(
    messages: List<Message>,
    firstKeptEntryId: String,
    tokensBefore: Int,
): CompactionResult {
    val keptMessages = messages.takeLast(10)
    val summary = keptMessages.joinToString("\n") { message ->
        when (message) {
            is UserMessage -> "user: ${message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }}"
            is AssistantMessage -> "assistant: ${
                message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
            }"

            is ToolResultMessage -> "tool(${message.toolName}): ${
                message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
            }"

            else -> "${message.role}: ..."
        }
    }

    return CompactionResult(
        summary = summary,
        firstKeptEntryId = firstKeptEntryId,
        tokensBefore = tokensBefore,
    )
}
