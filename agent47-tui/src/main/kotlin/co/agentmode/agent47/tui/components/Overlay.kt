package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.input.toKeyboardEvent
import co.agentmode.agent47.tui.overlays.formatRelativeAge
import java.time.Instant
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import co.agentmode.agent47.ui.core.state.*
import co.agentmode.agent47.ui.core.util.fuzzyMatch
import kotlin.math.max
import kotlin.math.min

/**
 * Creates and remembers a [SelectDialogState].
 */
@Composable
public fun <T> rememberSelectDialogState(
    items: List<SelectItem<T>>,
    initialSelectedIndex: Int = 0,
): SelectDialogState<T> = remember(items) {
    SelectDialogState(items, initialSelectedIndex)
}

/**
 * Modal surface composable that renders a floating dialog positioned at the given offset.
 * Because dialogs float over the transcript, the panel is a raised (lighter) fill outlined
 * with a single-line border so it reads clearly as a distinct layer above the content.
 * Individual dialogs render their own chrome (title, search, footer) as content.
 *
 * @param width Total width of the dialog in columns
 * @param height Total height of the dialog in rows
 * @param offsetX Column offset from the left edge of the terminal
 * @param offsetY Row offset from the top of the terminal
 * @param modifier Modifier applied to the root Box
 * @param body Composable slot for the dialog content
 */
@Composable
public fun ModalSurface(
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val theme = LocalThemeConfig.current
    Box(
        modifier = modifier
            .offset(x = offsetX, y = offsetY)
            .width(width)
            .height(height)
            .drawBehind {
                drawRect(background = theme.overlayBg, drawStyle = DrawStyle.Fill)
                drawRect(foreground = theme.colors.muted, drawStyle = DrawStyle.Stroke(1))
            },
    ) {
        body()
    }
}

private fun renderOverlayTitleRow(
    title: String,
    width: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    val escPart = "esc  "
    // The title gives way to the esc hint so the row stays exactly as wide as the surface.
    val titlePart = "  $title".take((width - escPart.length - 1).coerceAtLeast(0))
    val gap = (width - titlePart.length - escPart.length).coerceAtLeast(0)
    withStyle(SpanStyle(color = theme.markdownText, background = theme.overlayBg)) {
        append(titlePart)
        append(" ".repeat(gap))
    }
    withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
        append(escPart)
    }
}

private fun renderOverlayBlankRow(
    width: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    withStyle(SpanStyle(background = theme.overlayBg)) {
        append(" ".repeat(width))
    }
}

/** An indented, full-width line of dialog body text, padded so it paints every cell of its row. */
private fun renderOverlayTextRow(
    text: String,
    width: Int,
    theme: ThemeConfig,
    color: Color = theme.colors.muted,
) = buildAnnotatedString {
    val content = "  $text"
    val padded = content.take(width).padEnd(width)
    withStyle(SpanStyle(color = color, background = theme.overlayBg)) {
        append(padded)
    }
}

private fun renderOverlayFooterRow(
    text: String,
    width: Int,
    theme: ThemeConfig,
) = renderOverlayTextRow(text, width, theme)

private fun renderSearchRow(
    query: String,
    width: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    if (query.isEmpty()) {
        val placeholder = "  Search"
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
            append(placeholder.padEnd(width))
        }
    } else {
        val label = "  Search: "
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
            append(label)
        }
        val queryPart = query.take(width - label.length)
        withStyle(SpanStyle(color = theme.markdownText, background = theme.overlayBg)) {
            append(queryPart)
        }
        val remaining = (width - label.length - queryPart.length).coerceAtLeast(0)
        if (remaining > 0) {
            withStyle(SpanStyle(background = theme.overlayBg)) {
                append(" ".repeat(remaining))
            }
        }
    }
}

/**
 * Paints [rows] into a surface of exactly [height] rows, one row per line.
 *
 * Both directions of the fit matter. A surface fill colors cells but draws no characters, so any
 * cell a dialog leaves untouched still shows whatever is composed beneath it; short content is
 * therefore padded with fully painted blank rows. In the other direction a surface does not clip,
 * so content longer than [height] would draw past the terminal; surplus rows are dropped instead.
 */
@Composable
private fun OverlayRows(
    rows: List<AnnotatedString>,
    height: Int,
    width: Int,
    theme: ThemeConfig,
) {
    Column {
        for (i in 0 until height) {
            Text(rows.getOrNull(i) ?: renderOverlayBlankRow(width, theme))
        }
    }
}

/** Rows [SelectDialog] paints around its list: padding, title, search field, separators, and footer. */
private const val SELECT_CHROME_ROWS = 8

/** The height [SelectDialog] needs to show all [itemCount] items at once. */
internal fun selectDialogHeight(itemCount: Int): Int = SELECT_CHROME_ROWS + itemCount.coerceAtLeast(1)

internal fun <T> selectDialogRows(
    title: String,
    state: SelectDialogState<T>,
    width: Int,
    height: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val bodyHeight = (height - SELECT_CHROME_ROWS).coerceAtLeast(0)
    val visibleIndices = state.filteredIndices()
    val scrollTop = state.scrollTopFor(bodyHeight)

    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayTitleRow(title, width, theme))
    add(renderOverlayBlankRow(width, theme))
    add(renderSearchRow(state.query, width, theme))
    add(renderOverlayBlankRow(width, theme))

    for (i in 0 until bodyHeight) {
        val visibleIndex = scrollTop + i
        add(
            when {
                visibleIndex < visibleIndices.size -> {
                    val optionIndex = visibleIndices[visibleIndex]
                    val item = state.items[optionIndex]
                    renderSelectLine(
                        item.label,
                        optionIndex == state.selectedIndex,
                        width,
                        theme,
                        state.matchedPositions(optionIndex),
                        item.rightLabel ?: "",
                    )
                }

                visibleIndices.isEmpty() && i == 0 -> renderSelectLine("(no matches)", false, width, theme)
                else -> renderSelectLine("", false, width, theme)
            },
        )
    }

    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayFooterRow("↑/↓ navigate  enter select", width, theme))
    add(renderOverlayBlankRow(width, theme))
}

