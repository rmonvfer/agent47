package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
public data class TodoItem(
    val id: String,
    val content: String,
    val status: String,
    val priority: String,
)

/**
 * Holds the todo list state for a session. Thread-safe via synchronized access.
 * Each call to [update] replaces the entire list (matching OpenCode's replace-all strategy).
 */
public class TodoState {
    private val items = mutableListOf<TodoItem>()
    private val listeners = mutableListOf<(List<TodoItem>) -> Unit>()

    @Synchronized
    public fun update(newItems: List<TodoItem>) {
        items.clear()
        items.addAll(newItems)
        val snapshot = items.toList()
        listeners.forEach { it(snapshot) }
    }

    @Synchronized
    public fun getAll(): List<TodoItem> {
        return items.toList()
    }

    @Synchronized
    public fun addListener(listener: (List<TodoItem>) -> Unit) {
        listeners.add(listener)
    }
}

public class TodoWriteTool(
    private val todoState: TodoState,
) : AgentTool<List<TodoItem>> {

    override val label: String = "todowrite"

    override val definition: ToolDefinition = toolDefinition(
        "todowrite",
        loadToolPrompt("todowrite", "Create and manage a structured task list for the current session."),
    ) {
        array("todos") {
            required = true
            description = "The complete, updated todo list"
            items {
                string("id") { required = true; description = "Unique identifier for the todo item" }
                string("content") { required = true; description = "Brief description of the task" }
                string("status") { required = true; description = "Current status: pending, in_progress, completed, cancelled" }
                string("priority") { required = true; description = "Priority level: high, medium, low" }
            }
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<List<TodoItem>>?,
    ): AgentToolResult<List<TodoItem>> {
        val todosArray = parameters["todos"]?.jsonArray
            ?: error("Missing required parameter: todos")

        val validStatuses = setOf("pending", "in_progress", "completed", "cancelled")
        val validPriorities = setOf("high", "medium", "low")

        val items = todosArray.mapIndexed { index, element ->
            val obj = element.jsonObject
            val id = obj.string("id") ?: error("Todo at index $index missing 'id'")
            val content = obj.string("content") ?: error("Todo at index $index missing 'content'")
            val status = obj.string("status") ?: error("Todo at index $index missing 'status'")
            val priority = obj.string("priority") ?: error("Todo at index $index missing 'priority'")

            require(status in validStatuses) {
                "Todo '$id' has invalid status '$status'. Must be one of: $validStatuses"
            }
            require(priority in validPriorities) {
                "Todo '$id' has invalid priority '$priority'. Must be one of: $validPriorities"
            }

            TodoItem(id = id, content = content, status = status, priority = priority)
        }

        todoState.update(items)

        val completed = items.count { it.status == "completed" }
        val inProgress = items.count { it.status == "in_progress" }
        val pending = items.count { it.status == "pending" }
        val cancelled = items.count { it.status == "cancelled" }

        val summary = buildString {
            appendLine("Updated todo list (${items.size} items):")
            if (completed > 0) appendLine("  Completed: $completed")
            if (inProgress > 0) appendLine("  In Progress: $inProgress")
            if (pending > 0) appendLine("  Pending: $pending")
            if (cancelled > 0) appendLine("  Cancelled: $cancelled")
        }

        return AgentToolResult(
            content = listOf(TextContent(text = summary)),
            details = items,
        )
    }
}
