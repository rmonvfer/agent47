@file:Suppress("MatchingDeclarationName")

package co.agentmode.agent47.tui.layout

import kotlin.math.max
import kotlin.math.min

/**
 * The impure measurements the layout depends on, gathered once per frame so [computeTuiLayout] is a
 * pure function of the terminal size and these inputs.
 */
internal data class LayoutInputs(
    val visualLineCount: Int,
    val popupItemCount: Int,
    val taskBarVisible: Boolean,
    val taskBarLineCount: Int,
    val isStreaming: Boolean,
    val chatPinnedToBottom: Boolean,
    val hasBackgroundAgents: Boolean,
    val runningAgentCount: Int,
    val queuedAgentCount: Int,
)

/** Resolved row/column budget for the interactive screen. */
internal data class TuiLayout(
    val horizontalPadding: Int,
    val contentWidth: Int,
    val editorContentWidth: Int,
    val baseInputHeight: Int,
    val popupHeight: Int,
    val backgroundPanelHeight: Int,
    val editorTopMarginHeight: Int,
    val historyHeight: Int,
)

internal fun computeTuiLayout(width: Int, height: Int, inputs: LayoutInputs): TuiLayout {
    val statusHeight = 2 // ohm-style two-line footer
    val horizontalPadding = tuiHorizontalPadding(width)
    val contentWidth = (width - horizontalPadding * 2).coerceAtLeast(1)
    val editorPaddingWidth = if (contentWidth >= MIN_EDITOR_PADDED_WIDTH) 2 else 0
    val editorContentWidth = (contentWidth - editorPaddingWidth).coerceAtLeast(1)
    val baseInputHeight = min(8, max(1, inputs.visualLineCount))
    val popupRowCount = min(8, inputs.popupItemCount)
    val popupHeight = if (popupRowCount > 0) popupRowCount + (if (inputs.popupItemCount > 8) 1 else 0) + 1 else 0
    val taskBarHeight = if (inputs.taskBarVisible) inputs.taskBarLineCount else 0
    val activityHeight = if (inputs.isStreaming && !inputs.taskBarVisible) 2 else 0
    val editorTopMarginHeight =
        if (inputs.taskBarVisible || inputs.isStreaming || !inputs.chatPinnedToBottom) 0 else 1
    val borderHeight = 2
    val backgroundPanelHeight = if (!inputs.hasBackgroundAgents) {
        0
    } else {
        // Leading blank line + header + running rows + optional queued line (matches the panel).
        2 + inputs.runningAgentCount + if (inputs.queuedAgentCount > 0) 1 else 0
    }
    val historyHeight = max(
        1,
        height - statusHeight - borderHeight - popupHeight - baseInputHeight -
            activityHeight - taskBarHeight - editorTopMarginHeight - backgroundPanelHeight,
    )
    return TuiLayout(
        horizontalPadding = horizontalPadding,
        contentWidth = contentWidth,
        editorContentWidth = editorContentWidth,
        baseInputHeight = baseInputHeight,
        popupHeight = popupHeight,
        backgroundPanelHeight = backgroundPanelHeight,
        editorTopMarginHeight = editorTopMarginHeight,
        historyHeight = historyHeight,
    )
}

internal fun tuiEditorContentWidth(width: Int): Int {
    val horizontalPadding = tuiHorizontalPadding(width)
    val contentWidth = (width - horizontalPadding * 2).coerceAtLeast(1)
    val editorPaddingWidth = if (contentWidth >= MIN_EDITOR_PADDED_WIDTH) 2 else 0
    return (contentWidth - editorPaddingWidth).coerceAtLeast(1)
}

private fun tuiHorizontalPadding(width: Int): Int =
    if (width >= MIN_PADDED_WIDTH) HORIZONTAL_PADDING else 0

private const val HORIZONTAL_PADDING = 1
private const val MIN_PADDED_WIDTH = HORIZONTAL_PADDING * 2 + 1
private const val MIN_EDITOR_PADDED_WIDTH = 3
