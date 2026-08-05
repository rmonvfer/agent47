package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.session.BranchSummaryEntry
import co.agentmode.agent47.coding.core.session.CompactionEntry
import co.agentmode.agent47.coding.core.session.CustomEntry
import co.agentmode.agent47.coding.core.session.CustomMessageEntry
import co.agentmode.agent47.coding.core.session.LabelEntry
import co.agentmode.agent47.coding.core.session.ModelChangeEntry
import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionInfoEntry
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.coding.core.session.SessionTreeNode
import co.agentmode.agent47.coding.core.session.ThinkingLevelChangeEntry
import co.agentmode.agent47.ui.core.util.summarizeToolArguments

/** A tool call referenced by a [ToolResultMessage], looked up by its call id for display. */
public data class ToolCallRef(val name: String, val argumentsJson: String)

/** Indexes every tool call an assistant message issued, keyed by call id, for tool-result lookups. */
public fun buildToolCallMap(entries: List<SessionEntry>): Map<String, ToolCallRef> {
    val map = mutableMapOf<String, ToolCallRef>()
    for (entry in entries) {
        val message = (entry as? SessionMessageEntry)?.message as? AssistantMessage ?: continue
        for (block in message.content) {
            if (block is ToolCall) {
                map[block.id] = ToolCallRef(block.name, block.arguments.toString())
            }
        }
    }
    return map
}

/** Semantic color role for a piece of tree-row display text; the renderer maps this to a theme token. */
public enum class TreeTextRole { ACCENT, SUCCESS, WARNING, MUTED, DIM, ERROR, CUSTOM_LABEL, PLAIN }

/** One styled run of text in an entry's display line. */
public data class TreeTextSegment(val text: String, val role: TreeTextRole)

private const val MAX_ENTRY_TEXT_LENGTH = 200
private const val TOOL_SUMMARY_WIDTH = 60
private const val MAX_ERROR_TEXT_LENGTH = 80

internal fun normalizeOneLine(text: String): String =
    text.replace('\n', ' ').replace('\t', ' ').trim().take(MAX_ENTRY_TEXT_LENGTH)

internal fun extractText(content: List<ContentBlock>): String =
    content.filterIsInstance<TextContent>().joinToString(" ") { it.text }

/** Builds the styled display line for one tree entry. */
public fun buildEntryDisplaySegments(node: SessionTreeNode, toolCalls: Map<String, ToolCallRef>): List<TreeTextSegment> =
    when (val entry = node.entry) {
        is SessionMessageEntry -> messageSegments(entry.message, toolCalls)
        is CompactionEntry -> listOf(
            TreeTextSegment("[compaction: ${entry.tokensBefore / 1000}k tokens]", TreeTextRole.DIM),
        )
        is BranchSummaryEntry -> listOf(
            TreeTextSegment("[branch summary]: ", TreeTextRole.WARNING),
            TreeTextSegment(normalizeOneLine(entry.summary), TreeTextRole.PLAIN),
        )
        is ModelChangeEntry -> listOf(TreeTextSegment("[model: ${entry.modelId}]", TreeTextRole.DIM))
        is ThinkingLevelChangeEntry -> listOf(TreeTextSegment("[thinking: ${entry.thinkingLevel}]", TreeTextRole.DIM))
        is SessionInfoEntry -> listOf(TreeTextSegment("[title: ${entry.name ?: "(empty)"}]", TreeTextRole.DIM))
        is CustomEntry -> listOf(TreeTextSegment("[custom: ${entry.customType}]", TreeTextRole.DIM))
        is LabelEntry -> listOf(TreeTextSegment("[label: ${entry.label ?: "(cleared)"}]", TreeTextRole.DIM))
        is CustomMessageEntry -> listOf(
            TreeTextSegment("[${entry.customType}]: ", TreeTextRole.CUSTOM_LABEL),
            TreeTextSegment(normalizeOneLine(entry.content.joinToString("") { it.text }), TreeTextRole.PLAIN),
        )
    }

private fun messageSegments(message: Message, toolCalls: Map<String, ToolCallRef>): List<TreeTextSegment> = when (message) {
    is UserMessage -> listOf(
        TreeTextSegment("user: ", TreeTextRole.ACCENT),
        TreeTextSegment(normalizeOneLine(extractText(message.content)), TreeTextRole.PLAIN),
    )
    is AssistantMessage -> listOf(TreeTextSegment("assistant: ", TreeTextRole.SUCCESS), assistantBodySegment(message))
    is ToolResultMessage -> listOf(toolResultSegment(message, toolCalls))
    is BashExecutionMessage -> listOf(TreeTextSegment("[bash]: ${normalizeOneLine(message.command)}", TreeTextRole.DIM))
    else -> listOf(TreeTextSegment("[${message.role}]", TreeTextRole.DIM))
}

private fun assistantBodySegment(message: AssistantMessage): TreeTextSegment {
    val text = normalizeOneLine(extractText(message.content))
    return when {
        text.isNotEmpty() -> TreeTextSegment(text, TreeTextRole.PLAIN)
        message.stopReason == StopReason.ABORTED -> TreeTextSegment("(aborted)", TreeTextRole.MUTED)
        message.errorMessage != null ->
            TreeTextSegment(normalizeOneLine(message.errorMessage!!).take(MAX_ERROR_TEXT_LENGTH), TreeTextRole.ERROR)
        else -> TreeTextSegment("(no content)", TreeTextRole.MUTED)
    }
}

private fun toolResultSegment(message: ToolResultMessage, toolCalls: Map<String, ToolCallRef>): TreeTextSegment {
    val call = toolCalls[message.toolCallId]
    val summary = if (call != null) {
        "[${call.name}: ${summarizeToolArguments(call.name, call.argumentsJson, TOOL_SUMMARY_WIDTH)}]"
    } else {
        "[${message.toolName}]"
    }
    return TreeTextSegment(summary, TreeTextRole.MUTED)
}
