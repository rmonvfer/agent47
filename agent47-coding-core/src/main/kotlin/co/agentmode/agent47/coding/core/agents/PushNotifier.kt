package co.agentmode.agent47.coding.core.agents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opt-in push delivery of background-agent completions, with group-join batching. When
 * [BackgroundAgents] fires a completion, [onComplete] either delivers it immediately (`async`) or
 * batches it with its sibling tasks from the same `task` call (`smart`/`group`) and delivers one
 * combined notification once the whole batch finishes — or after [groupTimeoutMs] for stragglers.
 *
 * [deliver] is provided by the TUI layer and is responsible for actually surfacing the text to the
 * orchestrator (e.g. injecting a follow-up turn and waking the idle loop). The inbox (pull) is
 * always populated independently, so push is purely additive.
 */
public class PushNotifier(
    private val scope: CoroutineScope,
    private val joinMode: String,
    private val deliver: (String) -> Unit,
    private val groupTimeoutMs: Long = 30_000,
) {
    private class Batch(val expected: Int) {
        val lines = mutableListOf<String>()
        var timer: Job? = null
        var delivered = false
    }

    private val batches = HashMap<String, Batch>()
    private val lock = Any()

    public fun onComplete(agent: RunningAgent) {
        val line = summarize(agent)
        val groupId = agent.groupId

        if (joinMode == "async" || groupId == null || agent.groupSize <= 1) {
            deliver(wrap(listOf(line)))
            return
        }

        synchronized(lock) {
            val batch = batches.getOrPut(groupId) { Batch(agent.groupSize).also { startTimer(groupId, it) } }
            if (batch.delivered) {
                // A straggler arriving after the batch was already delivered — send it on its own.
                deliver(wrap(listOf(line)))
                return
            }
            batch.lines.add(line)
            if (batch.lines.size >= batch.expected) flush(groupId, batch)
        }
    }

    private fun startTimer(groupId: String, batch: Batch) {
        batch.timer = scope.launch {
            delay(groupTimeoutMs)
            synchronized(lock) {
                if (!batch.delivered && batch.lines.isNotEmpty()) flush(groupId, batch)
            }
        }
    }

    // Must be called under [lock].
    private fun flush(groupId: String, batch: Batch) {
        batch.delivered = true
        batch.timer?.cancel()
        batches.remove(groupId)
        deliver(wrap(batch.lines.toList()))
    }

    private fun summarize(agent: RunningAgent): String {
        val output = agent.result?.output?.take(500) ?: "done"
        return "• ${agent.id} (${agent.agentName}): $output"
    }

    private fun wrap(lines: List<String>): String =
        "Background agent${if (lines.size != 1) "s" else ""} finished:\n" + lines.joinToString("\n")
}
