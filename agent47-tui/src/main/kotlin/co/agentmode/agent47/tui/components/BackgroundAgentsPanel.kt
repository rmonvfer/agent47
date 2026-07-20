package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.coding.core.agents.RunningAgent
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.util.formatDuration
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * A persistent panel listing the sub-agents currently running in the background (launched via
 * the `task` tool). One columnar line per agent — id · live activity · elapsed — matching the
 * transcript's task-tool rendering. Renders nothing when no agents are running.
 */
@Composable
public fun BackgroundAgentsPanel(
    agents: List<RunningAgent>,
    width: Int,
) {
    if (agents.isEmpty()) return
    val theme = LocalThemeConfig.current
    val nameWidth = agents.maxOf { it.id.length }.coerceIn(6, 16)

    Column(modifier = Modifier.height(agents.size + 1)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) {
                    append("Background agents (${agents.size})")
                }
            },
        )
        agents.forEach { agent ->
            val progress = agent.progress
            val activity = progress?.currentTool?.let { "Running $it…" } ?: "Thinking…"
            val elapsed = if (progress != null && progress.durationMs > 0) formatDuration(progress.durationMs) else ""
            Text(agentRow(agent.id, nameWidth, activity, elapsed, width, theme))
        }
    }
}

private fun agentRow(
    id: String,
    nameWidth: Int,
    activity: String,
    elapsed: String,
    width: Int,
    theme: ThemeConfig,
): AnnotatedString {
    val idPadded = id.take(nameWidth).padEnd(nameWidth)
    val budget = (width - 2 - nameWidth - 2 - elapsed.length - 1).coerceAtLeast(0)
    val shown = if (activity.length > budget) activity.take((budget - 1).coerceAtLeast(0)) + "…" else activity
    val gap = (width - 2 - nameWidth - 2 - shown.length - elapsed.length).coerceAtLeast(1)
    return buildAnnotatedString {
        append("  ")
        withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) { append(idPadded) }
        append("  ")
        withStyle(SpanStyle(color = theme.colors.muted)) { append(shown) }
        if (elapsed.isNotEmpty()) {
            append(" ".repeat(gap))
            withStyle(SpanStyle(color = theme.colors.dim)) { append(elapsed) }
        }
    }
}
