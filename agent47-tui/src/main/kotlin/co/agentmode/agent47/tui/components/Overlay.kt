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
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Data class for items in a [SelectDialog].
 */
public data class SelectItem<T>(
    val label: String,
    val value: T,
)

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
        val q = query.trim()
        return items.indices.filter { idx ->
            items[idx].label.contains(q, ignoreCase = true)
        }
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
 * Modal chrome composable that renders a bordered dialog with a header, optional filter row,
 * scrollable body content, and optional footer. Positioned absolutely at the given offset.
 *
 * @param title Text shown in the header bar
 * @param width Total width of the dialog in columns
 * @param height Total height of the dialog in rows
 * @param offsetX Column offset from the left edge of the terminal
 * @param offsetY Row offset from the top of the terminal
 * @param filterText Optional filter text to show in the filter row (null hides the row)
 * @param footerText Optional text for the footer bar (null hides it)
 * @param modifier Modifier applied to the root Box
 * @param body Composable slot for the dialog body content
 */
@Composable
public fun ModalSurface(
    title: String,
    width: Int,
    height: Int,
    offsetX: Int,
    offsetY: Int,
    filterText: String? = null,
    footerText: String? = null,
    modifier: Modifier = Modifier,
    body: @Composable () -> Unit,
) {
    val theme = LocalThemeConfig.current
    val borderColor = theme.colors.dim
    val bgColor = theme.overlayBg
    val titleText = " $title "
    val footerLabel = if (footerText != null) " $footerText " else null

    Box(
        modifier = modifier
            .offset(x = offsetX, y = offsetY)
            .width(width)
            .height(height)
            .drawBehind {
                drawRect(
                    background = bgColor,
                    drawStyle = DrawStyle.Fill,
                )
                drawRect(
                    background = borderColor,
                    drawStyle = DrawStyle.Stroke(1),
                )
                // Overlay the title onto the top border row, starting at column 2
                if (titleText.length <= this.width - 2) {
                    drawText(
                        row = 0,
                        column = 2,
                        string = titleText,
                        foreground = theme.colors.accentBright,
                        background = bgColor,
                        textStyle = TextStyle.Bold,
                    )
                }
                // Overlay the footer onto the bottom border row, starting at column 2
                if (footerLabel != null && footerLabel.length <= this.width - 2) {
                    drawText(
                        row = this.height - 1,
                        column = 2,
                        string = footerLabel,
                        foreground = theme.colors.muted,
                        background = bgColor,
                    )
                }
            }
            .padding(1),
    ) {
        Column {
            // Filter row inside the border
            if (filterText != null) {
                Text(
                    buildAnnotatedString {
                        val padded = filterText.take(width - 2).padEnd(width - 2)
                        withStyle(SpanStyle(color = theme.colors.muted, background = bgColor)) {
                            append(padded)
                        }
                    },
                )
            }

            // Body fills remaining space
            body()
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

    // Inner area after border padding: (height - 2) rows, (width - 2) columns.
    // The filter row takes 1 inner row, leaving the rest for items.
    val filterHeight = 1
    val innerHeight = (height - 2).coerceAtLeast(1)
    val bodyHeight = (innerHeight - filterHeight).coerceAtLeast(1)
    val innerWidth = (width - 2).coerceAtLeast(1)

    val visibleIndices = state.filteredIndices()
    val scrollTop = state.scrollTopFor(bodyHeight)

    ModalSurface(
        title = title,
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        filterText = "filter: ${state.query}",
        footerText = "Arrows move | Enter select | Esc close",
        modifier = Modifier.onKeyEvent { event ->
            handleSelectDialogKey(event, state, onSubmit, onClose, onSelectionChanged)
        },
    ) {
        Column {
            for (i in 0 until bodyHeight) {
                val visibleIndex = scrollTop + i
                if (visibleIndex < visibleIndices.size) {
                    val optionIndex = visibleIndices[visibleIndex]
                    val selected = optionIndex == state.selectedIndex
                    val marker = if (selected) "> " else "  "
                    val label = marker + state.items[optionIndex].label
                    Text(renderSelectLine(label, selected, innerWidth, theme))
                } else if (visibleIndices.isEmpty() && i == 0) {
                    Text(renderSelectLine("  (no matches)", false, innerWidth, theme))
                } else {
                    Text(renderSelectLine("", false, innerWidth, theme))
                }
            }
        }
    }
}

private fun renderSelectLine(
    text: String,
    selected: Boolean,
    innerWidth: Int,
    theme: ThemeConfig,
) = buildAnnotatedString {
    val padded = text.take(innerWidth).padEnd(innerWidth)
    if (selected) {
        withStyle(
            SpanStyle(
                color = theme.colors.accentBright,
                background = theme.overlaySelectedBg,
            ),
        ) {
            append(padded)
        }
    } else {
        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
            append(padded)
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
    val innerWidth = (width - 2).coerceAtLeast(1)

    ModalSurface(
        title = title,
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        footerText = "Enter submit | Esc cancel",
        modifier = Modifier.onKeyEvent { event ->
            handlePromptDialogKey(event, state, onSubmit, onClose)
        },
    ) {
        Column {
            if (description != null) {
                val descLines = description.lines()
                for (line in descLines) {
                    Text(
                        buildAnnotatedString {
                            val padded = line.take(innerWidth).padEnd(innerWidth)
                            withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                                append(padded)
                            }
                        },
                    )
                }
                // Blank separator line
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                            append(" ".repeat(innerWidth))
                        }
                    },
                )
            }

            // Render the text input field
            val displayText = if (state.text.isEmpty()) {
                placeholder
            } else if (state.masked) {
                "•".repeat(state.text.length)
            } else {
                state.text
            }
            val isEmpty = state.text.isEmpty()
            val textColor = if (isEmpty) theme.colors.muted else theme.colors.accentBright

            Text(
                buildAnnotatedString {
                    // Build the visible text with a cursor indicator
                    val cursorInRange = state.cursorPos.coerceIn(0, state.text.length)
                    if (isEmpty) {
                        // Show placeholder with cursor at start
                        withStyle(SpanStyle(color = theme.colors.accentBright, background = theme.overlaySelectedBg)) {
                            append(if (placeholder.isNotEmpty()) placeholder.first().toString() else " ")
                        }
                        val rest = if (placeholder.length > 1) placeholder.substring(1) else ""
                        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                            append(rest.take(innerWidth - 1).padEnd(innerWidth - 1))
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
                        val remaining = (innerWidth - usedWidth).coerceAtLeast(0)
                        if (remaining > 0) {
                            withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                                append(" ".repeat(remaining))
                            }
                        }
                    }
                },
            )
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
     * Removes the topmost overlay from the stack, invoking its onClose callback.
     */
    public fun dismissTop() {
        val entry = stack.removeLastOrNull() ?: return
        when (entry) {
            is SelectOverlayEntry<*> -> entry.onClose()
            is PromptOverlayEntry -> entry.onClose()
            is InfoOverlayEntry -> entry.onClose()
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
    val innerWidth = (width - 2).coerceAtLeast(1)

    ModalSurface(
        title = title,
        width = width,
        height = height,
        offsetX = offsetX,
        offsetY = offsetY,
        footerText = "Esc cancel",
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
            for (line in lines) {
                Text(
                    buildAnnotatedString {
                        val padded = line.take(innerWidth).padEnd(innerWidth)
                        withStyle(SpanStyle(color = theme.colors.muted, background = theme.overlayBg)) {
                            append(padded)
                        }
                    },
                )
            }
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
    val dialogHeight = min(16, max(8, terminalHeight - 6))
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
    }
}
