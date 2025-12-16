package co.agentmode.agent47.coding.core.agents

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class AgentFrontmatter(
    val name: String? = null,
    val description: String? = null,
    val tools: List<String>? = null,
    val spawns: String? = null,
    val model: String? = null,
)

public object AgentParser {

    private val frontmatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    public fun parse(
        content: String,
        fallbackName: String,
        source: AgentSource,
        filePath: String? = null,
    ): AgentDefinition {
        val match = frontmatterRegex.matchEntire(content.trimStart())
        val yamlBlock = match?.groupValues?.get(1)?.trim().orEmpty()
        val body = match?.groupValues?.get(2)?.trim() ?: content.trim()

        val frontmatter = if (yamlBlock.isNotBlank()) {
            runCatching {
                Yaml.default.decodeFromString(AgentFrontmatter.serializer(), yamlBlock)
            }.getOrElse { AgentFrontmatter() }
        } else {
            AgentFrontmatter()
        }

        val name = frontmatter.name ?: fallbackName
        val description = frontmatter.description ?: name

        val spawns = parseSpawnsPolicy(frontmatter.spawns)
        val modelPatterns = frontmatter.model?.let { raw ->
            raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        val thinkingLevel = parseThinkingLevel(yamlBlock)

        val outputSchema = parseOutputSchema(yamlBlock)

        return AgentDefinition(
            name = name,
            description = description,
            systemPrompt = body,
            tools = frontmatter.tools,
            spawns = spawns,
            model = modelPatterns,
            thinkingLevel = thinkingLevel,
            output = outputSchema,
            source = source,
            filePath = filePath,
        )
    }

    private fun parseSpawnsPolicy(raw: String?): SpawnsPolicy {
        if (raw == null) return SpawnsPolicy.None
        val trimmed = raw.trim()
        return when {
            trimmed.equals("none", ignoreCase = true) -> SpawnsPolicy.None
            trimmed == "*" || trimmed.equals("all", ignoreCase = true) -> SpawnsPolicy.All
            else -> {
                val names = trimmed.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (names.isEmpty()) SpawnsPolicy.None else SpawnsPolicy.Named(names)
            }
        }
    }

    private fun parseThinkingLevel(yamlBlock: String): String? {
        val regex = Regex("""thinking-level:\s*(\S+)""")
        return regex.find(yamlBlock)?.groupValues?.get(1)
    }

    private fun parseOutputSchema(yamlBlock: String): JsonObject? {
        val regex = Regex("""output:\s*(\{.*\})""", RegexOption.DOT_MATCHES_ALL)
        val raw = regex.find(yamlBlock)?.groupValues?.get(1) ?: return null
        return runCatching {
            co.agentmode.agent47.ai.types.Agent47Json.decodeFromString(JsonObject.serializer(), raw)
        }.getOrNull()
    }
}
