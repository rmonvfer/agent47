package co.agentmode.agent47.tui.input

private const val PASTE_START_MARKER = "\u001b[200~"
private const val PASTE_END_MARKER = "\u001b[201~"

/**
 * Outcome of feeding one keyboard event through [PasteMarkerAccumulator].
 */
internal sealed interface PasteAccumulatorResult {
    /** Not part of a bracketed paste; dispatch the event normally. */
    data object PassThrough : PasteAccumulatorResult

    /** Consumed into an in-progress paste; nothing to dispatch yet. */
    data object Buffering : PasteAccumulatorResult

    /** The paste just completed; insert [text] as one atomic operation. */
    data class Complete(val text: String) : PasteAccumulatorResult
}

/**
 * Recognizes a bracketed-paste sequence (`ESC[200~ ... ESC[201~`) across a stream of keyboard
 * events and accumulates the content between the markers, so the caller can deliver it to the
 * editor as one atomic insertion instead of many separate keystrokes.
 *
 * The Mosaic terminal library parses `ESC[200~`/`ESC[201~` internally and does not forward them to
 * application code as keyboard input, so under normal operation this accumulator never has
 * anything to recognize: every event is a plain [PasteAccumulatorResult.PassThrough]. It exists as
 * a defensive, forward-compatible fallback for the (currently unobserved) case where the markers
 * arrive as literal text instead — e.g. a multi-character "unknown sequence" event — so the editor
 * never has to special-case them.
 *
 * Detecting the *start* of a paste requires the whole marker to appear within a single event's
 * text; a start marker is never assembled across several events, so an unrelated key press (a lone
 * Escape, or someone slowly typing the literal characters "[200~") can never be misread as the
 * beginning of a paste. Once a paste is confirmed to be in progress, the end marker may arrive
 * split across any number of subsequent events, since by then there is no more ambiguity about
 * what is being accumulated.
 */
internal class PasteMarkerAccumulator {
    private var insidePaste = false
    private val content = StringBuilder()

    fun feed(event: KeyboardEvent): PasteAccumulatorResult {
        val text = event.literalText()
        return when {
            text == null -> PasteAccumulatorResult.PassThrough
            insidePaste -> consume(text)
            else -> startPaste(text)
        }
    }

    /** Looks for [PASTE_START_MARKER] in [text]; a miss passes the event through untouched. */
    private fun startPaste(text: String): PasteAccumulatorResult {
        val startIndex = text.indexOf(PASTE_START_MARKER)
        if (startIndex == -1) return PasteAccumulatorResult.PassThrough

        insidePaste = true
        content.clear()
        return consume(text.substring(startIndex + PASTE_START_MARKER.length))
    }

    /** Appends [text] to the in-progress paste, completing it once [PASTE_END_MARKER] appears. */
    private fun consume(text: String): PasteAccumulatorResult {
        content.append(text)
        val endIndex = content.indexOf(PASTE_END_MARKER)
        if (endIndex == -1) return PasteAccumulatorResult.Buffering

        val pasted = content.substring(0, endIndex)
        insidePaste = false
        content.clear()
        return PasteAccumulatorResult.Complete(pasted)
    }
}

/** The literal character(s) [KeyboardEvent] represents, or null for keys with no text form (arrow
 * keys, Home/End, Page Up/Down) that can never be part of a paste marker or its content. */
private fun KeyboardEvent.literalText(): String? = when (val current = key) {
    is Key.Character -> current.value.toString()
    is Key.Unknown -> current.sequence
    Key.Enter -> "\r"
    Key.Tab -> "\t"
    Key.Escape -> "\u001b"
    Key.Backspace -> "\u007f"
    else -> null
}
