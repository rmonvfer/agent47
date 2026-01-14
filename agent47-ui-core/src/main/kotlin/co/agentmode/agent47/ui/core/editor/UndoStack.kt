package co.agentmode.agent47.ui.core.editor

/**
 * Undo/redo stack with an optional coalescing key.
 * Sequential pushes with the same key replace the latest undo entry.
 */
public class UndoStack<T>(
    initialState: T,
    private val maxEntries: Int = 200,
) {
    private data class Entry<T>(
        val state: T,
        val coalescingKey: String?,
    )

    private val undoEntries: MutableList<Entry<T>> = mutableListOf(Entry(initialState, null))
    private val redoEntries: ArrayDeque<Entry<T>> = ArrayDeque()

    public fun push(state: T, coalescingKey: String? = null) {
        val last = undoEntries.lastOrNull()
        if (coalescingKey != null && last?.coalescingKey == coalescingKey) {
            undoEntries[undoEntries.lastIndex] = Entry(state, coalescingKey)
        } else {
            undoEntries += Entry(state, coalescingKey)
            trimIfNeeded()
        }
        redoEntries.clear()
    }

    public fun undo(): T? {
        if (undoEntries.size <= 1) {
            return null
        }

        val current = undoEntries.removeAt(undoEntries.lastIndex)
        redoEntries.addFirst(current)
        return undoEntries.last().state
    }

    public fun redo(): T? {
        val restored = redoEntries.removeFirstOrNull() ?: return null
        undoEntries += restored
        trimIfNeeded()
        return restored.state
    }

    public fun clearAndReset(initialState: T) {
        undoEntries.clear()
        redoEntries.clear()
        undoEntries += Entry(initialState, null)
    }

    private fun trimIfNeeded() {
        if (undoEntries.size <= maxEntries) {
            return
        }

        val toRemove = undoEntries.size - maxEntries
        repeat(toRemove) {
            undoEntries.removeAt(0)
        }
    }
}
