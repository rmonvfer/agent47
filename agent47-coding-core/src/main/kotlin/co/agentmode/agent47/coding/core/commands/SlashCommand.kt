package co.agentmode.agent47.coding.core.commands

public data class SlashCommand(
    val name: String,
    val description: String,
    val content: String,
    val source: SlashCommandSource,
)

public enum class SlashCommandSource { BUNDLED, USER, PROJECT }
