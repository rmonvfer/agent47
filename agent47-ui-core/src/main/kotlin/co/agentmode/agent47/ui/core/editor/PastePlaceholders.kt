package co.agentmode.agent47.ui.core.editor

/** A large paste is replaced by a placeholder once it exceeds either threshold. */
public const val PASTE_PLACEHOLDER_LINE_THRESHOLD: Int = 10
public const val PASTE_PLACEHOLDER_CHAR_THRESHOLD: Int = 1000

/** Matches a placeholder like `[paste #1 +123 lines]` or `[paste #2 1234 chars]`. */
public val PASTE_MARKER_REGEX: Regex = Regex("""\[paste #(\d+)( (\+\d+ lines|\d+ chars))?\]""")

/** True when [text] is, in its entirety, one paste placeholder. */
public fun isPasteMarker(text: String): Boolean {
    return text.length >= MIN_PASTE_MARKER_LENGTH && PASTE_MARKER_REGEX.matches(text)
}

private const val MIN_PASTE_MARKER_LENGTH = 10

/**
 * Holds the full text of pastes large enough to have been replaced by a `[paste #N ...]`
 * placeholder in the editor, so the placeholder can be expanded back to the real content before
 * the message is submitted.
 *
 * Each paste gets a strictly increasing id; ids are never reused or renumbered after a marker is
 * deleted, so a stored id always resolves to the exact content it was created with.
 */
public class PastePlaceholderStore {
    private val pastes: MutableMap<Int, String> = mutableMapOf()
    private var counter: Int = 0

    /** Stores [text] under a new id and returns the placeholder marker to insert in its place. */
    public fun store(text: String): String {
        counter++
        val id = counter
        pastes[id] = text
        val lineCount = text.count { it == '\n' } + 1
        return if (lineCount > PASTE_PLACEHOLDER_LINE_THRESHOLD) {
            "[paste #$id +$lineCount lines]"
        } else {
            "[paste #$id ${text.length} chars]"
        }
    }

    /** True when [id] refers to a paste still held by this store. */
    public fun contains(id: Int): Boolean = id in pastes

    /** Drops the paste stored under [id], if any. The placeholder text itself is the caller's
     * responsibility to remove; this only affects later [expand]/[contains] calls. */
    public fun remove(id: Int) {
        pastes.remove(id)
    }

    /**
     * Replaces every placeholder in [text] whose id this store still holds with its full content.
     * A placeholder whose id is not (or no longer) present is left as literal text, matching how a
     * placeholder that outlived its paste (or was never one to begin with) should read.
     */
    public fun expand(text: String): String {
        return PASTE_MARKER_REGEX.replace(text) { match ->
            val id = match.groupValues[1].toIntOrNull()
            (id?.let(pastes::get)) ?: match.value
        }
    }

    /** Discards every stored paste and resets the id counter. */
    public fun clear() {
        pastes.clear()
        counter = 0
    }
}
