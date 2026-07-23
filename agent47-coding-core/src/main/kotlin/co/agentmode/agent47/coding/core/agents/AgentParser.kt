package co.agentmode.agent47.coding.core.agents

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses an agent markdown file into an [AgentDefinition]: a YAML frontmatter block followed by a
 * body (the system prompt). Frontmatter is parsed into a [YamlNode] tree rather than a strict data
 * class so that unknown keys are ignored and list-or-CSV fields (e.g. `tools`, `disallowed_tools`)
 * are both accepted.
 */
public object AgentParser {

    private val frontMatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    public fun parse(
        content: String,
        fallbackName: String,
        source: AgentSource,
        filePath: String? = null,
    ): AgentDefinition {
        val match = frontMatterRegex.matchEntire(content.trimStart())
        val yamlBlock = match?.groupValues?.get(1)?.trim().orEmpty()
        val body = match?.groupValues?.get(2)?.trim() ?: content.trim()

        val fm = if (yamlBlock.isNotBlank()) {
            runCatching { Yaml.default.parseToYamlNode(yamlBlock) as? YamlMap }.getOrNull()
        } else {
            null
        }

        val name = fm.scalar("name") ?: fallbackName
        val description = fm.scalar("description") ?: name

        return AgentDefinition(
            name = name,
            description = description,
            systemPrompt = body,
            tools = fm.stringList("tools"),
            spawns = parseSpawnsPolicy(fm.scalar("spawns")),
            model = fm.scalar("model")?.let { raw ->
                raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.ifEmpty { null }
            },
            thinkingLevel = fm.scalar("thinking-level") ?: fm.scalar("thinking"),
            output = parseOutputSchema(fm),
            source = source,
            filePath = filePath,
            skills = fm.stringList("skills"),
            displayName = fm.scalar("display_name"),
            disallowedTools = fm.stringList("disallowed_tools"),
            promptMode = if (fm.scalar("prompt_mode")?.equals("append", ignoreCase = true) == true) {
                PromptMode.APPEND
            } else {
                PromptMode.REPLACE
            },
            inheritContext = fm.bool("inherit_context"),
            isolated = fm.bool("isolated"),
            isolation = if (fm.scalar("isolation")?.equals("worktree", ignoreCase = true) == true) {
                IsolationMode.WORKTREE
            } else {
                null
            },
            memory = when (fm.scalar("memory")?.lowercase()) {
                "user" -> MemoryScope.USER
                "project" -> MemoryScope.PROJECT
                "local" -> MemoryScope.LOCAL
                else -> null
            },
            maxTurns = fm.nonNegativeInt("max_turns"),
            persistSession = fm.bool("persist_session"),
            outputTranscript = fm.bool("output_transcript"),
            sessionDir = fm.scalar("session_dir"),
            // Default true; only an explicit `enabled: false` disables the agent.
            enabled = fm.bool("enabled") != false,
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

    private fun parseOutputSchema(frontMatter: YamlMap?): JsonObject? =
        (frontMatter.child("output") as? YamlMap)?.toJsonObject()

    private fun YamlMap.toJsonObject(): JsonObject = JsonObject(
        entries.map { (key, value) -> key.content to value.toJsonElement() }.toMap(),
    )

    private fun YamlNode.toJsonElement(): JsonElement = when (this) {
        is YamlMap -> toJsonObject()
        is YamlList -> JsonArray(items.map { it.toJsonElement() })
        is YamlNull -> JsonNull
        is YamlScalar -> toJsonPrimitive()
        is YamlTaggedNode -> innerNode.toJsonElement()
    }

    private fun YamlScalar.toJsonPrimitive(): JsonPrimitive {
        val value = content.trim()
        return when {
            value.equals("true", ignoreCase = true) -> JsonPrimitive(true)
            value.equals("false", ignoreCase = true) -> JsonPrimitive(false)
            INTEGER_PATTERN.matches(value) -> value.toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            DECIMAL_PATTERN.matches(value) -> value.toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(value)
            else -> JsonPrimitive(content)
        }
    }

    // ---- YamlNode field readers. All tolerate a null map (no frontmatter) and absent keys. ----

    private fun YamlMap?.child(key: String): YamlNode? = this?.get<YamlNode>(key)

    /** A scalar string value, or null when absent/blank/null-node. */
    private fun YamlMap?.scalar(key: String): String? =
        (child(key) as? YamlScalar)?.content?.trim()?.takeIf { it.isNotBlank() }

    /** Tri-state boolean: explicit `true`/`false`, or null when absent/unparseable. */
    private fun YamlMap?.bool(key: String): Boolean? =
        when ((child(key) as? YamlScalar)?.content?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    /** Non-negative integer, or null when absent/negative/unparseable. `0` is a valid value. */
    private fun YamlMap?.nonNegativeInt(key: String): Int? =
        (child(key) as? YamlScalar)?.content?.trim()?.toIntOrNull()?.takeIf { it >= 0 }

    /**
     * A list field accepting either a YAML list (`[a, b]`) or a CSV scalar (`a, b`).
     * Absent → null (meaning "unset"); `none`/empty → emptyList; otherwise the trimmed items.
     */
    private fun YamlMap?.stringList(key: String): List<String>? = when (val node = child(key)) {
        null, is YamlNull -> null
        is YamlList -> node.items.mapNotNull { (it as? YamlScalar)?.content?.trim() }.filter { it.isNotBlank() }
        is YamlScalar -> {
            val s = node.content.trim()
            if (s.isEmpty() || s.equals("none", ignoreCase = true)) {
                emptyList()
            } else {
                s.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        }
        else -> null
    }

    private val INTEGER_PATTERN = Regex("""[-+]?(?:0|[1-9][0-9]*)""")
    private val DECIMAL_PATTERN = Regex("""[-+]?(?:(?:0|[1-9][0-9]*)\.[0-9]+|(?:0|[1-9][0-9]*)(?:[eE][-+]?[0-9]+))""")
}
