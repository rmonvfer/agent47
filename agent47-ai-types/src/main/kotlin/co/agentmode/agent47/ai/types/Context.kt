package co.agentmode.agent47.ai.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
public data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<ToolDefinition>? = null,
)
