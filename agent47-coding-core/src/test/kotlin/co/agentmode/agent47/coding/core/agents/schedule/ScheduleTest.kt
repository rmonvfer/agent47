package co.agentmode.agent47.coding.core.agents.schedule

import co.agentmode.agent47.coding.core.agents.AgentSource
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleTest {

    private val now = Instant.parse("2026-07-22T12:00:00Z")

    private fun result(id: String) = SubAgentResult(
        id = id,
        agent = "explore",
        agentSource = AgentSource.BUNDLED,
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

    @Test
    fun `detects relative one-shot`() {
        val d = ScheduleParser.detect("+10m", now)
        assertEquals(ScheduleType.ONCE, d.type)
        assertEquals(now.plusSeconds(600).toString(), d.normalized)
    }

    @Test
    fun `detects interval`() {
        val d = ScheduleParser.detect("5m", now)
        assertEquals(ScheduleType.INTERVAL, d.type)
        assertEquals(300_000L, d.intervalMs)
    }

    @Test
    fun `detects six-field cron`() {
        val d = ScheduleParser.detect("0 0 9 * * 1", now)
        assertEquals(ScheduleType.CRON, d.type)
        assertNotNull(ScheduleParser.nextCronRun("0 0 9 * * 1"))
    }

    @Test
    fun `rejects past iso and garbage`() {
        assertFailsWith<IllegalArgumentException> { ScheduleParser.detect("2020-01-01T00:00:00Z", now) }
        assertFailsWith<IllegalArgumentException> { ScheduleParser.detect("not a schedule", now) }
        assertFailsWith<IllegalArgumentException> { ScheduleParser.detect("0 0 9 * *", now) } // 5 fields
    }

    @Test
    fun `store round-trips jobs and persists`() = runBlocking {
        val path = createTempDirectory("sched-store").resolve("s.json")
        val store = ScheduleStore(path)
        val job = ScheduledSubagent(
            id = "j1",
            name = "nightly",
            description = "d",
            schedule = "0 0 9 * * 1",
            scheduleType = ScheduleType.CRON,
            subagentType = "explore",
            prompt = "scan",
            createdAt = now.toString(),
        )
        store.add(job)
        assertEquals(1, store.list().size)
        assertTrue(store.hasName("nightly"))
        store.update("j1") { it.copy(runCount = 3) }
        assertEquals(3, store.get("j1")?.runCount)

        // A fresh store reading the same file sees the persisted job.
        assertEquals(3, ScheduleStore(path).get("j1")?.runCount)

        assertTrue(store.remove("j1"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `once schedule fires then disables`() = runBlocking {
        val store = ScheduleStore(createTempDirectory("sched-fire").resolve("s.json"))
        val fired = CompletableDeferred<ScheduledSubagent>()
        val scheduler = SubagentScheduler(store) { job -> fired.complete(job) }
        scheduler.start()

        val job = scheduler.addJob(
            NewJobInput(name = "soon", description = "d", schedule = "+1s", subagentType = "explore", prompt = "go"),
        )

        val firedJob = withTimeout(5_000) { fired.await() }
        assertEquals("go", firedJob.prompt)

        // The one-shot disables itself after firing.
        var disabled = false
        repeat(30) {
            if (scheduler.get(job.id)?.enabled == false) { disabled = true; return@repeat }
            delay(50)
        }
        assertTrue(disabled, "one-shot job should be disabled after firing")
        scheduler.stop()
    }

    @Test
    fun `interval schedule does not overlap an active invocation`() = runBlocking {
        val store = ScheduleStore(createTempDirectory("sched-overlap").resolve("s.json"))
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val scheduler = SubagentScheduler(store) {
            val current = active.incrementAndGet()
            maxActive.updateAndGet { previous -> maxOf(previous, current) }
            started.complete(Unit)
            try {
                gate.await()
            } finally {
                active.decrementAndGet()
            }
        }
        store.add(
            ScheduledSubagent(
                id = "frequent",
                name = "frequent",
                description = "d",
                schedule = "50ms",
                scheduleType = ScheduleType.INTERVAL,
                intervalMs = 50,
                subagentType = "explore",
                prompt = "go",
                createdAt = Instant.now().toString(),
            ),
        )
        scheduler.start()

        withTimeout(5_000) { started.await() }
        delay(250)

        assertEquals(1, maxActive.get())
        assertEquals(1, active.get())
        gate.complete(Unit)
        scheduler.stop()
    }

    @Test
    fun `scheduled invocation waits for the background agent concurrency limit`() = runBlocking {
        val backgroundAgents = BackgroundAgents(maxConcurrent = 1)
        val blockerGate = CompletableDeferred<Unit>()
        val scheduledGate = CompletableDeferred<Unit>()
        val scheduledStarted = CompletableDeferred<Unit>()
        backgroundAgents.launch("blocker", "explore", "d", "block") {
            blockerGate.await()
            result("blocker")
        }

        val store = ScheduleStore(createTempDirectory("sched-concurrency").resolve("s.json"))
        store.add(
            ScheduledSubagent(
                id = "queued-schedule",
                name = "queued-schedule",
                description = "d",
                schedule = "50ms",
                scheduleType = ScheduleType.INTERVAL,
                intervalMs = 50,
                subagentType = "explore",
                prompt = "go",
                createdAt = Instant.now().toString(),
            ),
        )
        val scheduler = SubagentScheduler(store) { job ->
            val running = backgroundAgents.launch(job.id, "explore", job.description, job.prompt) {
                scheduledStarted.complete(Unit)
                scheduledGate.await()
                result(job.id)
            }
            check(running.awaitResult() != null)
        }
        scheduler.start()

        withTimeout(5_000) {
            while (backgroundAgents.queuedCount() == 0) delay(10)
        }
        assertEquals(1, backgroundAgents.runningCount())
        assertEquals(1, backgroundAgents.queuedCount())
        assertTrue(!scheduledStarted.isCompleted)

        blockerGate.complete(Unit)
        withTimeout(5_000) { scheduledStarted.await() }
        assertEquals(1, backgroundAgents.runningCount())
        scheduler.stop()
        scheduledGate.complete(Unit)
    }
}
