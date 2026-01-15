package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.AssistantMessageEvent
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.serialization.json.JsonObject

public fun interface AgentStreamFunction {
    public suspend fun invoke(
        model: Model,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream
}

public enum class AgentThinkingLevel {
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

public data class AgentToolResult<T>(
    val content: List<ContentBlock>,
    val details: T,
)

public fun interface AgentToolUpdateCallback<T> {
    public fun onUpdate(partialResult: AgentToolResult<T>): Unit
}

/**
 * A tool that agents can invoke during the agentic loop. Implementations provide a
 * [definition] (name, description, JSON Schema parameters) that is sent to the LLM,
 * and an [execute] method that runs when the model calls the tool.
 *
 * The type parameter [T] represents the tool-specific detail type returned alongside
 * content blocks in [AgentToolResult]. The agent loop calls [execute] for each
 * [ToolCall] in the assistant's response and feeds the results back as
 * [ToolResultMessage] entries.
 */
public interface AgentTool<T> {
    public val definition: ToolDefinition
    public val label: String

    public suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<T>? = null,
    ): AgentToolResult<T>
}

public data class AgentContext(
    val systemPrompt: String,
    val messages: MutableList<Message>,
    val tools: List<AgentTool<*>> = emptyList(),
)

public data class AgentState(
    val systemPrompt: String,
    val model: Model,
    val thinkingLevel: AgentThinkingLevel,
    val tools: List<AgentTool<*>>,
    val messages: List<Message>,
    val isStreaming: Boolean,
    val streamMessage: Message?,
    val pendingToolCalls: Set<String>,
    val error: String? = null,
)

public sealed interface AgentEvent {
    public val type: String
}

public data class AgentStartEvent(
    override val type: String = "agent_start",
) : AgentEvent

public data class AgentEndEvent(
    val messages: List<Message>,
    override val type: String = "agent_end",
) : AgentEvent

public data class TurnStartEvent(
    override val type: String = "turn_start",
) : AgentEvent

public data class TurnEndEvent(
    val message: Message,
    val toolResults: List<ToolResultMessage>,
    override val type: String = "turn_end",
) : AgentEvent

public data class MessageStartEvent(
    val message: Message,
    override val type: String = "message_start",
) : AgentEvent

public data class MessageUpdateEvent(
    val message: Message,
    val assistantMessageEvent: AssistantMessageEvent,
    override val type: String = "message_update",
) : AgentEvent

public data class MessageEndEvent(
    val message: Message,
    override val type: String = "message_end",
) : AgentEvent

public data class ToolExecutionStartEvent(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    override val type: String = "tool_execution_start",
) : AgentEvent

public data class ToolExecutionUpdateEvent(
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
    val partialResult: AgentToolResult<*>,
    override val type: String = "tool_execution_update",
) : AgentEvent

public data class ToolExecutionEndEvent(
    val toolCallId: String,
    val toolName: String,
    val result: AgentToolResult<*>,
    val isError: Boolean,
    override val type: String = "tool_execution_end",
) : AgentEvent

/**
 * Configuration for a single run of the agent loop.
 *
 * [convertToLlm] transforms the raw message history into the list sent to the LLM,
 * typically via [defaultConvertToLlm] which strips error turns and inserts synthetic
 * tool results for orphaned calls. [transformContext] is an optional second pass that
 * can further modify messages (e.g. for overflow trimming).
 *
 * [getSteeringMessages] and [getFollowUpMessages] are polled by the loop between turns.
 * Steering messages interrupt tool execution; follow-up messages are injected after the
 * model stops generating.
 */
public data class AgentLoopConfig(
    val model: Model,
    val reasoning: co.agentmode.agent47.ai.types.ThinkingLevel? = null,
    val sessionId: String? = null,
    val thinkingBudgets: co.agentmode.agent47.ai.types.ThinkingBudgets? = null,
    val maxRetryDelayMs: Long? = null,
    val convertToLlm: suspend (List<Message>) -> List<Message>,
    val transformContext: (suspend (List<Message>) -> List<Message>)? = null,
    val getApiKey: (suspend (provider: String) -> String?)? = null,
    val getSteeringMessages: (suspend () -> List<Message>)? = null,
    val getFollowUpMessages: (suspend () -> List<Message>)? = null,
    val beforeAgent: (suspend (List<Message>) -> List<Message>)? = null,
    val afterAgent: (suspend (List<Message>) -> Unit)? = null,
)

public fun defaultConvertToLlm(messages: List<Message>): List<Message> {
    val removeIndices = mutableSetOf<Int>()
    val insertions = mutableMapOf<Int, Message>()

    for (i in messages.indices) {
        val msg = messages[i]
        if (msg !is AssistantMessage || msg.stopReason != StopReason.ERROR) continue
        removeIndices.add(i)

        // Walk backwards: remove tool results, then the tool-call assistant message
        val summaryParts = mutableListOf<String>()
        var j = i - 1
        while (j >= 0) {
            val prev = messages[j]
            if (prev is ToolResultMessage) {
                val errorTag = if (prev.isError) " (error)" else ""
                val text = prev.content.filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }.take(200)
                summaryParts.add("Tool '${prev.toolName}'$errorTag: $text")
                removeIndices.add(j)
                j--
            } else if (prev is AssistantMessage && prev.content.any { it is ToolCall }) {
                removeIndices.add(j)
                break
            } else {
                break
            }
        }

        if (summaryParts.isNotEmpty()) {
            val summary = summaryParts.reversed().joinToString("\n")
            insertions[i] = UserMessage(
                content = listOf(
                    TextContent(
                        text = "[A previous tool exchange failed and was removed from context.\n$summary\nThe follow-up request failed: ${msg.errorMessage ?: "unknown error"}]"
                    )
                ),
                timestamp = msg.timestamp,
            )
        }
    }

    val sanitized = messages.flatMapIndexed { index, message ->
        when {
            index in removeIndices && index in insertions -> listOf(insertions[index]!!)
            index in removeIndices -> emptyList()
            message is CompactionSummaryMessage -> listOf(
                UserMessage(
                    content = listOf(
                        TextContent(text = "[Previous context summary]\n${message.summary}"),
                    ),
                    timestamp = message.timestamp,
                )
            )
            message.role == "user" || message.role == "assistant" || message.role == "toolResult" -> listOf(message)
            else -> emptyList()
        }
    }

    return insertSyntheticToolResults(sanitized)
}

