package co.agentmode.agent47.tui.util

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun executeShell(command: String, cwd: Path): Pair<String, Int> {
    return runCatching {
        val process = ProcessBuilder("/bin/sh", "-lc", command)
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "Command timed out after 60s" to 124
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        output.ifBlank { "(no output)" } to process.exitValue()
    }.getOrElse { error ->
        "Command failed: ${error.message ?: error::class.simpleName}" to 1
    }
}
