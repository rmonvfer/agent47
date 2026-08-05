package co.agentmode.agent47.tui.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PasteMarkerAccumulatorTest {
    private val startMarker = "\u001b[200~"
    private val endMarker = "\u001b[201~"

    private fun char(value: Char) = KeyboardEvent(Key.Character(value))

    private fun unknown(sequence: String) = KeyboardEvent(Key.Unknown(sequence))

    private fun feedChars(accumulator: PasteMarkerAccumulator, text: String) {
        for (value in text) {
            assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(char(value)))
        }
    }

    @Test
    fun `plain characters pass through unchanged`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(char('a')))
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(char('/')))
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(char(' ')))
    }

    @Test
    fun `a lone escape passes through`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(KeyboardEvent(Key.Escape)))
        // The accumulator is not left waiting for a marker that will never arrive: normal typing
        // right after the lone Escape still passes straight through.
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(char('x')))
    }

    @Test
    fun `keys with no literal text always pass through`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(KeyboardEvent(Key.ArrowUp)))
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(KeyboardEvent(Key.Home)))
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(KeyboardEvent(Key.PageDown)))
    }

    @Test
    fun `paste content split across many single-character chunks completes atomically`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(unknown(startMarker)))
        feedChars(accumulator, "hello world")
        assertEquals(
            PasteAccumulatorResult.Complete("hello world"),
            accumulator.feed(unknown(endMarker)),
        )

        // The accumulator resets after completing, ready for ordinary typing again.
        assertEquals(PasteAccumulatorResult.PassThrough, accumulator.feed(char('!')))
    }

    @Test
    fun `paste containing newlines is preserved verbatim`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(unknown(startMarker)))
        feedChars(accumulator, "line one\nline two\nline three")
        assertEquals(
            PasteAccumulatorResult.Complete("line one\nline two\nline three"),
            accumulator.feed(unknown(endMarker)),
        )
    }

    @Test
    fun `empty paste completes with empty text`() {
        val accumulator = PasteMarkerAccumulator()

        val result = accumulator.feed(unknown(startMarker + endMarker))
        assertEquals(PasteAccumulatorResult.Complete(""), result)
    }

    @Test
    fun `a literal escape inside an active paste is kept as content`() {
        val accumulator = PasteMarkerAccumulator()

        val escape = startMarker.first()

        assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(unknown(startMarker)))
        feedChars(accumulator, "before")
        assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(KeyboardEvent(Key.Escape)))
        feedChars(accumulator, "after")
        assertEquals(
            PasteAccumulatorResult.Complete("before" + escape + "after"),
            accumulator.feed(unknown(endMarker)),
        )
    }

    @Test
    fun `the end marker may itself arrive split across chunks`() {
        val accumulator = PasteMarkerAccumulator()

        assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(unknown(startMarker)))
        feedChars(accumulator, "content")
        for (value in endMarker.dropLast(1)) {
            assertEquals(PasteAccumulatorResult.Buffering, accumulator.feed(char(value)))
        }
        val last = accumulator.feed(char(endMarker.last()))
        assertIs<PasteAccumulatorResult.Complete>(last)
        assertEquals("content", last.text)
    }
}
