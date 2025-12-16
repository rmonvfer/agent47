package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public class EditTool(
    private val cwd: Path,
) : AgentTool<JsonObject?> {
    override val label: String = "edit"

    override val definition: ToolDefinition = toolDefinition("edit", loadToolPrompt("edit", "Edit a file by replacing exact text.")) {
        string("path") { required = true }
        string("oldText") { required = true }
        string("newText") { required = true }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val path = parameters.string("path") ?: error("Missing path")
        val oldText = parameters.string("oldText") ?: error("Missing oldText")
        val newText = parameters.string("newText") ?: error("Missing newText")

        val absolutePath = resolveToCwd(path, cwd)
        require(Files.exists(absolutePath)) { "File not found: $path" }

        val rawContent = withContext(Dispatchers.IO) {
            Files.readString(absolutePath)
        }
        val stripped = stripBom(rawContent)
        val originalEnding = detectLineEnding(stripped.text)
        val normalizedContent = normalizeToLf(stripped.text)
        val normalizedOld = normalizeToLf(oldText)
        val normalizedNew = normalizeToLf(newText)

        val match = fuzzyFindText(normalizedContent, normalizedOld)
        require(match.found) {
            "Could not find the exact text in $path. The old text must match exactly including all whitespace and newlines."
        }

        val fuzzyContent = normalizeForFuzzyMatch(normalizedContent)
        val fuzzyOld = normalizeForFuzzyMatch(normalizedOld)
        val occurrences = fuzzyContent.split(fuzzyOld).size - 1
        require(occurrences <= 1) {
            "Found $occurrences occurrences of the text in $path. The text must be unique. Please provide more context."
        }

        val base = match.contentForReplacement
        val edited = base.substring(0, match.index) + normalizedNew + base.substring(match.index + match.matchLength)
        require(base != edited) {
            "No changes made to $path. The replacement produced identical content."
        }

        val final = stripped.bom + restoreLineEndings(edited, originalEnding)
        withContext(Dispatchers.IO) {
            Files.writeString(absolutePath, final)
        }

        val diff = generateDiffString(base, edited)
        val details = buildJsonObject {
            put("diff", diff.diff)
            diff.firstChangedLine?.let { put("firstChangedLine", it) }
        }

        return AgentToolResult(
            content = listOf(TextContent(text = "Successfully replaced text in $path.")),
            details = details,
        )
    }
}