/**
 * A filterable list picker dialog. Renders a modal with header, filter input, scrollable
 * item list, and footer with keyboard shortcut hints.
 *
 * Keyboard handling:
 * - Arrow keys navigate the list
 * - Enter submits the selected item
 * - Escape closes the dialog
 * - Typing characters filters the list
 * - Backspace deletes from the filter
 * - Ctrl+U clears the filter
 *
 * @param title Dialog header text
 * @param state The [SelectDialogState] managing items, selection, and filtering
 * @param width Dialog width in columns
 * @param height Dialog height in rows
 * @param offsetX Column offset
 * @param offsetY Row offset
 * @param onSubmit Called with the selected value when Enter is pressed
 * @param onClose Called when Escape is pressed or the dialog is dismissed
 */
@Composable
public fun <T> SelectDialog(
    title: String,
    state: SelectDialogState<T>,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    onSubmit: (T) -> Unit,
    onClose: () -> Unit,
    onSelectionChanged: ((T) -> Unit)? = null,
) {
    val theme = LocalThemeConfig.current

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            handleSelectDialogKey(event, state, onSubmit, onClose, onSelectionChanged)
        },
    ) {
        OverlayRows(selectDialogRows(title, state, width, height, theme), height, width, theme)
    }
}

private fun renderSelectLine(
    text: String,
    selected: Boolean,
    width: Int,
    theme: ThemeConfig,
    matchedPositions: List<Int> = emptyList(),
    rightText: String = "",
) = buildAnnotatedString {
    val prefix = "  "
    val bg = if (selected) theme.overlaySelectedBg else theme.overlayBg
    val fg = theme.markdownText
    // Reserve room for the right-aligned segment (+ a 2-space gap) so the label truncates first.
    val rightReserve = if (rightText.isEmpty()) 0 else rightText.length + 2
    val maxLabelWidth = (width - prefix.length - rightReserve).coerceAtLeast(0)
    val label = text.take(maxLabelWidth)
    val matchSet = matchedPositions.toSet()

    withStyle(SpanStyle(color = fg, background = bg)) {
        append(prefix)
    }
    for ((i, ch) in label.withIndex()) {
        val color = if (i in matchSet) theme.colors.accentBright else fg
        withStyle(SpanStyle(color = color, background = bg)) {
            append(ch)
        }
    }
    val gap = (width - prefix.length - label.length - rightText.length).coerceAtLeast(0)
    if (gap > 0) {
        withStyle(SpanStyle(color = fg, background = bg)) {
            append(" ".repeat(gap))
        }
    }
    if (rightText.isNotEmpty()) {
        withStyle(SpanStyle(color = theme.colors.muted, background = bg)) {
            append(rightText)
        }
    }
}

private fun <T> handleSelectDialogKey(
    event: KeyEvent,
    state: SelectDialogState<T>,
    onSubmit: (T) -> Unit,
    onClose: () -> Unit,
    onSelectionChanged: ((T) -> Unit)? = null,
): Boolean {
    val keyboardEvent = event.toKeyboardEvent()
    return handleSelectDialogKeyboardEvent(keyboardEvent, state, onSubmit, onClose, onSelectionChanged)
}

private fun <T> handleSelectDialogKeyboardEvent(
    event: KeyboardEvent,
    state: SelectDialogState<T>,
    onSubmit: (T) -> Unit,
    onClose: () -> Unit,
    onSelectionChanged: ((T) -> Unit)? = null,
): Boolean {
    val visibleIndices = state.filteredIndices()

    if (visibleIndices.isEmpty()) {
        return when (event.key) {
            Key.Enter, Key.Escape -> {
                onClose()
                true
            }

            Key.Backspace -> {
                state.deleteChar()
                onSelectionChanged?.let { cb -> state.selectedValue()?.let(cb) }
                true
            }

            is Key.Character -> {
                if (event.ctrl && event.key.value.lowercaseChar() == 'u') {
                    state.clearFilter()
                    onSelectionChanged?.let { cb -> state.selectedValue()?.let(cb) }
                } else if (!event.ctrl && !event.alt) {
                    state.appendChar(event.key.value)
                }
                true
            }

            else -> true
        }
    }

    return when (event.key) {
        Key.ArrowUp -> {
            state.moveUp()
            onSelectionChanged?.let { callback -> state.selectedValue()?.let(callback) }
            true
        }

        Key.ArrowDown -> {
            state.moveDown()
            onSelectionChanged?.let { callback -> state.selectedValue()?.let(callback) }
            true
        }

        Key.Backspace -> {
            state.deleteChar()
            onSelectionChanged?.let { cb -> state.selectedValue()?.let(cb) }
            true
        }

        Key.Enter -> {
            val value = state.selectedValue()
            if (value != null) {
                onSubmit(value)
            }
            true
        }

        Key.Escape -> {
            onClose()
            true
        }

        is Key.Character -> {
            if (event.ctrl && event.key.value.lowercaseChar() == 'u') {
                state.clearFilter()
            } else if (!event.ctrl && !event.alt) {
                state.appendChar(event.key.value)
            }
            // Filtering changes the highlighted item, so refresh any live preview (e.g. theme).
            onSelectionChanged?.let { cb -> state.selectedValue()?.let(cb) }
            true
        }

        else -> true
    }
}

