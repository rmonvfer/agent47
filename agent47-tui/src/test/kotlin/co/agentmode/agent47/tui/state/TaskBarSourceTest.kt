package co.agentmode.agent47.tui.state

import co.agentmode.agent47.coding.core.agents.AgentSource
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.ui.core.state.TaskBarState
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the task bar against real [BackgroundAgents] state: which todo list the bar follows as
 * focus moves between the conversation and a background agent's transcript.
 */
class TaskBarSourceTest {
    private val gate = CompletableDeferred<Unit>()

    private fun subAgentResult(id: String) = SubAgentResult(
        id = id,
        agentSource = AgentSource.BUNDLED,
        agent = "explore",
        task = "task",
        description = "desc",
        exitCode = 0,
        output = "done",
        truncated = false,
        durationMs = 1,
        tokens = 0,
        error = null,
        aborted = false,
    )

    private fun todo(content: String) =
        TodoItem(id = content, content = content, status = "in_progress", priority = "high")

    /** A registry with two agents held open, so their todo lists stay reachable for the assertions. */
    private fun registryWithAgents(vararg ids: String): BackgroundAgents {
        val bg = BackgroundAgents()
        ids.forEach { id -> bg.launch(id, "explore", "desc", "task") { gate.await(); subAgentResult(id) } }
        return bg
    }

    @Test
    fun `the conversation shows the orchestrator's list`() {
        val main = TodoState()
        val bg = registryWithAgents("a1")

        assertEquals(main, taskBarTodoSource(main, viewingAgentId = null, backgroundAgents = bg))
        gate.complete(Unit)
    }

    @Test
    fun `a focused agent shows its own list`() {
        val main = TodoState()
        val bg = registryWithAgents("a1", "a2")

        assertEquals(bg.todosFor("a1"), taskBarTodoSource(main, "a1", bg))
        assertEquals(bg.todosFor("a2"), taskBarTodoSource(main, "a2", bg))
        gate.complete(Unit)
    }

    @Test
    fun `an agent the registry does not know has no list`() {
        val main = TodoState()
        val bg = registryWithAgents("a1")

        assertNull(taskBarTodoSource(main, "gone", bg))
        gate.complete(Unit)
    }

    @Test
    fun `the bar shows the focused agent's tasks, not the orchestrator's`() {
        val main = TodoState().apply { update(listOf(todo("orchestrator work"))) }
        val bg = registryWithAgents("a1")
        bg.todosFor("a1")!!.update(listOf(todo("agent work")))
        val bar = TaskBarState()

        bar.bind(taskBarTodoSource(main, null, bg))
        assertEquals(listOf("orchestrator work"), bar.items.map { it.content })

        bar.bind(taskBarTodoSource(main, "a1", bg))
        assertEquals(listOf("agent work"), bar.items.map { it.content })
        gate.complete(Unit)
    }

    @Test
    fun `a focused agent without tasks hides the bar`() {
        val main = TodoState().apply { update(listOf(todo("orchestrator work"))) }
        val bg = registryWithAgents("a1")
        val bar = TaskBarState().apply { bind(taskBarTodoSource(main, null, bg)) }

        bar.bind(taskBarTodoSource(main, "a1", bg))

        assertTrue(bar.items.isEmpty())
        assertFalse(bar.visible)
        assertEquals(0, bar.lineCount)
        gate.complete(Unit)
    }

    @Test
    fun `leaving focus mode restores the orchestrator's list`() {
        val main = TodoState().apply { update(listOf(todo("orchestrator work"))) }
        val bg = registryWithAgents("a1")
        bg.todosFor("a1")!!.update(listOf(todo("agent work")))
        val bar = TaskBarState().apply { bind(taskBarTodoSource(main, "a1", bg)) }

        bar.bind(taskBarTodoSource(main, null, bg))

        assertEquals(listOf("orchestrator work"), bar.items.map { it.content })
        assertTrue(bar.visible)
        gate.complete(Unit)
    }

    @Test
    fun `an agent left behind cannot push tasks into the bar`() {
        val main = TodoState().apply { update(listOf(todo("orchestrator work"))) }
        val bg = registryWithAgents("a1", "a2")
        val bar = TaskBarState().apply { bind(taskBarTodoSource(main, "a1", bg)) }

        bar.bind(taskBarTodoSource(main, "a2", bg))
        bg.todosFor("a1")!!.update(listOf(todo("late work from a1")))
        assertTrue(bar.items.isEmpty(), "a2 has no tasks, so the bar stays empty")

        bar.bind(taskBarTodoSource(main, null, bg))
        bg.todosFor("a2")!!.update(listOf(todo("late work from a2")))
        assertEquals(listOf("orchestrator work"), bar.items.map { it.content })
        gate.complete(Unit)
    }
}
