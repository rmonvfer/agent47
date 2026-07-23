package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.tui.session.SESSION_DATE_FORMAT
import co.agentmode.agent47.tui.session.firstUserText
import co.agentmode.agent47.ui.core.state.SelectItem
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId

@Suppress("ReturnCount")
internal fun OverlayNavigator.openSessionOverlay() {
    if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
        feed.appendCommandResult("Session picker is unavailable: no session directory configured")
        return
    }
    val sessions = runCatching {
        Files.list(sessionsDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".jsonl") }
                // Exclude sub-agent sessions, they aren't top-level conversations.
                .filter { !it.fileName.toString().startsWith("subagent-") }
                .sorted(Comparator.comparing<Path, String> { it.fileName.toString() }.reversed())
                .limit(50)
                .toList()
        }
    }.getOrElse {
        feed.appendCommandResult("Failed to list sessions: ${it.message ?: it::class.simpleName}")
        return
    }
    val projectCwd = cwd.toAbsolutePath().normalize().toString()
    val options = sessions.mapNotNull { path ->
        val session = runCatching { SessionManager(path) }.getOrNull() ?: return@mapNotNull null
        val header = session.getHeader()
        // Only show sessions that were started in this project.
        val sessionCwd = runCatching { Path.of(header.cwd).toAbsolutePath().normalize().toString() }.getOrNull()
        if (sessionCwd != projectCwd) return@mapNotNull null
        val date = runCatching {
            Instant.parse(header.timestamp).atZone(ZoneId.systemDefault()).format(SESSION_DATE_FORMAT)
        }.getOrNull()
        val title = firstUserText(session)?.take(56) ?: "(no messages)"
        val count = session.getEntries().count { it is SessionMessageEntry }
        SelectItem(
            label = if (date != null) "$date  $title" else title,
            value = path,
            rightLabel = "$count msg",
        )
    }
    if (options.isEmpty()) {
        feed.appendCommandResult("No saved sessions found for this project")
        return
    }
    overlays.push(
        title = "Sessions",
        items = options,
        selectedIndex = 0,
        onSubmit = { path ->
            session.load(path)
        },
    )
}
