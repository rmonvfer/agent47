package co.agentmode.agent47.coding.core.agents

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Environment facts injected into a sub-agent's system prompt. */
public data class EnvInfo(
    val isGitRepo: Boolean,
    val branch: String,
    val platform: String,
)

/** Detects git/platform context for a working directory, for a sub-agent's environment block. */
public object AgentEnv {

    public fun detect(cwd: Path): EnvInfo {
        val isGitRepo = runGit(cwd, listOf("rev-parse", "--is-inside-work-tree"))?.trim() == "true"
        val branch = if (isGitRepo) {
            runGit(cwd, listOf("branch", "--show-current"))?.trim()?.ifBlank { "unknown" } ?: "unknown"
        } else {
            ""
        }
        return EnvInfo(isGitRepo = isGitRepo, branch = branch, platform = System.getProperty("os.name") ?: "unknown")
    }

    private fun runGit(cwd: Path, args: List<String>): String? = runCatching {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(cwd.toFile())
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        if (process.exitValue() != 0) return null
        process.inputStream.bufferedReader().readText()
    }.getOrNull()
}
