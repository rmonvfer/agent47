package co.agentmode.agent47.ai.types

import kotlinx.serialization.Serializable

@Serializable
public sealed interface AssistantMessageEvent {
    public val type: String
}

@Serializable
public data class StartEvent(
    override val type: String = "start",
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class TextStartEvent(
    override val type: String = "text_start",
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class TextDeltaEvent(
    override val type: String = "text_delta",
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class TextEndEvent(
    override val type: String = "text_end",
    val contentIndex: Int,
    val content: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ThinkingStartEvent(
    override val type: String = "thinking_start",
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ThinkingDeltaEvent(
    override val type: String = "thinking_delta",
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ThinkingEndEvent(
    override val type: String = "thinking_end",
    val contentIndex: Int,
    val content: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ToolCallStartEvent(
    override val type: String = "toolcall_start",
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ToolCallDeltaEvent(
    override val type: String = "toolcall_delta",
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ToolCallEndEvent(
    override val type: String = "toolcall_end",
    val contentIndex: Int,
    val toolCall: ToolCall,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class DoneEvent(
    override val type: String = "done",
    val reason: StopReason,
    val message: AssistantMessage,
) : AssistantMessageEvent

@Serializable
public data class ErrorEvent(
    override val type: String = "error",
    val reason: StopReason,
    val error: AssistantMessage,
) : AssistantMessageEvent
