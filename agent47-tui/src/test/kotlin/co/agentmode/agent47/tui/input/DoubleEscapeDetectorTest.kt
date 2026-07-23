package co.agentmode.agent47.tui.input

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubleEscapeDetectorTest {
    @Test
    fun `second escape inside the window completes the gesture`() {
        var now = 1_000_000_000L
        val detector = DoubleEscapeDetector { now }

        assertFalse(detector.registerEscape())
        now += 499_999_999L
        assertTrue(detector.registerEscape())
    }

    @Test
    fun `escape at the timeout starts a new gesture`() {
        var now = 1_000_000_000L
        val detector = DoubleEscapeDetector { now }

        assertFalse(detector.registerEscape())
        now += 500_000_000L
        assertFalse(detector.registerEscape())
        now += 1L
        assertTrue(detector.registerEscape())
    }

    @Test
    fun `reset discards the first escape`() {
        var now = 1_000_000_000L
        val detector = DoubleEscapeDetector { now }

        assertFalse(detector.registerEscape())
        detector.reset()
        now += 1L
        assertFalse(detector.registerEscape())
    }
}
