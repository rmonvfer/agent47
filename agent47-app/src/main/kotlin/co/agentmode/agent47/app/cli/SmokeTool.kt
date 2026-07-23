package co.agentmode.agent47.app.cli

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.coding.core.tools.BashTool
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

internal fun runSmokeTool(args: List<String>, terminal: Terminal) {
    val command = args.joinToString(" ").ifBlank { "echo native-tool-smoke" }
    val tool = BashTool(Path.of("."))
    val result = runBlocking {
        tool.execute(
            toolCallId = "smoke-tool",
            parameters = buildJsonObject {
                put("command", command)
                put("timeout", 10)
            },
        )
    }
    val output = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    terminal.println(output.ifBlank { "(no output)" })
}
