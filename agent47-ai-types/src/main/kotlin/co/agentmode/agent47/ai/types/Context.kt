package co.agentmode.agent47.ai.types

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Describes a tool that the LLM can invoke. The [parameters] object is a JSON Schema
 * defining the tool's input. Providers serialize this differently (OpenAI wraps it in
 * a `function` object, Anthropic uses `input_schema`, Google uses `functionDeclarations`),
 * but the agent layer works with this uniform representation.
 */
@Serializable
public data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * Everything needed to make a single LLM API call: the system prompt, conversation
 * history, and available tools. Built by the agent loop from [AgentContext][co.agentmode.agent47.agent.core.AgentContext]
 * and passed to providers via [ApiProvider][co.agentmode.agent47.ai.core.providers.ApiProvider].
 */
@Serializable
public data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<ToolDefinition>? = null,
)
