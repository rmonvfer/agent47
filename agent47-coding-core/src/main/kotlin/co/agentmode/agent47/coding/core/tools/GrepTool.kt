package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonObject

public class GrepTool(
    private val cwd: Path,
) : AgentTool<JsonObject?> {
    override val label: String = "grep"

    override val definition: ToolDefinition = toolDefinition("grep", loadToolPrompt("grep", "Search file contents for a pattern.")) {
        string("pattern") { required = true }
        string("path")
        string("glob")
        boolean("ignoreCase")
        boolean("literal")
        int("context")
        int("limit")
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val patternText = parameters.string("pattern") ?: error("Missing pattern")
        val searchRoot = resolveToCwd(parameters.string("path", required = false) ?: ".", cwd)
        val glob = parameters.string("glob", required = false)
        val ignoreCase = parameters.boolean("ignoreCase", required = false) ?: false
        val literal = parameters.boolean("literal", required = false) ?: false
        val context = parameters.int("context", required = false)?.coerceAtLeast(0) ?: 0
        val limit = parameters.int("limit", required = false)?.coerceAtLeast(1) ?: 100

        require(Files.exists(searchRoot)) { "Path not found: $searchRoot" }

        val regex = if (literal) {
            Pattern.compile(Pattern.quote(patternText), if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
        } else {
            Pattern.compile(patternText, if (ignoreCase) Pattern.CASE_INSENSITIVE else 0)
        }

        val matches = mutableListOf<String>()
        var linesTruncated = false

        withContext(Dispatchers.IO) {
            Files.walk(searchRoot)
        }.use { stream ->
            val iterator = stream.filter { Files.isRegularFile(it) }.iterator()
            while (iterator.hasNext() && matches.size < limit) {
                val file = iterator.next()
                val relative = searchRoot.relativize(file).toString().replace("\\", "/")
                if (relative.contains("/node_modules/") || relative.contains("/.git/")) {
                    continue
                }
                if (glob != null && !Path.of(relative).matchesGlob(glob)) {
                    continue
                }

                val lines = runCatching { Files.readAllLines(file) }.getOrNull() ?: continue
                for ((index, line) in lines.withIndex()) {
                    if (matches.size >= limit) {
                        break
                    }
                    if (regex.matcher(line).find()) {
                        val start = (index - context).coerceAtLeast(0)
                        val end = (index + context).coerceAtMost(lines.lastIndex)
                        for (lineIndex in start..end) {
                            val prefix = if (lineIndex == index) ":" else "-"
                            val (truncatedLine, wasTruncated) = truncateLine(lines[lineIndex])
                            if (wasTruncated) {
                                linesTruncated = true
                            }
                            val sep = if (lineIndex == index) ":" else "-"
                            matches += "$relative$sep${lineIndex + 1}$prefix $truncatedLine"
                        }
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return AgentToolResult(content = listOf(TextContent(text = "No matches found")), details = null)
        }

        val truncation = truncateHead(matches.joinToString("\n"), TruncationOptions(maxLines = Int.MAX_VALUE))

        return buildToolOutput(truncation) {
            if (matches.size >= limit) limitReached("matches", limit, "matchLimitReached")
            truncationNotice()
            if (linesTruncated) {
                notice("Some lines truncated to $GREP_MAX_LINE_LENGTH chars. Use read tool to see full lines", "linesTruncated")
            }
        }
    }
}
