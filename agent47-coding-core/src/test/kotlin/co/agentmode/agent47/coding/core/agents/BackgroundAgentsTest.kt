package co.agentmode.agent47.coding.core.agents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundAgentsTest {

    private fun result(
        id: String,
        output: String = "done",
        exitCode: Int = 0,
        error: String? = null,
    ) = SubAgentResult(
        id = id,
        agent = "explore",
        agentSource = AgentSource.BUNDLED,
        task = "task",
        description = "desc",
        exitCode = exitCode,
        output = output,
        truncated = false,
        durationMs = 1,
        tokens = 0,
        error = error,
        aborted = false,
    )

    private fun awaitInbox(bg: BackgroundAgents, expected: Int, timeoutMs: Long = 5000): List<InboxMessage> {
        val collected = mutableListOf<InboxMessage>()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (collected.size < expected && System.currentTimeMillis() < deadline) {
            collected += bg.drainInbox()
            if (collected.size < expected) Thread.sleep(20)
        }
        return collected
    }

    @Test
    fun `completed agent posts its result to the inbox and stops running`() {
        val bg = BackgroundAgents()
        bg.launch("a1", "explore", "desc", "task") { result("a1", output = "the answer is 42") }

        val messages = awaitInbox(bg, 1)

        assertEquals(1, messages.size)
        assertEquals(InboxMessage.Kind.COMPLETED, messages[0].kind)
        assertTrue(messages[0].text.contains("the answer is 42"))
        assertTrue(bg.runningStatus().isEmpty(), "agent should no longer be running")
        // Draining is one-shot.
        assertTrue(bg.drainInbox().isEmpty())
    }

    @Test
    fun `failed agent is reported as FAILED`() {
        val bg = BackgroundAgents()
        bg.launch("a2", "explore", "desc", "task") { result("a2", exitCode = 1, error = "boom") }

        val messages = awaitInbox(bg, 1)

        assertEquals(InboxMessage.Kind.FAILED, messages[0].kind)
        assertTrue(messages[0].text.contains("boom"))
    }

    @Test
    fun `a crashing agent does not take down the registry`() {
        val bg = BackgroundAgents()
        bg.launch("bad", "explore", "desc", "task") { error("unexpected throw") }
        bg.launch("good", "explore", "desc", "task") { result("good", output = "ok") }

        val messages = awaitInbox(bg, 2)

        assertEquals(2, messages.size)
        assertTrue(messages.any { it.from == "bad" && it.kind == InboxMessage.Kind.FAILED })
        assertTrue(messages.any { it.from == "good" && it.kind == InboxMessage.Kind.COMPLETED })
    }

    @Test
    fun `uniqueId avoids collisions`() {
        val bg = BackgroundAgents()
        bg.launch("dup", "explore", "desc", "task") {
            Thread.sleep(200)
            result("dup")
        }
        assertEquals("dup-2", bg.uniqueId("dup"))
    }

    @Test
    fun `messages to the orchestrator land in the inbox`() {
        val bg = BackgroundAgents()
        assertTrue(bg.post(from = "a1", to = BackgroundAgents.ORCHESTRATOR, text = "need input"))
        val messages = bg.drainInbox()
        assertEquals(1, messages.size)
        assertEquals(InboxMessage.Kind.MESSAGE, messages[0].kind)
        assertTrue(messages[0].text.contains("need input"))
    }

    @Test
    fun `sending to an unknown agent fails cleanly`() {
        val bg = BackgroundAgents()
        assertFalse(bg.post(from = "orchestrator", to = "nope", text = "hi"))
    }
}
