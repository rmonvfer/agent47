package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.input.toKeyboardEvent
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.layout.*
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlin.math.max
import kotlin.math.min

/**
 * Data class for items in a [SelectDialog].
 */
public data class SelectItem<T>(
    val label: String,
    val value: T,
)

internal data class FuzzyResult(val index: Int, val score: Int, val matchedPositions: List<Int>)

internal fun fuzzyMatch(label: String, query: String): FuzzyResult? {
    if (query.isBlank()) return FuzzyResult(0, 0, emptyList())
    val q = query.trim().lowercase()
    val l = label.lowercase()
    val positions = mutableListOf<Int>()
    var qi = 0
    for (li in l.indices) {
        if (qi < q.length && l[li] == q[qi]) {
            positions.add(li)
            qi++
        }
    }
    if (qi < q.length) return null
    var score = positions.first()
    for (i in 1 until positions.size) {
        score += (positions[i] - positions[i - 1] - 1)
    }
    return FuzzyResult(0, score, positions)
}

/**
 * State holder for a [SelectDialog] that manages selection, filtering, and scroll position.
 * Use [rememberSelectDialogState] to create an instance scoped to the composition.
 */
@Stable
public class SelectDialogState<T>(
    internal val items: List<SelectItem<T>>,
    initialSelectedIndex: Int = 0,
) {
    internal var selectedIndex: Int by mutableIntStateOf(
        initialSelectedIndex.coerceIn(0, (items.lastIndex).coerceAtLeast(0)),
    )
    internal var query: String by mutableStateOf("")

    internal fun filteredIndices(): List<Int> {
        if (query.isBlank()) return items.indices.toList()
        val results = items.indices.mapNotNull { idx ->
            fuzzyMatch(items[idx].label, query)?.copy(index = idx)
        }
        return results.sortedBy { it.score }.map { it.index }
    }

    internal fun matchedPositions(itemIndex: Int): List<Int> {
        if (query.isBlank()) return emptyList()
        return fuzzyMatch(items[itemIndex].label, query)?.matchedPositions ?: emptyList()
    }

    internal fun moveUp() {
        val visible = filteredIndices()
        if (visible.isEmpty()) return
        val currentPos = visible.indexOf(selectedIndex)
        selectedIndex = if (currentPos <= 0) {
            visible.last()
        } else {
            visible[currentPos - 1]
        }
    }

    internal fun moveDown() {
        val visible = filteredIndices()
        if (visible.isEmpty()) return
        val currentPos = visible.indexOf(selectedIndex)
        selectedIndex = if (currentPos < 0 || currentPos >= visible.lastIndex) {
            visible.first()
        } else {
            visible[currentPos + 1]
        }
    }

    internal fun appendChar(ch: Char) {
        query += ch
        val refreshed = filteredIndices()
        if (refreshed.isNotEmpty() && selectedIndex !in refreshed) {
            selectedIndex = refreshed.first()
        }
    }

    internal fun deleteChar() {
        if (query.isNotEmpty()) {
            query = query.dropLast(1)
            val refreshed = filteredIndices()
            if (refreshed.isNotEmpty() && selectedIndex !in refreshed) {
                selectedIndex = refreshed.first()
            }
        }
    }

    internal fun clearFilter() {
        query = ""
        val refreshed = filteredIndices()
        if (refreshed.isNotEmpty()) {
            selectedIndex = refreshed.first()
        }
    }

    internal fun selectedValue(): T? {
        val visible = filteredIndices()
        if (visible.isEmpty()) return null
        val idx = if (selectedIndex in visible) selectedIndex else visible.first()
        return items[idx].value
    }

    internal fun scrollTopFor(listHeight: Int): Int {
        if (listHeight <= 0) return 0
        val visible = filteredIndices()
        val pos = visible.indexOf(selectedIndex).takeIf { it >= 0 } ?: 0
        return (pos - listHeight / 2).coerceIn(0, (visible.size - listHeight).coerceAtLeast(0))
    }
}

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
 * Modal surface composable that renders a bordered, background-filled dialog positioned at
 * the given offset. Individual dialogs render their own chrome (title, search, footer) as content.
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
    val titlePart = "  $title"
    val escPart = "esc  "
    val gap = (width - titlePart.length - escPart.length).coerceAtLeast(1)
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

