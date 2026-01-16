package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.ui.core.state.TaskBarState
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun GuiTaskBar(
    state: TaskBarState,
    isStreaming: Boolean = false,
    activityLabel: String = "",
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x08FFFFFF))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isStreaming && activityLabel.isNotBlank()) {
                val frames = listOf("\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F")
                Text(
                    text = frames[((System.currentTimeMillis() / 80) % frames.size).toInt()],
                    color = Color(0xFF64B5F6),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = activityLabel,
                    color = Color(0xFF64B5F6),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    text = "Tasks",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Items
        state.items.forEach { item ->
            val statusIcon = when (item.status) {
                "completed" -> "\u2714"
                "in_progress" -> "\u25CB"
                else -> "\u2022"
            }
            val statusColor = when (item.status) {
                "completed" -> Color(0xFF66BB6A)
                "in_progress" -> Color(0xFF64B5F6)
                else -> Color(0xFF9E9E9E)
            }

            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = statusIcon, color = statusColor, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                if (item.priority.isNotBlank()) {
                    Text(
                        text = "[${item.priority}]",
                        color = Color(0xFFFFA726),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = item.content,
                    color = Color(0xFFBBBBBB),
                    fontSize = 11.sp,
                )
            }
        }
    }
}
