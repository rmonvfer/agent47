package co.agentmode.agent47.tui.input

/**
 * Normalized keyboard event parsed from terminal input.
 */
public data class KeyboardEvent(
    val key: Key,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val text: String = "",
)
