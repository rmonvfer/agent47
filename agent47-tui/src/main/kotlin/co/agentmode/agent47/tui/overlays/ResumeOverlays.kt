package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.coding.core.session.SessionInfoEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.tui.session.firstUserText
import co.agentmode.agent47.ui.core.state.SelectItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

private data class ResumeCandidate(val path: Path, val session: SessionManager, val modified: Instant)

/**
 * Opens the `/resume` overlay: every session file for this project, newest modified first, showing
 * the session's name if it has one, else its first user message, plus a relative age.
 */
@Suppress("ReturnCount")
internal fun OverlayNavigator.openResumeOverlay() {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
        feed.appendCommandResult("Session picker is unavailable: no session directory configured")
        return
    }
    val files = runCatching {
        Files.list(sessionsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jsonl") }
                // Exclude sub-agent sessions, they aren't top-level conversations.
                .filter { !it.fileName.toString().startsWith("subagent-") }
                .toList()
        }
    }.getOrElse {
        feed.appendCommandResult("Failed to list sessions: ${it.message ?: it::class.simpleName}")
        return
    }

    val projectCwd = cwd.toAbsolutePath().normalize().toString()
    val candidates = files.mapNotNull { path ->
        val manager = runCatching { SessionManager(path) }.getOrNull() ?: return@mapNotNull null
        val sessionCwd = runCatching { Path.of(manager.getHeader().cwd).toAbsolutePath().normalize().toString() }.getOrNull()
        if (sessionCwd != projectCwd) return@mapNotNull null
        val modified = runCatching { Files.getLastModifiedTime(path).toInstant() }.getOrDefault(Instant.EPOCH)
        ResumeCandidate(path, manager, modified)
    }.sortedByDescending { it.modified }

    if (candidates.isEmpty()) {
        feed.appendCommandResult("No saved sessions found for this project")
        return
    }

    val options = candidates.map { candidate ->
        val name = candidate.session.getEntries().filterIsInstance<SessionInfoEntry>().lastOrNull()?.name
        val title = name ?: firstUserText(candidate.session) ?: "(no messages)"
        SelectItem(
            label = title.take(56),
            value = candidate.path,
            rightLabel = formatRelativeAge(candidate.modified),
        )
    }
    overlays.push(
        title = "Resume Session",
        items = options,
        selectedIndex = 0,
        onSubmit = { path -> session.load(path) },
    )
}

internal fun formatRelativeAge(modified: Instant): String {
    val elapsed = Duration.between(modified, Instant.now())
    val minutes = elapsed.toMinutes()
    val hours = elapsed.toHours()
    val days = elapsed.toDays()
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 30 -> "${days / 7}w"
        days < 365 -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
