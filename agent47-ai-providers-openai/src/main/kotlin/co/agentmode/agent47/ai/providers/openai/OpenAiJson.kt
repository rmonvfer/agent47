package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.core.providers.contentOrNull
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.CustomMessage
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

internal fun buildOpenAiMessages(context: Context): JsonArray {
    return buildJsonArray {
        context.messages.forEach { message ->
            add(message.toOpenAiMessage())
        }
    }
}

private fun Message.toOpenAiMessage(): JsonObject {
    return when (this) {
        is UserMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", contentToOpenAi(content))
        }

        is AssistantMessage -> buildJsonObject {
            put("role", JsonPrimitive("assistant"))
            put("content", contentToOpenAi(content))
            val toolCalls = content.filterIsInstance<ToolCall>()
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    buildJsonArray {
                        toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(call.id))
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
            put("tool_call_id", JsonPrimitive(toolCallId))
            put("name", JsonPrimitive(toolName))
            put("content", contentToOpenAi(content))
        }

        is CustomMessage -> buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", contentToOpenAi(content))
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

private fun contentToOpenAi(content: List<ContentBlock>): JsonElement {
    val textOnly = content.filterIsInstance<TextContent>()
    if (textOnly.isNotEmpty() && textOnly.size == content.size) {
        return JsonPrimitive(textOnly.joinToString("\n") { it.text })
    }

    return buildJsonArray {
        content.forEach { block ->
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

                is ToolCall -> add(
                    buildJsonObject {
                        put("type", JsonPrimitive("tool_call"))
                        put("id", JsonPrimitive(block.id))
                        put("name", JsonPrimitive(block.name))
                        put("arguments", block.arguments)
                    },
                )

                else -> add(JsonNull)
            }
        }
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

