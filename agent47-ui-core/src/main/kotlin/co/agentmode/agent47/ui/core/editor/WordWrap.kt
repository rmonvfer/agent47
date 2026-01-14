package co.agentmode.agent47.ui.core.editor

/**
 * Mapping between logical editor lines and visual wrapped lines.
 */
public object WordWrap {
    public data class WrappedSegment(
        val text: String,
        val startColumn: Int,
        val endColumn: Int,
    )

    public data class VisualLine(
        val logicalLine: Int,
        val startColumn: Int,
        val endColumn: Int,
        val text: String,
    )

    public data class VisualPosition(
        val row: Int,
        val column: Int,
    )

    public data class Mapping(
        val visualLines: List<VisualLine>,
        val lineStartRows: IntArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Mapping

            if (visualLines != other.visualLines) return false
            if (!lineStartRows.contentEquals(other.lineStartRows)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = visualLines.hashCode()
            result = 31 * result + lineStartRows.contentHashCode()
            return result
        }
    }

    public fun wrapLineWithOffsets(line: String, width: Int): List<WrappedSegment> {
        val safeWidth = width.coerceAtLeast(1)
        val wrapped = wrapPlainLine(line, safeWidth)
        if (wrapped.isEmpty()) {
            return listOf(WrappedSegment(text = "", startColumn = 0, endColumn = 0))
        }

        val segments = mutableListOf<WrappedSegment>()
        var start = 0
        for (chunk in wrapped) {
            val end = (start + chunk.length).coerceAtMost(line.length)
            segments += WrappedSegment(text = chunk, startColumn = start, endColumn = end)
            start = end
        }

        if (segments.isEmpty()) {
            segments += WrappedSegment(text = "", startColumn = 0, endColumn = 0)
        }
        return segments
    }

    public fun createMapping(lines: List<String>, width: Int): Mapping {
        val safeWidth = width.coerceAtLeast(1)
        if (lines.isEmpty()) {
            return Mapping(
                visualLines = listOf(VisualLine(0, 0, 0, "")),
                lineStartRows = intArrayOf(0),
            )
        }

        val visualLines = mutableListOf<VisualLine>()
        val lineStartRows = IntArray(lines.size)
        var row = 0

        for ((lineIndex, lineText) in lines.withIndex()) {
            lineStartRows[lineIndex] = row
            val segments = wrapLineWithOffsets(lineText, safeWidth)
            for (segment in segments) {
                visualLines += VisualLine(
                    logicalLine = lineIndex,
                    startColumn = segment.startColumn,
                    endColumn = segment.endColumn,
                    text = segment.text,
                )
                row += 1
            }
        }

        return Mapping(visualLines = visualLines, lineStartRows = lineStartRows)
    }

    public fun logicalToVisual(mapping: Mapping, position: Position): VisualPosition {
        if (mapping.visualLines.isEmpty()) {
            return VisualPosition(0, 0)
        }

        val safeLine = position.line.coerceIn(0, mapping.lineStartRows.lastIndex)
        val firstRow = mapping.lineStartRows[safeLine]
        var row = firstRow

        while (row < mapping.visualLines.size) {
            val visual = mapping.visualLines[row]
            if (visual.logicalLine != safeLine) {
                break
            }

            val isLastSegmentOfLine = row + 1 >= mapping.visualLines.size ||
                mapping.visualLines[row + 1].logicalLine != safeLine

            if (position.column <= visual.endColumn || isLastSegmentOfLine) {
                val column = (position.column - visual.startColumn)
                    .coerceIn(0, visual.endColumn - visual.startColumn)
                return VisualPosition(row = row, column = column)
            }
            row += 1
        }

        val fallbackRow = firstRow.coerceAtMost(mapping.visualLines.lastIndex)
        val fallback = mapping.visualLines[fallbackRow]
        return VisualPosition(
            row = fallbackRow,
            column = (position.column - fallback.startColumn)
                .coerceIn(0, fallback.endColumn - fallback.startColumn),
        )
    }

    public fun visualToLogical(mapping: Mapping, visualRow: Int, visualColumn: Int): Position {
        if (mapping.visualLines.isEmpty()) {
            return Position(0, 0)
        }

        val safeRow = visualRow.coerceIn(0, mapping.visualLines.lastIndex)
        val visual = mapping.visualLines[safeRow]
        val maxColumn = (visual.endColumn - visual.startColumn).coerceAtLeast(0)
        return Position(
            line = visual.logicalLine,
            column = visual.startColumn + visualColumn.coerceIn(0, maxColumn),
        )
    }

    /**
     * Wraps a single plain-text line into segments of at most [width] characters.
     * No ANSI awareness is needed since editor buffers contain plain text only.
     */
    private fun wrapPlainLine(text: String, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = (start + width).coerceAtMost(text.length)
            result += text.substring(start, end)
            start = end
        }
        return result
    }
}
