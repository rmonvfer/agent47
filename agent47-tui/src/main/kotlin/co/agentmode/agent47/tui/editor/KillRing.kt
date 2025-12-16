package co.agentmode.agent47.tui.editor

/**
 * Emacs-style kill ring for cut/yank operations.
 */
public class KillRing(
    private val maxSize: Int = 30,
) {
    private val entries: ArrayDeque<String> = ArrayDeque()
    private var yankCursor: Int = -1

    public fun add(text: String, append: Boolean = false, prepend: Boolean = false) {
        if (text.isEmpty()) {
            return
        }

        val merged = when {
            append && entries.isNotEmpty() -> entries.removeFirst() + text
            prepend && entries.isNotEmpty() -> text + entries.removeFirst()
            else -> null
        }

        entries.addFirst(merged ?: text)
        while (entries.size > maxSize) {
            entries.removeLast()
        }
        yankCursor = -1
    }

    public fun yank(): String? {
        if (entries.isEmpty()) {
            return null
        }
        yankCursor = 0
        return entries.first()
    }

    public fun yankPop(): String? {
        if (entries.isEmpty()) {
            return null
        }
        if (yankCursor < 0) {
            yankCursor = 0
            return entries.first()
        }
        yankCursor = (yankCursor + 1) % entries.size
        return entries.elementAt(yankCursor)
    }
}
