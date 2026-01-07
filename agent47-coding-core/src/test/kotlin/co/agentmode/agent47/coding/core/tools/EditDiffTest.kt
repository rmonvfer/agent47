package co.agentmode.agent47.coding.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditDiffTest {

    // --- detectLineEnding ---

    @Test
    fun `detectLineEnding returns LF for LF-only content`() {
        assertEquals("\n", detectLineEnding("hello\nworld\n"))
    }

    @Test
    fun `detectLineEnding returns CRLF when CRLF appears before LF`() {
        assertEquals("\r\n", detectLineEnding("hello\r\nworld\r\n"))
    }

    @Test
    fun `detectLineEnding returns LF when LF appears before CRLF`() {
        assertEquals("\n", detectLineEnding("hello\nworld\r\n"))
    }

    @Test
    fun `detectLineEnding returns LF for content without newlines`() {
        assertEquals("\n", detectLineEnding("no newlines here"))
    }

    @Test
    fun `detectLineEnding returns CRLF for mixed content where CRLF comes first`() {
        assertEquals("\r\n", detectLineEnding("first\r\nsecond\nthird"))
    }

    // --- normalizeToLf ---

    @Test
    fun `normalizeToLf converts CRLF to LF`() {
        assertEquals("hello\nworld\n", normalizeToLf("hello\r\nworld\r\n"))
    }

    @Test
    fun `normalizeToLf converts bare CR to LF`() {
        assertEquals("hello\nworld", normalizeToLf("hello\rworld"))
    }

    @Test
    fun `normalizeToLf passes through already LF content`() {
        val input = "hello\nworld\n"
        assertEquals(input, normalizeToLf(input))
    }

    @Test
    fun `normalizeToLf handles mixed CR and CRLF`() {
        assertEquals("a\nb\nc\n", normalizeToLf("a\r\nb\rc\r\n"))
    }

    // --- restoreLineEndings ---

    @Test
    fun `restoreLineEndings converts LF to CRLF when ending is CRLF`() {
        assertEquals("hello\r\nworld\r\n", restoreLineEndings("hello\nworld\n", "\r\n"))
    }

    @Test
    fun `restoreLineEndings passes through for LF ending`() {
        val input = "hello\nworld\n"
        assertEquals(input, restoreLineEndings(input, "\n"))
    }

    // --- normalizeForFuzzyMatch ---

    @Test
    fun `normalizeForFuzzyMatch converts curly quotes to straight`() {
        assertEquals("'hello' \"world\"", normalizeForFuzzyMatch("\u2018hello\u2019 \u201Cworld\u201D"))
    }

    @Test
    fun `normalizeForFuzzyMatch converts em-dash and en-dash to hyphen`() {
        assertEquals("a-b-c", normalizeForFuzzyMatch("a\u2014b\u2013c"))
    }

    @Test
    fun `normalizeForFuzzyMatch converts non-breaking space to space`() {
        assertEquals("hello world", normalizeForFuzzyMatch("hello\u00A0world"))
    }

    @Test
    fun `normalizeForFuzzyMatch trims trailing whitespace per line`() {
        assertEquals("hello\nworld", normalizeForFuzzyMatch("hello   \nworld  "))
    }

    // --- fuzzyFindText ---

    @Test
    fun `fuzzyFindText returns exact match with usedFuzzyMatch false`() {
        val result = fuzzyFindText("hello world", "world")
        assertTrue(result.found)
        assertEquals(6, result.index)
        assertEquals(5, result.matchLength)
        assertFalse(result.usedFuzzyMatch)
        assertEquals("hello world", result.contentForReplacement)
    }

    @Test
    fun `fuzzyFindText falls back to fuzzy match via unicode normalization`() {
        val content = "say \u201Chello\u201D"
        val search = "say \"hello\""
        val result = fuzzyFindText(content, search)
        assertTrue(result.found)
        assertTrue(result.usedFuzzyMatch)
        assertEquals(0, result.index)
    }

    @Test
    fun `fuzzyFindText returns found false when text is not found`() {
        val result = fuzzyFindText("hello world", "goodbye")
        assertFalse(result.found)
        assertEquals(-1, result.index)
        assertEquals(0, result.matchLength)
    }

    @Test
    fun `fuzzyFindText exact match returns correct matchLength`() {
        val result = fuzzyFindText("abcdef", "cde")
        assertTrue(result.found)
        assertEquals(2, result.index)
        assertEquals(3, result.matchLength)
    }

    // --- stripBom ---

    @Test
    fun `stripBom removes BOM prefix`() {
        val result = stripBom("\uFEFFhello")
        assertEquals("\uFEFF", result.bom)
        assertEquals("hello", result.text)
    }

    @Test
    fun `stripBom passes through content without BOM`() {
        val result = stripBom("hello")
        assertEquals("", result.bom)
        assertEquals("hello", result.text)
    }

    // --- generateDiffString ---

    @Test
    fun `generateDiffString returns empty diff for identical content`() {
        val result = generateDiffString("hello\nworld", "hello\nworld")
        assertEquals("", result.diff)
        assertNull(result.firstChangedLine)
    }

    @Test
    fun `generateDiffString shows single-line change`() {
        val result = generateDiffString("hello\nworld", "hello\nearth")
        assertTrue(result.diff.contains("-"))
        assertTrue(result.diff.contains("+"))
        assertTrue(result.diff.contains("world"))
        assertTrue(result.diff.contains("earth"))
        assertEquals(2, result.firstChangedLine)
    }

    @Test
    fun `generateDiffString shows added lines`() {
        val result = generateDiffString("line1\nline2", "line1\nline2\nline3")
        assertTrue(result.diff.contains("+"))
        assertTrue(result.diff.contains("line3"))
    }

    @Test
    fun `generateDiffString shows removed lines`() {
        val result = generateDiffString("line1\nline2\nline3", "line1\nline3")
        assertTrue(result.diff.contains("-"))
        assertTrue(result.diff.contains("line2"))
    }

    @Test
    fun `generateDiffString tracks firstChangedLine correctly`() {
        val old = "a\nb\nc\nd\ne"
        val new = "a\nb\nX\nd\ne"
        val result = generateDiffString(old, new)
        assertEquals(3, result.firstChangedLine)
    }

    @Test
    fun `generateDiffString includes context lines`() {
        val old = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10"
        val new = "line1\nline2\nline3\nline4\nline5\nCHANGED\nline7\nline8\nline9\nline10"
        val result = generateDiffString(old, new, contextLines = 2)
        assertTrue(result.diff.contains("line4"))
        assertTrue(result.diff.contains("line5"))
    }
}
