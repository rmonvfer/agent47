package co.agentmode.agent47.tui.overlays

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ResumeOverlaysTest {
    @Test
    fun `relative age buckets from just now up to years`() {
        val now = Instant.now()
        assertEquals("now", formatRelativeAge(now))
        assertEquals("5m", formatRelativeAge(now.minus(Duration.ofMinutes(5))))
        assertEquals("3h", formatRelativeAge(now.minus(Duration.ofHours(3))))
        assertEquals("2d", formatRelativeAge(now.minus(Duration.ofDays(2))))
        assertEquals("2w", formatRelativeAge(now.minus(Duration.ofDays(14))))
        assertEquals("6mo", formatRelativeAge(now.minus(Duration.ofDays(180))))
        assertEquals("2y", formatRelativeAge(now.minus(Duration.ofDays(800))))
    }
}
