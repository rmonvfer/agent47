package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.coding.core.tools.TodoItem
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.ui.core.state.TASK_BAR_VISIBLE_ITEM_LIMIT
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
 * header. Up to [TASK_BAR_VISIBLE_ITEM_LIMIT] items are rendered with a status icon and content,
 * followed by an overflow count when additional items are hidden.
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

internal fun renderTaskBarLines(
    items: List<TodoItem>,
    width: Int,
    isStreaming: Boolean,
    spinnerFrame: Int,
    activityLabel: String,
    theme: ThemeConfig,
): List<com.jakewharton.mosaic.text.AnnotatedString> = buildList {
    add(annotated(""))

    // Header line: spinner + activity when streaming, "Tasks" otherwise
    add(buildAnnotatedString {
        if (isStreaming) {
            val frames = listOf("\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F")
            val frame = frames[spinnerFrame.mod(frames.size)]
            withStyle(SpanStyle(color = theme.colors.accent)) { append(frame) }
            append(" ")
            val label = activityLabel.ifBlank { "Working" }
            withStyle(SpanStyle(color = theme.colors.muted)) { append(label) }
            withStyle(SpanStyle(color = theme.colors.muted)) { append("...") }
        } else {
            withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) {
                append("Tasks")
            }
        }
    })

    // Task items \u2014 textual checkboxes shared with the transcript's todo renderer
    val maxContent = (width - 7).coerceAtLeast(10)
    val visibleItems = items
        .sortedBy(::taskBarItemPriority)
        .take(TASK_BAR_VISIBLE_ITEM_LIMIT)
    visibleItems.forEach { item ->
        val (marker, markerColor) = when (item.status) {
            "completed" -> "[x]" to theme.todoCompleted
            "in_progress" -> "[~]" to theme.todoInProgress
            "cancelled" -> "[-]" to theme.todoCancelled
            else -> "[ ]" to theme.todoPending
        }
        val done = item.status == "completed" || item.status == "cancelled"
        val contentStyle = if (done) {
            SpanStyle(color = theme.colors.muted, textStyle = TextStyle.Strikethrough)
        } else {
            SpanStyle(color = theme.markdownText)
        }
        add(buildAnnotatedString {
            append("  ")
            withStyle(SpanStyle(color = markerColor)) { append(marker) }
            append(" ")
            withStyle(contentStyle) {
                append(item.content.take(maxContent))
                if (item.content.length > maxContent) append("\u2026")
            }
        })
    }
    val hiddenItemCount = items.size - visibleItems.size
    if (hiddenItemCount > 0) {
        add(annotated("  … +$hiddenItemCount more", SpanStyle(color = theme.colors.dim)))
    }
}

private fun taskBarItemPriority(item: TodoItem): Int = when (item.status) {
    "in_progress" -> 0
    "pending" -> 1
    else -> 2
}
