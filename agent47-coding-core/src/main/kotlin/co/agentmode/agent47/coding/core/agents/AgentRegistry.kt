package co.agentmode.agent47.coding.core.agents

import java.nio.file.Path

public class AgentRegistry(
    private val projectDir: Path?,
    private val globalDir: Path?,
) {
    private var agents: List<AgentDefinition> = emptyList()
    private var agentsByName: Map<String, AgentDefinition> = emptyMap()

    init {
        refresh()
    }

    public fun refresh() {
        agents = AgentDiscovery.discover(projectDir, globalDir)
        agentsByName = agents.associateBy { it.name }
    }

    public fun get(name: String): AgentDefinition? = agentsByName[name]

    public fun getAll(): List<AgentDefinition> = agents
}
