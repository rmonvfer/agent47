package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

public class FindTool(
    private val cwd: Path,
) : AgentTool<JsonObject?> {
    override val label: String = "find"

    override val definition: ToolDefinition = toolDefinition("find", loadToolPrompt("find", "Search for files by glob pattern.")) {
        string("pattern") { required = true }
        string("path")
        int("limit")
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val pattern = parameters.string("pattern") ?: error("Missing pattern")
        val pathArg = parameters.string("path", required = false) ?: "."
        val limit = parameters.int("limit", required = false) ?: 1000

        val root = resolveToCwd(pathArg, cwd)
        require(Files.exists(root)) { "Path not found: $root" }

        val matcher = FileSystems.getDefault().getPathMatcher("glob:$pattern")
        val fallbackMatcher = if (pattern.startsWith("**/")) {
            FileSystems.getDefault().getPathMatcher("glob:${pattern.removePrefix("**/")}")
        } else {
            null
        }
        val results = mutableListOf<String>()

        withContext(Dispatchers.IO) {
            Files.walk(root)
        }.use { stream ->
            stream.forEach { file ->
                if (results.size >= limit) {
                    return@forEach
                }
                val rel = root.relativize(file)
                if (rel.toString().isEmpty()) {
                    return@forEach
                }
                if (rel.toString().contains("/node_modules/") || rel.toString().contains("/.git/")) {
                    return@forEach
                }
                if (
                    matcher.matches(rel) ||
                    matcher.matches(file.fileName) ||
                    fallbackMatcher?.matches(rel) == true ||
                    fallbackMatcher?.matches(file.fileName) == true
                ) {
                    val normalized = rel.toString().replace("\\", "/") + if (Files.isDirectory(file)) "/" else ""
                    results += normalized
                }
            }
        }

        if (results.isEmpty()) {
            return AgentToolResult(content = listOf(TextContent(text = "No files found matching pattern")), details = null)
        }

        val truncation = truncateHead(results.joinToString("\n"), TruncationOptions(maxLines = Int.MAX_VALUE))

        return buildToolOutput(truncation) {
            if (results.size >= limit) limitReached("results", limit, "resultLimitReached")
            truncationNotice()
        }
    }
}
