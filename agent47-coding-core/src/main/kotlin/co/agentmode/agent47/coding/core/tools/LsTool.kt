package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.JsonObject

public class LsTool(
    private val cwd: Path,
) : AgentTool<JsonObject?> {
    override val label: String = "ls"

    override val definition: ToolDefinition = toolDefinition("ls", loadToolPrompt("ls", "List directory contents.")) {
        string("path")
        int("limit")
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val pathArg = parameters.string("path", required = false) ?: "."
        val limit = parameters.int("limit", required = false) ?: 500
        val dir = resolveToCwd(pathArg, cwd)

        require(Files.exists(dir)) { "Path not found: $dir" }
        require(dir.isDirectory()) { "Not a directory: $dir" }

        val entries = withContext(Dispatchers.IO) {
            Files.list(dir)
        }.use { stream ->
            stream
                .map { entry ->
                    val name = entry.fileName.toString()
                    if (Files.isDirectory(entry)) "$name/" else name
                }
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList()
        }

        if (entries.isEmpty()) {
            return AgentToolResult(content = listOf(TextContent(text = "(empty directory)")), details = null)
        }

        val limited = entries.take(limit)
        val truncation = truncateHead(limited.joinToString("\n"), TruncationOptions(maxLines = Int.MAX_VALUE))

        return buildToolOutput(truncation) {
            if (entries.size > limit) limitReached("entries", limit, "entryLimitReached")
            truncationNotice()
        }
    }
}
