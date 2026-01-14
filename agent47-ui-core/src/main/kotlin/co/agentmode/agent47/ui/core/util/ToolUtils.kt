package co.agentmode.agent47.ui.core.util

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Extracts a concise summary from a tool's JSON arguments string based on the tool name.
 * Returns the most relevant field (file path, command, pattern, etc.) truncated to [maxWidth].
 */
public fun summarizeToolArguments(toolName: String, arguments: String, maxWidth: Int): String {
    val json = runCatching {
        Json.parseToJsonElement(arguments) as? JsonObject
    }.getOrNull()

    val raw = when (toolName.lowercase()) {
        "read" -> json?.get("file_path")?.jsonPrimitive?.content
        "edit" -> json?.get("file_path")?.jsonPrimitive?.content
        "multiedit" -> json?.get("file_path")?.jsonPrimitive?.content
        "write" -> json?.get("file_path")?.jsonPrimitive?.content
        "bash" -> json?.get("command")?.jsonPrimitive?.content?.replace('\n', ' ')
        "grep" -> json?.get("pattern")?.jsonPrimitive?.content
        "find" -> json?.get("pattern")?.jsonPrimitive?.content
            ?: json?.get("glob")?.jsonPrimitive?.content
        "ls" -> json?.get("path")?.jsonPrimitive?.content ?: "."
        "todowrite" -> json?.get("todos")?.let { "${it}" }?.take(maxWidth)
        "task" -> json?.get("description")?.jsonPrimitive?.content
        else -> null
    } ?: return arguments.take(maxWidth)

    return if (raw.length > maxWidth) raw.take(maxWidth - 1) + "\u2026" else raw
}

public fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        "${minutes}m${seconds}s"
    }
}

/**
 * Extracts the first line of text from the last [UserMessage] in the entries list.
 * Returns null if no user message exists.
 */
public fun findLastUserPromptText(entries: List<ChatHistoryEntry>): String? {
    val msg = entries.lastOrNull { it.message is UserMessage }?.message as? UserMessage ?: return null
    val text = msg.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
    return text.ifBlank { null }
}
