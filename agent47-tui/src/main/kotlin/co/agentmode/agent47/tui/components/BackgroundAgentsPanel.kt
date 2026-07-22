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

private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * A persistent panel listing background sub-agents (launched via the `task` tool). A leading blank
 * line gives it breathing room, then a header and one row per running agent showing an animated
 * spinner, the agent id, its live activity (current tool or the text it is streaming), stats, and a
 * live-ticking elapsed time; queued agents collapse to a single summary line. Renders nothing when
 * idle or when [mode] is "off".
 *
 * Elapsed and stats are recomputed on each recomposition, which the caller drives via [spinnerFrame]
 * (bumped ~10×/s while agents run), so the panel updates in near real time without its own ticker.
 */
@Composable
public fun BackgroundAgentsPanel(
    agents: List<RunningAgent>,
    width: Int,
    spinnerFrame: Int = 0,
    mode: String = "background",
) {
    if (mode == "off" || agents.isEmpty()) return
    val theme = LocalThemeConfig.current
    val now = System.currentTimeMillis()

    val running = agents.filter { it.status == RunningAgent.Status.RUNNING }
    val queued = agents.filter { it.status == RunningAgent.Status.QUEUED }
    val nameWidth = agents.maxOf { it.id.length }.coerceIn(6, 16)
    val spinner = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.size]

    // +1 leading blank line (top margin) + header + running rows + optional queued line.
    val lineCount = 1 + 1 + running.size + if (queued.isNotEmpty()) 1 else 0

    Column(modifier = Modifier.height(lineCount)) {
        Text("")
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) {
                    append("● Agents (${agents.size})")
                }
            },
        )
        running.forEach { agent ->
            Text(runningRow(agent, nameWidth, spinner, width, now, theme))
        }
        if (queued.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    append("  ")
                    withStyle(SpanStyle(color = theme.colors.dim)) { append("◦ ${queued.size} queued") }
                },
            )
        }
    }
}

private fun runningRow(
    agent: RunningAgent,
    nameWidth: Int,
    spinner: String,
    width: Int,
    now: Long,
    theme: ThemeConfig,
): AnnotatedString {
    val progress = agent.progress
    val activity = activityOf(progress?.currentTool, progress?.streamingText)
    val elapsed = if (agent.startedAt > 0) formatDuration(now - agent.startedAt) else ""
    val stats = progress?.let { p ->
        val parts = buildList {
            if (p.turnCount > 0) add("↻${p.turnCount}")
            if (p.toolCount > 0) add("${p.toolCount} tools")
            if (p.tokens > 0) add(formatTokens(p.tokens))
        }
        if (parts.isEmpty()) "" else parts.joinToString(" · ")
    } ?: ""

    val idPadded = agent.id.take(nameWidth).padEnd(nameWidth)
    val right = listOf(stats, elapsed).filter { it.isNotEmpty() }.joinToString("  ")
    // "  " + spinner + " " + id + "  " + activity + gap + right
    val fixed = 2 + 2 + nameWidth + 2 + right.length + 1
    val budget = (width - fixed).coerceAtLeast(0)
    val shown = if (activity.length > budget) activity.take((budget - 1).coerceAtLeast(0)) + "…" else activity
    val gap = (width - fixed - shown.length + 1).coerceAtLeast(1)

    return buildAnnotatedString {
        append("  ")
        withStyle(SpanStyle(color = theme.colors.accent)) { append(spinner) }
        append(" ")
        withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) { append(idPadded) }
        append("  ")
        withStyle(SpanStyle(color = theme.colors.muted)) { append(shown) }
        if (right.isNotEmpty()) {
            append(" ".repeat(gap))
            withStyle(SpanStyle(color = theme.colors.dim)) { append(right) }
        }
    }
}

/** Prefer the running tool, else the text the agent is currently streaming, else a starting hint. */
private fun activityOf(currentTool: String?, streamingText: String?): String = when {
    currentTool != null -> "running $currentTool…"
    !streamingText.isNullOrBlank() -> streamingText.replace('\n', ' ').trim()
    else -> "starting…"
}

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM tok".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk tok".format(tokens / 1_000.0)
    else -> "$tokens tok"
}