private fun renderOverlayFooterRow(
    text: String,
    width: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    val content = "  $text"
    val padded = content.take(width).padEnd(width)
    withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
        append(padded)
    }
}

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

    val topPadding = 1
    val titleHeight = 1
    val spacer1 = 1
    val searchHeight = 1
    val spacer2 = 1
    val spacerBeforeFooter = 1
    val footerHeight = 1
    val bottomPadding = 1
    val chrome = topPadding + titleHeight + spacer1 + searchHeight + spacer2 + spacerBeforeFooter + footerHeight + bottomPadding
    val bodyHeight = (height - chrome).coerceAtLeast(1)

    val visibleIndices = state.filteredIndices()
    val scrollTop = state.scrollTopFor(bodyHeight)

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            handleSelectDialogKey(event, state, onSubmit, onClose, onSelectionChanged)
        },
    ) {
        Column {
            Text(renderOverlayBlankRow(width, theme))
            Text(renderOverlayTitleRow(title, width, theme))
            Text(renderOverlayBlankRow(width, theme))
            Text(renderSearchRow(state.query, width, theme))
            Text(renderOverlayBlankRow(width, theme))

            for (i in 0 until bodyHeight) {
                val visibleIndex = scrollTop + i
                if (visibleIndex < visibleIndices.size) {
                    val optionIndex = visibleIndices[visibleIndex]
                    val selected = optionIndex == state.selectedIndex
                    val label = state.items[optionIndex].label
                    val positions = state.matchedPositions(optionIndex)
                    Text(renderSelectLine(label, selected, width, theme, positions))
                } else if (visibleIndices.isEmpty() && i == 0) {
                    Text(renderSelectLine("(no matches)", false, width, theme))
                } else {
                    Text(renderSelectLine("", false, width, theme))
                }
            }

            Text(renderOverlayBlankRow(width, theme))
            Text(renderOverlayFooterRow("↑/↓ navigate  enter select", width, theme))
            Text(renderOverlayBlankRow(width, theme))
        }
    }
}