/**
 * A text input dialog. Renders a modal with a title, optional description,
 * a single-line text input field, and a footer with keyboard hints.
 *
 * Keyboard handling:
 * - Typing characters appends to the input
 * - Backspace deletes the previous character
 * - Delete removes the next character
 * - Left/Right arrows move the cursor
 * - Home/End or Ctrl+A/Ctrl+E jump to start/end
 * - Ctrl+U clears the input
 * - Enter submits the current text
 * - Escape closes the dialog
 *
 * @param title Dialog header text
 * @param state The [PromptDialogState] managing the text input
 * @param width Dialog width in columns
 * @param height Dialog height in rows
 * @param offsetX Column offset
 * @param offsetY Row offset
 * @param placeholder Placeholder text shown when input is empty
 * @param description Optional descriptive text shown above the input
 * @param onSubmit Called with the input text when Enter is pressed
 * @param onClose Called when Escape is pressed
 */
@Composable
public fun PromptDialog(
    title: String,
    state: PromptDialogState,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    placeholder: String = "",
    description: String? = null,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalThemeConfig.current

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            handlePromptDialogKey(event, state, onSubmit, onClose)
        },
    ) {
        OverlayRows(promptDialogRows(title, state, width, height, placeholder, description, theme), height, width, theme)
    }
}

private fun renderPromptInputRow(
    state: PromptDialogState,
    width: Int,
    placeholder: String,
    theme: ThemeConfig,
) = buildAnnotatedString {
    val inputWidth = (width - 4).coerceAtLeast(1)
    withStyle(SpanStyle(background = theme.overlayBg)) {
        append("  ")
    }
    val isEmpty = state.text.isEmpty()
    val textColor = if (isEmpty) theme.colors.muted else theme.colors.accentBright
    val cursorInRange = state.cursorPos.coerceIn(0, state.text.length)
    if (isEmpty) {
        withStyle(SpanStyle(color = theme.colors.accentBright, background = theme.overlaySelectedBg)) {
            append(if (placeholder.isNotEmpty()) placeholder.first().toString() else " ")
        }
        val rest = if (placeholder.length > 1) placeholder.substring(1) else ""
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
            append(rest.take(inputWidth - 1).padEnd(inputWidth - 1))
        }
    } else {
        val shown = if (state.masked) "•".repeat(state.text.length) else state.text
        // The field scrolls horizontally around the cursor, which may rest one past the last
        // character, so text longer than the field never widens the row beyond the surface.
        val field = "$shown "
        val windowStart = (cursorInRange - inputWidth + 1)
            .coerceIn(0, (field.length - inputWidth).coerceAtLeast(0))
        val window = field.drop(windowStart).take(inputWidth).padEnd(inputWidth)
        val cursorInWindow = cursorInRange - windowStart

        withStyle(SpanStyle(color = textColor, background = theme.overlayBg)) {
            append(window.take(cursorInWindow))
        }
        withStyle(SpanStyle(color = theme.colors.accentBright, background = theme.overlaySelectedBg)) {
            append(window[cursorInWindow].toString())
        }
        withStyle(SpanStyle(color = textColor, background = theme.overlayBg)) {
            append(window.substring(cursorInWindow + 1))
        }
    }
    withStyle(SpanStyle(background = theme.overlayBg)) {
        append("  ")
    }
}

/** Rows [PromptDialog] paints around its description: padding, title, separator, input, and footer. */
private const val PROMPT_CHROME_ROWS = 6

/** The height [PromptDialog] needs for its input plus an optional [description] block. */
internal fun promptDialogHeight(description: String?): Int =
    PROMPT_CHROME_ROWS + if (description == null) 0 else description.lines().size + 1

internal fun promptDialogRows(
    title: String,
    state: PromptDialogState,
    width: Int,
    height: Int,
    placeholder: String,
    description: String?,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayTitleRow(title, width, theme))
    add(renderOverlayBlankRow(width, theme))

    // The description takes whatever the surface leaves between the title and the input, so a
    // description longer than the terminal clips rather than pushing the input row out of view.
    val descriptionLines = description?.lines().orEmpty()
    for (i in 0 until (height - PROMPT_CHROME_ROWS).coerceAtLeast(0)) {
        val line = descriptionLines.getOrNull(i)
        add(if (line == null) renderOverlayBlankRow(width, theme) else renderOverlayTextRow(line, width, theme))
    }

    add(renderPromptInputRow(state, width, placeholder, theme))
    add(renderOverlayFooterRow("enter submit  esc cancel", width, theme))
    add(renderOverlayBlankRow(width, theme))
}

