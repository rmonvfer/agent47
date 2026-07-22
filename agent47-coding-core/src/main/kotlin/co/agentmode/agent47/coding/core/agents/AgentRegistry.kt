package co.agentmode.agent47.coding.core.agents

import java.nio.file.Path

/**
 * Name → [AgentDefinition] lookup over discovered agents. Lookups are case-insensitive.
 *
 * A definition may be disabled (`enabled: false` in its frontmatter): it stays registered and
 * visible to [getAll] (so management UIs can re-enable it) but is excluded from [getAvailable]
 * and [get] — the spawnable set. When [disableDefaultAgents] is set, bundled defaults are treated
 * as unavailable too.
 */
public class AgentRegistry(
    private val projectDir: Path?,
    private val globalDir: Path?,
    private val disableDefaultAgents: Boolean = false,
) {
    private var agents: List<AgentDefinition> = emptyList()
    private var availableByName: Map<String, AgentDefinition> = emptyMap()

    init {
        refresh()
    }

    public fun refresh() {
        agents = AgentDiscovery.discover(projectDir, globalDir)
        availableByName = agents
            .filter { isAvailable(it) }
            .associateBy { it.name.lowercase() }
    }

    private fun isAvailable(def: AgentDefinition): Boolean =
        def.enabled && !(disableDefaultAgents && def.source == AgentSource.BUNDLED)

    /** A spawnable agent by name (case-insensitive), or null if unknown/disabled/unavailable. */
    public fun get(name: String): AgentDefinition? = availableByName[name.lowercase()]

    /** Spawnable agents (enabled, honoring [disableDefaultAgents]). */
    public fun getAvailable(): List<AgentDefinition> = agents.filter { isAvailable(it) }

    /** Every discovered agent, including disabled ones (for management UIs). */
    public fun getAll(): List<AgentDefinition> = agents
}
