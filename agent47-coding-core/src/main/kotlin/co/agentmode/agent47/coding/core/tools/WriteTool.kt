package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

public class WriteTool(
    private val cwd: Path,
) : AgentTool<Nothing?> {
    override val label: String = "write"

    override val definition: ToolDefinition = toolDefinition("write", loadToolPrompt("write", "Write content to a file.")) {
        string("path") { required = true }
        string("content") { required = true }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: co.agentmode.agent47.agent.core.AgentToolUpdateCallback<Nothing?>?,
    ): AgentToolResult<Nothing?> {
        val path = parameters.string("path") ?: error("Missing path")
        val content = parameters.string("content") ?: error("Missing content")

        val absolute = resolveToCwd(path, cwd)
        Files.createDirectories(absolute.parent)
        Files.writeString(absolute, content)

        return AgentToolResult(
            content = listOf(TextContent(text = "Successfully wrote ${content.toByteArray().size} bytes to $path")),
            details = null,
        )
    }
}
