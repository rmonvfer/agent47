package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.ui.core.state.StatusBarState
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun GuiStatusBar(
    state: StatusBarState,
    modifier: Modifier = Modifier,
) {
    val modelId = state.modelId
    val contextTokens = state.contextTokens
    val contextWindow = state.contextWindow
    val inputTokens = state.inputTokens
    val outputTokens = state.outputTokens
    val totalTokens = state.totalTokens
    val cost = state.cost
    val branch = state.branch

    Row(
        modifier = modifier
            .background(Color(0xFF1E1E1E))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left side: busy indicator + model + thinking
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.busy) {
                val frames = listOf("\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F")
                Text(
                    text = frames[state.spinnerFrame.mod(frames.size)],
                    color = Color(0xFF64B5F6),
                    fontSize = 12.sp,
                )
            }

            if (modelId != null) {
                Text(
                    text = modelId,
                    color = JewelTheme.globalColors.text.info,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (state.thinking) {
                Text(
                    text = "thinking",
                    color = Color(0xFFFFA726),
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        // Right side: context %, tokens, cost, git branch, cwd
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Context usage percentage
            if (contextTokens != null && contextWindow != null && contextWindow > 0) {
                val pct = (contextTokens.toDouble() / contextWindow * 100).toInt()
                val contextColor = when {
                    pct >= 80 -> Color(0xFFEF5350)
                    pct >= 50 -> Color(0xFFFFA726)
                    else -> Color(0xFF66BB6A)
                }
                Text(
                    text = "${pct}%",
                    color = contextColor,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Token counts
            if (inputTokens != null || outputTokens != null) {
                val tokenText = buildString {
                    if (inputTokens != null) append("\u2193${formatTokens(inputTokens)}")
                    if (inputTokens != null && outputTokens != null) append(" ")
                    if (outputTokens != null) append("\u2191${formatTokens(outputTokens)}")
                    if (totalTokens != null) append(" (${formatTokens(totalTokens)})")
                }
                Text(
                    text = tokenText,
                    color = JewelTheme.globalColors.text.info,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Cost
            if (cost != null && cost > 0.0) {
                Text(
                    text = "\$${String.format("%.4f", cost)}",
                    color = JewelTheme.globalColors.text.info,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Git branch
            if (branch != null) {
                Text(
                    text = "\uD83C\uDF3F $branch",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                )
            }

            // CWD
            Text(
                text = state.cwdName,
                color = Color(0xFF757575),
                fontSize = 12.sp,
            )
        }
    }
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}
