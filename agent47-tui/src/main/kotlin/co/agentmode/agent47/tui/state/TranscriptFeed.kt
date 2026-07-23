package co.agentmode.agent47.tui.state

import androidx.compose.runtime.Stable
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.ui.core.state.ChatHistoryState

/**
 * Single owner of transcript appends. Every write goes through [ChatHistoryState.appendMessage],
 * which bumps the state version, so the chat view invalidates without a parallel counter.
 */
@Stable
internal class TranscriptFeed(
    private val chat: ChatHistoryState,
    private val client: AgentClient,
    private val currentModel: () -> Model?,
) {
    fun appendSystemMessage(text: String) {
        val agentState = client.state
        val model = currentModel()
        val assistant = AssistantMessage(
            content = listOf(TextContent(text = text)),
            api = model?.api ?: agentState.model.api,
            provider = model?.provider ?: agentState.model.provider,
            model = model?.id ?: agentState.model.id,
            usage = emptyUsage(),
            stopReason = StopReason.STOP,
            timestamp = System.currentTimeMillis(),
        )
        chat.appendMessage(assistant)
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