public fun insertSyntheticToolResults(messages: List<Message>): List<Message> {
    val result = mutableListOf<Message>()

    for (i in messages.indices) {
        result.add(messages[i])
        val msg = messages[i]
        if (msg !is AssistantMessage) continue

        val toolCalls = msg.content.filterIsInstance<ToolCall>()
        if (toolCalls.isEmpty()) continue

        val matchedIds = mutableSetOf<String>()
        for (j in (i + 1) until messages.size) {
            val candidate = messages[j]
            if (candidate is ToolResultMessage && candidate.toolCallId in toolCalls.map { it.id }) {
                matchedIds.add(candidate.toolCallId)
            }
            if (candidate is AssistantMessage) break
        }

        for (call in toolCalls) {
            if (call.id !in matchedIds) {
                result.add(
                    ToolResultMessage(
                        toolCallId = call.id,
                        toolName = call.name,
                        content = listOf(TextContent(text = "Tool execution was aborted")),
                        details = null,
                        isError = true,
                        timestamp = msg.timestamp,
                    )
                )
            }
        }
    }

    return result
}

public fun createSkippedToolResult(toolCall: ToolCall): ToolResultMessage {
    return ToolResultMessage(
        toolCallId = toolCall.id,
        toolName = toolCall.name,
        content = listOf(TextContent(text = "Skipped due to queued user message.")),
        details = null,
        isError = true,
        timestamp = System.currentTimeMillis(),
    )
}
