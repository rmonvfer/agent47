package co.agentmode.agent47.ui.core.state

import androidx.compose.runtime.*
import co.agentmode.agent47.coding.core.session.SessionManager

/**
 * Data class for items in a select dialog.
 */
public data class SelectItem<T>(
    val label: String,
    val value: T,
    val rightLabel: String? = null,
)

/**
 * State holder for a select dialog that manages selection, filtering, and scroll position.
 */
@Stable
public class SelectDialogState<T>(
    public val items: List<SelectItem<T>>,
    initialSelectedIndex: Int = 0,
) {
    public var selectedIndex: Int by mutableIntStateOf(
        initialSelectedIndex.coerceIn(0, (items.lastIndex).coerceAtLeast(0)),
    )
    public var query: String by mutableStateOf("")

    public fun filteredIndices(): List<Int> {
        if (query.isBlank()) return items.indices.toList()
        val results = items.indices.mapNotNull { idx ->
            co.agentmode.agent47.ui.core.util.fuzzyMatch(items[idx].label, query)?.copy(index = idx)
        }
        return results.sortedBy { it.score }.map { it.index }
    }

    public fun matchedPositions(itemIndex: Int): List<Int> {
        if (query.isBlank()) return emptyList()
        return co.agentmode.agent47.ui.core.util.fuzzyMatch(items[itemIndex].label, query)?.matchedPositions ?: emptyList()
    }

    public fun moveUp() {
        val visible = filteredIndices()
        if (visible.isEmpty()) return
        val currentPos = visible.indexOf(selectedIndex)
        selectedIndex = if (currentPos <= 0) {
            visible.last()
        } else {
            visible[currentPos - 1]
        }
    }

    public fun moveDown() {
        val visible = filteredIndices()
        if (visible.isEmpty()) return
        val currentPos = visible.indexOf(selectedIndex)
        selectedIndex = if (currentPos < 0 || currentPos >= visible.lastIndex) {
            visible.first()
        } else {
            visible[currentPos + 1]
        }
    }

    public fun appendChar(ch: Char) {
        query += ch
        val refreshed = filteredIndices()
        if (refreshed.isNotEmpty() && selectedIndex !in refreshed) {
            selectedIndex = refreshed.first()
        }
    }

    public fun deleteChar() {
        if (query.isNotEmpty()) {
            query = query.dropLast(1)
            val refreshed = filteredIndices()
            if (refreshed.isNotEmpty() && selectedIndex !in refreshed) {
                selectedIndex = refreshed.first()
            }
        }
    }

    public fun clearFilter() {
        query = ""
        val refreshed = filteredIndices()
        if (refreshed.isNotEmpty()) {
            selectedIndex = refreshed.first()
        }
    }

    public fun selectedValue(): T? {
        val visible = filteredIndices()
        if (visible.isEmpty()) return null
        val idx = if (selectedIndex in visible) selectedIndex else visible.first()
        return items[idx].value
    }

    public fun scrollTopFor(listHeight: Int): Int {
        if (listHeight <= 0) return 0
        val visible = filteredIndices()
        val pos = visible.indexOf(selectedIndex).takeIf { it >= 0 } ?: 0
        return (pos - listHeight / 2).coerceIn(0, (visible.size - listHeight).coerceAtLeast(0))
    }
}

/**
 * Sealed interface representing the result of an overlay interaction.
 */
public sealed interface OverlayResult<out T> {
    public data object Dismissed : OverlayResult<Nothing>
    public data class Selected<T>(val value: T) : OverlayResult<T>
}

/**
 * State holder for a prompt dialog that manages the text input field.
 */
@Stable
public class PromptDialogState(
    initialValue: String = "",
) {
    public var text: String by mutableStateOf(initialValue)
    public var cursorPos: Int by mutableIntStateOf(initialValue.length)
    public var masked: Boolean by mutableStateOf(false)

    public fun appendChar(ch: Char) {
        text = text.substring(0, cursorPos) + ch + text.substring(cursorPos)
        cursorPos++
    }

    public fun deleteChar() {
        if (cursorPos > 0) {
            text = text.substring(0, cursorPos - 1) + text.substring(cursorPos)
            cursorPos--
        }
    }

    public fun deleteForward() {
        if (cursorPos < text.length) {
            text = text.substring(0, cursorPos) + text.substring(cursorPos + 1)
        }
    }

    public fun moveLeft() {
        if (cursorPos > 0) cursorPos--
    }

    public fun moveRight() {
        if (cursorPos < text.length) cursorPos++
    }

    public fun moveHome() {
        cursorPos = 0
    }

    public fun moveEnd() {
        cursorPos = text.length
    }

    public fun clear() {
        text = ""
        cursorPos = 0
    }
}

/**
 * An entry in the [OverlayHostState] stack, representing one active overlay.
 */
public sealed interface OverlayEntry {
    public val id: Int
}

@Stable
public class SelectOverlayEntry<T>(
    override val id: Int,
    public val title: String,
    public val items: List<SelectItem<T>>,
    public val initialSelectedIndex: Int,
    public val keepOpenOnSubmit: Boolean,
    public val onSubmit: (T) -> Unit,
    public val onClose: () -> Unit,
    public val onSelectionChanged: ((T) -> Unit)? = null,
) : OverlayEntry {
    public var dialogState: SelectDialogState<T>? = null
}

