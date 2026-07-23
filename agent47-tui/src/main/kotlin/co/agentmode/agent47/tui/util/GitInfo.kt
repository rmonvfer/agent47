package co.agentmode.agent47.tui.util

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun detectBranchName(path: Path): String? {
    return runCatching {
        val process = ProcessBuilder("git", "-C", path.toString(), "rev-parse", "--abbrev-ref", "HEAD")
            .redirectErrorStream(true)
            .start()
        val ok = process.waitFor(1, TimeUnit.SECONDS)
        if (!ok || process.exitValue() != 0) return null
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim().ifBlank { null }
    }.getOrNull()
}
