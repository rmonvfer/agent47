package co.agentmode.agent47.tui.input

@Suppress("CyclomaticComplexMethod")
internal fun keyboardShortcutName(event: KeyboardEvent): String? {
    val key = when (val value = event.key) {
        is Key.Character -> value.value.lowercaseChar().toString()
        Key.Enter -> "enter"
        Key.Tab -> "tab"
        Key.Escape -> "escape"
        Key.Backspace -> "backspace"
        Key.Delete -> "delete"
        Key.ArrowUp -> "up"
        Key.ArrowDown -> "down"
        Key.ArrowLeft -> "left"
        Key.ArrowRight -> "right"
        Key.PageUp -> "pageup"
        Key.PageDown -> "pagedown"
        else -> return null
    }
    return buildList {
        if (event.ctrl) add("ctrl")
        if (event.alt) add("alt")
        if (event.shift) add("shift")
        add(key)
    }.joinToString("+")
}
