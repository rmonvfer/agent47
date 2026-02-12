package co.agentmode.agent47.tui.rendering

import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class WrapAnnotatedTest {

    @Test
    fun `wraps at word boundary on space`() {
        val text = annotated("hello world foo")
        val lines = wrapAnnotated(text, 11)
        assertEquals(2, lines.size)
        assertEquals("hello world", lines[0].text)
        assertEquals("foo", lines[1].text)
    }

    @Test
    fun `wraps at word boundary consuming trailing spaces`() {
        val text = annotated("one two three")
        val lines = wrapAnnotated(text, 8)
        assertEquals(2, lines.size)
        assertEquals("one two", lines[0].text)
        assertEquals("three", lines[1].text)
    }

    @Test
    fun `falls back to character-level break for long words`() {
        val text = annotated("abcdefghij")
        val lines = wrapAnnotated(text, 5)
        assertEquals(2, lines.size)
        assertEquals("abcde", lines[0].text)
        assertEquals("fghij", lines[1].text)
    }

    @Test
    fun `preserves hyphen at break point`() {
        val text = annotated("self-contained unit")
        val lines = wrapAnnotated(text, 10)
        assertEquals(3, lines.size)
        assertEquals("self-", lines[0].text)
        assertEquals("contained", lines[1].text)
        assertEquals("unit", lines[2].text)
    }

    @Test
    fun `handles exact width without wrapping`() {
        val text = annotated("exact")
        val lines = wrapAnnotated(text, 5)
        assertEquals(1, lines.size)
        assertEquals("exact", lines[0].text)
    }

    @Test
    fun `handles empty text`() {
        val text = annotated("")
        val lines = wrapAnnotated(text, 10)
        assertEquals(1, lines.size)
        assertEquals("", lines[0].text)
    }

    @Test
    fun `handles single word shorter than width`() {
        val text = annotated("hi")
        val lines = wrapAnnotated(text, 10)
        assertEquals(1, lines.size)
        assertEquals("hi", lines[0].text)
    }

    @Test
    fun `preserves newlines`() {
        val text = annotated("line one\nline two")
        val lines = wrapAnnotated(text, 40)
        assertEquals(2, lines.size)
        assertEquals("line one", lines[0].text)
        assertEquals("line two", lines[1].text)
    }

    @Test
    fun `preserves span styles across word breaks`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(color = Color.Red)) {
                append("bold text here")
            }
        }
        val lines = wrapAnnotated(styled, 10)
        assertEquals(2, lines.size)
        assertEquals("bold text", lines[0].text)
        assertEquals("here", lines[1].text)
        // Both lines should have style ranges
        assert(lines[0].spanStyles.isNotEmpty()) { "first line should have styles" }
        assert(lines[1].spanStyles.isNotEmpty()) { "second line should have styles" }
    }

    @Test
    fun `character-level wrapping for raw mode`() {
        val text = annotated("hello world")
        val lines = wrapAnnotatedRaw(text, 5)
        assertEquals(3, lines.size)
        assertEquals("hello", lines[0].text)
        assertEquals(" worl", lines[1].text)
        assertEquals("d", lines[2].text)
    }

    @Test
    fun `wrapWithPrefix uses first prefix on first line`() {
        val text = annotated("hello world foo bar")
        val lines = wrapWithPrefix(
            text = text,
            width = 20,
            firstPrefix = annotated("## "),
            restPrefix = annotated("   "),
        )
        assert(lines[0].text.startsWith("## ")) { "first line should start with ##" }
        lines.drop(1).forEach { line ->
            assert(line.text.startsWith("   ")) { "continuation should start with spaces" }
        }
    }

    @Test
    fun `multiple words wrap correctly`() {
        val text = annotated("the quick brown fox jumps over the lazy dog")
        val lines = wrapAnnotated(text, 15)
        lines.forEach { line ->
            assert(line.text.length <= 15) { "line '${line.text}' exceeds width 15" }
        }
        val joined = lines.joinToString(" ") { it.text }
        assertEquals("the quick brown fox jumps over the lazy dog", joined)
    }
}
