package co.agentmode.agent47.tui.state

import co.agentmode.agent47.coding.core.agents.AgentSource
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises [buildAgentListRows] against real [BackgroundAgents] state (rather than hand-built
 * [co.agentmode.agent47.coding.core.agents.RunningAgent] instances, since completion is only
 * reachable through the registry's public API).
 */
class AgentListRowsTest {
    private fun subAgentResult(id: String, exitCode: Int = 0, error: String? = null) = SubAgentResult(
        id = id,
        agent = "explore",
        agentSource = AgentSource.BUNDLED,
        task = "task",
        description = "desc",
        exitCode = exitCode,
        output = "done",
        truncated = false,
        durationMs = 1,
        tokens = 0,
        error = error,
        aborted = false,
    )

    private fun awaitDone(bg: BackgroundAgents, id: String, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (bg.visibleAgents().none { it.id == id && it.done } && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun `an empty agent list yields no rows at all, not even main`() {
        assertEquals(emptyList(), buildAgentListRows(emptyList(), null, mainRunning = false, mainActivity = "", now = 0))
    }

    @Test
    fun `main leads the row list, followed by every visible agent`() {
        val bg = BackgroundAgents()
        val gate = CompletableDeferred<Unit>()
        bg.launch("a1", "explore", "desc", "task") { gate.await(); subAgentResult("a1") }
        bg.launch("a2", "explore", "desc", "task") { gate.await(); subAgentResult("a2") }

        val rows = buildAgentListRows(bg.visibleAgents(), focusedAgentId = null, mainRunning = true, mainActivity = "Thinking", now = 1_500)

        assertEquals(listOf(MAIN_AGENT_ROW_ID, "a1", "a2"), rows.map { it.id })
        assertEquals(true, rows.first().isMain)
        assertEquals(AgentRowState.RUNNING, rows.first().state)
        assertEquals("Thinking", rows.first().activity)
        assertNull(rows.first().elapsedMs)
        gate.complete(Unit)
    }

    @Test
    fun `main is idle when the orchestrator is not streaming`() {
        val bg = BackgroundAgents()
        val gate = CompletableDeferred<Unit>()
        bg.launch("a1", "explore", "desc", "task") { gate.await(); subAgentResult("a1") }

        val rows = buildAgentListRows(bg.visibleAgents(), null, mainRunning = false, mainActivity = "", now = 0)

        assertEquals(AgentRowState.IDLE, rows.first().state)
        gate.complete(Unit)
    }

    @Test
    fun `the focused agent is hidden from the list but main and the rest remain`() {
        val bg = BackgroundAgents()
        val gate = CompletableDeferred<Unit>()
        bg.launch("a1", "explore", "desc", "task") { gate.await(); subAgentResult("a1") }
        bg.launch("a2", "explore", "desc", "task") { gate.await(); subAgentResult("a2") }

        val rows = buildAgentListRows(bg.visibleAgents(), focusedAgentId = "a1", mainRunning = false, mainActivity = "", now = 0)

        assertEquals(listOf(MAIN_AGENT_ROW_ID, "a2"), rows.map { it.id })
        gate.complete(Unit)
    }

    @Test
    fun `a queued agent reports the queued activity and idle state`() {
        val bg = BackgroundAgents(maxConcurrent = 1)
        val gate = CompletableDeferred<Unit>()
        bg.launch("run", "explore", "desc", "task") { gate.await(); subAgentResult("run") }
        bg.launch("wait", "explore", "desc", "task") { gate.await(); subAgentResult("wait") }

        val row = buildAgentListRows(bg.visibleAgents(), null, mainRunning = false, mainActivity = "", now = 0).first { it.id == "wait" }

        assertEquals(AgentRowState.IDLE, row.state)
        assertEquals("queued", row.activity)
        assertNull(row.elapsedMs)
        gate.complete(Unit)
    }

    @Test
    fun `a running agent's elapsed time ticks against now, and its progress feeds tokens and activity`() {
        val bg = BackgroundAgents()
        val gate = CompletableDeferred<Unit>()
        bg.launch("a1", "explore", "desc", "task") { gate.await(); subAgentResult("a1") }
        val startedAt = bg.visibleAgents().first { it.id == "a1" }.startedAt
        bg.updateProgress(
            "a1",
            SubAgentProgress(index = 0, id = "a1", agent = "explore", status = "running", currentTool = "grep", toolCount = 1, tokens = 4_200, durationMs = 500),
        )

        val row = buildAgentListRows(bg.visibleAgents(), null, mainRunning = false, mainActivity = "", now = startedAt + 2_500).first { it.id == "a1" }

        assertEquals(AgentRowState.RUNNING, row.state)
        assertEquals("running grep…", row.activity)
        assertEquals(2_500L, row.elapsedMs)
        assertEquals(4_200L, row.tokens)
        gate.complete(Unit)
    }

    @Test
    fun `a completed agent freezes its elapsed time and reports done or failed`() {
        val bg = BackgroundAgents()
        bg.launch("ok", "explore", "desc", "task") { subAgentResult("ok") }
        bg.launch("bad", "explore", "desc", "task") { subAgentResult("bad", exitCode = 1, error = "boom") }
        awaitDone(bg, "ok")
        awaitDone(bg, "bad")

        val early = buildAgentListRows(bg.visibleAgents(), null, mainRunning = false, mainActivity = "", now = 1_000)
        val muchLater = buildAgentListRows(bg.visibleAgents(), null, mainRunning = false, mainActivity = "", now = 999_999)

        val ok = early.first { it.id == "ok" }
        assertEquals(AgentRowState.DONE, ok.state)
        assertEquals("done", ok.activity)
        assertEquals(ok.elapsedMs, muchLater.first { it.id == "ok" }.elapsedMs, "a done agent's elapsed time must not depend on `now`")

        val bad = early.first { it.id == "bad" }
        assertEquals(AgentRowState.DONE, bad.state)
        assertEquals("failed", bad.activity)
    }

    @Test
    fun `selection wraps forward past the last row and backward past the first`() {
        assertEquals(1, agentSelectionMoveDown(0, 3))
        assertEquals(2, agentSelectionMoveDown(1, 3))
        assertEquals(0, agentSelectionMoveDown(2, 3))

        assertEquals(0, agentSelectionMoveUp(1, 3))
        assertEquals(2, agentSelectionMoveUp(0, 3))
    }

    @Test
    fun `selection movement on an empty row list stays at zero`() {
        assertEquals(0, agentSelectionMoveDown(0, 0))
        assertEquals(0, agentSelectionMoveUp(0, 0))
    }
}
