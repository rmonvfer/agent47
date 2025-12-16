package co.agentmode.agent47.tui.input

import com.jakewharton.mosaic.layout.KeyEvent

/**
 * Converts a Mosaic [KeyEvent] into the TUI's normalized [KeyboardEvent].
 *
 * Mosaic uses string-based key identifiers parsed from the terminal's event
 * stream (via crossterm). Single-character keys arrive as their character
 * value; special keys use well-known names like "ArrowUp", "Enter", etc.
 */
public fun KeyEvent.toKeyboardEvent(): KeyboardEvent {
    val normalized = when (key) {
        "ArrowUp", "Up" -> Key.ArrowUp
        "ArrowDown", "Down" -> Key.ArrowDown
        "ArrowLeft", "Left" -> Key.ArrowLeft
        "ArrowRight", "Right" -> Key.ArrowRight
        "Home" -> Key.Home
        "End" -> Key.End
        "PageUp", "Page Up", "Prior" -> Key.PageUp
        "PageDown", "Page Down", "Next" -> Key.PageDown
        "Delete" -> Key.Delete
        "Backspace" -> Key.Backspace
        "Tab", "BackTab" -> Key.Tab
        "Enter" -> Key.Enter
        "Escape", "Esc" -> Key.Escape
        else -> {
            if (key.length == 1) {
                Key.Character(key[0])
            } else {
                Key.Unknown(key)
            }
        }
    }

    return KeyboardEvent(
        key = normalized,
        ctrl = ctrl,
        alt = alt,
        shift = shift || key == "BackTab",
        text = if (key.length == 1) key else "",
    )
}