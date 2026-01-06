package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public class BashTool(
    private val cwd: Path,
    public val commandPrefix: String? = null,
) : AgentTool<JsonObject?> {
    override val label: String = "bash"

    override val definition: ToolDefinition = toolDefinition("bash", loadToolPrompt("bash", "Execute a bash command in the current working directory.")) {
        string("command") { required = true }
        int("timeout")
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<JsonObject?>?,
    ): AgentToolResult<JsonObject?> {
        val command = parameters.string("command") ?: error("Missing command")
        val timeoutSeconds = parameters.int("timeout", required = false)

        return withContext(Dispatchers.IO) {
            require(Files.exists(cwd)) { "Working directory does not exist: $cwd" }

            val resolvedCommand = if (commandPrefix != null) "$commandPrefix\n$command" else command
            val process = ProcessBuilder("/bin/bash", "-lc", resolvedCommand)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start()

            val outputChunks = mutableListOf<String>()
            val outputFile: File = Path.of(System.getProperty("java.io.tmpdir"), "agent47-bash-${UUID.randomUUID()}.log").toFile()
            outputFile.outputStream().use { logStream ->
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        outputChunks += line
                        logStream.write((line + "\n").toByteArray())

                        val tail = truncateTail(outputChunks.joinToString("\n"))
                        onUpdate?.onUpdate(
                            AgentToolResult(
                                content = listOf(TextContent(text = tail.content)),
                                details = if (tail.truncated) buildJsonObject {
                                    put("truncated", true)
                                    put("fullOutputPath", outputFile.absolutePath)
                                } else null,
                            ),
                        )
                    }
                }
            }

            val finished = if (timeoutSeconds != null && timeoutSeconds > 0) {
                process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!finished) {
                process.destroyForcibly()
                throw IllegalStateException("Command timed out after $timeoutSeconds seconds")
            }

            val fullOutput = outputChunks.joinToString("\n")
            val truncation = truncateTail(fullOutput)
            var outputText = truncation.content.ifBlank { "(no output)" }

            val details = if (truncation.truncated) {
                buildJsonObject {
                    put("truncated", true)
                    put("fullOutputPath", outputFile.absolutePath)
                    put("totalLines", truncation.totalLines)
                }
            } else {
                null
            }

            if (truncation.truncated) {
                val startLine = truncation.totalLines - truncation.outputLines + 1
                val endLine = truncation.totalLines
                outputText += "\n\n[Showing lines $startLine-$endLine of ${truncation.totalLines}. Full output: ${outputFile.absolutePath}]"
            }

            if (process.exitValue() != 0) {
                throw IllegalStateException("$outputText\n\nCommand exited with code ${process.exitValue()}")
            }

            AgentToolResult(content = listOf(TextContent(text = outputText)), details = details)
        }
    }
}
