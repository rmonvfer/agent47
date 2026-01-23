package co.agentmode.agent47.ui.core.state

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.coding.core.tools.ToolDetails

/**
 * Represents a single entry in the chat history, keyed for identity-based updates.
 */
@Stable
public class ChatHistoryEntry(
    public val key: String,
    message: Message? = null,
    toolExecution: ToolExecutionView? = null,
) {
    public var message: Message? by mutableStateOf(message)
    public var toolExecution: ToolExecutionView? by mutableStateOf(toolExecution)
}

/**
 * Holds the full state of the chat history: entries, scroll offset, and
 * per-item collapse toggles. Designed to be driven by agent events from
 * outside Compose (e.g., a LaunchedEffect collecting a Flow).
 *
 * All mutations trigger recomposition automatically because the backing
 * fields are Compose snapshot-state objects.
 */
@Stable
public class ChatHistoryState {
    public val entries: SnapshotStateList<ChatHistoryEntry> = mutableStateListOf()
    public var scrollTopLine: Int by mutableIntStateOf(0)
    public val toolCollapsedState: SnapshotStateMap<String, Boolean> = mutableStateMapOf()
    public val thinkingCollapsedState: SnapshotStateMap<String, Boolean> = mutableStateMapOf()

    private var activeAssistantKey: String? = null
    private var assistantSequence by mutableLongStateOf(0L)
    public var pinnedToBottom: Boolean by mutableStateOf(true)
    public var version: Int by mutableIntStateOf(0)
        private set

    public fun appendMessage(message: Message) {
        if (message is AssistantMessage) {
            startAssistantMessage(message)
            return
        }
        val key = messageKey(message)
        if (entries.any { it.key == key }) return
        entries += ChatHistoryEntry(
            key = key,
            message = message,
            toolExecution = toolExecutionFor(message),
        )
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateMessage(message: Message) {
        if (message is AssistantMessage) {
            updateAssistantMessage(message)
            return
        }
        val key = messageKey(message)
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) {
            appendMessage(message)
            return
        }
        entries[index].message = message
        if (message is ToolResultMessage) {
            entries[index].toolExecution = toolExecutionFor(message)
                ?: entries[index].toolExecution
        }
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun startAssistantMessage(message: AssistantMessage) {
        val key = nextAssistantKey()
        entries += ChatHistoryEntry(key = key, message = message)
        activeAssistantKey = key
        thinkingCollapsedState[key] = true
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateAssistantMessage(message: AssistantMessage) {
        val activeKey = activeAssistantKey
        val activeIndex = if (activeKey != null) entries.indexOfFirst { it.key == activeKey } else -1
        val index = if (activeIndex >= 0) {
            activeIndex
        } else {
            entries.indexOfLast { it.message is AssistantMessage }
        }
        if (index < 0) {
            startAssistantMessage(message)
            return
        }
        entries[index].message = message
        activeAssistantKey = entries[index].key
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun endAssistantMessage(message: AssistantMessage) {
        updateAssistantMessage(message)
        activeAssistantKey = null
    }

    public fun appendToolExecution(execution: ToolExecutionView) {
        val key = "tool:${execution.toolCallId}"
        toolCollapsedState.putIfAbsent(key, defaultToolCollapsed(execution.toolName))
        entries += ChatHistoryEntry(key = key, toolExecution = execution)
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateToolExecution(execution: ToolExecutionView) {
        val key = "tool:${execution.toolCallId}"
        val index = entries.indexOfLast { it.key == key }
        if (index < 0) {
            appendToolExecution(execution)
            return
        }
        val existing = entries[index].toolExecution
        val preservedStartedAt =
            if (execution.startedAt == 0L && existing != null)
                existing.startedAt
            else execution.startedAt

        entries[index].toolExecution = execution.copy(
            collapsed = toolCollapsedState[key] ?: execution.collapsed,
            startedAt = preservedStartedAt,
        )
    }

    public fun scrollUp(lines: Int = 1) {
        scrollTopLine = (scrollTopLine - lines.coerceAtLeast(1)).coerceAtLeast(0)
        pinnedToBottom = false
    }

    public fun scrollDown(lines: Int = 1) {
        scrollTopLine += lines.coerceAtLeast(1)
    }

    public fun toggleLatestToolCollapsed(): Boolean {
        val entry = entries.asReversed().firstOrNull { it.toolExecution != null } ?: return false
        val key = entry.key
        val current = toolCollapsedState[key] ?: false
        toolCollapsedState[key] = !current
        return true
    }

    public fun toggleLatestThinkingCollapsed(): Boolean {
        val entry = entries.asReversed().firstOrNull { e ->
            val msg = e.message
            msg is AssistantMessage && msg.content.any { it is ThinkingContent }
        } ?: return false
        val key = entry.key
        val current = thinkingCollapsedState[key] ?: true
        thinkingCollapsedState[key] = !current
        return true
    }

    public fun hasEntries(): Boolean = entries.isNotEmpty()

    /**
     * Flag consumed by the UI layer to auto-scroll to the bottom on the next frame.
     * Reset after the scroll is applied.
     */
    public var scrollToBottom: Boolean by mutableStateOf(false)

    private fun messageKey(message: Message): String = when (message) {
        is ToolResultMessage -> "tool:${message.toolCallId}"
        else -> "${message.role}:${message.timestamp}"
    }

    private fun nextAssistantKey(): String {
        assistantSequence += 1
        return "assistant-stream:$assistantSequence"
    }

    private fun toolExecutionFor(message: Message): ToolExecutionView? = when (message) {
        is ToolResultMessage -> ToolExecutionView.fromToolResult(
            result = message,
            collapsed = defaultToolCollapsed(message.toolName),
        )

        else -> null
    }

    private fun defaultToolCollapsed(@Suppress("UNUSED_PARAMETER") toolName: String): Boolean = true
}

/**
 * Data model for a tool execution view.
 */
public data class ToolExecutionView(
    val toolCallId: String,
    val toolName: String,
    val arguments: String = "",
    val output: String = "",
    val details: ToolDetails? = null,
    val isError: Boolean = false,
    val pending: Boolean = false,
    val collapsed: Boolean = false,
    val startedAt: Long = 0L,
) {
    public companion object {
        public fun fromToolResult(
            result: ToolResultMessage,
            arguments: String = "",
            collapsed: Boolean = false,
        ): ToolExecutionView {
            val output = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .ifBlank { fallbackOutput(result.content) }

            return ToolExecutionView(
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                arguments = arguments,
                output = output,
                details = result.details?.let { ToolDetails.Generic(it) },
                isError = result.isError,
                pending = false,
                collapsed = collapsed,
            )
        }

        private fun fallbackOutput(content: List<ContentBlock>): String {
            if (content.isEmpty()) return ""
            return content.joinToString("\n") { "[${it.type}]" }
        }
    }
}
