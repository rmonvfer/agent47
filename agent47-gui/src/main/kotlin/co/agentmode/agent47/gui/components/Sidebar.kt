package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.gui.ProjectGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.ui.component.Text
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
public fun Sidebar(
    onNewSession: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    loadSessionGroups: () -> List<ProjectGroup>,
    onLoadSession: (Path) -> Unit,
    currentCwd: String,
    modifier: Modifier = Modifier,
) {
    var groups by remember { mutableStateOf(emptyList<ProjectGroup>()) }
    val expandedProjects = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { loadSessionGroups() }
    }

    // Auto-expand the current project
    LaunchedEffect(groups, currentCwd) {
        if (expandedProjects.isEmpty()) {
            groups.forEach { group ->
                expandedProjects[group.cwd] = group.cwd == currentCwd
            }
        }
    }

    Column(
        modifier = modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(Color(0xFF18181B)),
    ) {
        // Top actions
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ActionButton(label = "New", onClick = onNewSession)
            ActionButton(label = "Skills", onClick = onOpenSkills)
        }

        // Section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Sessions",
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            )
        }

        // Session list
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            groups.forEach { group ->
                val isExpanded = expandedProjects[group.cwd] ?: false

                item(key = "project-${group.cwd}") {
                    ProjectHeader(
                        name = group.projectName,
                        sessionCount = group.sessions.size,
                        isExpanded = isExpanded,
                        isCurrent = group.cwd == currentCwd,
                        onClick = { expandedProjects[group.cwd] = !isExpanded },
                    )
                }

                if (isExpanded) {
                    items(group.sessions, key = { "session-${it.id}" }) { session ->
                        SessionRow(
                            timestamp = session.timestamp,
                            onClick = { onLoadSession(session.path) },
                        )
                    }
                }
            }
        }

        // Bottom settings
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            ActionButton(label = "Settings", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = Color(0xFFD4D4D8),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ProjectHeader(
    name: String,
    sessionCount: Int,
    isExpanded: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isExpanded) "\u25BE" else "\u25B8",
            color = Color(0xFF71717A),
            fontSize = 10.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            color = if (isCurrent) Color(0xFF60A5FA) else Color(0xFFA1A1AA),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sessionCount.toString(),
            color = Color(0xFF52525B),
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SessionRow(
    timestamp: String,
    onClick: () -> Unit,
) {
    val displayTime = formatSessionTime(timestamp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 30.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = displayTime,
            color = Color(0xFF71717A),
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("MMM d, HH:mm")
    .withZone(ZoneId.systemDefault())

private fun formatSessionTime(timestamp: String): String {
    return runCatching {
        val instant = Instant.parse(timestamp)
        timeFormatter.format(instant)
    }.getOrElse { timestamp.take(16) }
}
