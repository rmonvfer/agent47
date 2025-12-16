package co.agentmode.agent47.tui.editor

import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent

import java.nio.file.Path

public data class EditorAutocompleteRenderModel(
    val row: Int,
    val column: Int,
    val trigger: Char,
    val items: List<CompletionItem>,
    val selectedIndex: Int,
)

public data class EditorRenderResult(
    val lines: List<String>,
    val cursorRow: Int,
    val cursorColumn: Int,
    val topMarkerVisible: Boolean,
    val bottomMarkerVisible: Boolean,
    val autocomplete: EditorAutocompleteRenderModel?,
)

/**
 * Multi-line text editor model with cursor movement, undo/redo, and autocomplete.
 */
public class Editor(
    initialText: String = "",
    slashCommands: List<String> = emptyList(),
    slashCommandDetails: Map<String, String> = emptyMap(),
    fileCompletionRoot: Path = Path.of("."),
) {
    public val state: EditorState = EditorState(initialText)

    private val undoStack: UndoStack<EditorSnapshot> = UndoStack(state.snapshot())
    private val killRing: KillRing = KillRing()
    private val autocomplete: AutocompleteManager = AutocompleteManager(
        providers = listOf(
            SlashCommandCompletionProvider(slashCommands, slashCommandDetails),
            FileCompletionProvider(fileCompletionRoot),
        ),
    )

    private val history: MutableList<String> = mutableListOf()
    private var historyIndex: Int? = null
    private var historyDraft: String = ""
    private var preferredColumn: Int? = null
    private var scrollTopVisualLine: Int = 0
    private var lastRenderWidth: Int = 1
    private var lastRenderHeight: Int = 1
    private var previousEditWasKill: Boolean = false

    public fun setText(text: String) {
        state.setText(text)
        undoStack.clearAndReset(state.snapshot())
        resetNavigationState()
        resetEditState()
        preferredColumn = null
        scrollTopVisualLine = 0
        autocomplete.dismiss()
    }

    public fun text(): String = state.text()

    public fun hasAutocompletePopup(): Boolean {
        return autocomplete.popup?.items?.isNotEmpty() == true
    }

    public fun slashCommandPopupItemCount(): Int {
        val popup = autocomplete.popup ?: return 0
        return if (popup.trigger == '/') popup.items.size else 0
    }

    public fun moveCursorToVisual(visibleRow: Int, visibleColumn: Int) {
        val mapping = WordWrap.createMapping(state.lines, lastRenderWidth)
        if (mapping.visualLines.isEmpty()) {
            return
        }

        val targetVisualRow = (scrollTopVisualLine + visibleRow)
            .coerceIn(0, mapping.visualLines.lastIndex)
        val target = WordWrap.visualToLogical(
            mapping = mapping,
            visualRow = targetVisualRow,
            visualColumn = visibleColumn.coerceAtLeast(0),
        )
        state.moveCursorTo(target.line, target.column)
        preferredColumn = null
        resetNavigationState()
        resetEditState()
        updateAutocomplete()
    }

    public fun setHistory(entries: List<String>) {
        history.clear()
        history.addAll(entries)
        resetNavigationState()
    }

    public fun handle(event: KeyboardEvent): Boolean {
        if (handleAutocompleteNavigation(event)) {
            if (event.key != Key.Escape) {
                updateAutocomplete()
            }
            return true
        }

        if (isCtrlChar(event, 'z') && !event.shift) {
            undoStack.undo()?.let { state.restore(it) }
            resetNavigationState()
            resetEditState()
            preferredColumn = null
            updateAutocomplete()
            return true
        }

        if (isCtrlChar(event, 'r') || (isCtrlChar(event, 'z') && event.shift)) {
            undoStack.redo()?.let { state.restore(it) }
            resetNavigationState()
            resetEditState()
            preferredColumn = null
            updateAutocomplete()
            return true
        }

        when {
            isCtrlChar(event, 'a') -> {
                state.moveHome(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                updateAutocomplete()
                return true
            }

            isCtrlChar(event, 'e') -> {
                state.moveEnd(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                updateAutocomplete()
                return true
            }

            isCtrlChar(event, 'k') -> {
                val killed = state.killToEndOfLine()
                if (killed.isNotEmpty()) {
                    killRing.add(killed, append = previousEditWasKill)
                    pushUndo(null)
                }
                previousEditWasKill = true
                preferredColumn = null
                updateAutocomplete()
                return true
            }

            isCtrlChar(event, 'u') -> {
                val killed = state.killToStartOfLine()
                if (killed.isNotEmpty()) {
                    killRing.add(killed, prepend = previousEditWasKill)
                    pushUndo(null)
                }
                previousEditWasKill = true
                preferredColumn = null
                updateAutocomplete()
                return true
            }

            isCtrlChar(event, 'w') -> {
                val killed = state.deleteWordBackward()
                if (killed.isNotEmpty()) {
                    killRing.add(killed, prepend = previousEditWasKill)
                    pushUndo(null)
                }
                previousEditWasKill = true
                preferredColumn = null
                updateAutocomplete()
                return true
            }

            isCtrlChar(event, 'y') -> {
                val yank = killRing.yank().orEmpty()
                if (yank.isNotEmpty()) {
                    state.yank(yank)
                    pushUndo(null)
                    resetEditState()
                    preferredColumn = null
                    updateAutocomplete()
                }
                return true
            }

            event.alt && eventCharacter(event) == 'y' -> {
                val yank = killRing.yankPop().orEmpty()
                if (yank.isNotEmpty()) {
                    state.yank(yank)
                    pushUndo(null)
                    resetEditState()
                    preferredColumn = null
                    updateAutocomplete()
                }
                return true
            }

            event.alt && eventCharacter(event) == 'b' -> {
                state.moveWordLeft(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                updateAutocomplete()
                return true
            }

            event.alt && eventCharacter(event) == 'f' -> {
                state.moveWordRight(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                updateAutocomplete()
                return true
            }

            event.ctrl && (eventCharacter(event) == 'p' || event.key == Key.ArrowUp) -> {
                if (moveHistoryPrevious()) {
                    updateAutocomplete()
                    return true
                }
            }

            event.ctrl && (eventCharacter(event) == 'n' || event.key == Key.ArrowDown) -> {
                if (moveHistoryNext()) {
                    updateAutocomplete()
                    return true
                }
            }
        }

        val handled = when (val key = event.key) {
            is Key.Character -> {
                if (key.value == '\n' || key.value == '\r') {
                    if (!autocomplete.applySelection(state)) {
                        state.insertNewline()
                    }
                    pushUndo(null)
                    resetNavigationState()
                    resetEditState()
                    preferredColumn = null
                    true
                } else if (event.ctrl || event.alt) {
                    false
                } else {
                    state.insertText(key.value.toString())
                    pushUndo("typing")
                    resetNavigationState()
                    resetEditState()
                    preferredColumn = null
                    true
                }
            }

            Key.Enter -> {
                val handled = if (autocomplete.applySelection(state)) {
                    true
                } else if (event.shift || event.alt) {
                    state.insertNewline()
                    true
                } else {
                    false
                }

                if (handled) {
                    pushUndo(null)
                    resetNavigationState()
                    resetEditState()
                    preferredColumn = null
                }
                handled
            }

            Key.Tab -> {
                if (!autocomplete.applySelection(state)) {
                    state.insertText("    ")
                    pushUndo("typing")
                } else {
                    pushUndo(null)
                }
                resetNavigationState()
                resetEditState()
                preferredColumn = null
                true
            }

            Key.Backspace -> {
                if (event.alt) {
                    state.deleteWordBackward()
                } else {
                    state.deleteBackward()
                }
                pushUndo(null)
                resetNavigationState()
                resetEditState()
                preferredColumn = null
                true
            }

            Key.Delete -> {
                if (event.alt) {
                    state.deleteWordForward()
                } else {
                    state.deleteForward()
                }
                pushUndo(null)
                resetNavigationState()
                resetEditState()
                preferredColumn = null
                true
            }

            Key.ArrowLeft -> {
                if (event.ctrl || event.alt) {
                    state.moveWordLeft(selecting = event.shift)
                } else {
                    state.moveLeft(selecting = event.shift)
                }
                preferredColumn = null
                resetEditState()
                true
            }

            Key.ArrowRight -> {
                if (event.ctrl || event.alt) {
                    state.moveWordRight(selecting = event.shift)
                } else {
                    state.moveRight(selecting = event.shift)
                }
                preferredColumn = null
                resetEditState()
                true
            }

            Key.ArrowUp -> {
                val useHistory = state.lineCount == 1 && history.isNotEmpty()
                if (useHistory && moveHistoryPrevious()) {
                    true
                } else {
                    preferredColumn = state.moveUp(selecting = event.shift, preferredColumn = preferredColumn)
                    resetEditState()
                    true
                }
            }

            Key.ArrowDown -> {
                val useHistory = state.lineCount == 1 && history.isNotEmpty()
                if (useHistory && moveHistoryNext()) {
                    true
                } else {
                    preferredColumn = state.moveDown(selecting = event.shift, preferredColumn = preferredColumn)
                    resetEditState()
                    true
                }
            }

            Key.Home -> {
                state.moveHome(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                true
            }

            Key.End -> {
                state.moveEnd(selecting = event.shift)
                preferredColumn = null
                resetEditState()
                true
            }

            Key.PageUp -> {
                moveByVisualRows(
                    deltaRows = -pageJumpRows(),
                    selecting = event.shift,
                )
                preferredColumn = null
                resetEditState()
                true
            }

            Key.PageDown -> {
                moveByVisualRows(
                    deltaRows = pageJumpRows(),
                    selecting = event.shift,
                )
                preferredColumn = null
                resetEditState()
                true
            }

            Key.Escape -> {
                val hadPopup = autocomplete.popup != null
                autocomplete.dismiss()
                resetEditState()
                hadPopup
            }

            is Key.Unknown,
                -> false
        }

        if (handled) {
            updateAutocomplete()
        }
        return handled
    }

    public fun render(width: Int, height: Int): EditorRenderResult {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        lastRenderWidth = safeWidth
        lastRenderHeight = safeHeight
        val mapping = WordWrap.createMapping(state.lines, safeWidth)
        val cursorVisual = WordWrap.logicalToVisual(mapping, state.cursor)

        ensureCursorVisible(cursorVisual.row, safeHeight, mapping.visualLines.size)
        val topMarkerVisible = scrollTopVisualLine > 0
        val bottomMarkerVisible = scrollTopVisualLine + safeHeight < mapping.visualLines.size

        val lines = MutableList(safeHeight) { rowIndex ->
            val visualRow = scrollTopVisualLine + rowIndex
            val text = mapping.visualLines.getOrNull(visualRow)?.text.orEmpty()
            text.padEnd(safeWidth)
        }

        if (topMarkerVisible && lines.isNotEmpty()) {
            lines[0] = withFirstCharacter(lines[0], '^')
        }
        if (bottomMarkerVisible && lines.isNotEmpty()) {
            lines[lines.lastIndex] = withLastCharacter(lines.last(), 'v')
        }

        val cursorRow = (cursorVisual.row - scrollTopVisualLine).coerceIn(0, safeHeight - 1)
        val cursorColumn = cursorVisual.column.coerceIn(0, safeWidth - 1)
        val popupRender = autocomplete.popup?.let { popup ->
            val popupRow = if (cursorRow + 1 < safeHeight) cursorRow + 1 else (cursorRow - 1).coerceAtLeast(0)
            EditorAutocompleteRenderModel(
                row = popupRow,
                column = popup.tokenStart.coerceIn(0, safeWidth - 1),
                trigger = popup.trigger,
                items = popup.items,
                selectedIndex = popup.selectedIndex,
            )
        }

        return EditorRenderResult(
            lines = lines,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            topMarkerVisible = topMarkerVisible,
            bottomMarkerVisible = bottomMarkerVisible,
            autocomplete = popupRender,
        )
    }

    private fun handleAutocompleteNavigation(event: KeyboardEvent): Boolean {
        val popup = autocomplete.popup ?: return false
        if (popup.items.isEmpty()) {
            autocomplete.dismiss()
            return false
        }

        return when (event.key) {
            Key.ArrowUp -> {
                autocomplete.selectPrevious()
                true
            }

            Key.ArrowDown -> {
                autocomplete.selectNext()
                true
            }

            Key.Tab,
            Key.Enter,
                -> {
                val applied = autocomplete.applySelection(state)
                if (applied) {
                    pushUndo(null)
                }
                applied
            }

            Key.Escape -> {
                autocomplete.dismiss()
                true
            }

            else -> false
        }
    }

    private fun pushUndo(coalescingKey: String?) {
        undoStack.push(state.snapshot(), coalescingKey)
    }

    private fun updateAutocomplete() {
        autocomplete.update(state.currentLineText(), state.cursor.column)
    }

    private fun moveHistoryPrevious(): Boolean {
        if (history.isEmpty()) {
            return false
        }

        if (historyIndex == null) {
            historyDraft = state.text()
            historyIndex = history.lastIndex
        } else if (historyIndex!! > 0) {
            historyIndex = historyIndex!! - 1
        }

        val index = historyIndex ?: return false
        state.setText(history[index])
        state.moveCursorTo(state.lineCount - 1, state.lines.lastOrNull()?.length ?: 0)
        preferredColumn = null
        resetEditState()
        autocomplete.dismiss()
        return true
    }

    private fun moveHistoryNext(): Boolean {
        val current = historyIndex ?: return false

        if (current >= history.lastIndex) {
            historyIndex = null
            state.setText(historyDraft)
            state.moveCursorTo(state.lineCount - 1, state.lines.lastOrNull()?.length ?: 0)
            preferredColumn = null
            resetEditState()
            autocomplete.dismiss()
            return true
        }

        historyIndex = current + 1
        state.setText(history[historyIndex!!])
        state.moveCursorTo(state.lineCount - 1, state.lines.lastOrNull()?.length ?: 0)
        preferredColumn = null
        resetEditState()
        autocomplete.dismiss()
        return true
    }

    private fun resetNavigationState() {
        historyIndex = null
        historyDraft = ""
    }

    private fun resetEditState() {
        previousEditWasKill = false
    }

    private fun ensureCursorVisible(cursorVisualRow: Int, height: Int, totalRows: Int) {
        if (cursorVisualRow < scrollTopVisualLine) {
            scrollTopVisualLine = cursorVisualRow
        } else if (cursorVisualRow >= scrollTopVisualLine + height) {
            scrollTopVisualLine = cursorVisualRow - height + 1
        }
        val maxTop = (totalRows - height).coerceAtLeast(0)
        scrollTopVisualLine = scrollTopVisualLine.coerceIn(0, maxTop)
    }

    private fun moveByVisualRows(deltaRows: Int, selecting: Boolean) {
        val mapping = WordWrap.createMapping(state.lines, lastRenderWidth)
        val cursorVisual = WordWrap.logicalToVisual(mapping, state.cursor)
        val targetRow = (cursorVisual.row + deltaRows).coerceIn(0, mapping.visualLines.lastIndex)
        val target = WordWrap.visualToLogical(mapping, targetRow, cursorVisual.column)
        state.moveCursorTo(target.line, target.column, keepSelection = selecting)
        ensureCursorVisible(targetRow, lastRenderHeight, mapping.visualLines.size)
    }

    private fun pageJumpRows(): Int {
        return (lastRenderHeight - 1).coerceAtLeast(1)
    }

    private fun eventCharacter(event: KeyboardEvent): Char? {
        return (event.key as? Key.Character)?.value?.lowercaseChar()
    }

    private fun isCtrlChar(event: KeyboardEvent, expected: Char): Boolean {
        return event.ctrl && eventCharacter(event) == expected
    }

    private fun withFirstCharacter(text: String, char: Char): String {
        if (text.isEmpty()) {
            return char.toString()
        }
        return buildString(text.length) {
            append(char)
            append(text.substring(1))
        }
    }

    private fun withLastCharacter(text: String, char: Char): String {
        if (text.isEmpty()) {
            return char.toString()
        }
        return buildString(text.length) {
            append(text.substring(0, text.length - 1))
            append(char)
        }
    }
}
