package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.types.AssistantMessageEvent
import co.agentmode.agent47.ai.types.AssistantMessageEventStream
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.ai.types.ToolResultMessage
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
)

public fun defaultConvertToLlm(messages: List<Message>): List<Message> {
    return messages.filter { message ->
        message.role == "user" || message.role == "assistant" || message.role == "toolResult"
    }
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
