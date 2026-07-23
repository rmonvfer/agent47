package co.agentmode.agent47.tui.components

import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.state.TaskBarState
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskBarTest {
    @Test
    fun `widget has leading space with its title adjacent to tasks`() {
        val items = listOf(
            TodoItem(
                id = "implement",
                content = "Implement the layout",
                status = "in_progress",
                priority = "high",
            ),
            TodoItem(
                id = "verify",
                content = "Verify the layout",
                status = "pending",
                priority = "medium",
            ),
        )
        val lines = renderTaskBarLines(
            items = items,
            width = 80,
            isStreaming = false,
            spinnerFrame = 0,
            activityLabel = "",
            theme = ThemeConfig(),
        ).map { it.text }

        assertEquals(
            listOf(
                "",
                "Tasks",
                "  [~] Implement the layout",
                "  [ ] Verify the layout",
            ),
            lines,
        )

        val todoState = TodoState().apply { update(items) }
        val taskBarState = TaskBarState().apply { bind(todoState) }
        assertEquals(lines.size, taskBarState.lineCount)
    }

    @Test
    fun `large widget shows four tasks and an overflow count`() {
        val items = (1..16).map { index ->
            TodoItem(
                id = "task-$index",
                content = "Task $index",
                status = "pending",
                priority = "medium",
            )
        }
        val lines = renderTaskBarLines(
            items = items,
            width = 80,
            isStreaming = false,
            spinnerFrame = 0,
            activityLabel = "",
            theme = ThemeConfig(),
        ).map { it.text }

        assertEquals(
            listOf(
                "",
                "Tasks",
                "  [ ] Task 1",
                "  [ ] Task 2",
                "  [ ] Task 3",
                "  [ ] Task 4",
                "  … +12 more",
            ),
            lines,
        )

        val todoState = TodoState().apply { update(items) }
        val taskBarState = TaskBarState().apply { bind(todoState) }
        assertEquals(lines.size, taskBarState.lineCount)
    }

    @Test
    fun `active tasks remain visible when completed tasks fill the item limit`() {
        val items = (1..4).map { index ->
            TodoItem(
                id = "completed-$index",
                content = "Completed $index",
                status = "completed",
                priority = "medium",
            )
        } + TodoItem(
            id = "active",
            content = "Active task",
            status = "in_progress",
            priority = "high",
        )

        val lines = renderTaskBarLines(
            items = items,
            width = 80,
            isStreaming = false,
            spinnerFrame = 0,
            activityLabel = "",
            theme = ThemeConfig(),
        ).map { it.text }

        assertEquals("  [~] Active task", lines[2])
        assertEquals("  … +1 more", lines.last())
    }
}
