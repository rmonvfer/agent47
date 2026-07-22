package co.agentmode.agent47.coding.core.agents

import kotlinx.serialization.json.JsonObject

public enum class AgentSource { BUNDLED, USER, PROJECT }

/** How the agent's markdown body combines with the parent's system prompt. */
public enum class PromptMode { REPLACE, APPEND }

/** Execution isolation mode. `WORKTREE` runs the agent in a temporary git worktree. */
public enum class IsolationMode { WORKTREE }

/** Persistent-memory scope for an agent's MEMORY.md directory. */
public enum class MemoryScope { USER, PROJECT, LOCAL }

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
    val skills: List<String>? = null,
    val displayName: String? = null,
    val disallowedTools: List<String>? = null,
    val promptMode: PromptMode = PromptMode.REPLACE,
    val inheritContext: Boolean? = null,
    val isolated: Boolean? = null,
    val isolation: IsolationMode? = null,
    val memory: MemoryScope? = null,
    val maxTurns: Int? = null,
    val persistSession: Boolean? = null,
    val outputTranscript: Boolean? = null,
    val sessionDir: String? = null,
    val enabled: Boolean = true,
) {
    /** Name shown in UIs — [displayName] when set, otherwise [name]. */
    val label: String get() = displayName ?: name
}

public sealed interface SpawnsPolicy {
    public data object None : SpawnsPolicy
    public data object All : SpawnsPolicy
    public data class Named(val agents: List<String>) : SpawnsPolicy
}