@Stable
public class PromptOverlayEntry(
    override val id: Int,
    public val title: String,
    public val placeholder: String,
    public val initialValue: String,
    public val description: String?,
    public val masked: Boolean,
    public val onSubmit: (String) -> Unit,
    public val onClose: () -> Unit,
) : OverlayEntry {
    public var dialogState: PromptDialogState? = null
}

/**
 * A non-interactive overlay that displays informational text. Used for
 * flows that require the user to wait (e.g. OAuth device code polling).
 * Dismissible only via Escape.
 */
@Stable
public class InfoOverlayEntry(
    override val id: Int,
    public val title: String,
    public val lines: List<String>,
    public val onClose: () -> Unit,
) : OverlayEntry

/**
 * A scrollable text overlay for viewing long content such as sub-agent conversations.
 * Supports Ctrl+U/Ctrl+D, PgUp/PgDn, and arrow keys for scrolling.
 */
@Stable
public class ScrollableTextOverlayEntry(
    override val id: Int,
    public val title: String,
    public val lines: List<String>,
    public val onClose: () -> Unit,
) : OverlayEntry {
    public var scrollTop: Int by mutableIntStateOf(0)
}

/**
 * The session-tree navigator overlay (`/tree`). Wraps a [TreeSelectorState] built lazily by the
 * renderer, matching how [SelectOverlayEntry] defers its [SelectDialogState] the same way.
 */
@Stable
public class TreeOverlayEntry(
    override val id: Int,
    public val session: SessionManager,
    public val initialSelectedId: String?,
    public val onSelect: (String) -> Unit,
    public val onClose: () -> Unit,
) : OverlayEntry {
    public var selectorState: TreeSelectorState? = null
}

/** One user message offered by the `/fork` overlay, in chronological order. */
public data class UserMessageItem(val id: String, val text: String, val timestamp: Long? = null)

/**
 * State for the `/fork` overlay's message list: chronological order, newest preselected, no
 * search — just up/down navigation over a short list of user messages.
 */
@Stable
public class UserMessageListState(public val items: List<UserMessageItem>, initialSelectedId: String? = null) {
    public var selectedIndex: Int by mutableIntStateOf(
        (initialSelectedId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1)
            .let { found -> if (found >= 0) found else (items.size - 1).coerceAtLeast(0) },
    )

    public fun moveUp() {
        if (items.isEmpty()) return
        selectedIndex = if (selectedIndex <= 0) items.lastIndex else selectedIndex - 1
    }

    public fun moveDown() {
        if (items.isEmpty()) return
        selectedIndex = if (selectedIndex >= items.lastIndex) 0 else selectedIndex + 1
    }

    public fun selected(): UserMessageItem? = items.getOrNull(selectedIndex)
}

/** The `/fork` overlay: pick a user message to branch a new session from. */
@Stable
public class UserMessageOverlayEntry(
    override val id: Int,
    public val items: List<UserMessageItem>,
    public val initialSelectedId: String?,
    public val onSelect: (String) -> Unit,
    public val onClose: () -> Unit,
) : OverlayEntry {
    public var listState: UserMessageListState? = null
}

/**
 * State holder for the overlay host. Manages a stack of overlay entries and provides
 * push/dismiss operations.
 */
@Stable
public class OverlayHostState {
    public val stack: MutableList<OverlayEntry> = mutableStateListOf()
    private var nextId: Int = 0

    /** True when at least one overlay is showing. */
    public val hasOverlay: Boolean
        get() = stack.isNotEmpty()

    /**
     * Pushes a new select overlay onto the stack.
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
     */
    public fun pushPrompt(
        title: String,
        placeholder: String = "",
        initialValue: String = "",
        description: String? = null,
        masked: Boolean = false,
        onSubmit: (String) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        stack += PromptOverlayEntry(
            id = nextId++,
            title = title,
            placeholder = placeholder,
            initialValue = initialValue,
            description = description,
            masked = masked,
            onSubmit = onSubmit,
            onClose = onClose,
        )
    }

    /**
     * Pushes a non-interactive info overlay that displays text lines.
     * Dismissible only via Escape.
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
     * Pushes a session-tree navigator overlay (`/tree`) onto the stack.
     */
    public fun pushTree(
        session: SessionManager,
        initialSelectedId: String? = null,
        onSelect: (String) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        stack += TreeOverlayEntry(
            id = nextId++,
            session = session,
            initialSelectedId = initialSelectedId,
            onSelect = onSelect,
            onClose = onClose,
        )
    }

    /**
     * Pushes a user-message picker overlay (`/fork`) onto the stack.
     */
    public fun pushUserMessages(
        items: List<UserMessageItem>,
        initialSelectedId: String? = null,
        onSelect: (String) -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        stack += UserMessageOverlayEntry(
            id = nextId++,
            items = items,
            initialSelectedId = initialSelectedId,
            onSelect = onSelect,
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
            is TreeOverlayEntry -> entry.onClose()
            is UserMessageOverlayEntry -> entry.onClose()
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
                is TreeOverlayEntry -> entry.onClose()
                is UserMessageOverlayEntry -> entry.onClose()
            }
        }
    }
}
