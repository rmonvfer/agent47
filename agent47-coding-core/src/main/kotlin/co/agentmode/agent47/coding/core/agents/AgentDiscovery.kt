package co.agentmode.agent47.coding.core.agents

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

public object AgentDiscovery {

    public fun discover(projectDir: Path?, globalDir: Path?): List<AgentDefinition> {
        val seen = mutableSetOf<String>()
        val agents = mutableListOf<AgentDefinition>()

        discoverFromDirectory(projectDir, AgentSource.PROJECT, seen, agents)
        discoverFromDirectory(globalDir, AgentSource.USER, seen, agents)
        discoverFromClasspath(seen, agents)

        return agents
    }

    private fun discoverFromDirectory(
        dir: Path?,
        source: AgentSource,
        seen: MutableSet<String>,
        out: MutableList<AgentDefinition>,
    ) {
        if (dir == null || !dir.exists()) return

        runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.extension == "md" }
                    .sorted()
                    .forEach { path ->
                        val fallbackName = path.nameWithoutExtension
                        val content = path.readText()
                        val definition = AgentParser.parse(
                            content = content,
                            fallbackName = fallbackName,
                            source = source,
                            filePath = path.toAbsolutePath().toString(),
                        )
                        if (definition.name !in seen) {
                            seen += definition.name
                            out += definition
                        }
                    }
            }
        }
    }

    private fun discoverFromClasspath(seen: MutableSet<String>, out: MutableList<AgentDefinition>) {
        val resourceNames = listOf("explore.md", "plan.md", "task.md", "quick_task.md")
        for (resourceName in resourceNames) {
            val stream = AgentDiscovery::class.java.classLoader
                .getResourceAsStream("agents/$resourceName")
                ?: continue
            val content = stream.bufferedReader().readText()
            val fallbackName = resourceName.removeSuffix(".md")
            val definition = AgentParser.parse(
                content = content,
                fallbackName = fallbackName,
                source = AgentSource.BUNDLED,
            )
            if (definition.name !in seen) {
                seen += definition.name
                out += definition
            }
        }
    }
}
