package co.agentmode.agent47.ai.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public sealed interface ContentBlock {
    public val type: String
}

@Serializable
@SerialName("text")
public data class TextContent(
    override val type: String = "text",
    val text: String,
    val textSignature: String? = null,
) : ContentBlock

@Serializable
@SerialName("thinking")
public data class ThinkingContent(
    override val type: String = "thinking",
    val thinking: String,
    val thinkingSignature: String? = null,
) : ContentBlock

@Serializable
@SerialName("image")
public data class ImageContent(
    override val type: String = "image",
    val data: String,
    val mimeType: String,
) : ContentBlock

@Serializable
@SerialName("toolCall")
public data class ToolCall(
    override val type: String = "toolCall",
    val id: String,
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val thoughtSignature: String? = null,
) : ContentBlock

@Serializable
public data class UsageCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val total: Double,
)

@Serializable
public data class Usage(
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    val totalTokens: Int,
    val cost: UsageCost,
)

public fun emptyUsage(): Usage = Usage(
    input = 0,
    output = 0,
    cacheRead = 0,
    cacheWrite = 0,
    totalTokens = 0,
    cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
)

@Serializable
public enum class StopReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("toolUse")
    TOOL_USE,

    @SerialName("error")
    ERROR,

    @SerialName("aborted")
    ABORTED,
}

@Serializable
public sealed interface Message {
    public val role: String
    public val timestamp: Long
}

@Serializable
@SerialName("user")
public data class UserMessage(
    override val role: String = "user",
    val content: List<ContentBlock>,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("assistant")
public data class AssistantMessage(
    override val role: String = "assistant",
    val content: List<ContentBlock>,
    val api: ApiId,
    val provider: ProviderId,
    val model: String,
    val usage: Usage,
    val stopReason: StopReason,
    val errorMessage: String? = null,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("toolResult")
public data class ToolResultMessage(
    override val role: String = "toolResult",
    val toolCallId: String,
    val toolName: String,
    val content: List<ContentBlock>,
    val details: JsonObject? = null,
    val isError: Boolean,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("custom")
public data class CustomMessage(
    override val role: String = "custom",
    val customType: String,
    val content: List<ContentBlock>,
    val display: Boolean = true,
    val details: JsonObject? = null,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("bashExecution")
public data class BashExecutionMessage(
    override val role: String = "bashExecution",
    val command: String,
    val output: String,
    val exitCode: Int?,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("branchSummary")
public data class BranchSummaryMessage(
    override val role: String = "branchSummary",
    val fromId: String,
    val summary: String,
    override val timestamp: Long,
) : Message

@Serializable
@SerialName("compactionSummary")
public data class CompactionSummaryMessage(
    override val role: String = "compactionSummary",
    val summary: String,
    val tokensBefore: Int,
    override val timestamp: Long,
) : Message
