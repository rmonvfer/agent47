package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionTreeNode

/**
 * Drives the /tree overlay: flattening, search, filter cycling, and fold/unfold, over a single
 * [SessionManager]'s tree. Pure state (no Compose or theme dependency) so it's unit testable; the
 * renderer reads [rows] and [selectedIndex] and maps [TreeTextRole] to theme colors.
 */
public class TreeSelectorState(session: SessionManager, initialSelectedId: String? = null) {
    private val tree: List<SessionTreeNode> = session.getTree()
    private val entriesById: Map<String, SessionEntry> = session.getEntries().associateBy { it.id }
    private val foldedIds: MutableSet<String> = mutableSetOf()

    /** Every tool call in the session, keyed by call id, for [buildEntryDisplaySegments] to resolve tool-result rows. */
    public val toolCalls: Map<String, ToolCallRef> = buildToolCallMap(session.getEntries())

    public val currentLeafId: String? = session.getLeafId()
    public val activePathIds: Set<String> = run {
        val result = mutableSetOf<String>()
        var cursor = currentLeafId
        while (cursor != null) {
            result += cursor
            cursor = entriesById[cursor]?.parentId
        }
        result
    }

    public var filterMode: TreeFilterMode = TreeFilterMode.DEFAULT
        private set
    public var searchQuery: String = ""
        private set
    public var rows: List<TreeRow> = emptyList()
        private set
    public var multipleRoots: Boolean = false
        private set
    public var selectedIndex: Int = 0
        private set
    private var selectedId: String? = initialSelectedId ?: currentLeafId

    init {
        recompute()
    }

    public fun selectedRow(): TreeRow? = rows.getOrNull(selectedIndex)

    public fun isFolded(id: String): Boolean = id in foldedIds

    public fun moveUp() {
        if (rows.isEmpty()) return
        selectedIndex = if (selectedIndex <= 0) rows.lastIndex else selectedIndex - 1
        selectedId = rows[selectedIndex].id
    }

    public fun moveDown() {
        if (rows.isEmpty()) return
        selectedIndex = if (selectedIndex >= rows.lastIndex) 0 else selectedIndex + 1
        selectedId = rows[selectedIndex].id
    }

    public fun appendSearchChar(ch: Char) {
        searchQuery += ch
        foldedIds.clear()
        recompute()
    }

    /** Backspaces the search query; returns false when there was nothing to delete. */
    public fun backspaceSearch(): Boolean {
        if (searchQuery.isEmpty()) return false
        searchQuery = searchQuery.dropLast(1)
        foldedIds.clear()
        recompute()
        return true
    }

    /** Clears the search query; returns false when it was already empty. */
    public fun clearSearch(): Boolean {
        if (searchQuery.isEmpty()) return false
        searchQuery = ""
        foldedIds.clear()
        recompute()
        return true
    }

    public fun cycleFilter() {
        filterMode = filterMode.next()
        foldedIds.clear()
        recompute()
    }

    /** Folds the selected row if it has visible children and isn't already folded; else moves to its parent. */
    public fun foldOrMoveToParent() {
        val row = selectedRow() ?: return
        if (row.hasVisibleChildren && row.id !in foldedIds) {
            foldedIds += row.id
            recompute()
            return
        }
        val (index, id) = nearestVisibleSelection(rows, row.node.entry.parentId, entriesById)
        selectedIndex = index
        selectedId = id
    }

    /** Unfolds the selected row if folded; else moves to its first visible child (the next row). */
    public fun unfoldOrMoveToChild() {
        val row = selectedRow() ?: return
        when {
            row.id in foldedIds -> {
                foldedIds -= row.id
                recompute()
            }
            row.hasVisibleChildren -> {
                val next = rows.getOrNull(selectedIndex + 1)
                if (next != null) {
                    selectedIndex += 1
                    selectedId = next.id
                }
            }
        }
    }

    private fun recompute() {
        val flattened = flattenSessionTree(tree, { node -> nodePasses(node, currentLeafId, filterMode, searchQuery) }, foldedIds)
        rows = flattened.rows
        multipleRoots = flattened.multipleRoots
        val (index, id) = nearestVisibleSelection(rows, selectedId, entriesById)
        selectedIndex = index
        selectedId = id
    }
}
