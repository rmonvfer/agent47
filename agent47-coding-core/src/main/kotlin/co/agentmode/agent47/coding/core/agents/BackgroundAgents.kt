package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    /** Batch this agent belongs to (all agents from one `task` call share it), for grouped push. */
    public val groupId: String? = null,
    /** Number of agents in this agent's batch. */
    public val groupSize: Int = 1,
) {
    private val completion = CompletableDeferred<SubAgentResult?>()

    public enum class Status { QUEUED, RUNNING }

    @Volatile public var status: Status = Status.QUEUED
    /** Wall-clock ms when the agent started running (0 while queued), for live elapsed display. */
    @Volatile public var startedAt: Long = 0L
    @Volatile public var progress: SubAgentProgress? = null
    @Volatile public var result: SubAgentResult? = null
    @Volatile public var agentRef: Agent? = null
    @Volatile public var job: Job? = null
    @Volatile private var finished: Boolean = false
    public val done: Boolean get() = finished

    public suspend fun awaitResult(): SubAgentResult? = completion.await()

    internal fun complete(result: SubAgentResult?) {
        finished = true
        completion.complete(result)
    }
}

/**
 * Registry + inbox for background sub-agents launched by the `task` tool. Thread-safe: each
 * agent runs on a background supervisor scope while the orchestrator (main loop, another thread)
 * polls progress/results via [drainInbox]/[runningStatus] and routes messages via [post].
 *
 * At most [maxConcurrent] agents run concurrently; the rest wait in a FIFO queue and start as
 * running slots free up ([drainQueue]). A failing agent never takes down its siblings (supervisor)
 * nor the registry.
 */
