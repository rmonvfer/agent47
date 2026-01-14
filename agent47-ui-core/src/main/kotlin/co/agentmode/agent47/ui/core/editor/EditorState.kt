package co.agentmode.agent47.ui.core.editor

/**
 * Zero-based cursor position.
 */
public data class Position(
    val line: Int,
    val column: Int,
)

/**
 * Text selection defined by anchor and caret positions.
 */
public data class Selection(
    val anchor: Position,
    val caret: Position,
) {
    public fun isCollapsed(): Boolean = anchor == caret

    public fun ordered(): Pair<Position, Position> {
        return if (
            anchor.line < caret.line ||
            (anchor.line == caret.line && anchor.column <= caret.column)
        ) {
            anchor to caret
        } else {
            caret to anchor
        }
    }
}

/**
 * Immutable snapshot used by undo/redo.
 */
public data class EditorSnapshot(
    val lines: List<String>,
    val cursor: Position,
    val selection: Selection?,
)

/**
 * Mutable editor text model.
 */
public class EditorState(initialText: String = "") {
    private val mutableLines: MutableList<String> = mutableListOf()

    public var cursor: Position = Position(line = 0, column = 0)
        private set

    public var selection: Selection? = null
        private set

    init {
        setText(initialText)
    }

    public val lines: List<String>
        get() = mutableLines

    public val lineCount: Int
        get() = mutableLines.size

    public fun currentLineText(): String = mutableLines[cursor.line]

    public fun text(): String = mutableLines.joinToString("\n")

    public fun setText(text: String) {
        mutableLines.clear()
        mutableLines.addAll(text.split('\n'))
        if (mutableLines.isEmpty()) {
            mutableLines += ""
        }
        cursor = Position(0, 0)
        clearSelection()
        clampCursor()
    }

    public fun snapshot(): EditorSnapshot {
        return EditorSnapshot(
            lines = mutableLines.toList(),
            cursor = cursor,
            selection = selection,
        )
    }

    public fun restore(snapshot: EditorSnapshot) {
        mutableLines.clear()
        mutableLines.addAll(snapshot.lines)
        if (mutableLines.isEmpty()) {
            mutableLines += ""
        }
        cursor = snapshot.cursor
        selection = snapshot.selection
        clampCursor()
        selection = selection?.let { normalizeSelection(it) }
    }

    public fun clearSelection() {
        selection = null
    }

    public fun setSelectionAnchor() {
        if (selection == null) {
            selection = Selection(anchor = cursor, caret = cursor)
        }
    }

    public fun updateSelectionToCursor() {
        val existing = selection ?: Selection(anchor = cursor, caret = cursor)
        selection = normalizeSelection(existing.copy(caret = cursor))
    }

    public fun moveCursorTo(line: Int, column: Int, keepSelection: Boolean = false) {
        cursor = Position(
            line = line.coerceIn(0, mutableLines.lastIndex),
            column = column.coerceAtLeast(0),
        )
        clampCursor()

        if (keepSelection) {
            updateSelectionToCursor()
        } else {
            clearSelection()
        }
    }

    public fun moveLeft(selecting: Boolean = false) {
        if (!selecting && hasSelection()) {
            val (start, _) = selection!!.ordered()
            moveCursorTo(start.line, start.column, keepSelection = false)
            return
        }

        val next = when {
            cursor.column > 0 -> cursor.copy(column = cursor.column - 1)
            cursor.line > 0 -> Position(cursor.line - 1, mutableLines[cursor.line - 1].length)
            else -> cursor
        }
        moveWithOptionalSelection(next, selecting)
    }

    public fun moveRight(selecting: Boolean = false) {
        if (!selecting && hasSelection()) {
            val (_, end) = selection!!.ordered()
            moveCursorTo(end.line, end.column, keepSelection = false)
            return
        }

        val line = mutableLines[cursor.line]
        val next = when {
            cursor.column < line.length -> cursor.copy(column = cursor.column + 1)
            cursor.line < mutableLines.lastIndex -> Position(cursor.line + 1, 0)
            else -> cursor
        }
        moveWithOptionalSelection(next, selecting)
    }

