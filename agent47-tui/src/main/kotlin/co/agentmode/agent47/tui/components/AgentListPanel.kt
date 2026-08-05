package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.state.AgentListRow
import co.agentmode.agent47.tui.state.AgentRowState
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.util.formatDuration
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

private val SPINNER_FRAMES = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

/**
 * The runtime agent list rendered below the editor and status bar: a `main` row for the
 * orchestrator followed by every visible background agent, Claude-Code style — a state dot, the
 * agent's name, its truncated current-activity snippet, and a right-aligned elapsed time and
 * token count where the registry has them. The highlighted row while [selectionMode] is active
 * renders like an overlay selection (filled background, bold). Renders nothing when [rows] is
 * empty; [rows] (and therefore this panel's visibility) is the same list the layout math sizes
 * against, so an empty list here means zero reserved height too.
 */
@Composable
internal fun AgentListPanel(
    rows: List<AgentListRow>,
    width: Int,
    spinnerFrame: Int,
    selectionMode: Boolean,
    selectedIndex: Int,
) {
    if (rows.isEmpty()) return
    val theme = LocalThemeConfig.current
    val spinner = SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.size]
    val nameWidth = rows.maxOf { it.label.length }.coerceIn(4, 16)

    Column {
        rows.forEachIndexed { index, row ->
            val selected = selectionMode && index == selectedIndex
            Text(agentRowLine(row, nameWidth, spinner, width, selected, theme))
        }
    }
}

private fun agentRowLine(
    row: AgentListRow,
    nameWidth: Int,
    spinner: String,
    width: Int,
    selected: Boolean,
    theme: ThemeConfig,
): AnnotatedString {
    val bg = if (selected) theme.overlaySelectedBg else Color.Unspecified
    val dotColor = when (row.state) {
        AgentRowState.RUNNING -> theme.colors.accent
        AgentRowState.DONE -> theme.colors.success
        AgentRowState.IDLE -> theme.colors.muted
    }
    val dot = if (row.state == AgentRowState.RUNNING) spinner else "●"
    val namePadded = row.label.take(nameWidth).padEnd(nameWidth)
    val right = buildList {
        row.elapsedMs?.let { add(formatDuration(it)) }
        row.tokens?.takeIf { it > 0 }?.let { add(formatTokens(it)) }
    }.joinToString("  ")

    // "  " + dot + " " + name + "  " before the activity snippet, mirroring the fixed prefix used
    // for the old running-agent row so truncation math stays easy to follow.
    val prefix = "  $dot $namePadded  "
    val rightReserve = if (right.isEmpty()) 0 else right.length + 2
    val activityBudget = (width - prefix.length - rightReserve).coerceAtLeast(0)
    val shownActivity = if (row.activity.length > activityBudget) {
        row.activity.take((activityBudget - 1).coerceAtLeast(0)) + "…"
    } else {
        row.activity
    }
    val gap = (width - prefix.length - shownActivity.length - right.length).coerceAtLeast(0)

    return buildAnnotatedString {
        withStyle(rowStyle(theme.markdownText, bg, selected)) { append("  ") }
        withStyle(rowStyle(dotColor, bg, selected)) { append(dot) }
        withStyle(rowStyle(theme.markdownText, bg, selected)) { append(" ") }
        withStyle(rowStyle(theme.markdownText, bg, selected)) { append(namePadded) }
        withStyle(rowStyle(theme.markdownText, bg, selected)) { append("  ") }
        withStyle(rowStyle(theme.colors.muted, bg, selected)) { append(shownActivity) }
        if (gap > 0) withStyle(rowStyle(theme.markdownText, bg, selected)) { append(" ".repeat(gap)) }
        if (right.isNotEmpty()) withStyle(rowStyle(theme.colors.dim, bg, selected)) { append(right) }
    }
}

/** A [SpanStyle] with bold applied only when [bold], matching the overlay selection convention. */
private fun rowStyle(color: Color, background: Color, bold: Boolean): SpanStyle =
    if (bold) SpanStyle(color = color, background = background, textStyle = TextStyle.Bold) else SpanStyle(color = color, background = background)

private fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> "%.1fM tok".format(tokens / 1_000_000.0)
    tokens >= 1_000 -> "%.1fk tok".format(tokens / 1_000.0)
    else -> "$tokens tok"
}
