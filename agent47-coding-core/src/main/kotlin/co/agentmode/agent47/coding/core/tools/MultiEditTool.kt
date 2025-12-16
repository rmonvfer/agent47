package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * Applies multiple sequential edits to a single file atomically.
 * Delegates to the same logic as [EditTool] for each individual edit,
 * but rolls back all changes if any edit fails.
 */
public class MultiEditTool(
    private val cwd: Path,
) : AgentTool<JsonObject?> {

    override val label: String = "multiedit"

    override val definition: ToolDefinition = toolDefinition(
        "multiedit",
        loadToolPrompt("multiedit", "Apply multiple edits to a single file atomically."),
    ) {
        string("path") { required = true; description = "Absolute path to the file to modify" }
        array("edits") {
            required = true
            description = "Array of edit operations to apply sequentially"
            items {
                string("oldText") { required = true; description = "The exact text to find and replace" }
                string("newText") { required = true; description = "The replacement text" }
            }
        }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val path = parameters.string("path") ?: error("Missing path")
        val edits = parameters["edits"]?.jsonArray
            ?: error("Missing edits array")

        if (edits.isEmpty()) {
            return AgentToolResult(
                content = listOf(TextContent(text = "Error: edits array is empty")),
                details = null,
            )
        }

        val absolutePath = resolveToCwd(path, cwd)
        require(Files.exists(absolutePath)) { "File not found: $path" }

        val rawContent = withContext(Dispatchers.IO) {
            Files.readString(absolutePath)
        }
        val stripped = stripBom(rawContent)
        val originalEnding = detectLineEnding(stripped.text)
        var currentContent = normalizeToLf(stripped.text)

        val diffs = mutableListOf<String>()

        for ((index, editElement) in edits.withIndex()) {
            val editObj = editElement.jsonObject
            val oldText = editObj.string("oldText")
                ?: error("Edit at index $index missing 'oldText'")
            val newText = editObj.string("newText")
                ?: error("Edit at index $index missing 'newText'")

            val normalizedOld = normalizeToLf(oldText)
            val normalizedNew = normalizeToLf(newText)

            val match = fuzzyFindText(currentContent, normalizedOld)
            if (!match.found) {
                return AgentToolResult(
                    content = listOf(
                        TextContent(
                            text = "Error: Edit $index failed - could not find the exact text in $path. " +
                                "The old text must match exactly including all whitespace and newlines. " +
                                "No edits were applied.",
                        ),
                    ),
                    details = null,
                )
            }

            val fuzzyContent = normalizeForFuzzyMatch(currentContent)
            val fuzzyOld = normalizeForFuzzyMatch(normalizedOld)
            val occurrences = fuzzyContent.split(fuzzyOld).size - 1
            if (occurrences > 1) {
                return AgentToolResult(
                    content = listOf(
                        TextContent(
                            text = "Error: Edit $index failed - found $occurrences occurrences of the text in $path. " +
                                "The text must be unique. Please provide more context. No edits were applied.",
                        ),
                    ),
                    details = null,
                )
            }

            val base = match.contentForReplacement
            val edited = base.substring(0, match.index) + normalizedNew + base.substring(match.index + match.matchLength)
            if (base == edited) {
                return AgentToolResult(
                    content = listOf(
                        TextContent(
                            text = "Error: Edit $index produced no changes in $path. " +
                                "oldText and newText are the same. No edits were applied.",
                        ),
                    ),
                    details = null,
                )
            }

            val diff = generateDiffString(currentContent, edited)
            diffs += diff.diff
            currentContent = edited
        }

        val finalContent = stripped.bom + restoreLineEndings(currentContent, originalEnding)
        withContext(Dispatchers.IO) {
            Files.writeString(absolutePath, finalContent)
        }

        val details = buildJsonObject {
            put("editsApplied", edits.size)
            put("diff", diffs.joinToString("\n---\n"))
        }

        return AgentToolResult(
            content = listOf(TextContent(text = "Successfully applied ${edits.size} edit(s) to $path.")),
            details = details,
        )
    }
}
