@file:Suppress("MatchingDeclarationName")

package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
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

/** Filter mode for the /tree overlay's entry list. */
public enum class TreeFilterMode {
    DEFAULT,
    NO_TOOLS,
    USER_ONLY,
    ALL,
    ;

    /** The next mode in the cycle, wrapping from [ALL] back to [DEFAULT]. */
    public fun next(): TreeFilterMode = entries[(ordinal + 1) % entries.size]
}

/** Whether [entry] passes this filter mode, independent of search or the empty-tool-turn rule. */
public fun TreeFilterMode.passes(entry: SessionEntry): Boolean {
    val isSettingsEntry = entry is LabelEntry || entry is CustomEntry ||
        entry is ModelChangeEntry || entry is ThinkingLevelChangeEntry || entry is SessionInfoEntry
    return when (this) {
        TreeFilterMode.USER_ONLY -> entry is SessionMessageEntry && entry.message is UserMessage
        TreeFilterMode.NO_TOOLS -> !isSettingsEntry && !(entry is SessionMessageEntry && entry.message is ToolResultMessage)
        TreeFilterMode.ALL -> true
        TreeFilterMode.DEFAULT -> !isSettingsEntry
    }
}

/** True for an assistant turn that only issued tool calls: it renders nothing readable on its own. */
private fun isEmptyAssistantToolTurn(entry: SessionEntry): Boolean {
    val message = (entry as? SessionMessageEntry)?.message as? AssistantMessage
    val hasText = message?.content?.any { it is TextContent && it.text.isNotBlank() } ?: true
    val isErrorOrAborted = message?.stopReason == StopReason.ABORTED || message?.errorMessage != null
    return message != null && !hasText && !isErrorOrAborted
}

private fun searchableMessageText(message: Message): String = when (message) {
    is UserMessage -> "${message.role} ${extractText(message.content)}"
    is AssistantMessage -> "${message.role} ${extractText(message.content)}"
    is ToolResultMessage -> "${message.role} ${extractText(message.content)}"
    is BashExecutionMessage -> "${message.role} ${message.command}"
    else -> message.role
}

private fun searchableEntryText(entry: SessionEntry): String = when (entry) {
    is SessionMessageEntry -> searchableMessageText(entry.message)
    is CustomMessageEntry -> "${entry.customType} ${entry.content.joinToString(" ") { it.text }}"
    is CompactionEntry -> "compaction"
    is BranchSummaryEntry -> "branch summary ${entry.summary}"
    is SessionInfoEntry -> "title ${entry.name.orEmpty()}"
    is ModelChangeEntry -> "model ${entry.modelId}"
    is ThinkingLevelChangeEntry -> "thinking ${entry.thinkingLevel}"
    is CustomEntry -> "custom ${entry.customType}"
    is LabelEntry -> "label ${entry.label.orEmpty()}"
}

private fun searchableText(node: SessionTreeNode): String {
    val base = searchableEntryText(node.entry)
    return node.label?.let { "$it $base" } ?: base
}

private fun matchesSearch(node: SessionTreeNode, searchQuery: String): Boolean {
    val tokens = searchQuery.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val text = searchableText(node).lowercase()
    return tokens.all { text.contains(it) }
}

/** Whether [node] survives the empty-tool-turn rule, [filterMode], and any active [searchQuery]. */
internal fun nodePasses(
    node: SessionTreeNode,
    currentLeafId: String?,
    filterMode: TreeFilterMode,
    searchQuery: String,
): Boolean {
    val entry = node.entry
    val isCurrentLeaf = entry.id == currentLeafId
    val passesEmptyToolRule = isCurrentLeaf || !isEmptyAssistantToolTurn(entry)
    val passesFilterMode = filterMode.passes(entry)
    val passesSearch = searchQuery.isBlank() || matchesSearch(node, searchQuery)
    return passesEmptyToolRule && passesFilterMode && passesSearch
}
