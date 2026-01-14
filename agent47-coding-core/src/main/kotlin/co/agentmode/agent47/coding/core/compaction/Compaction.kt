package co.agentmode.agent47.coding.core.compaction

import co.agentmode.agent47.ai.types.*
import kotlinx.serialization.Serializable

@Serializable
public data class CompactionSettings(
    val enabled: Boolean = true,
    val auto: Boolean = true,
    val prune: Boolean = true,
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

public val COMPACTION_PROMPT: String = """
Provide a detailed summary for continuing the conversation above.
Focus on information helpful for continuing, including what was done, what's being worked on, which files are involved, and what's planned next.

Use this template:
---
## Goal
[What goal(s) is the user trying to accomplish?]

## Instructions
[Important instructions the user gave that are still relevant]

## Discoveries
[Notable things learned during this conversation]

## Accomplished
[Work completed, work in progress, work remaining]

## Relevant files / directories
[Structured list of files read, edited, or created that pertain to the task]
---
""".trimIndent()

public fun pruneToolOutputs(messages: List<Message>, keepRecentTokens: Int): List<Message> {
    val result = messages.toMutableList()
    var recentTokens = 0
    var protectedBoundary = result.size

    for (i in result.indices.reversed()) {
        val msg = result[i]
        if (msg is UserMessage) {
            protectedBoundary = i
            break
        }
        recentTokens += estimateTokens(msg)
        if (recentTokens >= keepRecentTokens) {
            protectedBoundary = i
            break
        }
    }

    for (i in 0 until protectedBoundary) {
        val msg = result[i]
        if (msg is ToolResultMessage) {
            val text = msg.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (text.length > 500) {
                val truncated = text.take(200) + "\n... [truncated ${text.length - 200} chars]"
                result[i] = ToolResultMessage(
                    toolCallId = msg.toolCallId,
                    toolName = msg.toolName,
                    content = listOf(TextContent(text = truncated)),
                    details = msg.details,
                    isError = msg.isError,
                    timestamp = msg.timestamp,
                )
            }
        }
    }
    return result
}

public fun buildCompactionMessages(messages: List<Message>): List<Message> {
    val historyText = buildString {
        for (msg in messages) {
            when (msg) {
                is UserMessage -> {
                    appendLine("[user]")
                    msg.content.filterIsInstance<TextContent>().forEach { appendLine(it.text) }
                }
                is AssistantMessage -> {
                    appendLine("[assistant]")
                    msg.content.filterIsInstance<TextContent>().forEach { appendLine(it.text) }
                    msg.content.filterIsInstance<ToolCall>().forEach {
                        appendLine("  tool_call: ${it.name}(${it.arguments.toString().take(200)})")
                    }
                }
                is ToolResultMessage -> {
                    val text = msg.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                    val preview = if (text.length > 300) text.take(300) + "..." else text
                    appendLine("[tool:${msg.toolName}] ${if (msg.isError) "ERROR" else "OK"}: $preview")
                }
                is CompactionSummaryMessage -> {
                    appendLine("[previous_summary]")
                    appendLine(msg.summary)
                }
                else -> {
                    appendLine("[${msg.role}]")
                }
            }
            appendLine()
        }
    }

    return listOf(
        UserMessage(
            content = listOf(TextContent(text = historyText)),
            timestamp = System.currentTimeMillis(),
        ),
        UserMessage(
            content = listOf(TextContent(text = COMPACTION_PROMPT)),
            timestamp = System.currentTimeMillis(),
        ),
    )
}

public fun applyCompaction(
    messages: List<Message>,
    summary: String,
    cutPointIndex: Int,
    tokensBefore: Int,
): List<Message> {
    val compactionMessage = CompactionSummaryMessage(
        summary = summary,
        tokensBefore = tokensBefore,
        timestamp = System.currentTimeMillis(),
    )
    return listOf(compactionMessage) + messages.drop(cutPointIndex)
}
