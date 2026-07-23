package co.agentmode.agent47.ui.core.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.coding.core.tools.TodoState

/**
 * Compose-reactive wrapper around [TodoState] that bridges the listener-based
 * update mechanism into Compose snapshot state. Call [bind] once inside a
 * LaunchedEffect to start listening.
 */
@Stable
public class TaskBarState {
    public var items: List<TodoItem> by mutableStateOf(emptyList())
        private set

    /**
     * True when the task bar should be visible: at least one item exists
     * and at least one item is pending or in progress.
     */
    public val visible: Boolean
        get() = items.isNotEmpty() && items.any { it.status == "pending" || it.status == "in_progress" }

    /**
     * Number of lines the task bar occupies (leading spacing + header + visible items + overflow).
     */
    public val lineCount: Int
        get() {
            if (!visible) return 0
            val visibleItemCount = minOf(items.size, TASK_BAR_VISIBLE_ITEM_LIMIT)
            val overflowLineCount = if (items.size > visibleItemCount) 1 else 0
            return 2 + visibleItemCount + overflowLineCount
        }

    /**
     * Register a listener on the given [TodoState] so that Compose
     * recomposes whenever the todo list changes.
     */
    public fun bind(todoState: TodoState) {
        items = todoState.getAll()
        todoState.addListener { snapshot ->
            items = snapshot
        }
    }
}

public const val TASK_BAR_VISIBLE_ITEM_LIMIT: Int = 4
