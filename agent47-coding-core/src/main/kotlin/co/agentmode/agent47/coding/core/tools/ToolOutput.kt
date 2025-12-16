package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds an [AgentToolResult] by combining the truncated content with
 * optional notices and detail keys. Used by search/listing tools (grep, find, ls)
 * that share the same truncation + notices + details pattern.
 *
 * ```kotlin
 * return buildToolOutput(truncation) {
 *     if (matches.size >= limit) limitReached("matches", limit, "matchLimitReached")
 *     truncationNotice()
 * }
 * ```
 */
public fun buildToolOutput(
    truncation: TruncationResult,
    block: ToolOutputBuilder.() -> Unit,
): AgentToolResult<JsonObject?> {
    val builder = ToolOutputBuilder(truncation).apply(block)
    return builder.build()
}

public class ToolOutputBuilder(
    private val truncation: TruncationResult,
) {
    private val notices = mutableListOf<String>()
    private val detailEntries = mutableListOf<Pair<String, Any>>()

    public fun limitReached(label: String, limit: Int, detailKey: String) {
        notices += "$limit $label limit reached. Use limit=${limit * 2} for more, or refine pattern"
        detailEntries += detailKey to limit
    }

    public fun truncationNotice() {
        if (truncation.truncated) {
            notices += "${formatSize(DEFAULT_MAX_BYTES)} limit reached"
            detailEntries += "truncated" to true
        }
    }

    public fun notice(message: String, detailKey: String? = null) {
        notices += message
        if (detailKey != null) {
            detailEntries += detailKey to true
        }
    }

    internal fun build(): AgentToolResult<JsonObject?> {
        var output = truncation.content
        if (notices.isNotEmpty()) {
            output += "\n\n[${notices.joinToString(". ")}]"
        }

        val details: JsonObject? = if (notices.isEmpty()) {
            null
        } else {
            buildJsonObject {
                detailEntries.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> put(key, value)
                        is Int -> put(key, value)
                        is String -> put(key, value)
                    }
                }
            }
        }

        return AgentToolResult(
            content = listOf(TextContent(text = output)),
            details = details,
        )
    }
}