private fun handlePromptDialogKey(
    event: KeyEvent,
    state: PromptDialogState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
): Boolean {
    val keyboardEvent = event.toKeyboardEvent()
    return when (keyboardEvent.key) {
        Key.Enter -> {
            onSubmit(state.text)
            true
        }

        Key.Escape -> {
            onClose()
            true
        }

        Key.Backspace -> {
            state.deleteChar()
            true
        }

        Key.Delete -> {
            state.deleteForward()
            true
        }

        Key.ArrowLeft -> {
            state.moveLeft()
            true
        }

        Key.ArrowRight -> {
            state.moveRight()
            true
        }

        Key.Home -> {
            state.moveHome()
            true
        }

        Key.End -> {
            state.moveEnd()
            true
        }

        is Key.Character -> {
            if (keyboardEvent.ctrl) {
                when (keyboardEvent.key.value.lowercaseChar()) {
                    'u' -> state.clear()
                    'a' -> state.moveHome()
                    'e' -> state.moveEnd()
                }
            } else if (!keyboardEvent.alt) {
                state.appendChar(keyboardEvent.key.value)
            }
            true
        }

        else -> true
    }
}

/**
 * Creates and remembers an [OverlayHostState].
 */
@Composable
public fun rememberOverlayHostState(): OverlayHostState = remember { OverlayHostState() }

/**
 * A non-interactive informational dialog that displays text lines.
 * Only responds to Escape to close.
 */
@Composable
public fun InfoDialog(
    title: String,
    lines: List<String>,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    onClose: () -> Unit,
) {
    val theme = LocalThemeConfig.current

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            val keyboardEvent = event.toKeyboardEvent()
            when (keyboardEvent.key) {
                Key.Escape -> {
                    onClose()
                    true
                }

                else -> true
            }
        },
    ) {
        OverlayRows(infoDialogRows(title, lines, width, height, theme), height, width, theme)
    }
}

/** Rows [InfoDialog] paints around its text: padding, title, separator, and footer. */
private const val TEXT_CHROME_ROWS = 5

/** The height [InfoDialog] needs to show all [lineCount] lines. */
internal fun infoDialogHeight(lineCount: Int): Int = TEXT_CHROME_ROWS + lineCount.coerceAtLeast(1)

internal fun infoDialogRows(
    title: String,
    lines: List<String>,
    width: Int,
    height: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayTitleRow(title, width, theme))
    add(renderOverlayBlankRow(width, theme))

    for (i in 0 until (height - TEXT_CHROME_ROWS).coerceAtLeast(0)) {
        val line = lines.getOrNull(i)
        add(if (line == null) renderOverlayBlankRow(width, theme) else renderOverlayTextRow(line, width, theme))
    }

    add(renderOverlayFooterRow("esc cancel", width, theme))
    add(renderOverlayBlankRow(width, theme))
}

/**
 * A scrollable text dialog for viewing long content. Supports keyboard scrolling
 * with Ctrl+U/D (half-page), PgUp/PgDn (full page), and arrow keys (single line).
 */
@Composable
public fun ScrollableTextDialog(
    title: String,
    lines: List<String>,
    scrollTop: Int,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    onScroll: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalThemeConfig.current
    val bodyHeight = (height - TEXT_CHROME_ROWS).coerceAtLeast(1)
    val maxScroll = (lines.size - bodyHeight).coerceAtLeast(0)
    val safeTop = scrollTop.coerceIn(0, maxScroll)

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            val keyboardEvent = event.toKeyboardEvent()
            when {
                keyboardEvent.key == Key.Escape -> {
                    onClose()
                    true
                }
                keyboardEvent.key == Key.ArrowUp -> {
                    onScroll((safeTop - 1).coerceAtLeast(0))
                    true
                }
                keyboardEvent.key == Key.ArrowDown -> {
                    onScroll((safeTop + 1).coerceAtMost(maxScroll))
                    true
                }
                keyboardEvent.key == Key.PageUp -> {
                    onScroll((safeTop - bodyHeight).coerceAtLeast(0))
                    true
                }
                keyboardEvent.key == Key.PageDown -> {
                    onScroll((safeTop + bodyHeight).coerceAtMost(maxScroll))
                    true
                }
                keyboardEvent.ctrl && keyboardEvent.key is Key.Character && keyboardEvent.key.value.lowercaseChar() == 'u' -> {
                    onScroll((safeTop - bodyHeight / 2).coerceAtLeast(0))
                    true
                }
                keyboardEvent.ctrl && keyboardEvent.key is Key.Character && keyboardEvent.key.value.lowercaseChar() == 'd' -> {
                    onScroll((safeTop + bodyHeight / 2).coerceAtMost(maxScroll))
                    true
                }
                else -> true
            }
        },
    ) {
        val rows = buildList {
            add(renderOverlayBlankRow(width, theme))
            add(renderOverlayTitleRow(title, width, theme))
            add(renderOverlayBlankRow(width, theme))

            for (i in 0 until bodyHeight) {
                val lineText = lines.getOrNull(safeTop + i).orEmpty()
                add(renderOverlayTextRow(lineText, width, theme, theme.markdownText))
            }

            val scrollInfo = if (lines.size > bodyHeight) " (${safeTop + 1}-${min(safeTop + bodyHeight, lines.size)}/${lines.size})" else ""
            add(renderOverlayFooterRow("↑/↓ scroll  PgUp/PgDn page  esc close$scrollInfo", width, theme))
            add(renderOverlayBlankRow(width, theme))
        }
        OverlayRows(rows, height, width, theme)
    }
}

/** The height [ScrollableTextDialog] needs to show all [lineCount] lines without scrolling. */
internal fun scrollableTextDialogHeight(lineCount: Int): Int = TEXT_CHROME_ROWS + lineCount.coerceAtLeast(1)

