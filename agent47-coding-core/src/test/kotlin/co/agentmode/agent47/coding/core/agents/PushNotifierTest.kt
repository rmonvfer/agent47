package co.agentmode.agent47.coding.core.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PushNotifierTest {

    private fun agent(id: String, groupId: String?, groupSize: Int): RunningAgent {
        val a = RunningAgent(id, "explore", "desc", "task", groupId, groupSize)
        a.result = SubAgentResult(
            id = id, agent = "explore", agentSource = AgentSource.BUNDLED, task = "task", description = "desc",
            exitCode = 0, output = "output-$id", truncated = false, durationMs = 1, tokens = 0, error = null, aborted = false,
        )
        return a
    }

    @Test
    fun `async mode delivers each completion immediately`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delivered = mutableListOf<String>()
        val notifier = PushNotifier(scope, joinMode = "async", deliver = { delivered.add(it) })

        notifier.onComplete(agent("a", "batch1", 2))
        notifier.onComplete(agent("b", "batch1", 2))

        assertEquals(2, delivered.size)
        assertTrue(delivered[0].contains("output-a"))
    }

    @Test
    fun `smart mode batches a group into one notification`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delivered = mutableListOf<String>()
        val notifier = PushNotifier(scope, joinMode = "smart", deliver = { delivered.add(it) })

        notifier.onComplete(agent("a", "batch1", 2))
        assertEquals(0, delivered.size, "should hold until the batch completes")
        notifier.onComplete(agent("b", "batch1", 2))

        assertEquals(1, delivered.size)
        assertTrue(delivered[0].contains("output-a"))
        assertTrue(delivered[0].contains("output-b"))
    }
}
