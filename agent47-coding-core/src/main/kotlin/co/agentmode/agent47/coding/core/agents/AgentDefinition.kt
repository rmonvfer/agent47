package co.agentmode.agent47.coding.core.agents

import kotlinx.serialization.json.JsonObject

public enum class AgentSource { BUNDLED, USER, PROJECT }

public data class AgentDefinition(
    val name: String,
    val description: String,
    val systemPrompt: String,
    val tools: List<String>?,
    val spawns: SpawnsPolicy,
    val model: List<String>?,
    val thinkingLevel: String?,
    val output: JsonObject?,
    val source: AgentSource,
    val filePath: String?,
)

public sealed interface SpawnsPolicy {
    public data object None : SpawnsPolicy
    public data object All : SpawnsPolicy
    public data class Named(val agents: List<String>) : SpawnsPolicy
}
