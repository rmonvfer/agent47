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
import java.util.UUID

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
    public fun add(item: TodoItem) {
        items.add(item)
        val snapshot = items.toList()
        listeners.forEach { it(snapshot) }
    }

    @Synchronized
    public fun updateItem(
        id: String,
        content: String? = null,
        status: String? = null,
        priority: String? = null,
    ): TodoItem? {
        val index = items.indexOfFirst { it.id == id }
        if (index == -1) return null
        val current = items[index]
        val updated = current.copy(
            content = content ?: current.content,
            status = status ?: current.status,
            priority = priority ?: current.priority,
        )
        items[index] = updated
        val snapshot = items.toList()
        listeners.forEach { it(snapshot) }
        return updated
    }

    @Synchronized
    public fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) {
            val snapshot = items.toList()
            listeners.forEach { it(snapshot) }
        }
        return removed
    }

    @Synchronized
    public fun addListener(listener: (List<TodoItem>) -> Unit) {
        listeners.add(listener)
    }
}

private fun generateTodoId(): String = UUID.randomUUID().toString().substring(0, 8)

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

public class TodoReadTool(
    private val todoState: TodoState,
) : AgentTool<List<TodoItem>> {

    override val label: String = "todoread"

    override val definition: ToolDefinition = toolDefinition(
        "todoread",
        loadToolPrompt("todoread", "Read the current todo list to see all items and their statuses."),
    ) {}

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<List<TodoItem>>?,
    ): AgentToolResult<List<TodoItem>> {
        val items = todoState.getAll()

        if (items.isEmpty()) {
            return AgentToolResult(
                content = listOf(TextContent(text = "No todos found. The todo list is empty.")),
                details = items,
            )
        }

        val completed = items.count { it.status == "completed" }
        val inProgress = items.count { it.status == "in_progress" }
        val pending = items.count { it.status == "pending" }
        val cancelled = items.count { it.status == "cancelled" }

        val summary = buildString {
            appendLine("Todo list (${items.size} items):")
            if (completed > 0) appendLine("  Completed: $completed")
            if (inProgress > 0) appendLine("  In Progress: $inProgress")
            if (pending > 0) appendLine("  Pending: $pending")
            if (cancelled > 0) appendLine("  Cancelled: $cancelled")
            appendLine()
            for (item in items) {
                appendLine("[${item.id}] (${item.status}) [${item.priority}] ${item.content}")
            }
        }

        return AgentToolResult(
            content = listOf(TextContent(text = summary)),
            details = items,
        )
    }
}

public class TodoCreateTool(
    private val todoState: TodoState,
) : AgentTool<TodoItem> {

    override val label: String = "todocreate"

    override val definition: ToolDefinition = toolDefinition(
        "todocreate",
        loadToolPrompt("todocreate", "Create a single todo item and add it to the list."),
    ) {
        string("content") {
            required = true
            description = "Brief description of the task"
        }
        enum("status", listOf("pending", "in_progress", "completed", "cancelled")) {
            description = "Initial status (default: pending)"
        }
        enum("priority", listOf("high", "medium", "low")) {
            description = "Priority level (default: medium)"
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<TodoItem>?,
    ): AgentToolResult<TodoItem> {
        val content = parameters.string("content")
            ?: error("Missing required parameter: content")
        val status = parameters.string("status", required = false) ?: "pending"
        val priority = parameters.string("priority", required = false) ?: "medium"

        val validStatuses = setOf("pending", "in_progress", "completed", "cancelled")
        val validPriorities = setOf("high", "medium", "low")

        require(status in validStatuses) {
            "Invalid status '$status'. Must be one of: $validStatuses"
        }
        require(priority in validPriorities) {
            "Invalid priority '$priority'. Must be one of: $validPriorities"
        }

        val item = TodoItem(
            id = generateTodoId(),
            content = content,
            status = status,
            priority = priority,
        )
        todoState.add(item)

        return AgentToolResult(
            content = listOf(TextContent(text = "Created todo [${item.id}]: ${item.content}")),
            details = item,
        )
    }
}

public class TodoUpdateTool(
    private val todoState: TodoState,
) : AgentTool<TodoItem> {

    override val label: String = "todoupdate"

    override val definition: ToolDefinition = toolDefinition(
        "todoupdate",
        loadToolPrompt("todoupdate", "Update an existing todo item by ID."),
    ) {
        string("id") {
            required = true
            description = "The ID of the todo item to update"
        }
        string("content") {
            description = "Updated task description"
        }
        enum("status", listOf("pending", "in_progress", "completed", "cancelled")) {
            description = "Updated status"
        }
        enum("priority", listOf("high", "medium", "low")) {
            description = "Updated priority level"
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<TodoItem>?,
    ): AgentToolResult<TodoItem> {
        val id = parameters.string("id")
            ?: error("Missing required parameter: id")
        val content = parameters.string("content", required = false)
        val status = parameters.string("status", required = false)
        val priority = parameters.string("priority", required = false)

        val validStatuses = setOf("pending", "in_progress", "completed", "cancelled")
        val validPriorities = setOf("high", "medium", "low")

        if (status != null) {
            require(status in validStatuses) {
                "Invalid status '$status'. Must be one of: $validStatuses"
            }
        }
        if (priority != null) {
            require(priority in validPriorities) {
                "Invalid priority '$priority'. Must be one of: $validPriorities"
            }
        }

        val updated = todoState.updateItem(id, content, status, priority)
            ?: error("Todo with id '$id' not found")

        return AgentToolResult(
            content = listOf(TextContent(text = "Updated todo [${updated.id}]: ${updated.content} (${updated.status})")),
            details = updated,
        )
    }
}
