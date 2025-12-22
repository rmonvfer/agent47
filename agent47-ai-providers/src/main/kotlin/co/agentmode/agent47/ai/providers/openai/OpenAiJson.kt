package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.core.providers.contentOrNull
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun buildOpenAiMessages(context: Context, model: Model, compat: OpenAiCompat): JsonArray {
    return buildJsonArray {
        if (!context.systemPrompt.isNullOrBlank()) {
            val role = if (model.reasoning && compat.supportsDeveloperRole) "developer" else "system"
            add(buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(context.systemPrompt))
            })
        }
        context.messages.forEach { message ->
            add(message.toOpenAiMessage(compat))
        }
    }
}

private fun Message.toOpenAiMessage(compat: OpenAiCompat): JsonObject {
    return when (this) {
        is UserMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", contentToOpenAi(content, compat))
        }

        is AssistantMessage -> buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            put("content", contentToOpenAi(content, compat))
            val toolCalls = content.filterIsInstance<ToolCall>()
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(normalizeToolCallId(call.id, compat)))
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(call.name))
                                            put("arguments", JsonPrimitive(call.arguments.toString()))
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

        is ToolResultMessage -> buildJsonObject {
            put("role", JsonPrimitive("tool"))
            put("tool_call_id", JsonPrimitive(normalizeToolCallId(toolCallId, compat)))
            put("name", JsonPrimitive(toolName))
            put("content", contentToOpenAi(content, compat))
        }

        is CustomMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", contentToOpenAi(content, compat))
        }

        is BashExecutionMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", JsonPrimitive("<bash command=\"${command}\">\n${output}\n</bash>"))
        }

        is BranchSummaryMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", JsonPrimitive(summary))
        }

        is CompactionSummaryMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", JsonPrimitive(summary))
        }
    }
}

internal fun contentToText(content: List<ContentBlock>): String {
    return content.joinToString(separator = "\n") { block ->
        when (block) {
            is TextContent -> block.text
            is ThinkingContent -> "<thinking>\n${block.thinking}\n</thinking>"
            is ToolCall -> "<tool_call name=\"${block.name}\">${block.arguments}</tool_call>"
            else -> ""
        }
    }.trim()
}

private fun contentToOpenAi(content: List<ContentBlock>, compat: OpenAiCompat): JsonElement {
    // ToolCall blocks are handled separately via the top-level "tool_calls" field
    // in toOpenAiMessage(). Exclude them from content to avoid sending unsupported
    // types (e.g. "tool_call") inside the content array, which strict APIs reject.
    val contentBlocks = content.filter { it !is ToolCall }

    // When compat requires thinking as plain text, convert ThinkingContent to TextContent
    val resolved = if (compat.requiresThinkingAsText) {
        contentBlocks.map { block ->
            if (block is ThinkingContent) TextContent(text = "<thinking>\n${block.thinking}\n</thinking>")
            else block
        }
    } else {
        contentBlocks
    }

    val textOnly = resolved.filterIsInstance<TextContent>()
    if (textOnly.isNotEmpty() && textOnly.size == resolved.size) {
        return JsonPrimitive(textOnly.joinToString("\n") { it.text })
    }

    if (resolved.isEmpty()) {
        return JsonPrimitive("")
    }

    return buildJsonArray {
        resolved.forEach { block ->
            when (block) {
                is TextContent -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(block.text))
                    },
                )

                is ThinkingContent -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("<thinking>\n${block.thinking}\n</thinking>"))
                    },
                )

                else -> add(JsonNull)
            }
        }
    }
}

internal fun normalizeToolCallId(id: String, compat: OpenAiCompat): String {
    if (!compat.requiresMistralToolIds) return id
    val alphanumeric = id.replace(Regex("[^a-zA-Z0-9]"), "")
    return when {
        alphanumeric.length >= 9 -> alphanumeric.take(9)
        else -> alphanumeric.padEnd(9, '0')
    }
}

internal fun parseToolCallsFromCompletions(choice: JsonObject): List<ToolCall> {
    val calls = choice["message"]
        ?.jsonObject
        ?.get("tool_calls")
        ?.jsonArray
        ?: return emptyList()

    return calls.mapNotNull { entry ->
        val call = entry.jsonObject
        val id = call["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val function = call["function"]?.jsonObject ?: return@mapNotNull null
        val name = function["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val argumentsRaw = function["arguments"]?.jsonPrimitive?.content ?: "{}"
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(argumentsRaw).jsonObject
        }.getOrElse {
            buildJsonObject { }
        }
        ToolCall(id = id, name = name, arguments = args)
    }
}

