package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A message waiting in the orchestrator's inbox. */
public data class InboxMessage(
    val from: String,
    val kind: Kind,
    val text: String,
    val timestamp: Long,
) {
    public enum class Kind { COMPLETED, FAILED, MESSAGE }
}

/** Live state of one background sub-agent. */
public class RunningAgent(
    public val id: String,
    public val agentName: String,
    public val description: String?,
    public val task: String,
) {
    @Volatile public var progress: SubAgentProgress? = null
    @Volatile public var result: SubAgentResult? = null
    @Volatile public var agentRef: Agent? = null
    public val done: Boolean get() = result != null
}

/**
 * Registry + inbox for background sub-agents launched by the `task` tool. Thread-safe: each
 * agent runs on a background supervisor scope while the orchestrator (main loop, another thread)
 * polls progress/results via [check][drainInbox]/[runningStatus] and routes messages via [post].
 *
 * A failing agent never takes down its siblings (supervisor) nor the registry.
 */
public class BackgroundAgents {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val agents = ConcurrentHashMap<String, RunningAgent>()
    private val order = CopyOnWriteArrayList<String>()
    private val inbox = ConcurrentLinkedQueue<InboxMessage>()

    /** Returns [preferred] if free, else a suffixed variant, so agent ids never collide. */
    public fun uniqueId(preferred: String): String {
        if (!agents.containsKey(preferred)) return preferred
        var n = 2
        while (agents.containsKey("$preferred-$n")) n++
        return "$preferred-$n"
    }

    /**
     * Registers and starts a background agent. [run] performs the actual work (typically
     * [runSubAgent]) and returns its result; it is expected not to throw for normal agent
     * errors (those come back inside the [SubAgentResult]).
     */
    public fun launch(
        id: String,
        agentName: String,
        description: String?,
        task: String,
        run: suspend (RunningAgent) -> SubAgentResult,
    ) {
        val running = RunningAgent(id, agentName, description, task)
        agents[id] = running
        order.add(id)
        scope.launch {
            try {
                val result = run(running)
                running.result = result
                inbox.add(
                    InboxMessage(
                        from = id,
                        kind = if (isFailure(result)) InboxMessage.Kind.FAILED else InboxMessage.Kind.COMPLETED,
                        text = completionText(running, result),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } catch (_: CancellationException) {
                // Cancelled (abort / new session) — leave no inbox trace.
            } catch (e: Throwable) {
                inbox.add(
                    InboxMessage(
                        from = id,
                        kind = InboxMessage.Kind.FAILED,
                        text = "Agent '$id' crashed: ${e.message ?: e::class.simpleName}",
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    public fun updateProgress(id: String, progress: SubAgentProgress) {
        agents[id]?.progress = progress.copy(id = id)
    }

    public fun setAgentRef(id: String, agent: Agent) {
        agents[id]?.agentRef = agent
    }

    /**
     * Routes a message from [from] to [to]. `orchestrator` (or an unknown recipient that equals
     * [ORCHESTRATOR]) posts into the orchestrator inbox; any other id steers that running agent.
     * Returns false when the target agent is unknown or already finished.
     */
    public fun post(from: String, to: String, text: String): Boolean {
        if (to.equals(ORCHESTRATOR, ignoreCase = true)) {
            inbox.add(InboxMessage(from, InboxMessage.Kind.MESSAGE, "$from → $text", System.currentTimeMillis()))
            return true
        }
        val ref = agents[to]?.takeIf { !it.done }?.agentRef ?: return false
        ref.steer(
            UserMessage(
                content = listOf(TextContent(text = "[message from $from] $text")),
                timestamp = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /** Pops and returns all pending inbox messages. */
    public fun drainInbox(): List<InboxMessage> = buildList {
        while (true) add(inbox.poll() ?: break)
    }

    /** Agents still running, in launch order. */
    public fun runningStatus(): List<RunningAgent> = order.mapNotNull { agents[it] }.filter { !it.done }

    /** Progress snapshots for the still-running agents (for the UI). */
    public fun progressList(): List<SubAgentProgress> = runningStatus().mapNotNull { it.progress }

    public fun hasRunning(): Boolean = runningStatus().isNotEmpty()

    /** Cancels all running agents and clears state, leaving the registry usable for new launches. */
    public fun cancelAll() {
        job.cancelChildren()
        agents.clear()
        order.clear()
        inbox.clear()
    }

    private fun isFailure(r: SubAgentResult): Boolean = r.aborted || r.error != null || r.exitCode != 0

    private fun completionText(running: RunningAgent, r: SubAgentResult): String =
        if (isFailure(r)) {
            "Agent '${running.id}' (${running.agentName}) failed: ${r.error ?: "exit ${r.exitCode}"}"
        } else {
            "Agent '${running.id}' (${running.agentName}) finished:\n${r.output}"
        }

    public companion object {
        public const val ORCHESTRATOR: String = "orchestrator"
    }
}
