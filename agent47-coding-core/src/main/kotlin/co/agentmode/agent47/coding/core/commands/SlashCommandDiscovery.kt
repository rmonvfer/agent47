package co.agentmode.agent47.coding.core.commands

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText

@Serializable
internal data class CommandFrontmatter(
    val name: String? = null,
    val description: String? = null,
)

public object SlashCommandDiscovery {

    private val frontmatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    public fun discover(projectDir: Path?, globalDir: Path?): List<SlashCommand> {
        val seen = mutableSetOf<String>()
        val commands = mutableListOf<SlashCommand>()

        discoverFromDirectory(projectDir, SlashCommandSource.PROJECT, seen, commands)
        discoverFromDirectory(globalDir, SlashCommandSource.USER, seen, commands)
        discoverFromClasspath(seen, commands)

        return commands
    }

    private fun discoverFromDirectory(
        dir: Path?,
        source: SlashCommandSource,
        seen: MutableSet<String>,
        out: MutableList<SlashCommand>,
    ) {
        if (dir == null || !dir.exists()) return

        runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.extension == "md" }
                    .sorted()
                    .forEach { path ->
                        val command = parseCommand(path, source)
                        if (command.name !in seen) {
                            seen += command.name
                            out += command
                        }
                    }
            }
        }
    }

    private fun discoverFromClasspath(seen: MutableSet<String>, out: MutableList<SlashCommand>) {
        val resourceNames = listOf("init.md")
        for (resourceName in resourceNames) {
            val stream = SlashCommandDiscovery::class.java.classLoader
                .getResourceAsStream("commands/$resourceName")
                ?: continue
            val content = stream.bufferedReader().readText()
            val command = parseCommandFromString(content, resourceName, SlashCommandSource.BUNDLED)
            if (command.name !in seen) {
                seen += command.name
                out += command
            }
        }
    }

    private fun parseCommand(path: Path, source: SlashCommandSource): SlashCommand {
        return parseCommandFromString(path.readText(), path.fileName.toString(), source)
    }

    private fun parseCommandFromString(raw: String, fileName: String, source: SlashCommandSource): SlashCommand {
        val match = frontmatterRegex.matchEntire(raw.trimStart())
        val yamlBlock = match?.groupValues?.get(1)?.trim().orEmpty()
        val body = match?.groupValues?.get(2)?.trim() ?: raw.trim()

        val frontmatter = if (yamlBlock.isNotBlank()) {
            runCatching {
                Yaml.default.decodeFromString(CommandFrontmatter.serializer(), yamlBlock)
            }.getOrElse { CommandFrontmatter() }
        } else {
            CommandFrontmatter()
        }

        val name = frontmatter.name ?: fileName.removeSuffix(".md")
        val description = frontmatter.description ?: name

        return SlashCommand(
            name = name,
            description = description,
            content = body,
            source = source,
        )
    }
}
