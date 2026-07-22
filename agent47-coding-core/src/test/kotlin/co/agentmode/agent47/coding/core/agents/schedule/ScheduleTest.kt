package co.agentmode.agent47.coding.core.agents.schedule

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScheduleTest {

    private val now = Instant.parse("2026-07-22T12:00:00Z")

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
}
