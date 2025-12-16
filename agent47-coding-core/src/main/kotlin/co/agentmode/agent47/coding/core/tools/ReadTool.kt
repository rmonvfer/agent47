package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.ImageContent
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public class ReadTool(
    private val cwd: Path,
    private val skillReader: SkillReader? = null,
) : AgentTool<JsonObject?> {
    override val label: String = "read"
    override val definition: ToolDefinition = toolDefinition("read", loadToolPrompt("read", "Read the contents of a file.")) {
        string("path") { required = true }
        int("offset")
        int("limit")
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val path = parameters.string("path") ?: error("Missing path")
        val offset = parameters.int("offset")
        val limit = parameters.int("limit")

        if (path.startsWith("skill://")) {
            return readSkill(path, offset, limit)
        }

        val absolutePath = resolveReadPath(path, cwd)
        require(Files.exists(absolutePath)) { "File not found: $path" }

        if (looksLikeImage(absolutePath)) {
            val mimeType = withContext(Dispatchers.IO) {
                Files.probeContentType(absolutePath)
            } ?: "image/png"
            val data = java.util.Base64.getEncoder().encodeToString(withContext(Dispatchers.IO) {
                Files.readAllBytes(absolutePath)
            })
            return AgentToolResult(
                content = listOf(
                    TextContent(text = "Read image file [$mimeType]"),
                    ImageContent(data = data, mimeType = mimeType),
                ),
                details = null,
            )
        }

        val text = withContext(Dispatchers.IO) {
            Files.readString(absolutePath)
        }
        val lines = text.split("\n")
        val start = (offset ?: 1).coerceAtLeast(1) - 1
        require(start < lines.size) { "Offset ${offset ?: 1} is beyond end of file (${lines.size} lines total)" }

        val selected = if (limit != null) {
            lines.subList(start, (start + limit).coerceAtMost(lines.size)).joinToString("\n")
        } else {
            lines.subList(start, lines.size).joinToString("\n")
        }

        val truncation = truncateHead(selected)
        val details = if (truncation.truncated) {
            buildJsonObject {
                put("truncated", true)
                put("truncatedBy", truncation.truncatedBy?.name?.lowercase() ?: "")
                put("outputLines", truncation.outputLines)
                put("totalLines", truncation.totalLines)
            }
        } else {
            null
        }

        val textOut = buildString {
            append(truncation.content)
            if (truncation.truncated) {
                val endLine = start + truncation.outputLines
                append("\n\n[Showing lines ${start + 1}-$endLine of ${lines.size}. Use offset=${endLine + 1} to continue.]")
            } else if (limit != null && start + limit < lines.size) {
                append("\n\n[${lines.size - (start + limit)} more lines in file. Use offset=${start + limit + 1} to continue.]")
            }
        }

        return AgentToolResult(
            content = listOf(TextContent(text = textOut)),
            details = details
        )
    }

    private fun readSkill(path: String, offset: Int?, limit: Int?): AgentToolResult<JsonObject?> {
        val reader = skillReader
            ?: return AgentToolResult(
                content = listOf(TextContent(text = "skill:// protocol is not available")),
                details = null,
            )

        val stripped = path.removePrefix("skill://")
        val slashIndex = stripped.indexOf('/')
        val skillName: String
        val relativePath: String?

        if (slashIndex < 0) {
            skillName = stripped
            relativePath = null
        } else {
            skillName = stripped.substring(0, slashIndex)
            relativePath = stripped.substring(slashIndex + 1).ifBlank { null }
        }

        val content = reader.readSkillFile(skillName, relativePath)
            ?: return AgentToolResult(
                content = listOf(TextContent(text = "Skill not found: $path")),
                details = null,
            )

        val lines = content.split("\n")
        val start = (offset ?: 1).coerceAtLeast(1) - 1
        if (start >= lines.size) {
            return AgentToolResult(
                content = listOf(TextContent(text = "Offset ${offset ?: 1} is beyond end of skill content (${lines.size} lines total)")),
                details = null,
            )
        }

        val selected = if (limit != null) {
            lines.subList(start, (start + limit).coerceAtMost(lines.size)).joinToString("\n")
        } else {
            lines.subList(start, lines.size).joinToString("\n")
        }

        return AgentToolResult(
            content = listOf(TextContent(text = selected)),
            details = null,
        )
    }

    private fun looksLikeImage(path: Path): Boolean {
        val name = path.fileName.toString().lowercase()
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(
            ".webp"
        )
    }
}

public fun interface SkillReader {
    public fun readSkillFile(name: String, relativePath: String?): String?
}
