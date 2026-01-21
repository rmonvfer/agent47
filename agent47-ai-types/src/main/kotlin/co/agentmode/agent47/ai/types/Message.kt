package co.agentmode.agent47.ai.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A piece of content within a [Message]. Content blocks are polymorphic: they can represent
 * plain text, model thinking, images, or tool calls. Providers serialize each variant
 * differently, but the agent layer works with them uniformly.
 */
@Serializable
public sealed interface ContentBlock {
    public val type: String
}

/**
 * Plain text content. The [textSignature] is an opaque token used by some providers
 * (notably Anthropic) for cross-turn replay of extended thinking; it is stripped
 * when messages cross provider boundaries.
 */
@Serializable
@SerialName("text")
public data class TextContent(
    override val type: String = "text",
    val text: String,
    val textSignature: String? = null,
) : ContentBlock

/**
 * Extended thinking / chain-of-thought content produced by reasoning models.
 * When messages cross provider boundaries, thinking blocks are converted to
 * [TextContent] wrapped in `<thinking>` tags and the [thinkingSignature] is stripped.
 */
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

/**
 * A tool invocation requested by the model. The [id] is provider-assigned and must be
 * echoed back in the corresponding [ToolResultMessage]. The [thoughtSignature] is an
 * opaque provider token stripped during cross-provider thinking conversion.
 */
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

/**
 * A single entry in a conversation. Messages are polymorphic: user input, assistant
 * responses, tool results, and several synthetic message types (bash execution records,
 * branch summaries, compaction summaries) all implement this interface. Messages are
 * serialized to JSONL for session persistence and converted to provider-specific
 * formats before being sent to the LLM.
 */
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

/**
 * A response from the LLM. Tracks which [api] and [provider] produced it so that
 * cross-provider thinking conversion can distinguish same-provider messages (which
 * keep their signatures intact) from foreign ones. The [stopReason] indicates why
 * the model stopped generating: natural stop, length limit, tool use, or error.
 */
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

/**
 * Represents the result of a tool execution returned to the LLM after invoking a tool.
 * Contains the output content blocks from the tool call along with metadata identifying
 * which tool was invoked and whether execution succeeded or failed. Serialized as part
 * of the conversation history and converted to provider-specific formats before being
 * sent to the LLM.
 */
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
