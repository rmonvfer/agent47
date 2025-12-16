package co.agentmode.agent47.tui.input

/**
 * Key model for keyboard input.
 */
public sealed interface Key {
    public data class Character(val value: Char) : Key

    public data object ArrowUp : Key

    public data object ArrowDown : Key

    public data object ArrowLeft : Key

    public data object ArrowRight : Key

    public data object Home : Key

    public data object End : Key

    public data object PageUp : Key

    public data object PageDown : Key

    public data object Delete : Key

    public data object Backspace : Key

    public data object Tab : Key

    public data object Enter : Key

    public data object Escape : Key

    public data class Unknown(val sequence: String) : Key
}
