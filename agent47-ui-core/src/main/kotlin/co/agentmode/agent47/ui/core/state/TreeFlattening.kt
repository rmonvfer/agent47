package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionTreeNode

/** A vertical continuation line (`│`) drawn at [level] when [show] is true, for a row beneath a branch point. */
public data class TreeGutter(val level: Int, val show: Boolean)

/** One row of the flattened, filtered tree, ready to render. */
public data class TreeRow(
    val node: SessionTreeNode,
    val indent: Int,
    val showConnector: Boolean,
    val isLast: Boolean,
    val gutters: List<TreeGutter>,
    val isVirtualRootChild: Boolean,
    val hasVisibleChildren: Boolean,
) {
    public val id: String get() = node.entry.id
}

/**
 * Result of [flattenSessionTree]: the flattened rows plus whether the visible tree has more than
 * one root, which the renderer needs to reproduce the same indentation math used to build [rows].
 */
public data class TreeFlattenResult(val rows: List<TreeRow>, val multipleRoots: Boolean)

/** The roots that remain once entries failing [passesFilter] are skipped and their children promoted. */
private fun visibleRootsOf(roots: List<SessionTreeNode>, passesFilter: (SessionTreeNode) -> Boolean): List<SessionTreeNode> {
    val result = mutableListOf<SessionTreeNode>()
    fun collect(current: SessionTreeNode) {
        if (passesFilter(current)) result += current else current.children.forEach(::collect)
    }
    roots.forEach(::collect)
    return result
}

/** [node]'s children once entries failing [passesFilter] are skipped and their children promoted; empty when folded. */
private fun visibleChildrenOf(
    node: SessionTreeNode,
    passesFilter: (SessionTreeNode) -> Boolean,
    foldedIds: Set<String>,
): List<SessionTreeNode> {
    if (node.entry.id in foldedIds) return emptyList()
    val result = mutableListOf<SessionTreeNode>()
    fun collect(current: SessionTreeNode) {
        for (child in current.children) {
            if (passesFilter(child)) result += child else collect(child)
        }
    }
    collect(node)
    return result
}

/** The indent and gutters a row's children inherit, per the flatten algorithm's indentation rules. */
private data class ChildLayout(val indent: Int, val gutters: List<TreeGutter>)

/**
 * A row's own [TreeRow.showConnector] is set from its parent's branching exactly when it was laid
 * out, so it doubles as "this row is the first generation after a branch" for its own children:
 * that first generation gets +1 indent for visual grouping, a plain single-child chain stays flat,
 * and a row whose children branch always gets +1 regardless.
 */
private fun childLayoutFor(
    row: TreeRow,
    multipleChildren: Boolean,
    multipleRoots: Boolean,
): ChildLayout {
    val childIndent = when {
        multipleChildren -> row.indent + 1
        row.showConnector && row.indent > 0 -> row.indent + 1
        else -> row.indent
    }
    val connectorDisplayed = row.showConnector && !row.isVirtualRootChild
    val displayIndent = if (multipleRoots) maxOf(0, row.indent - 1) else row.indent
    val connectorPosition = maxOf(0, displayIndent - 1)
    val childGutters = if (connectorDisplayed) row.gutters + TreeGutter(connectorPosition, !row.isLast) else row.gutters
    return ChildLayout(childIndent, childGutters)
}

/**
 * Flattens [roots] into display rows: entries that fail [passesFilter] are skipped and their
 * children re-parented onto the nearest passing ancestor, and descent stops below any id in
 * [foldedIds]. Indentation stays flat along single-child chains and only steps in at branch
 * points, so a long linear conversation doesn't drift into a rightward staircase.
 */
public fun flattenSessionTree(
    roots: List<SessionTreeNode>,
    passesFilter: (SessionTreeNode) -> Boolean,
    foldedIds: Set<String>,
): TreeFlattenResult {
    val visibleRoots = visibleRootsOf(roots, passesFilter)
    val multipleRoots = visibleRoots.size > 1
    val out = mutableListOf<TreeRow>()

    fun walk(node: SessionTreeNode, indent: Int, showConnector: Boolean, isLast: Boolean, gutters: List<TreeGutter>, isVirtualRootChild: Boolean) {
        val children = visibleChildrenOf(node, passesFilter, foldedIds)
        val row = TreeRow(node, indent, showConnector, isLast, gutters, isVirtualRootChild, children.isNotEmpty())
        out += row

        val multipleChildren = children.size > 1
        val layout = childLayoutFor(row, multipleChildren, multipleRoots)
        children.forEachIndexed { index, child ->
            walk(child, layout.indent, multipleChildren, index == children.lastIndex, layout.gutters, false)
        }
    }

    visibleRoots.forEachIndexed { index, root ->
        val isLast = index == visibleRoots.lastIndex
        walk(root, if (multipleRoots) 1 else 0, multipleRoots, isLast, emptyList(), multipleRoots)
    }
    return TreeFlattenResult(out, multipleRoots)
}

/** The row index and id [targetId] should select: itself if visible, else its nearest visible ancestor. */
internal fun nearestVisibleSelection(
    rows: List<TreeRow>,
    targetId: String?,
    entriesById: Map<String, SessionEntry>,
): Pair<Int, String?> {
    var cursor = targetId
    var found: Int? = null
    while (rows.isNotEmpty() && cursor != null && found == null) {
        val index = rows.indexOfFirst { it.id == cursor }
        if (index >= 0) {
            found = index
        } else {
            cursor = entriesById[cursor]?.parentId
        }
    }
    return when {
        rows.isEmpty() -> 0 to targetId
        found != null -> found to cursor
        else -> rows.lastIndex to rows.last().id
    }
}
