package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.coding.core.agents.SubAgentResult
import kotlinx.serialization.json.JsonObject

/**
 * Typed details attached to a tool execution result. Each variant carries
 * strongly-typed data for a specific tool so the TUI can render it without
 * parsing raw JSON.
 */
public sealed class ToolDetails {

    /**
     * Generic metadata carried as a JSON object, used by tools that don't
     * need specialised rendering (Read, Edit, Bash, Grep, etc.).
     */
    public data class Generic(val json: JsonObject) : ToolDetails()

    /**
     * Todo list state produced by the todowrite tool.
     */
    public data class Todo(val items: List<TodoItem>) : ToolDetails()

    /**
     * Results from the batch tool (parallel tool execution).
     */
    public data class Batch(val results: List<BatchToolCallResult>) : ToolDetails()

    /**
     * Results from the task tool (sub-agent execution).
     */
    public data class SubAgent(val results: List<SubAgentResult>) : ToolDetails()

    public companion object {
        /**
         * Converts an untyped `AgentToolResult.details` value into a [ToolDetails]
         * variant based on the tool name, or returns `null` if there's nothing
         * meaningful to carry.
         */
        @Suppress("UNCHECKED_CAST")
        public fun from(toolName: String, details: Any?): ToolDetails? {
            if (details == null) return null
            return when (toolName) {
                "todowrite" -> {
                    val items = details as? List<*> ?: return null
                    Todo(items.filterIsInstance<TodoItem>())
                }
                "batch" -> {
                    val results = details as? List<*> ?: return null
                    Batch(results.filterIsInstance<BatchToolCallResult>())
                }
                "task" -> {
                    val results = details as? List<*> ?: return null
                    SubAgent(results.filterIsInstance<SubAgentResult>())
                }
                else -> {
                    (details as? JsonObject)?.let { Generic(it) }
                }
            }
        }
    }
}
