package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.editor.CompletionItem
import co.agentmode.agent47.tui.editor.EditorAutocompleteRenderModel
import co.agentmode.agent47.tui.editor.EditorRenderResult
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Renders the multi-line editor area from an [EditorRenderResult] produced
 * by the [Editor][co.agentmode.agent47.tui.editor.Editor] model.
 *
 * Each visual line is emitted as a [Text] node. The cursor is rendered by
 * inverting the character at the cursor position.
 */
@Composable
public fun EditorView(
    result: EditorRenderResult,
    width: Int,
    height: Int,
    bashMode: Boolean = false,
) {
    val theme = LocalThemeConfig.current

    val prompt = if (bashMode) "! " else "❯ "
    val promptColor = if (bashMode) theme.colors.error else theme.colors.accent

    val textStyle = SpanStyle(
        color = theme.codeBlockFg,
    )

    Column(modifier = Modifier.width(width).height(height)) {
        result.lines.forEachIndexed { rowIndex, lineText ->
            val isFirstLine = rowIndex == 0
            val prefix = if (isFirstLine) prompt else " ".repeat(prompt.length)
            val contentWidth = width - prefix.length

            // In bash mode the prompt already shows "! ", so strip the leading "!"
            // from the first line to avoid displaying it twice.
            val strippedText = if (bashMode && isFirstLine && lineText.startsWith("!")) {
                lineText.removePrefix("!")
            } else {
                lineText
            }
            val displayText = strippedText.take(contentWidth).padEnd(contentWidth)

            val hasCursor = rowIndex == result.cursorRow
            val cursorCol = if (bashMode && isFirstLine && lineText.startsWith("!")) {
                (result.cursorColumn - 1).coerceAtLeast(0)
            } else {
                result.cursorColumn
            }

            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = promptColor)) {
                    append(prefix)
                }

                if (hasCursor) {
                    val adjustedCursorCol = cursorCol.coerceIn(0, displayText.length)
                    val before = displayText.substring(0, adjustedCursorCol)
                    val cursorChar = if (adjustedCursorCol < displayText.length) {
                        displayText[adjustedCursorCol].toString()
                    } else {
                        " "
                    }
                    val after = if (adjustedCursorCol + 1 < displayText.length) {
                        displayText.substring(adjustedCursorCol + 1)
                    } else {
                        ""
                    }

                    withStyle(textStyle) { append(before) }
                    withStyle(SpanStyle(textStyle = TextStyle.Invert)) {
                        append(cursorChar)
                    }
                    withStyle(textStyle) { append(after) }
                } else {
                    withStyle(textStyle) { append(displayText) }
                }
            })
        }
    }
}

/**
 * Renders the autocomplete popup as a list of completion items.
 */
@Composable
internal fun AutocompletePopup(
    model: EditorAutocompleteRenderModel,
    maxWidth: Int,
    theme: co.agentmode.agent47.tui.theme.ThemeConfig,
) {
    val items = model.items
    val maxVisible = 8.coerceAtMost(items.size)

    // Compute scroll window so the selected item is always visible
    val scrollOffset = run {
        val selected = model.selectedIndex.coerceIn(0, items.lastIndex)
        // Keep selected item within the visible window
        val minOffset = (selected - maxVisible + 1).coerceAtLeast(0)
        val maxOffset = selected.coerceAtLeast(0)
        // Prefer keeping the window as stable as possible (start from minOffset)
        minOffset.coerceAtMost((items.size - maxVisible).coerceAtLeast(0))
    }
    val visibleItems = items.subList(scrollOffset, (scrollOffset + maxVisible).coerceAtMost(items.size))

    val labelColumnWidth = visibleItems.maxOf { it.label.length }
    val availableWidth = maxWidth.coerceAtLeast(10)
    val gap = 4
    val detailBudget = (availableWidth - labelColumnWidth - gap - 2).coerceAtLeast(0)
    val itemWidth = availableWidth

    Column {
        visibleItems.forEachIndexed { index, item ->
            val isSelected = (scrollOffset + index) == model.selectedIndex
            Text(renderCompletionItem(item, isSelected, itemWidth, labelColumnWidth, detailBudget, theme))
        }
        val hiddenBelow = items.size - (scrollOffset + maxVisible)
        if (hiddenBelow > 0) {
            Text(annotated(
                " +$hiddenBelow more",
                SpanStyle(color = theme.colors.muted),
            ))
        }
    }
}

private fun renderCompletionItem(
    item: CompletionItem,
    selected: Boolean,
    width: Int,
    labelColumnWidth: Int,
    detailBudget: Int,
    theme: co.agentmode.agent47.tui.theme.ThemeConfig,
) = buildAnnotatedString {
    val label = item.label
    val detail = item.detail

    val labelStyle = if (selected) {
        SpanStyle(color = theme.markdownText, background = theme.overlaySelectedBg)
    } else {
        SpanStyle(color = theme.markdownText, background = theme.statusBarBg)
    }
    val detailStyle = if (selected) {
        SpanStyle(color = theme.colors.muted, background = theme.overlaySelectedBg)
    } else {
        SpanStyle(color = theme.colors.muted, background = theme.statusBarBg)
    }
    val bgStyle = if (selected) {
        SpanStyle(background = theme.overlaySelectedBg)
    } else {
        SpanStyle(background = theme.statusBarBg)
    }

    withStyle(bgStyle) {
        append(" ")
        withStyle(labelStyle) { append(label) }
        val labelPadding = (labelColumnWidth - label.length).coerceAtLeast(0)
        append(" ".repeat(labelPadding))

        if (detail != null && detailBudget > 0) {
            append("    ")
            val truncatedDetail = if (detail.length > detailBudget) {
                detail.take(detailBudget - 1) + "\u2026"
            } else {
                detail
            }
            withStyle(detailStyle) { append(truncatedDetail) }
            val detailPadding = (detailBudget - truncatedDetail.length).coerceAtLeast(0)
            append(" ".repeat(detailPadding))
        }
        append(" ")
    }
}