private fun renderSelectLine(
    text: String,
    selected: Boolean,
    width: Int,
    theme: ThemeConfig,
    matchedPositions: List<Int> = emptyList(),
) = buildAnnotatedString {
    val prefix = "  "
    val maxLabelWidth = (width - prefix.length).coerceAtLeast(0)
    val label = text.take(maxLabelWidth)
    val bg = if (selected) theme.overlaySelectedBg else theme.overlayBg
    val fg = theme.markdownText
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
    val remaining = (width - prefix.length - label.length).coerceAtLeast(0)
    if (remaining > 0) {
        withStyle(SpanStyle(color = fg, background = bg)) {
            append(" ".repeat(remaining))
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
                true
            }

            is Key.Character -> {
                if (!event.ctrl && !event.alt) {
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
            true
        }

        else -> true
    }
}

/**
 * Sealed interface representing the result of an overlay interaction.
 */
public sealed interface OverlayResult<out T> {
    /** The overlay was dismissed without a selection. */
    public data object Dismissed : OverlayResult<Nothing>

    /** The overlay produced a selected value. */
    public data class Selected<T>(val value: T) : OverlayResult<T>
}

/**
 * State holder for a [PromptDialog] that manages the text input field.
 */
@Stable
public class PromptDialogState(
    initialValue: String = "",
) {
    internal var text: String by mutableStateOf(initialValue)
    internal var cursorPos: Int by mutableIntStateOf(initialValue.length)
    internal var masked: Boolean by mutableStateOf(false)

    internal fun appendChar(ch: Char) {
        text = text.substring(0, cursorPos) + ch + text.substring(cursorPos)
        cursorPos++
    }

    internal fun deleteChar() {
        if (cursorPos > 0) {
            text = text.substring(0, cursorPos - 1) + text.substring(cursorPos)
            cursorPos--
        }
    }

    internal fun deleteForward() {
        if (cursorPos < text.length) {
            text = text.substring(0, cursorPos) + text.substring(cursorPos + 1)
        }
    }

    internal fun moveLeft() {
        if (cursorPos > 0) cursorPos--
    }

    internal fun moveRight() {
        if (cursorPos < text.length) cursorPos++
    }

    internal fun moveHome() {
        cursorPos = 0
    }

    internal fun moveEnd() {
        cursorPos = text.length
    }

    internal fun clear() {
        text = ""
        cursorPos = 0
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
    val inputWidth = (width - 4).coerceAtLeast(1)

    ModalSurface(
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        modifier = Modifier.onKeyEvent { event ->
            handlePromptDialogKey(event, state, onSubmit, onClose)
        },
    ) {
        Column {
            Text(renderOverlayBlankRow(width, theme))
            Text(renderOverlayTitleRow(title, width, theme))
            Text(renderOverlayBlankRow(width, theme))

            if (description != null) {
                val descLines = description.lines()
                for (line in descLines) {
                    Text(
                        buildAnnotatedString {
                            val padded = ("  " + line).take(width).padEnd(width)
                            withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                                append(padded)
                            }
                        },
                    )
                }
                Text(renderOverlayBlankRow(width, theme))
            }

            Text(
                buildAnnotatedString {
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
                        val beforeCursor = shown.substring(0, cursorInRange)
                        val atCursor = if (cursorInRange < shown.length) shown[cursorInRange].toString() else " "
                        val afterCursor = if (cursorInRange < shown.length) shown.substring(cursorInRange + 1) else ""

                        withStyle(SpanStyle(color = textColor, background = theme.overlayBg)) {
                            append(beforeCursor)
                        }
                        withStyle(SpanStyle(color = theme.colors.accentBright, background = theme.overlaySelectedBg)) {
                            append(atCursor)
                        }
                        withStyle(SpanStyle(color = textColor, background = theme.overlayBg)) {
                            append(afterCursor)
                        }

                        val usedWidth = beforeCursor.length + 1 + afterCursor.length
                        val remaining = (inputWidth - usedWidth).coerceAtLeast(0)
                        if (remaining > 0) {
                            withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                                append(" ".repeat(remaining))
                            }
                        }
                    }
                    withStyle(SpanStyle(background = theme.overlayBg)) {
                        append("  ")
                    }
                },
            )

            Text(renderOverlayFooterRow("enter submit  esc cancel", width, theme))
            Text(renderOverlayBlankRow(width, theme))
        }
    }
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
 * An entry in the [OverlayHostState] stack, representing one active overlay.
 */
public sealed interface OverlayEntry {
    public val id: Int
}

@Stable
public class SelectOverlayEntry<T> internal constructor(
    override val id: Int,
    internal val title: String,
    internal val items: List<SelectItem<T>>,
    internal val initialSelectedIndex: Int,
    internal val keepOpenOnSubmit: Boolean,
    internal val onSubmit: (T) -> Unit,
    internal val onClose: () -> Unit,
    internal val onSelectionChanged: ((T) -> Unit)? = null,
) : OverlayEntry {
    internal var dialogState: SelectDialogState<T>? = null
}

@Stable
public class PromptOverlayEntry internal constructor(
    override val id: Int,
    internal val title: String,
    internal val placeholder: String,
    internal val description: String?,
    internal val masked: Boolean,
    internal val onSubmit: (String) -> Unit,
    internal val onClose: () -> Unit,
) : OverlayEntry {
    internal var dialogState: PromptDialogState? = null
}

/**
 * A non-interactive overlay that displays informational text. Used for
 * flows that require the user to wait (e.g. OAuth device code polling).
 * Dismissible only via Escape.
 */
@Stable
public class InfoOverlayEntry internal constructor(
    override val id: Int,
    internal val title: String,
    internal val lines: List<String>,
    internal val onClose: () -> Unit,
) : OverlayEntry

/**
 * A scrollable text overlay for viewing long content such as sub-agent conversations.
 * Supports Ctrl+U/Ctrl+D, PgUp/PgDn, and arrow keys for scrolling.
 */
@Stable
public class ScrollableTextOverlayEntry internal constructor(
    override val id: Int,
    internal val title: String,
    internal val lines: List<String>,
    internal val onClose: () -> Unit,
) : OverlayEntry {
    internal var scrollTop: Int by mutableIntStateOf(0)
}

/**
 * State holder for [OverlayHost]. Manages a stack of overlay entries and provides
 * push/dismiss operations. Use [rememberOverlayHostState] to create an instance.
 */