    public fun moveUp(selecting: Boolean = false, preferredColumn: Int? = null): Int {
        val targetColumn = preferredColumn ?: cursor.column
        val nextLine = (cursor.line - 1).coerceAtLeast(0)
        val nextColumn = targetColumn.coerceAtMost(mutableLines[nextLine].length)
        moveWithOptionalSelection(Position(nextLine, nextColumn), selecting)
        return targetColumn
    }

    public fun moveDown(selecting: Boolean = false, preferredColumn: Int? = null): Int {
        val targetColumn = preferredColumn ?: cursor.column
        val nextLine = (cursor.line + 1).coerceAtMost(mutableLines.lastIndex)
        val nextColumn = targetColumn.coerceAtMost(mutableLines[nextLine].length)
        moveWithOptionalSelection(Position(nextLine, nextColumn), selecting)
        return targetColumn
    }

    public fun moveHome(selecting: Boolean = false) {
        moveWithOptionalSelection(cursor.copy(column = 0), selecting)
    }

    public fun moveEnd(selecting: Boolean = false) {
        moveWithOptionalSelection(cursor.copy(column = currentLineText().length), selecting)
    }

    public fun moveWordLeft(selecting: Boolean = false) {
        var line = cursor.line
        var column = cursor.column
        while (true) {
            if (column == 0) {
                if (line == 0) {
                    break
                }
                line -= 1
                column = mutableLines[line].length
                if (column == 0) {
                    continue
                }
            }

            val text = mutableLines[line]
            var i = column - 1
            while (i >= 0 && text[i].isWhitespace()) {
                i--
            }
            if (i < 0) {
                column = 0
                break
            }
            while (i >= 0 && isWordChar(text[i])) {
                i--
            }
            column = i + 1
            break
        }
        moveWithOptionalSelection(Position(line, column), selecting)
    }

    public fun moveWordRight(selecting: Boolean = false) {
        var line = cursor.line
        var column = cursor.column
        while (true) {
            val text = mutableLines[line]
            if (column >= text.length) {
                if (line >= mutableLines.lastIndex) {
                    break
                }
                line += 1
                column = 0
                continue
            }

            var i = column
            while (i < text.length && text[i].isWhitespace()) {
                i++
            }
            while (i < text.length && isWordChar(text[i])) {
                i++
            }
            column = i
            break
        }
        moveWithOptionalSelection(Position(line, column), selecting)
    }

    public fun insertText(text: String) {
        if (text.isEmpty()) return
        deleteSelectionIfAny()

        val current = mutableLines[cursor.line]
        val before = current.substring(0, cursor.column)
        val after = current.substring(cursor.column)
        val split = text.split('\n')

        if (split.size == 1) {
            mutableLines[cursor.line] = before + text + after
            cursor = cursor.copy(column = cursor.column + text.length)
            return
        }

        mutableLines[cursor.line] = before + split.first()
        val middle = split.subList(1, split.lastIndex)
        for ((idx, value) in middle.withIndex()) {
            mutableLines.add(cursor.line + 1 + idx, value)
        }
        val last = split.last() + after
        mutableLines.add(cursor.line + split.lastIndex, last)

        cursor = Position(
            line = cursor.line + split.lastIndex,
            column = split.last().length,
        )
    }

    public fun insertNewline() {
        insertText("\n")
    }

    public fun deleteBackward(): String {
        deleteSelectionIfAny()?.let { return it }

        if (cursor.column > 0) {
            val line = mutableLines[cursor.line]
            val deleted = line[cursor.column - 1].toString()
            mutableLines[cursor.line] = line.removeRange(cursor.column - 1, cursor.column)
            cursor = cursor.copy(column = cursor.column - 1)
            return deleted
        }

        if (cursor.line > 0) {
            val previous = mutableLines[cursor.line - 1]
            val current = mutableLines[cursor.line]
            mutableLines[cursor.line - 1] = previous + current
            mutableLines.removeAt(cursor.line)
            cursor = Position(cursor.line - 1, previous.length)
            return "\n"
        }

        return ""
    }

    public fun deleteForward(): String {
        deleteSelectionIfAny()?.let { return it }

        val line = mutableLines[cursor.line]
        if (cursor.column < line.length) {
            val deleted = line[cursor.column].toString()
            mutableLines[cursor.line] = line.removeRange(cursor.column, cursor.column + 1)
            return deleted
        }

        if (cursor.line < mutableLines.lastIndex) {
            val next = mutableLines[cursor.line + 1]
            mutableLines[cursor.line] = line + next
            mutableLines.removeAt(cursor.line + 1)
            return "\n"
        }

        return ""
    }

