package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.coding.core.tools.TodoState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskBarStateTest {

    private fun todo(content: String, status: String = "pending") =
        TodoItem(id = content, content = content, status = status, priority = "medium")

    @Test
    fun `binding adopts the items the list already holds`() {
        val todos = TodoState().apply { update(listOf(todo("write the parser"))) }

        val state = TaskBarState().apply { bind(todos) }

        assertEquals(listOf("write the parser"), state.items.map { it.content })
        assertTrue(state.visible)
    }

    @Test
    fun `changes to the bound list reach the bar`() {
        val todos = TodoState()
        val state = TaskBarState().apply { bind(todos) }

        todos.update(listOf(todo("run the tests")))

        assertEquals(listOf("run the tests"), state.items.map { it.content })
    }

    @Test
    fun `rebinding switches to the new list`() {
        val first = TodoState().apply { update(listOf(todo("first task"))) }
        val second = TodoState().apply { update(listOf(todo("second task"))) }
        val state = TaskBarState().apply { bind(first) }

        state.bind(second)

        assertEquals(listOf("second task"), state.items.map { it.content })
    }

    @Test
    fun `the list left behind by a rebind no longer updates the bar`() {
        val first = TodoState().apply { update(listOf(todo("first task"))) }
        val second = TodoState().apply { update(listOf(todo("second task"))) }
        val state = TaskBarState().apply { bind(first) }

        state.bind(second)
        first.update(listOf(todo("stale update")))

        assertEquals(listOf("second task"), state.items.map { it.content })
    }

    @Test
    fun `binding an empty list hides the bar`() {
        val todos = TodoState().apply { update(listOf(todo("done already", status = "completed"))) }
        val state = TaskBarState().apply { bind(todos) }

        state.bind(TodoState())

        assertTrue(state.items.isEmpty())
        assertFalse(state.visible)
        assertEquals(0, state.lineCount)
    }

    @Test
    fun `binding no list at all empties the bar`() {
        val todos = TodoState().apply { update(listOf(todo("write the parser"))) }
        val state = TaskBarState().apply { bind(todos) }

        state.bind(null)

        assertTrue(state.items.isEmpty())
        assertFalse(state.visible)
    }

    @Test
    fun `unbinding clears the bar and stops further updates`() {
        val todos = TodoState().apply { update(listOf(todo("write the parser"))) }
        val state = TaskBarState().apply { bind(todos) }

        state.unbind()
        todos.update(listOf(todo("later work")))

        assertTrue(state.items.isEmpty())
        assertFalse(state.visible)
    }
}