@Stable
public class OverlayHostState {
    internal val stack: MutableList<OverlayEntry> = mutableStateListOf()
    private var nextId: Int = 0

    /** True when at least one overlay is showing. */
    public val hasOverlay: Boolean
        get() = stack.isNotEmpty()

    /**
     * Pushes a new select overlay onto the stack.
     *
     * @param title Dialog title
     * @param items Items to show in the list
     * @param selectedIndex Initial selected index
     * @param keepOpenOnSubmit If true, the overlay stays open after a submission
     * @param onSubmit Called when the user selects an item
     * @param onClose Called when the user dismisses the overlay
     * @param onSelectionChanged Called when the highlighted selection changes (e.g. arrow navigation)
     */
    public fun <T> push(
        title: String,
        items: List<SelectItem<T>>,
        selectedIndex: Int = 0,
        keepOpenOnSubmit: Boolean = false,
        onSubmit: (T) -> Unit = {},
        onClose: () -> Unit = {},
        onSelectionChanged: ((T) -> Unit)? = null,
    ) {
        stack += SelectOverlayEntry(
            id = nextId++,
            title = title,
            items = items,
            initialSelectedIndex = selectedIndex,
            keepOpenOnSubmit = keepOpenOnSubmit,
            onSubmit = onSubmit,
            onClose = onClose,
            onSelectionChanged = onSelectionChanged,
        )
    }

    /**
     * Pushes a new text prompt overlay onto the stack.
     *
     * @param title Dialog title
     * @param placeholder Placeholder text for the input field
     * @param description Optional descriptive text above the input
     * @param masked If true, input is shown as bullet characters (for passwords/API keys)
     * @param onSubmit Called with the entered text when the user presses Enter
     * @param onClose Called when the user dismisses the overlay
     */
    public fun pushPrompt(
        title: String,
        placeholder: String = "",
        description: String? = null,
        masked: Boolean = false,
        onSubmit: (String) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        stack += PromptOverlayEntry(
            id = nextId++,
            title = title,
            placeholder = placeholder,
            description = description,
            masked = masked,
            onSubmit = onSubmit,
            onClose = onClose,
        )
    }

    /**
     * Pushes a non-interactive info overlay that displays text lines.
     * Dismissible only via Escape.
     *
     * @param title Dialog title
     * @param lines Text lines to display in the dialog body
     * @param onClose Called when the user dismisses the overlay
     */
    public fun pushInfo(
        title: String,
        lines: List<String>,
        onClose: () -> Unit = {},
    ) {
        stack += InfoOverlayEntry(
            id = nextId++,
            title = title,
            lines = lines,
            onClose = onClose,
        )
    }

    /**
     * Pushes a scrollable text overlay for viewing long content.
     * Supports keyboard scrolling (Ctrl+U/D, PgUp/PgDn, arrow keys).
     *
     * @param title Dialog title
     * @param lines Text lines to display in the scrollable body
     * @param onClose Called when the user dismisses the overlay
     */
    public fun pushScrollableText(
        title: String,
        lines: List<String>,
        onClose: () -> Unit = {},
    ) {
        stack += ScrollableTextOverlayEntry(
            id = nextId++,
            title = title,
            lines = lines,
            onClose = onClose,
        )
    }

    /**
     * Removes the topmost overlay from the stack, invoking its onClose callback.
     */
    public fun dismissTop() {
        val entry = stack.removeLastOrNull() ?: return
        when (entry) {
            is SelectOverlayEntry<*> -> entry.onClose()
            is PromptOverlayEntry -> entry.onClose()
            is InfoOverlayEntry -> entry.onClose()
            is ScrollableTextOverlayEntry -> entry.onClose()
        }
    }

    /**
     * Removes the topmost overlay from the stack without invoking its onClose callback.
     */
    public fun dismissTopSilent() {
        stack.removeLastOrNull()
    }

