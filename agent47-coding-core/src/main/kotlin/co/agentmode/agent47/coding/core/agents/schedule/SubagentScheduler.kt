package co.agentmode.agent47.coding.core.agents.schedule

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process, session-lifetime scheduler for subagents. Fires only
 * while the app runs (no OS daemon). Each enabled job is armed by a coroutine that sleeps until its
 * next fire time: intervals loop, one-shots fire once then disable, cron recomputes each iteration.
 *
 * Firing calls [spawn], which is expected to launch a background agent bypassing the concurrency
 * queue so a timed fire never waits behind long-running agents. Job status is updated after the
 * launch returns (session-lifetime scheduling does not track the agent to completion).
 */
public class SubagentScheduler(
    private val store: ScheduleStore,
    private val spawn: suspend (ScheduledSubagent) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val armed = ConcurrentHashMap<String, Job>()

    @Volatile
    private var active = false

    public fun isActive(): Boolean = active

    public fun start() {
        active = true
        store.list().filter { it.enabled }.forEach { armJob(it) }
    }

    public fun stop() {
        active = false
        armed.values.forEach { it.cancel() }
        armed.clear()
    }

    public fun list(): List<ScheduledSubagent> = store.list()

    public fun get(id: String): ScheduledSubagent? = store.get(id)

    public suspend fun addJob(input: NewJobInput): ScheduledSubagent {
        if (store.hasName(input.name)) {
            throw IllegalArgumentException("A scheduled job named \"${input.name}\" already exists.")
        }
        val job = buildJob(input)
        store.add(job)
        if (job.enabled) armJob(job)
        return job
    }

    public suspend fun removeJob(id: String): Boolean {
        unarm(id)
        return store.remove(id)
    }

    public suspend fun updateJob(id: String, transform: (ScheduledSubagent) -> ScheduledSubagent): ScheduledSubagent? {
        val updated = store.update(id, transform) ?: return null
        unarm(id)
        if (updated.enabled) armJob(updated)
        return updated
    }

    private fun buildJob(input: NewJobInput): ScheduledSubagent {
        val detected = ScheduleParser.detect(input.schedule)
        return ScheduledSubagent(
            id = UUID.randomUUID().toString().substring(0, 10),
            name = input.name,
            description = input.description,
            schedule = detected.normalized,
            scheduleType = detected.type,
            intervalMs = detected.intervalMs,
            subagentType = input.subagentType,
            prompt = input.prompt,
            model = input.model,
            thinking = input.thinking,
            maxTurns = input.maxTurns,
            isolation = input.isolation,
            enabled = true,
            createdAt = Instant.now().toString(),
            runCount = 0,
        )
    }

    private fun armJob(job: ScheduledSubagent) {
        val task = when (job.scheduleType) {
            ScheduleType.INTERVAL -> scope.launch {
                val intervalMs = job.intervalMs ?: return@launch
                while (isActive && active) {
                    delay(intervalMs)
                    fire(job.id)
                }
            }
            ScheduleType.ONCE -> scope.launch {
                val target = runCatching { Instant.parse(job.schedule) }.getOrNull() ?: return@launch
                val delayMs = target.toEpochMilli() - System.currentTimeMillis()
                if (delayMs > 0) delay(delayMs)
                fire(job.id)
                store.update(job.id) { it.copy(enabled = false) }
            }
            ScheduleType.CRON -> scope.launch {
                while (isActive && active) {
                    val next = ScheduleParser.nextCronRun(job.schedule, ZonedDateTime.now()) ?: break
                    val delayMs = next.toInstant().toEpochMilli() - System.currentTimeMillis()
                    if (delayMs > 0) delay(delayMs)
                    fire(job.id)
                }
            }
        }
        armed[job.id] = task
    }

    private fun unarm(id: String) {
        armed.remove(id)?.cancel()
    }

    private suspend fun fire(id: String) {
        val job = store.get(id) ?: return
        if (!job.enabled) return

        store.update(id) { it.copy(lastStatus = "running") }
        try {
            spawn(job)
            store.update(id) {
                it.copy(
                    lastRun = Instant.now().toString(),
                    lastStatus = "success",
                    runCount = it.runCount + 1,
                    nextRun = computeNextRun(it),
                )
            }
        } catch (e: Exception) {
            store.update(id) { it.copy(lastRun = Instant.now().toString(), lastStatus = "error") }
        }
    }

    private fun computeNextRun(job: ScheduledSubagent): String? = when (job.scheduleType) {
        ScheduleType.ONCE -> null
        ScheduleType.INTERVAL -> job.intervalMs?.let { Instant.now().plusMillis(it).toString() }
        ScheduleType.CRON -> ScheduleParser.nextCronRun(job.schedule)?.toInstant()?.toString()
    }
}