private fun treeRoleColor(role: TreeTextRole, theme: ThemeConfig): Color = when (role) {
    TreeTextRole.ACCENT -> theme.colors.accent
    TreeTextRole.SUCCESS -> theme.colors.success
    TreeTextRole.WARNING -> theme.colors.warning
    TreeTextRole.MUTED -> theme.colors.muted
    TreeTextRole.DIM -> theme.colors.dim
    TreeTextRole.ERROR -> theme.colors.error
    TreeTextRole.CUSTOM_LABEL -> theme.customMessageLabel
    TreeTextRole.PLAIN -> theme.markdownText
}

/** A [SpanStyle] with bold applied only when [bold], since there is no explicit "not bold" textStyle value. */
private fun rowSpanStyle(color: Color, background: Color, bold: Boolean): SpanStyle =
    if (bold) SpanStyle(color = color, background = background, textStyle = TextStyle.Bold) else SpanStyle(color = color, background = background)

/** Where a row's own connector sits, if it has one: the char to draw and the level it occupies. */
private data class TreeConnector(val char: Char?, val level: Int)

/** The character at ([level], [posInLevel]) of a row's indentation prefix. */
private fun treePrefixChar(
    level: Int,
    posInLevel: Int,
    gutters: List<TreeGutter>,
    connector: TreeConnector,
    folded: Boolean,
    hasVisibleChildren: Boolean,
): Char {
    val gutter = gutters.firstOrNull { it.level == level }
    return when {
        gutter != null -> if (posInLevel == 0) (if (gutter.show) '│' else ' ') else ' '
        connector.char != null && level == connector.level -> when (posInLevel) {
            0 -> connector.char
            1 -> if (folded) '⊞' else if (hasVisibleChildren) '⊟' else '─'
            else -> ' '
        }
        else -> ' '
    }
}

/**
 * Builds the indentation prefix for one tree row: `│` gutters at each ancestor branch point, plus
 * this row's own `├─`/`└─` connector with a fold indicator (`⊟`/`⊞`) spliced into its dash.
 */
private fun buildTreeRowPrefix(row: TreeRow, multipleRoots: Boolean, folded: Boolean): String {
    val displayIndent = if (multipleRoots) max(0, row.indent - 1) else row.indent
    val totalChars = displayIndent * 3
    if (totalChars <= 0) return ""

    val hasConnector = row.showConnector && !row.isVirtualRootChild
    val connector = TreeConnector(
        char = if (hasConnector) (if (row.isLast) '└' else '├') else null,
        level = if (hasConnector) displayIndent - 1 else -1,
    )

    val chars = CharArray(totalChars) { i ->
        treePrefixChar(i / 3, i % 3, row.gutters, connector, folded, row.hasVisibleChildren)
    }
    return String(chars)
}

/** Truncates display segments to fit [maxWidth] columns, dropping or clipping whatever overflows. */
private fun truncateTreeSegments(segments: List<TreeTextSegment>, maxWidth: Int): List<TreeTextSegment> {
    if (maxWidth <= 0) return emptyList()
    val result = mutableListOf<TreeTextSegment>()
    var used = 0
    var truncated = false
    for (segment in segments) {
        if (truncated) continue
        val remaining = maxWidth - used
        when {
            remaining <= 0 -> truncated = true
            segment.text.length <= remaining -> {
                result += segment
                used += segment.text.length
            }
            else -> {
                result += segment.copy(text = segment.text.take(remaining))
                used = maxWidth
                truncated = true
            }
        }
    }
    return result
}

private fun renderTreeRow(
    row: TreeRow,
    selectorState: TreeSelectorState,
    isSelected: Boolean,
    width: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    val bg = if (isSelected) theme.overlaySelectedBg else theme.overlayBg
    val folded = selectorState.isFolded(row.id)
    val showsFoldInConnector = row.showConnector && !row.isVirtualRootChild
    val foldMarker = if (folded && !showsFoldInConnector) "⊞ " else ""
    val pathMarker = if (row.id in selectorState.activePathIds) "• " else ""
    // Indentation yields to the surface: a deeply nested row clips its connectors rather than
    // drawing a row wider than the dialog.
    val prefix = buildTreeRowPrefix(row, selectorState.multipleRoots, folded)
        .take((width - 2 - foldMarker.length - pathMarker.length).coerceAtLeast(0))
    val leadIn = "  $prefix$foldMarker$pathMarker"

    withStyle(rowSpanStyle(theme.colors.dim, bg, isSelected)) {
        append("  ")
        append(prefix)
    }
    if (foldMarker.isNotEmpty()) {
        withStyle(rowSpanStyle(theme.colors.accent, bg, isSelected)) { append(foldMarker) }
    }
    if (pathMarker.isNotEmpty()) {
        withStyle(rowSpanStyle(theme.colors.accent, bg, isSelected)) { append(pathMarker) }
    }

    val bodyBudget = (width - leadIn.length).coerceAtLeast(0)
    val segments = truncateTreeSegments(buildEntryDisplaySegments(row.node, selectorState.toolCalls), bodyBudget)
    var used = leadIn.length
    for (segment in segments) {
        withStyle(rowSpanStyle(treeRoleColor(segment.role, theme), bg, isSelected)) { append(segment.text) }
        used += segment.text.length
    }
    val padding = (width - used).coerceAtLeast(0)
    if (padding > 0) {
        withStyle(SpanStyle(background = bg)) { append(" ".repeat(padding)) }
    }
}

