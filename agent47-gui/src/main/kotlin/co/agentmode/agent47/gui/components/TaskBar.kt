package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.ui.core.state.TaskBarState
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun GuiTaskBar(
    state: TaskBarState,
    isStreaming: Boolean = false,
    activityLabel: String = "",
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    Divider(Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.taskBarBackground)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isStreaming && activityLabel.isNotBlank()) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = activityLabel,
                    color = AppColors.info,
                    style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.Bold),
                )
            } else {
                Text(
                    text = "Tasks",
                    color = AppColors.textMuted,
                    style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        // Items
        state.items.forEach { item ->
            val statusColor = when (item.status) {
                "completed" -> AppColors.success
                "in_progress" -> AppColors.info
                else -> AppColors.textMuted
            }

            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (item.status) {
                    "completed" -> Icon(
                        imageVector = Tabler.Outline.Check,
                        contentDescription = "Completed",
                        modifier = Modifier.size(12.dp),
                        tint = statusColor,
                    )
                    "in_progress" -> CircularProgressIndicator(modifier = Modifier.size(12.dp))
                    else -> Icon(
                        imageVector = Tabler.Outline.Circle,
                        contentDescription = "Pending",
                        modifier = Modifier.size(12.dp),
                        tint = statusColor,
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (item.priority.isNotBlank()) {
                    Text(
                        text = "[${item.priority}]",
                        color = AppColors.warning,
                        style = JewelTheme.typography.small,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = item.content,
                    color = AppColors.textLight,
                    style = JewelTheme.typography.small,
                )
            }
        }
    }
}
