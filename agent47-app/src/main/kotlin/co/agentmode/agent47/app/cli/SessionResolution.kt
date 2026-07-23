package co.agentmode.agent47.app.cli

import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.session.SessionManager
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal fun sessionsBaseDir(options: CliOptions, config: AgentConfig): Path =
    options.sessionDir ?: config.sessionsDir

internal fun resolveSession(options: CliOptions, config: AgentConfig, terminal: Terminal): SessionManager? {
    if (options.noSession) return null

    val base = sessionsBaseDir(options, config)
    val projectCwd = Path.of(System.getProperty("user.dir"))
    val sessionPath = when {
        options.session != null -> options.session
        options.continueSession -> findLatestProjectSession(base, projectCwd) ?: createNewSession(base)
        options.resume != null -> findSessionById(base, options.resume) ?: run {
            terminal.printWarning("No session found matching '${options.resume}' — starting a new session")
            createNewSession(base)
        }

        else -> createNewSession(base)
    }

    return SessionManager(sessionPath, projectCwd = projectCwd)
}

internal fun createNewSession(sessionsDir: Path): Path {
    Files.createDirectories(sessionsDir)
    return sessionsDir.resolve("session-${System.currentTimeMillis()}.jsonl")
}

internal fun findLatestProjectSession(sessionDir: Path, projectCwd: Path): Path? {
    if (!sessionDir.exists()) return null

    val canonicalProject = projectCwd.canonicalPath()
    return Files.list(sessionDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".jsonl") }
            .filter { file ->
                runCatching {
                    Path.of(SessionManager(file).getHeader().cwd).canonicalPath() == canonicalProject
                }.getOrDefault(false)
            }
            .sorted { a, b -> b.fileName.compareTo(a.fileName) }
            .findFirst()
            .orElse(null)
    }
}

/** Finds the session file whose header id equals [id], or a unique prefix of it. */
internal fun findSessionById(sessionsDir: Path, id: String): Path? =
    if (sessionsDir.exists()) matchSessionId(sessionsDir, id) else null

private fun matchSessionId(sessionsDir: Path, id: String): Path? {
    val files = Files.list(sessionsDir).use { stream ->
        stream.filter { it.toString().endsWith(".jsonl") }.toList()
    }
    var prefixMatch: Path? = null
    var prefixCount = 0
    for (file in files) {
        val sid = runCatching { SessionManager(file).getHeader().id }.getOrNull() ?: continue
        if (sid == id) return file
        if (sid.startsWith(id)) {
            prefixMatch = file
            prefixCount++
        }
    }
    return if (prefixCount == 1) prefixMatch else null
}

private fun Path.canonicalPath(): Path =
    runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