private fun renderTreeSearchRow(selectorState: TreeSelectorState, width: Int, theme: ThemeConfig) = buildAnnotatedString {
    if (selectorState.searchQuery.isEmpty()) {
        val hint = "  Type to search  ·  ←/→ fold/unfold  ·  tab cycle filter"
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
            append(hint.take(width).padEnd(width))
        }
    } else {
        val label = "  Search: "
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) { append(label) }
        val queryPart = selectorState.searchQuery.take((width - label.length).coerceAtLeast(0))
        withStyle(SpanStyle(color = theme.markdownText, background = theme.overlayBg)) { append(queryPart) }
        val remaining = (width - label.length - queryPart.length).coerceAtLeast(0)
        if (remaining > 0) {
            withStyle(SpanStyle(background = theme.overlayBg)) { append(" ".repeat(remaining)) }
        }
    }
}

private fun treeFilterSuffix(mode: TreeFilterMode): String = when (mode) {
    TreeFilterMode.DEFAULT -> ""
    TreeFilterMode.NO_TOOLS -> " [no-tools]"
    TreeFilterMode.USER_ONLY -> " [user]"
    TreeFilterMode.ALL -> " [all]"
}

/** Escape clears an active search first (matching the popup-dismiss convention), then closes. */
private fun handleTreeEscape(selectorState: TreeSelectorState, onClose: () -> Unit) {
    if (!selectorState.clearSearch()) onClose()
}

/** A plain, unmodified character appends to the type-to-search query. */
private fun handleTreeCharacter(event: KeyboardEvent, character: Key.Character, selectorState: TreeSelectorState) {
    if (!event.ctrl && !event.alt) {
        selectorState.appendSearchChar(character.value)
    }
}

private fun handleTreeDialogKey(
    event: KeyEvent,
    selectorState: TreeSelectorState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
): Boolean {
    val keyboardEvent = event.toKeyboardEvent()
    when (val key = keyboardEvent.key) {
        Key.ArrowUp -> selectorState.moveUp()
        Key.ArrowDown -> selectorState.moveDown()
        Key.ArrowLeft -> selectorState.foldOrMoveToParent()
        Key.ArrowRight -> selectorState.unfoldOrMoveToChild()
        Key.Tab -> selectorState.cycleFilter()
        Key.Backspace -> selectorState.backspaceSearch()
        Key.Enter -> selectorState.selectedRow()?.let { row -> onSubmit(row.id) }
        Key.Escape -> handleTreeEscape(selectorState, onClose)
        is Key.Character -> handleTreeCharacter(keyboardEvent, key, selectorState)
        else -> Unit
    }
    return true
}

/**
 * The `/tree` session navigator: a scrollable, foldable, searchable view of [selectorState]'s
 * flattened rows. Selecting a row hands its entry id to [onSubmit]; the caller decides what
 * navigating there means (including the current-leaf no-op and the branch-summary prompt).
 */
@Composable
public fun TreeSelectorDialog(
    selectorState: TreeSelectorState,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalThemeConfig.current

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event -> handleTreeDialogKey(event, selectorState, onSubmit, onClose) },
    ) {
        OverlayRows(treeSelectorDialogRows(selectorState, width, height, theme), height, width, theme)
    }
}

/** The height [TreeSelectorDialog] needs to show all [rowCount] entries at once. */
internal fun treeSelectorDialogHeight(rowCount: Int): Int = SELECT_CHROME_ROWS + rowCount.coerceAtLeast(1)

internal fun treeSelectorDialogRows(
    selectorState: TreeSelectorState,
    width: Int,
    height: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val bodyHeight = (height - SELECT_CHROME_ROWS).coerceAtLeast(0)
    val rows = selectorState.rows
    val scrollTop = if (rows.isEmpty()) {
        0
    } else {
        max(0, min(selectorState.selectedIndex - bodyHeight / 2, rows.size - bodyHeight))
    }

    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayTitleRow("Session Tree", width, theme))
    add(renderOverlayBlankRow(width, theme))
    add(renderTreeSearchRow(selectorState, width, theme))
    add(renderOverlayBlankRow(width, theme))

    for (i in 0 until bodyHeight) {
        val rowIndex = scrollTop + i
        add(
            when {
                rowIndex < rows.size ->
                    renderTreeRow(rows[rowIndex], selectorState, rowIndex == selectorState.selectedIndex, width, theme)

                rows.isEmpty() && i == 0 -> renderSelectLine("No entries found", false, width, theme)
                else -> renderSelectLine("", false, width, theme)
            },
        )
    }

    add(renderOverlayBlankRow(width, theme))
    val counter = if (rows.isEmpty()) "(0/0)" else "(${selectorState.selectedIndex + 1}/${rows.size})"
    add(renderOverlayFooterRow("$counter${treeFilterSuffix(selectorState.filterMode)}", width, theme))
    add(renderOverlayBlankRow(width, theme))
}

private fun renderUserMessageLine(item: UserMessageItem, isSelected: Boolean, width: Int, theme: ThemeConfig) = buildAnnotatedString {
    val bg = if (isSelected) theme.overlaySelectedBg else theme.overlayBg
    val cursor = if (isSelected) "› " else "  "
    val normalized = item.text.replace('\n', ' ').trim()
    val maxWidth = (width - cursor.length).coerceAtLeast(0)
    val truncated = normalized.take(maxWidth)

    withStyle(SpanStyle(color = theme.colors.accent, background = bg)) { append(cursor) }
    withStyle(rowSpanStyle(theme.markdownText, bg, isSelected)) { append(truncated) }
    val padding = (width - cursor.length - truncated.length).coerceAtLeast(0)
    if (padding > 0) {
        withStyle(SpanStyle(background = bg)) { append(" ".repeat(padding)) }
    }
}