    /**
     * Clears all overlays, invoking each one's onClose callback.
     */
    public fun clear() {
        while (stack.isNotEmpty()) {
            when (val entry = stack.removeLast()) {
                is SelectOverlayEntry<*> -> entry.onClose()
                is PromptOverlayEntry -> entry.onClose()
                is InfoOverlayEntry -> entry.onClose()
                is ScrollableTextOverlayEntry -> entry.onClose()
            }
        }
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
        Column {
            Text(renderOverlayBlankRow(width, theme))
            Text(renderOverlayTitleRow(title, width, theme))
            Text(renderOverlayBlankRow(width, theme))

            for (line in lines) {
                Text(
                    buildAnnotatedString {
                        val padded = ("  " + line).take(width).padEnd(width)
                        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                            append(padded)
                        }
                    },
                )
            }

            Text(renderOverlayFooterRow("esc cancel", width, theme))
            Text(renderOverlayBlankRow(width, theme))
        }
    }
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
    val chrome = 5 // blank + title + blank + footer + blank
    val bodyHeight = (height - chrome).coerceAtLeast(1)
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
        Column {
            Text(renderOverlayBlankRow(width, theme))
            Text(renderOverlayTitleRow(title, width, theme))
            Text(renderOverlayBlankRow(width, theme))

            for (i in 0 until bodyHeight) {
                val lineIndex = safeTop + i
                val lineText = if (lineIndex < lines.size) lines[lineIndex] else ""
                Text(
                    buildAnnotatedString {
                        val padded = ("  " + lineText).take(width).padEnd(width)
                        withStyle(SpanStyle(color = theme.markdownText, background = theme.overlayBg)) {
                            append(padded)
                        }
                    },
                )
            }

            val scrollInfo = if (lines.size > bodyHeight) " (${safeTop + 1}-${min(safeTop + bodyHeight, lines.size)}/${lines.size})" else ""
            Text(renderOverlayFooterRow("↑/↓ scroll  PgUp/PgDn page  esc close$scrollInfo", width, theme))
            Text(renderOverlayBlankRow(width, theme))
        }
    }
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
    val dialogHeight = min(24, max(8, terminalHeight - 4))
    val offsetX = max(0, (terminalWidth - dialogWidth) / 2)
    val offsetY = max(0, (terminalHeight - dialogHeight) / 2)

    when (entry) {
        is SelectOverlayEntry<*> -> {
            @Suppress("UNCHECKED_CAST")
            val typedEntry = entry as SelectOverlayEntry<Any?>

            val dialogState = typedEntry.dialogState ?: SelectDialogState(
                items = typedEntry.items,
                initialSelectedIndex = typedEntry.initialSelectedIndex,
            ).also { typedEntry.dialogState = it }

            SelectDialog(
                title = typedEntry.title,
                state = dialogState,
                width = dialogWidth,
                height = dialogHeight,
                offsetX = offsetX,
                offsetY = offsetY,
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
            val promptHeight = min(10, max(6, terminalHeight - 6))
            val promptOffsetY = max(0, (terminalHeight - promptHeight) / 2)

            val dialogState = entry.dialogState ?: PromptDialogState().also {
                it.masked = entry.masked
                entry.dialogState = it
            }

            PromptDialog(
                title = entry.title,
                state = dialogState,
                width = dialogWidth,
                height = promptHeight,
                offsetX = offsetX,
                offsetY = promptOffsetY,
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
            val infoHeight = min(10, max(6, terminalHeight - 6))
            val infoOffsetY = max(0, (terminalHeight - infoHeight) / 2)

            InfoDialog(
                title = entry.title,
                lines = entry.lines,
                width = dialogWidth,
                height = infoHeight,
                offsetX = offsetX,
                offsetY = infoOffsetY,
                onClose = {
                    state.stack.removeLastOrNull()
                    entry.onClose()
                },
            )
        }

        is ScrollableTextOverlayEntry -> {
            val scrollableHeight = min(terminalHeight - 2, max(12, terminalHeight - 4))
            val scrollableOffsetY = max(0, (terminalHeight - scrollableHeight) / 2)

            ScrollableTextDialog(
                title = entry.title,
                lines = entry.lines,
                scrollTop = entry.scrollTop,
                width = dialogWidth,
                height = scrollableHeight,
                offsetX = offsetX,
                offsetY = scrollableOffsetY,
                onScroll = { newTop -> entry.scrollTop = newTop },
                onClose = {
                    state.stack.removeLastOrNull()
                    entry.onClose()
                },
            )
        }
    }
}