    public fun deleteWordBackward(): String {
        if (hasSelection()) {
            return deleteSelectionIfAny().orEmpty()
        }

        val end = cursor
        moveWordLeft(selecting = false)
        val start = cursor
        return deleteRange(start, end)
    }

    public fun deleteWordForward(): String {
        if (hasSelection()) {
            return deleteSelectionIfAny().orEmpty()
        }

        val start = cursor
        moveWordRight(selecting = false)
        val end = cursor
        cursor = start
        return deleteRange(start, end)
    }

    public fun killToEndOfLine(): String {
        deleteSelectionIfAny()?.let { return it }

        val line = mutableLines[cursor.line]
        if (cursor.column < line.length) {
            val killed = line.substring(cursor.column)
            mutableLines[cursor.line] = line.substring(0, cursor.column)
            return killed
        }

        if (cursor.line < mutableLines.lastIndex) {
            val next = mutableLines.removeAt(cursor.line + 1)
            mutableLines[cursor.line] = line + next
            return "\n"
        }

        return ""
    }

    public fun killToStartOfLine(): String {
        deleteSelectionIfAny()?.let { return it }
        val line = mutableLines[cursor.line]
        if (cursor.column == 0) return ""

        val killed = line.substring(0, cursor.column)
        mutableLines[cursor.line] = line.substring(cursor.column)
        cursor = cursor.copy(column = 0)
        return killed
    }

    public fun yank(text: String) {
        insertText(text)
    }

    public fun replaceInCurrentLine(startColumn: Int, endColumn: Int, replacement: String) {
        val line = mutableLines[cursor.line]
        val safeStart = startColumn.coerceIn(0, line.length)
        val safeEnd = endColumn.coerceIn(safeStart, line.length)
        mutableLines[cursor.line] = line.substring(0, safeStart) + replacement + line.substring(safeEnd)
        cursor = cursor.copy(column = safeStart + replacement.length)
        clearSelection()
    }

    public fun hasSelection(): Boolean = selection?.isCollapsed() == false

    private fun moveWithOptionalSelection(position: Position, selecting: Boolean) {
        if (selecting) {
            setSelectionAnchor()
        }
        cursor = position
        clampCursor()
        if (selecting) {
            updateSelectionToCursor()
        } else {
            clearSelection()
        }
    }

    private fun deleteSelectionIfAny(): String? {
        val current = selection ?: return null
        if (current.isCollapsed()) {
            clearSelection()
            return null
        }

        val (start, end) = current.ordered()
        return deleteRange(start, end)
    }

    private fun deleteRange(start: Position, end: Position): String {
        if (start == end) return ""

        val deleted = if (start.line == end.line) {
            val line = mutableLines[start.line]
            val removed = line.substring(start.column, end.column)
            mutableLines[start.line] = line.removeRange(start.column, end.column)
            removed
        } else {
            val firstLine = mutableLines[start.line]
            val lastLine = mutableLines[end.line]

            val deletedParts = mutableListOf<String>()
            deletedParts += firstLine.substring(start.column)
            for (lineIndex in (start.line + 1) until end.line) {
                deletedParts += mutableLines[lineIndex]
            }
            deletedParts += lastLine.substring(0, end.column)

            mutableLines[start.line] = firstLine.substring(0, start.column) + lastLine.substring(end.column)
            repeat(end.line - start.line) {
                mutableLines.removeAt(start.line + 1)
            }

            deletedParts.joinToString("\n")
        }

        cursor = start
        clearSelection()
        clampCursor()
        return deleted
    }

    private fun normalizeSelection(value: Selection): Selection {
        val anchor = clampPosition(value.anchor)
        val caret = clampPosition(value.caret)
        return Selection(anchor = anchor, caret = caret)
    }

    private fun clampPosition(position: Position): Position {
        val safeLine = position.line.coerceIn(0, mutableLines.lastIndex)
        val safeColumn = position.column.coerceIn(0, mutableLines[safeLine].length)
        return Position(safeLine, safeColumn)
    }

    private fun clampCursor() {
        cursor = clampPosition(cursor)
    }

    private fun isWordChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char == '_' || char == '-'
    }
}