private fun renderUserMessageAge(item: UserMessageItem, isSelected: Boolean, width: Int, theme: ThemeConfig) = buildAnnotatedString {
    val bg = if (isSelected) theme.overlaySelectedBg else theme.overlayBg
    val age = item.timestamp?.let { formatRelativeAge(Instant.ofEpochMilli(it)) }.orEmpty()
    val text = if (age.isEmpty()) "" else "  $age"
    withStyle(SpanStyle(color = theme.colors.muted, background = bg)) {
        append(text.take(width).padEnd(width))
    }
}

private fun handleUserMessageDialogKey(
    event: KeyEvent,
    listState: UserMessageListState,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
): Boolean {
    val keyboardEvent = event.toKeyboardEvent()
    return when (keyboardEvent.key) {
        Key.ArrowUp -> {
            listState.moveUp()
            true
        }

        Key.ArrowDown -> {
            listState.moveDown()
            true
        }

        Key.Enter -> {
            listState.selected()?.let { item -> onSubmit(item.id) }
            true
        }

        Key.Escape -> {
            onClose()
            true
        }

        else -> true
    }
}

/**
 * The `/fork` message picker: user messages in chronological order, two lines each (text plus a
 * "Message N of M" caption), newest preselected.
 */
/** Rows per `/fork` list item: the message line, its age line, and a separator. */
private const val USER_MESSAGE_ROW_HEIGHT = 3

@Composable
public fun UserMessageSelectorDialog(
    listState: UserMessageListState,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    onSubmit: (String) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalThemeConfig.current

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event -> handleUserMessageDialogKey(event, listState, onSubmit, onClose) },
    ) {
        OverlayRows(userMessageDialogRows(listState, width, height, theme), height, width, theme)
    }
}

/** Rows [UserMessageSelectorDialog] paints around its list: padding, title, separators, and footer. */
private const val USER_MESSAGE_CHROME_ROWS = 6

/** Messages the `/fork` list shows before it starts scrolling. */
private const val MAX_USER_MESSAGE_ITEMS = 10

/** The height [UserMessageSelectorDialog] needs for [itemCount] messages. */
internal fun userMessageDialogHeight(itemCount: Int): Int = if (itemCount == 0) {
    USER_MESSAGE_CHROME_ROWS + 1
} else {
    USER_MESSAGE_CHROME_ROWS + min(itemCount, MAX_USER_MESSAGE_ITEMS) * USER_MESSAGE_ROW_HEIGHT
}

internal fun userMessageDialogRows(
    listState: UserMessageListState,
    width: Int,
    height: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val bodyHeight = (height - USER_MESSAGE_CHROME_ROWS).coerceAtLeast(0)
    val maxVisibleItems = bodyHeight / USER_MESSAGE_ROW_HEIGHT
    val items = listState.items
    val scrollTop = if (items.isEmpty()) {
        0
    } else {
        max(0, min(listState.selectedIndex - maxVisibleItems / 2, items.size - maxVisibleItems))
    }

    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayTitleRow("Fork from Message", width, theme))
    add(renderOverlayBlankRow(width, theme))

    if (items.isEmpty()) {
        add(renderSelectLine("No user messages found", false, width, theme))
    } else {
        for (i in 0 until maxVisibleItems) {
            val item = items.getOrNull(scrollTop + i)
            if (item == null) {
                // Every body row is painted, item or not, so the surface never exposes the
                // transcript behind it.
                repeat(USER_MESSAGE_ROW_HEIGHT) { add(renderOverlayBlankRow(width, theme)) }
            } else {
                val isSelected = scrollTop + i == listState.selectedIndex
                add(renderUserMessageLine(item, isSelected, width, theme))
                add(renderUserMessageAge(item, isSelected, width, theme))
                add(renderOverlayBlankRow(width, theme))
            }
        }
    }

    val footer = if (items.size > maxVisibleItems) {
        "↑/↓ navigate  enter select  (${listState.selectedIndex + 1}/${items.size})"
    } else {
        "↑/↓ navigate  enter select"
    }
    add(renderOverlayBlankRow(width, theme))
    add(renderOverlayFooterRow(footer, width, theme))
    add(renderOverlayBlankRow(width, theme))
}

/** Rows left clear above and below a dialog so it reads as floating over the transcript. */
private const val DIALOG_MARGIN_ROWS = 4

/** Ceiling for list and form dialogs; longer content scrolls inside the dialog instead of growing it. */
private const val MAX_DIALOG_HEIGHT = 24

/**
 * Sizes a dialog to the [naturalHeight] its content paints, within what [terminalHeight] can show.
 * The terminal is a hard ceiling: a surface taller than the render target draws outside it.
 */
private fun fitDialogHeight(
    naturalHeight: Int,
    terminalHeight: Int,
    ceiling: Int = MAX_DIALOG_HEIGHT,
): Int {
    val available = min(ceiling, terminalHeight - DIALOG_MARGIN_ROWS).coerceIn(1, terminalHeight)
    return naturalHeight.coerceIn(1, available)
}

/** Vertically centers a dialog of [height] rows within [terminalHeight]. */
private fun centeredOffsetY(height: Int, terminalHeight: Int): Int = max(0, (terminalHeight - height) / 2)

