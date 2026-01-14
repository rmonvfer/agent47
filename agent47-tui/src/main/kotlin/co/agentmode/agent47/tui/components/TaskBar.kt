package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.ui.core.state.TaskBarState
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Renders a pinned task bar showing the current todo items.
 *
 * When [isStreaming] is true and [activityLabel] is provided, the header line
 * shows a spinner with the activity label. Otherwise it shows a static "Tasks"
 * header. Each item is rendered with a status icon and content.
 *
 * @param state        The reactive task bar state
 * @param width        Terminal width for truncation
 * @param isStreaming   Whether the agent is currently streaming
 * @param spinnerFrame Current spinner animation frame
 * @param activityLabel Label for the current activity (e.g., "Running bash")
 */
@Composable
public fun TaskBar(
    state: TaskBarState,
    width: Int,
    isStreaming: Boolean = false,
    spinnerFrame: Int = 0,
    activityLabel: String = "",
) {
    if (!state.visible) return

    val theme = LocalThemeConfig.current
    val lines = renderTaskBarLines(state.items, width, isStreaming, spinnerFrame, activityLabel, theme)

    Column(modifier = Modifier.height(lines.size)) {
        lines.forEach { line -> Text(line) }
    }
}

private fun renderTaskBarLines(
    items: List<TodoItem>,
    width: Int,
    isStreaming: Boolean,
    spinnerFrame: Int,
    activityLabel: String,
    theme: ThemeConfig,
): List<com.jakewharton.mosaic.text.AnnotatedString> = buildList {
    // Header line: spinner + activity when streaming, "Tasks" otherwise
    add(buildAnnotatedString {
        if (isStreaming) {
            val frames = listOf("\u28CB", "\u28D9", "\u28F9", "\u28F8", "\u28FC", "\u28F4", "\u28E6", "\u28E7", "\u28C7", "\u28CF")
            val frame = frames[spinnerFrame.mod(frames.size)]
            withStyle(SpanStyle(color = theme.colors.accent)) { append(frame) }
            append(" ")
            val label = activityLabel.ifBlank { "Working" }
            withStyle(SpanStyle(color = theme.colors.accentBright)) { append(label) }
            withStyle(SpanStyle(color = theme.colors.muted)) { append("...") }
        } else {
            withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) {
                append("Tasks")
            }
        }
    })

    // Task items
    val maxContent = (width - 7).coerceAtLeast(10)
    items.forEach { item ->
        val (icon, statusColor) = when (item.status) {
            "completed" -> "\u2714" to theme.todoCompleted
            "in_progress" -> "\u25FC" to theme.todoInProgress
            "pending" -> "\u25FB" to theme.todoPending
            "cancelled" -> "\u2717" to theme.todoCancelled
            else -> "\u25FB" to theme.todoPending
        }
        val contentStyle = if (item.status == "completed" || item.status == "cancelled") {
            SpanStyle(color = theme.colors.muted)
        } else {
            SpanStyle()
        }
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.muted)) { append("  \u23BF  ") }
            withStyle(SpanStyle(color = statusColor)) { append(icon) }
            append(" ")
            withStyle(contentStyle) {
                append(item.content.take(maxContent))
                if (item.content.length > maxContent) append("\u2026")
            }
        })
    }
}
