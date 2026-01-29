package co.agentmode.agent47.gui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.gui.ProjectGroup
import co.agentmode.agent47.gui.SessionInfo
import co.agentmode.agent47.gui.theme.AppColors
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.foundation.lazy.tree.rememberTreeState
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed class SidebarItem {
    data class Project(val group: ProjectGroup, val isCurrent: Boolean) : SidebarItem()
    data class Session(val info: SessionInfo) : SidebarItem()
}

@OptIn(ExperimentalJewelApi::class, ExperimentalFoundationApi::class)
@Composable
public fun Sidebar(
    onNewSession: () -> Unit,
    onOpenSkills: () -> Unit,
    loadSessionGroups: () -> List<ProjectGroup>,
    onLoadSession: (Path) -> Unit,
    currentCwd: String,
    modifier: Modifier = Modifier,
) {
    var groups by remember { mutableStateOf(emptyList<ProjectGroup>()) }
    val treeState = rememberTreeState()

    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { loadSessionGroups() }
    }

    // Auto-expand the current project
    LaunchedEffect(groups, currentCwd) {
        groups.forEach { group ->
            if (group.cwd == currentCwd && group.cwd !in treeState.openNodes) {
                treeState.toggleNode(group.cwd)
            }
        }
    }

    val tree = remember(groups, currentCwd) {
        buildTree<SidebarItem> {
            groups.forEach { group ->
                addNode(SidebarItem.Project(group, group.cwd == currentCwd), id = group.cwd) {
                    group.sessions.forEach { session ->
                        addLeaf(SidebarItem.Session(session), id = session.id)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(AppColors.sidebarBackground),
    ) {
        // Top actions
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Tooltip(tooltip = { Text("Start a new session") }) {
                ActionButton(label = "New", icon = Tabler.Outline.Plus, onClick = onNewSession)
            }
            Tooltip(tooltip = { Text("Browse available skills") }) {
                ActionButton(label = "Skills", icon = Tabler.Outline.Command, onClick = onOpenSkills)
            }
        }

        Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

        // Section header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "Sessions",
                color = AppColors.sectionLabel,
                style = JewelTheme.typography.small.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                ),
            )
        }

        // Session tree
        LazyTree(
            tree = tree,
            treeState = treeState,
            onElementClick = { element ->
                when (val data = element.data) {
                    is SidebarItem.Session -> onLoadSession(data.info.path)
                    is SidebarItem.Project -> treeState.toggleNode(element.id)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { element ->
            when (val data = element.data) {
                is SidebarItem.Project -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = data.group.projectName,
                            color = if (data.isCurrent) AppColors.sidebarAccent else AppColors.sidebarTextMuted,
                            style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = data.group.sessions.size.toString(),
                            color = AppColors.sidebarTextDim,
                            style = JewelTheme.typography.small,
                        )
                    }
                }
                is SidebarItem.Session -> {
                    Text(
                        text = formatSessionTime(data.info.timestamp),
                        color = AppColors.sectionLabel,
                        style = JewelTheme.typography.small,
                        maxLines = 1,
                    )
                }
            }
        }

    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    var isHovered by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .onHover { isHovered = it }
            .background(if (isHovered) AppColors.hoverBackground else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(14.dp),
            tint = AppColors.sidebarText,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = AppColors.sidebarText,
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
