package co.agentmode.agent47.app.compaction

import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.BRANCH_SUMMARY_PREAMBLE
import co.agentmode.agent47.coding.core.session.BRANCH_SUMMARY_PROMPT
import kotlinx.coroutines.CancellationException

internal data class BranchSummaryResult(
    val summary: String? = null,
    val aborted: Boolean = false,
    val error: String? = null,
)

/**
 * Summarizes an abandoned session branch for context when navigation returns to it later.
 * Renders [messages] into a plain-text transcript - so the model treats it as material to
 * summarize rather than a conversation to continue - and runs a single completion against it.
 */
internal suspend fun generateBranchSummary(
    messages: List<Message>,
    model: Model,
    aiRuntime: AiRuntime,
    modelRegistry: ModelRegistry,
    customInstructions: String? = null,
): BranchSummaryResult {
    if (messages.isEmpty()) {
        return BranchSummaryResult(summary = "No content to summarize")
    }

    val instructions = if (customInstructions != null) {
        "$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: $customInstructions"
    } else {
        BRANCH_SUMMARY_PROMPT
    }
    val promptText = "<conversation>\n${serializeConversation(messages)}\n</conversation>\n\n$instructions"
    val context = Context(
        messages = listOf(
            UserMessage(content = listOf(TextContent(text = promptText)), timestamp = System.currentTimeMillis()),
        ),
    )

    val result = runCatching {
        aiRuntime.completeSimple(
            model,
            context,
            SimpleStreamOptions(apiKey = modelRegistry.getApiKeyForProvider(model.provider.value), maxTokens = 2048),
        )
    }
    // Cancellation must propagate rather than be reported as a summarization error - the caller
    // distinguishes an aborted request (retry later) from one that genuinely failed.
    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }

    return result.fold(
        onSuccess = { response -> responseToResult(response) },
        onFailure = { error -> BranchSummaryResult(error = error.message ?: "Summarization failed") },
    )
}

private fun responseToResult(response: AssistantMessage): BranchSummaryResult = when (response.stopReason) {
    StopReason.ABORTED -> BranchSummaryResult(aborted = true)
    StopReason.ERROR -> BranchSummaryResult(error = response.errorMessage ?: "Summarization failed")
    else -> {
        val summaryText = response.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        BranchSummaryResult(summary = BRANCH_SUMMARY_PREAMBLE + summaryText.ifBlank { "No summary generated" })
    }
}

private fun serializeConversation(messages: List<Message>): String {
    val parts = mutableListOf<String>()
    for (message in messages) {
        when (message) {
            is UserMessage -> serializeUser(message)?.let(parts::add)
            is AssistantMessage -> parts += serializeAssistant(message)
            is ToolResultMessage -> serializeToolResult(message)?.let(parts::add)
            is CustomMessage -> serializeCustom(message)?.let(parts::add)
            is BashExecutionMessage -> parts += "[Bash]: ${message.command}\n${message.output}"
            is BranchSummaryMessage -> parts += "[Previous branch summary]: ${message.summary}"
            is CompactionSummaryMessage -> parts += "[Previous summary]: ${message.summary}"
        }
    }
    return parts.joinToString("\n\n")
}

private fun serializeUser(message: UserMessage): String? {
    val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    return "[User]: $text".takeIf { text.isNotEmpty() }
}

private fun serializeAssistant(message: AssistantMessage): List<String> {
    val parts = mutableListOf<String>()
    val thinking = message.content.filterIsInstance<ThinkingContent>().joinToString("\n") { it.thinking }
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val toolCalls = message.content.filterIsInstance<ToolCall>()
        .joinToString("; ") { call -> "${call.name}(${call.arguments.toString().take(200)})" }
    if (thinking.isNotEmpty()) parts += "[Assistant thinking]: $thinking"
    if (text.isNotEmpty()) parts += "[Assistant]: $text"
    if (toolCalls.isNotEmpty()) parts += "[Assistant tool calls]: $toolCalls"
    return parts
}

private fun serializeToolResult(message: ToolResultMessage): String? {
    val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    return "[Tool result]: $text".takeIf { text.isNotEmpty() }
}

private fun serializeCustom(message: CustomMessage): String? {
    val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
    return "[${message.customType}]: $text".takeIf { text.isNotEmpty() }
}
