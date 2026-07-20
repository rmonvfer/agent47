package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val PROGRESS_UPDATE_LINE_INTERVAL = 16

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

            val collector = BashOutputCollector()
            try {
                coroutineScope {
                    // Drain output on a separate coroutine so the timeout is enforced against
                    // wall-clock time; a blocking read loop here would only end at process EOF.
                    val readerJob = launch {
                        runCatching {
                            process.inputStream.bufferedReader().use { reader ->
                                while (isActive) {
                                    val line = reader.readLine() ?: break
                                    collector.append(line)
                                    if (collector.totalLines % PROGRESS_UPDATE_LINE_INTERVAL == 0) {
                                        onUpdate?.onUpdate(collector.toToolResult())
                                    }
                                }
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
                        killProcessTree(process)
                        readerJob.cancelAndJoin()
                        throw IllegalStateException("Command timed out after $timeoutSeconds seconds")
                    }

                    readerJob.join()
                }
            } finally {
                collector.close()
            }

            val snapshot = collector.snapshot()
            var outputText = snapshot.content.ifBlank { "(no output)" }

            val details = if (snapshot.truncated) {
                buildJsonObject {
                    put("truncated", true)
                    snapshot.logPath?.let { put("fullOutputPath", it) }
                    put("totalLines", snapshot.totalLines)
                }
            } else {
                null
            }

            if (snapshot.truncated) {
                val startLine = snapshot.totalLines - snapshot.outputLines + 1
                val endLine = snapshot.totalLines
                val suffix = snapshot.logPath?.let { " Full output: $it" } ?: ""
                outputText += "\n\n[Showing lines $startLine-$endLine of ${snapshot.totalLines}.$suffix]"
            }

            if (process.exitValue() != 0) {
                throw IllegalStateException("$outputText\n\nCommand exited with code ${process.exitValue()}")
            }

            AgentToolResult(content = listOf(TextContent(text = outputText)), details = details)
        }
    }
}

private fun killProcessTree(process: Process) {
    // A bare destroyForcibly() only kills the shell, orphaning grandchildren; kill descendants too.
    runCatching { process.descendants().forEach { it.destroyForcibly() } }
    process.destroyForcibly()
}

private data class BashSnapshot(
    val content: String,
    val truncated: Boolean,
    val totalLines: Int,
    val outputLines: Int,
    val logPath: String?,
)

/**
 * Accumulates process output while keeping memory bounded. Small outputs are held entirely in
 * memory and never touch disk; once the output exceeds the display limits, the full output is
 * spilled to a lazily created temp log and only a bounded tail is retained for display.
 */
private class BashOutputCollector(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val tail = ArrayDeque<String>()
    private var tailBytes = 0
    private var spilled = false
    private var logFile: Path? = null
    private var logWriter: BufferedWriter? = null

    var totalLines: Int = 0
        private set

    fun append(line: String) {
        totalLines++
        tail.addLast(line)
        tailBytes += byteLength(line) + 1
        if (spilled) {
            writeLog(line)
        }
        trimTail()
    }

    private fun trimTail() {
        while (tail.size > maxLines || tailBytes > maxBytes) {
            if (!spilled) beginSpill()
            val removed = tail.removeFirst()
            tailBytes -= byteLength(removed) + 1
            if (tail.isEmpty()) break
        }
    }

    private fun beginSpill() {
        // The output exceeded the display limits, so persist everything buffered so far and stream
        // the rest to the log. Each line reaches the log exactly once (buffered lines here, later
        // lines via append()).
        val file = Path.of(System.getProperty("java.io.tmpdir"), "agent47-bash-${UUID.randomUUID()}.log")
        val writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)
        for (buffered in tail) {
            writer.write(buffered)
            writer.write("\n")
        }
        writer.flush()
        logFile = file
        logWriter = writer
        spilled = true
    }

    private fun writeLog(line: String) {
        logWriter?.let {
            it.write(line)
            it.write("\n")
        }
    }

    fun snapshot(): BashSnapshot = BashSnapshot(
        content = tail.joinToString("\n"),
        truncated = spilled,
        totalLines = totalLines,
        outputLines = tail.size,
        logPath = logFile?.toString(),
    )

    fun toToolResult(): AgentToolResult<JsonObject?> {
        val snapshot = snapshot()
        return AgentToolResult(
            content = listOf(TextContent(text = snapshot.content)),
            details = if (snapshot.truncated) {
                buildJsonObject {
                    put("truncated", true)
                    snapshot.logPath?.let { put("fullOutputPath", it) }
                }
            } else {
                null
            },
        )
    }

    fun close() {
        logWriter?.let {
            runCatching {
                it.flush()
                it.close()
            }
        }
        logWriter = null
    }

    private fun byteLength(line: String): Int = line.toByteArray(StandardCharsets.UTF_8).size
}