@Composable
private fun TreeOverlayHostEntry(
    entry: TreeOverlayEntry,
    state: OverlayHostState,
    dialogWidth: Int,
    terminalHeight: Int,
    offsetX: Int,
) {
    val selectorState = entry.selectorState
        ?: TreeSelectorState(entry.session, entry.initialSelectedId).also { entry.selectorState = it }

    val height = fitDialogHeight(treeSelectorDialogHeight(selectorState.rows.size), terminalHeight)
    TreeSelectorDialog(
        selectorState = selectorState,
        width = dialogWidth,
        height = height,
        offsetX = offsetX,
        offsetY = centeredOffsetY(height, terminalHeight),
        onSubmit = { id ->
            state.stack.removeLastOrNull()
            entry.onSelect(id)
        },
        onClose = {
            state.stack.removeLastOrNull()
            entry.onClose()
        },
    )
}

@Composable
private fun UserMessageOverlayHostEntry(
    entry: UserMessageOverlayEntry,
    state: OverlayHostState,
    dialogWidth: Int,
    terminalHeight: Int,
    offsetX: Int,
) {
    val listState = entry.listState
        ?: UserMessageListState(entry.items, entry.initialSelectedId).also { entry.listState = it }

    val height = fitDialogHeight(userMessageDialogHeight(entry.items.size), terminalHeight)
    UserMessageSelectorDialog(
        listState = listState,
        width = dialogWidth,
        height = height,
        offsetX = offsetX,
        offsetY = centeredOffsetY(height, terminalHeight),
        onSubmit = { id ->
            state.stack.removeLastOrNull()
            entry.onSelect(id)
        },
        onClose = {
            state.stack.removeLastOrNull()
            entry.onClose()
        },
    )
}

/**
 * Renders the topmost overlay in the [OverlayHostState] stack as a centered dialog.
 * The overlay intercepts all key events when visible, preventing them from reaching
 * underlying content.
 *
 * @param state The overlay host state
 * @param terminalWidth Terminal width in columns (used for centering)
 * @param terminalHeight Terminal height in rows (used for centering)
 */
@Composable
public fun OverlayHost(
    state: OverlayHostState,
    terminalWidth: Int,
    terminalHeight: Int,
) {
    val entry = state.stack.lastOrNull() ?: return

    val dialogWidth = min(88, max(32, terminalWidth - 6))
    val offsetX = max(0, (terminalWidth - dialogWidth) / 2)

    when (entry) {
        is SelectOverlayEntry<*> -> {
            @Suppress("UNCHECKED_CAST")
            val typedEntry = entry as SelectOverlayEntry<Any?>

            val dialogState = typedEntry.dialogState ?: SelectDialogState(
                items = typedEntry.items,
                initialSelectedIndex = typedEntry.initialSelectedIndex,
            ).also { typedEntry.dialogState = it }

            // Sized on the full item list rather than the filtered one, so typing a filter narrows
            // the list in place instead of resizing and re-centering the dialog on every keystroke.
            val height = fitDialogHeight(selectDialogHeight(typedEntry.items.size), terminalHeight)
            SelectDialog(
                title = typedEntry.title,
                state = dialogState,
                width = dialogWidth,
                height = height,
                offsetX = offsetX,
                offsetY = centeredOffsetY(height, terminalHeight),
                onSubmit = { value ->
                    if (typedEntry.keepOpenOnSubmit) {
                        typedEntry.onSubmit(value)
                    } else {
                        state.stack.removeLastOrNull()
                        typedEntry.onSubmit(value)
                    }
                },
                onClose = {
                    state.stack.removeLastOrNull()
                    typedEntry.onClose()
                },
                onSelectionChanged = typedEntry.onSelectionChanged,
            )
        }

        is PromptOverlayEntry -> {
            val dialogState = entry.dialogState ?: PromptDialogState(entry.initialValue).also {
                it.masked = entry.masked
                entry.dialogState = it
            }

            val height = fitDialogHeight(promptDialogHeight(entry.description), terminalHeight)
            PromptDialog(
                title = entry.title,
                state = dialogState,
                width = dialogWidth,
                height = height,
                offsetX = offsetX,
                offsetY = centeredOffsetY(height, terminalHeight),
                placeholder = entry.placeholder,
                description = entry.description,
                onSubmit = { text ->
                    state.stack.removeLastOrNull()
                    entry.onSubmit(text)
                },
                onClose = {
                    state.stack.removeLastOrNull()
                    entry.onClose()
                },
            )
        }

        is InfoOverlayEntry -> {
            val height = fitDialogHeight(infoDialogHeight(entry.lines.size), terminalHeight)
            InfoDialog(
                title = entry.title,
                lines = entry.lines,
                width = dialogWidth,
                height = height,
                offsetX = offsetX,
                offsetY = centeredOffsetY(height, terminalHeight),
                onClose = {
                    state.stack.removeLastOrNull()
                    entry.onClose()
                },
            )
        }

        is ScrollableTextOverlayEntry -> {
            // A document viewer, so it may use the full terminal rather than the list-dialog ceiling.
            val height = fitDialogHeight(scrollableTextDialogHeight(entry.lines.size), terminalHeight, terminalHeight)
            ScrollableTextDialog(
                title = entry.title,
                lines = entry.lines,
                scrollTop = entry.scrollTop,
                width = dialogWidth,
                height = height,
                offsetX = offsetX,
                offsetY = centeredOffsetY(height, terminalHeight),
                onScroll = { newTop -> entry.scrollTop = newTop },
                onClose = {
                    state.stack.removeLastOrNull()
                    entry.onClose()
                },
            )
        }

        is TreeOverlayEntry -> TreeOverlayHostEntry(entry, state, dialogWidth, terminalHeight, offsetX)

        is UserMessageOverlayEntry -> UserMessageOverlayHostEntry(entry, state, dialogWidth, terminalHeight, offsetX)
    }
}
