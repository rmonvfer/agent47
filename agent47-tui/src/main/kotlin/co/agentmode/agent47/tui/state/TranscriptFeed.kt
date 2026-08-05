package co.agentmode.agent47.tui.state

import androidx.compose.runtime.Stable
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ui.core.state.ChatHistoryState

/**
 * Single owner of transcript appends. Every write goes through [ChatHistoryState.appendMessage],
 * which bumps the state version, so the chat view invalidates without a parallel counter.
 */
@Stable
internal class TranscriptFeed(
    private val chat: ChatHistoryState,
) {
    /**
     * Appends a transcript-only status line. System notes are plain text entries: they are
     * never persisted to the session and never attributed to the assistant.
     */
    fun appendSystemMessage(text: String) {
        chat.appendMessage(
            CustomMessage(
                customType = "system_note",
                content = listOf(TextContent(text = text)),
                display = true,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    /** Appends a transcript-only error line, rendered with an Error: prefix. */
    fun appendErrorMessage(text: String) {
        chat.appendMessage(
            CustomMessage(
                customType = "system_error",
                content = listOf(TextContent(text = text)),
                display = true,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    fun showCommandInput(text: String) {
        chat.appendMessage(
            UserMessage(
                content = listOf(TextContent(text = text)),
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    fun appendCommandResult(text: String) {
        chat.appendMessage(
            CustomMessage(
                customType = "command_result",
                content = listOf(TextContent(text = text)),
                display = true,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }
}