public class BackgroundAgents(
    maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val agents = ConcurrentHashMap<String, RunningAgent>()
    private val order = CopyOnWriteArrayList<String>()
    private val inbox = ConcurrentLinkedQueue<InboxMessage>()

    private class QueuedLaunch(
        val running: RunningAgent,
        val run: suspend (RunningAgent) -> SubAgentResult,
        val generation: Long,
    )

    // [lock] guards [queue], [runningBackground], and [maxConcurrentLimit] together so slot
    // reservation and dequeue are atomic.
    private val lock = Any()
    private val queue = ArrayDeque<QueuedLaunch>()
    private var runningBackground = 0
    private var maxConcurrentLimit = maxConcurrent.coerceAtLeast(1)
    private var generation = 0L

    // The orchestrator (main-loop) agent, set once it exists. Used so sub-agents can inherit its
    // system prompt (append mode) and conversation (inherit_context), and for push notifications.
    @Volatile private var orchestrator: Agent? = null

    public fun setOrchestrator(agent: Agent) {
        orchestrator = agent
    }

    public fun orchestratorSystemPrompt(): String? = orchestrator?.state?.systemPrompt

    public fun orchestratorMessages(): List<Message> = orchestrator?.state?.messages ?: emptyList()

    // Invoked once per agent when it completes (result stored), in addition to the inbox. Used by
    // the opt-in push-notification path; the inbox (pull) is always populated regardless.
    @Volatile private var completionListener: ((RunningAgent) -> Unit)? = null

    public fun setCompletionListener(listener: (RunningAgent) -> Unit) {
        completionListener = listener
    }

    /** Returns [preferred] if free, else a suffixed variant, so agent ids never collide. */
    public fun uniqueId(preferred: String): String {
        if (!agents.containsKey(preferred)) return preferred
        var n = 2
        while (agents.containsKey("$preferred-$n")) n++
        return "$preferred-$n"
    }

    public fun maxConcurrent(): Int = synchronized(lock) { maxConcurrentLimit }

    /** Updates the concurrency ceiling (min 1) and starts any queued agents that now fit. */
    public fun setMaxConcurrent(n: Int) {
        synchronized(lock) { maxConcurrentLimit = n.coerceAtLeast(1) }
        drainQueue()
    }

    public fun runningCount(): Int = synchronized(lock) { runningBackground }

    public fun queuedCount(): Int = synchronized(lock) { queue.size }

    /**
     * Registers a background agent and either starts it immediately or queues it if the
     * concurrency ceiling is reached. [run] performs the actual work (typically [runSubAgent]);
     * it is expected not to throw for normal agent errors (those come back inside [SubAgentResult]).
     * The returned [RunningAgent] can be used to await completion, including while it is queued.
     */
    public fun launch(
        id: String,
        agentName: String,
        description: String?,
        task: String,
        groupId: String? = null,
        groupSize: Int = 1,
        run: suspend (RunningAgent) -> SubAgentResult,
    ): RunningAgent {
        val running = RunningAgent(id, agentName, description, task, groupId, groupSize)
        agents[id] = running
        order.add(id)

        val (startNow, launchGeneration) = synchronized(lock) {
            val currentGeneration = generation
            if (runningBackground < maxConcurrentLimit) {
                runningBackground++
                true to currentGeneration
            } else {
                queue.addLast(QueuedLaunch(running, run, currentGeneration))
                false to currentGeneration
            }
        }
        if (startNow) startAgent(running, run, launchGeneration)
        return running
    }

    private fun startAgent(
        running: RunningAgent,
        run: suspend (RunningAgent) -> SubAgentResult,
        launchGeneration: Long,
    ) {
        running.status = RunningAgent.Status.RUNNING
        running.startedAt = System.currentTimeMillis()
        running.job = scope.launch {
            try {
                val result = run(running)
                running.result = result
                running.complete(result)
                inbox.add(
                    InboxMessage(
                        from = running.id,
                        kind = if (isFailure(result)) InboxMessage.Kind.FAILED else InboxMessage.Kind.COMPLETED,
                        text = completionText(running, result),
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                completionListener?.invoke(running)
            } catch (_: CancellationException) {
                // Cancelled (abort / new session) — leave no inbox trace.
                running.complete(null)
            } catch (e: Throwable) {
                running.complete(null)
                inbox.add(
                    InboxMessage(
                        from = running.id,
                        kind = InboxMessage.Kind.FAILED,
                        text = "Agent '${running.id}' crashed: ${e.message ?: e::class.simpleName}",
                        timestamp = System.currentTimeMillis(),
                    ),
                )
            } finally {
                onAgentFinished(launchGeneration)
            }
        }
    }

    private fun onAgentFinished(launchGeneration: Long) {
        val startable = synchronized(lock) {
            if (launchGeneration != generation) return@synchronized emptyList()
            runningBackground = (runningBackground - 1).coerceAtLeast(0)
            pollStartable()
        }
        startable.forEach { startAgent(it.running, it.run, it.generation) }
    }

    /** Starts as many queued agents as fit. Reserves their running slots. Public entry drains only. */
    private fun drainQueue() {
        val startable = synchronized(lock) { pollStartable() }
        startable.forEach { startAgent(it.running, it.run, it.generation) }
    }

    /** Must be called under [lock]: reserves a running slot for each returned launch. */
    private fun pollStartable(): List<QueuedLaunch> = buildList {
        while (queue.isNotEmpty() && runningBackground < maxConcurrentLimit) {
            add(queue.removeFirst())
            runningBackground++
        }
    }

    public fun updateProgress(id: String, progress: SubAgentProgress) {
        agents[id]?.progress = progress.copy(id = id)
    }

    public fun setAgentRef(id: String, agent: Agent) {
        agents[id]?.agentRef = agent
    }

    /**
     * Aborts a single agent by id. A running agent's coroutine is cancelled; a still-queued agent is
     * removed before it starts (with a note into the orchestrator inbox). Returns false when the id
     * is unknown or the agent has already finished.
     */
    public fun abort(id: String): Boolean {
        val running = agents[id] ?: return false
        if (running.done) return false

        val wasQueued = synchronized(lock) {
            val queued = queue.firstOrNull { it.running.id == id }
            if (queued != null) queue.remove(queued) else false
        }
        if (wasQueued) {
            running.complete(null)
            agents.remove(id)
            order.remove(id)
            inbox.add(
                InboxMessage(
                    from = id,
                    kind = InboxMessage.Kind.MESSAGE,
                    text = "Agent '$id' (${running.agentName}) was cancelled before it started.",
                    timestamp = System.currentTimeMillis(),
                ),
            )
            return true
        }

        val runningJob = running.job ?: return false
        runningJob.cancel()
        return true
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

    /** Agents still running or queued, in launch order. */
    public fun runningStatus(): List<RunningAgent> = order.mapNotNull { agents[it] }.filter { !it.done }

    /** Progress snapshots for the still-running agents (for the UI). */
    public fun progressList(): List<SubAgentProgress> = runningStatus().mapNotNull { it.progress }

    public fun hasRunning(): Boolean = runningStatus().isNotEmpty()

    /** Cancels all running agents and clears state, leaving the registry usable for new launches. */
    public fun cancelAll() {
        val queued = synchronized(lock) {
            generation++
            val pending = queue.map { it.running }
            queue.clear()
            runningBackground = 0
            job.cancelChildren()
            agents.clear()
            order.clear()
            inbox.clear()
            pending
        }
        queued.forEach { it.complete(null) }
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
        public const val DEFAULT_MAX_CONCURRENT: Int = 4
    }
}
