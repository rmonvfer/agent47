package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.agent.core.AgentEndEvent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentStartEvent
import co.agentmode.agent47.agent.core.MessageEndEvent
import co.agentmode.agent47.agent.core.MessageStartEvent
import co.agentmode.agent47.agent.core.MessageUpdateEvent
import co.agentmode.agent47.agent.core.RetryEvent
import co.agentmode.agent47.agent.core.ToolExecutionEndEvent
import co.agentmode.agent47.agent.core.ToolExecutionStartEvent
import co.agentmode.agent47.agent.core.ToolExecutionUpdateEvent
import co.agentmode.agent47.agent.core.TurnEndEvent
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns the conversation loop: submitting user messages, translating agent events into chat and
 * usage state, the prompt-job lifecycle, and draining queued push notifications.
 */
@Stable
internal class ConversationController(
    private val state: TuiAppState,
    private val client: AgentClient,
    private val feed: TranscriptFeed,
    private val scope: CoroutineScope,
) {
    @Suppress("TooGenericExceptionCaught")
    fun submitMessage(message: UserMessage) {
        state.chatHistory.appendMessage(message)
        state.activeSessionManager?.appendMessage(message)

        if (state.isStreaming) {
            // The user message has already been appended above, so it shows in the transcript
            // right below the in-flight response. Do NOT append an assistant-style "queued" note:
            // appendSystemMessage builds an AssistantMessage, which would steal the active-assistant
            // slot and cause the still-streaming response to write into the note's entry.
            runCatching {
                client.followUp(message)
            }.onFailure { error ->
                feed.appendSystemMessage("Failed to queue follow-up: ${error.message ?: error::class.simpleName}")
            }
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            try {
                client.prompt(listOf(message))
            } catch (_: CancellationException) {
            } catch (error: Throwable) {
                feed.appendSystemMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
            } finally {
                state.currentPromptJob = null
            }
        }
        state.currentPromptJob = job
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun onAgentEvent(event: AgentEvent) {
        val chatHistoryState = state.chatHistory
        val toolArgumentsById = state.toolArgumentsById
        when (event) {
            is AgentStartEvent -> {
                state.isStreaming = true
                state.liveActivityLabel = "Thinking"
            }

            is AgentEndEvent -> {
                state.isStreaming = false
                state.currentPromptJob = null
                state.liveActivityLabel = ""
            }

            is RetryEvent -> {
                val seconds = (event.delayMs + 999L) / 1000L
                state.liveActivityLabel = "Retrying (${event.attempt}/${event.maxAttempts}) in ${seconds}s"
            }

            is MessageStartEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.startAssistantMessage(message)
                    // Clear any lingering "Retrying..." label once the model starts responding again.
                    state.liveActivityLabel = "Thinking"
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.appendMessage(message)
                }
            }

            is MessageUpdateEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.updateAssistantMessage(message)
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.updateMessage(message)
                }
            }

            is MessageEndEvent -> {
                val message = event.message
                if (message is AssistantMessage) {
                    chatHistoryState.endAssistantMessage(message)
                } else if (message !is ToolResultMessage) {
                    chatHistoryState.updateMessage(message)
                }
                // User messages are already persisted in submitMessage / initialUserMessage handling.
                // Only persist assistant and tool result messages from the event stream.
                if (message !is UserMessage) {
                    state.activeSessionManager?.appendMessage(message)
                }
            }

            is ToolExecutionStartEvent -> {
                toolArgumentsById[event.toolCallId] = event.arguments.toString()
                state.liveActivityLabel = "Running ${event.toolName}"
                chatHistoryState.appendToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = "",
                        pending = true,
                        collapsed = defaultToolCollapsed(event.toolName),
                        startedAt = System.currentTimeMillis(),
                    ),
                )
            }

            is ToolExecutionUpdateEvent -> {
                val output = event.partialResult.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                chatHistoryState.updateToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = output,
                        details = ToolDetails.from(event.toolName, event.partialResult.details),
                        pending = true,
                        collapsed = defaultToolCollapsed(event.toolName),
                    ),
                )
            }

            is ToolExecutionEndEvent -> {
                val output = event.result.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                state.liveActivityLabel = "Thinking"
                chatHistoryState.updateToolExecution(
                    ToolExecutionView(
                        toolCallId = event.toolCallId,
                        toolName = event.toolName,
                        arguments = toolArgumentsById[event.toolCallId].orEmpty(),
                        output = output,
                        isError = event.isError,
                        pending = false,
                        details = ToolDetails.from(event.toolName, event.result.details),
                        collapsed = defaultToolCollapsed(event.toolName),
                    ),
                )
                toolArgumentsById.remove(event.toolCallId)
            }

            is TurnEndEvent -> {
                val assistant = event.message as? AssistantMessage
                if (assistant != null) {
                    state.usage.add(assistant.usage)
                }
            }

            else -> {
                // no-op for TurnStartEvent and any future event types
            }
        }
    }

    fun deliverPushQueue() {
        // Deliver any queued push notifications to the orchestrator: follow-up while it is
        // mid-turn, otherwise start a fresh turn so it reacts to the completion.
        while (true) {
            val text = state.pushQueue.poll() ?: break
            val msg = UserMessage(content = listOf(TextContent(text = text)), timestamp = System.currentTimeMillis())
            if (state.isStreaming) {
                runCatching { client.followUp(msg) }
            } else {
                state.chatHistory.appendMessage(msg)
                state.activeSessionManager?.appendMessage(msg)
                scope.launch(Dispatchers.IO) { runCatching { client.prompt(listOf(msg)) } }
            }
        }
    }
}

private fun defaultToolCollapsed(toolName: String): Boolean = when (toolName.lowercase()) {
    "read", "grep", "find", "ls" -> true
    else -> false
}
