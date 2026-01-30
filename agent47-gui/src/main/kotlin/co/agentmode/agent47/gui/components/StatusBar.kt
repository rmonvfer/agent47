package co.agentmode.agent47.gui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.ui.core.state.StatusBarState
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.HorizontalProgressBar
import org.jetbrains.jewel.ui.component.Icon
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

    val statusBarBg = AppColors.statusBarBackground

    Row(
        modifier = modifier
            .background(statusBarBg)
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
                CircularProgressIndicator(modifier = Modifier.size(12.dp))
            }

            if (modelId != null) {
                Text(
                    text = modelId,
                    color = AppColors.textSecondary,
                    style = JewelTheme.consoleTextStyle,
                )
            }

            if (state.thinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Tabler.Outline.Bulb,
                        contentDescription = "Thinking",
                        modifier = Modifier.size(12.dp),
                        tint = AppColors.warning,
                    )
                    Text(
                        text = "thinking",
                        color = AppColors.warning,
                        style = JewelTheme.typography.medium,
                    )
                }
            }
        }

        Spacer(Modifier.width(16.dp))

        // Right side: context %, tokens, cost, git branch, cwd
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Context usage percentage with progress bar
            if (contextTokens != null && contextWindow != null && contextWindow > 0) {
                val pct = (contextTokens.toDouble() / contextWindow * 100).toInt()
                val fraction = (contextTokens.toFloat() / contextWindow).coerceIn(0f, 1f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HorizontalProgressBar(
                        progress = fraction,
                        modifier = Modifier.width(60.dp).height(4.dp),
                    )
                    val contextColor = when {
                        pct >= 80 -> AppColors.error
                        pct >= 50 -> AppColors.warning
                        else -> AppColors.success
                    }
                    Text(
                        text = "${pct}%",
                        color = contextColor,
                        style = JewelTheme.consoleTextStyle,
                    )
                }
            }

            // Token counts with arrow icons
            if (inputTokens != null || outputTokens != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (inputTokens != null) {
                        Icon(
                            imageVector = Tabler.Outline.ArrowDown,
                            contentDescription = "Input",
                            modifier = Modifier.size(10.dp),
                            tint = AppColors.textSecondary,
                        )
                        Text(
                            text = formatTokens(inputTokens),
                            color = AppColors.textSecondary,
                            style = JewelTheme.consoleTextStyle,
                        )
                    }
                    if (outputTokens != null) {
                        Icon(
                            imageVector = Tabler.Outline.ArrowUp,
                            contentDescription = "Output",
                            modifier = Modifier.size(10.dp),
                            tint = AppColors.textSecondary,
                        )
                        Text(
                            text = formatTokens(outputTokens),
                            color = AppColors.textSecondary,
                            style = JewelTheme.consoleTextStyle,
                        )
                    }
                    if (totalTokens != null) {
                        Text(
                            text = "(${formatTokens(totalTokens)})",
                            color = AppColors.textSecondary,
                            style = JewelTheme.consoleTextStyle,
                        )
                    }
                }
            }

            // Cost
            if (cost != null && cost > 0.0) {
                Text(
                    text = "\$${String.format("%.4f", cost)}",
                    color = AppColors.textSecondary,
                    style = JewelTheme.consoleTextStyle,
                )
            }

            // Git branch
            if (branch != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Tabler.Outline.GitBranch,
                        contentDescription = "Branch",
                        modifier = Modifier.size(12.dp),
                        tint = AppColors.textMuted,
                    )
                    Text(
                        text = branch,
                        color = AppColors.textMuted,
                        style = JewelTheme.typography.medium,
                    )
                }
            }

            // CWD
            Text(
                text = state.cwdName,
                color = AppColors.textDim,
                style = JewelTheme.typography.medium,
            )
        }
    }
}

private fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
    else -> n.toString()
}
