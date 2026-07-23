@file:Suppress("MatchingDeclarationName")

package co.agentmode.agent47.tui.commands

internal data class SlashCommandSpec(
    val command: String,
    val description: String,
)

internal val builtinSlashCommands: List<SlashCommandSpec> = listOf(
    SlashCommandSpec("/help", "Show help and shortcuts"),
    SlashCommandSpec("/commands", "List available slash commands"),
    SlashCommandSpec("/new", "Start a new session"),
    SlashCommandSpec("/clear", "Clear the chat display"),
    SlashCommandSpec("/model", "Pick or set the active model"),
    SlashCommandSpec("/provider", "Connect a provider"),
    SlashCommandSpec("/theme", "Pick a color theme"),
    SlashCommandSpec("/session", "Load a saved session"),
    SlashCommandSpec("/compact", "Compact conversation context"),
    SlashCommandSpec("/reload", "Reload extensions"),
    SlashCommandSpec("/memory", "Show loaded instruction files"),
    SlashCommandSpec("/agents", "View and steer background sub-agents"),
    SlashCommandSpec("/settings", "Open interactive settings"),
    SlashCommandSpec("/exit", "Exit interactive mode"),
)

internal fun helpText(slashCommands: List<SlashCommandSpec>): String = buildString {
    appendLine("Commands:")
    slashCommands.forEach { spec ->
        appendLine("  ${spec.command.padEnd(20)} ${spec.description}")
    }
    appendLine("")
    appendLine("Shortcuts:")
    appendLine("  Enter          Submit")
    appendLine("  Shift+Enter    Insert newline (Alt+Enter also works)")
    appendLine("  Ctrl+C         Interrupt current run, then press twice to exit")
    appendLine("  Ctrl+L         Clear visible chat")
    appendLine("  Ctrl+T         Toggle thinking")
    appendLine("  Ctrl+P/Ctrl+N  Cycle models")
    appendLine("  Ctrl+O         Open settings overlay")
    appendLine("  Ctrl+G         Toggle latest thinking block")
    appendLine("  Ctrl+E         Toggle latest tool details")
    appendLine("  Ctrl+R         View sub-agent results")
    appendLine("  PgUp/PgDn      Scroll chat history")
    appendLine("  Ctrl+U/Ctrl+D  Scroll chat history")
    appendLine("  Up/Down        Scroll chat when input is empty")
    appendLine("  Alt+PgUp/PgDn  Scroll chat history")
    appendLine("  Esc            Interrupt agent or close modal")
    append("Prefix a line with ! to run local shell commands.")
}
