package co.agentmode.agent47.ui.core.util

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
        "read" -> json?.get("path")?.jsonPrimitive?.content
            ?: json?.get("file_path")?.jsonPrimitive?.content
        "edit" -> json?.get("path")?.jsonPrimitive?.content
            ?: json?.get("file_path")?.jsonPrimitive?.content
        "multiedit" -> json?.get("path")?.jsonPrimitive?.content
            ?: json?.get("file_path")?.jsonPrimitive?.content
        "write" -> json?.get("path")?.jsonPrimitive?.content
            ?: json?.get("file_path")?.jsonPrimitive?.content
        "bash" -> json?.get("command")?.jsonPrimitive?.content?.replace('\n', ' ')
        "grep" -> json?.get("pattern")?.jsonPrimitive?.content
        "glob", "find" -> json?.get("pattern")?.jsonPrimitive?.content
            ?: json?.get("glob")?.jsonPrimitive?.content
        "ls" -> json?.get("path")?.jsonPrimitive?.content ?: "."
        "todowrite" -> json?.get("todos")?.let { "${it}" }?.take(maxWidth)
        "todocreate", "todoupdate", "todoread" -> json?.get("subject")?.jsonPrimitive?.content
            ?: json?.get("taskId")?.jsonPrimitive?.content
        "task" -> {
            val desc = json?.get("description")?.jsonPrimitive?.content
            val agentType = json?.get("subagent_type")?.jsonPrimitive?.content
            if (agentType != null && desc != null) "$agentType ($desc)" else desc ?: agentType
        }
        "batch" -> {
            val count = json?.get("invocations")?.jsonArray?.size
            if (count != null) "$count calls" else null
        }
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
 * Derives a concise one-line summary of a tool's output for collapsed views.
 * Returns a human-readable string like "200 lines", "Applied 1 edit", or "Done".
 */
public fun summarizeToolOutput(toolName: String, output: String, details: ToolDetails?, isError: Boolean): String {
    if (isError) {
        val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return firstLine?.take(80) ?: "Error"
    }

    return when (toolName.lowercase()) {
        "read" -> {
            val lineCount = if (output.isBlank()) 0 else output.lines().size
            val truncated = (details as? ToolDetails.Generic)?.json
                ?.get("truncated")?.jsonPrimitive?.content == "true"
            if (lineCount == 0) "Empty" else "$lineCount lines${if (truncated) " (truncated)" else ""}"
        }
        "write" -> "Done"
        "edit" -> "Applied 1 edit"
        "multiedit" -> {
            val count = (details as? ToolDetails.Generic)?.json
                ?.get("editsApplied")?.jsonPrimitive?.int ?: 1
            "Applied $count edit${if (count != 1) "s" else ""}"
        }
        "bash" -> {
            if (output.isBlank()) "Done"
            else {
                val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                firstLine?.take(60) ?: "Done"
            }
        }
        "grep" -> {
            val lineCount = if (output.isBlank()) 0 else output.lines().count { it.isNotBlank() }
            if (lineCount == 0) "No matches" else "$lineCount matches"
        }
        "glob", "find" -> {
            val lineCount = if (output.isBlank()) 0 else output.lines().count { it.isNotBlank() }
            if (lineCount == 0) "No results" else "$lineCount results"
        }
        "ls" -> {
            val lineCount = if (output.isBlank()) 0 else output.lines().count { it.isNotBlank() }
            if (lineCount == 0) "Empty" else "$lineCount entries"
        }
        else -> if (output.isBlank()) "Done" else {
            val firstLine = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            firstLine?.take(60) ?: "Done"
        }
    }
}

/**
 * Formats a token count into a compact human-readable string.
 * Examples: 500 → "500tok", 96500 → "96.5k tok", 1200000 → "1.2M tok".
 */
public fun formatTokens(tokens: Long): String = when {
    tokens < 1000 -> "${tokens}tok"
    tokens < 1_000_000 -> "${"%.1f".format(tokens / 1000.0)}k tok"
    else -> "${"%.1f".format(tokens / 1_000_000.0)}M tok"
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
