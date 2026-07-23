package co.agentmode.agent47.tui.input

internal class DoubleEscapeDetector(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var firstEscapeAtNanos: Long? = null

    fun registerEscape(): Boolean {
        val now = nanoTime()
        val elapsed = firstEscapeAtNanos?.let { now - it }
        if (elapsed != null && elapsed >= 0 && elapsed < DOUBLE_ESCAPE_WINDOW_NANOS) {
            firstEscapeAtNanos = null
            return true
        }

        firstEscapeAtNanos = now
        return false
    }

    fun reset() {
        firstEscapeAtNanos = null
    }
}

private const val DOUBLE_ESCAPE_WINDOW_NANOS = 500_000_000L
