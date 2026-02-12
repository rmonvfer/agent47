package co.agentmode.agent47.tui.rendering

import co.agentmode.agent47.tui.testing.SnapshotAssertions.assertSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownRendererTest {

    private val renderer = MarkdownRenderer()
    private val width = 60

    private fun render(markdown: String, w: Int = width): String {
        return renderer.render(markdown, w).joinToString("\n") { it.text }
    }

    @Test
    fun `renders headings at all levels`() {
        val md = """
            # Heading 1
            ## Heading 2
            ### Heading 3
        """.trimIndent()
        val output = render(md)
        assertTrue(output.contains("# Heading 1"))
        assertTrue(output.contains("## Heading 2"))
        assertTrue(output.contains("### Heading 3"))
    }

    @Test
    fun `heading content has bold style applied`() {
        val md = "# My Title"
        val lines = renderer.render(md, 40)
        assertEquals(1, lines.size)
        val line = lines[0]
        // The heading text "My Title" should have at least one SpanStyle range with Bold
        val hasBold = line.spanStyles.any { range ->
            range.item.textStyle == com.jakewharton.mosaic.ui.TextStyle.Bold
        }
        assertTrue(hasBold, "heading content should have Bold style")
    }

    @Test
    fun `renders paragraphs with word wrapping`() {
        val md = "The quick brown fox jumps over the lazy dog repeatedly"
        val output = render(md, 20)
        val lines = output.lines()
        lines.forEach { line ->
            assertTrue(line.length <= 20, "line '$line' exceeds width 20")
        }
    }

    @Test
    fun `renders fenced code block with language label`() {
        val md = """
            ```kotlin
            fun main() {
                println("Hello")
            }
            ```
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("┌─ kotlin"))
        assertTrue(output.contains("│ fun main()"))
        assertTrue(output.contains("└"))
    }

    @Test
    fun `renders fenced code block without language`() {
        val md = """
            ```
            some code
            ```
        """.trimIndent()
        val output = render(md, 30)
        assertTrue(output.contains("┌"))
        assertTrue(output.contains("│ some code"))
        assertTrue(output.contains("└"))
    }

    @Test
    fun `renders blockquote`() {
        val md = "> This is a quoted paragraph"
        val output = render(md, 40)
        assertTrue(output.contains("│"))
    }

    @Test
    fun `renders nested blockquote`() {
        val md = """
            > Outer quote
            > > Inner quote
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("│"))
    }

    @Test
    fun `renders bullet list`() {
        val md = """
            - First item
            - Second item
            - Third item
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("- First item"))
        assertTrue(output.contains("- Second item"))
        assertTrue(output.contains("- Third item"))
    }

    @Test
    fun `renders ordered list`() {
        val md = """
            1. First
            2. Second
            3. Third
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("1. First"))
        assertTrue(output.contains("2. Second"))
        assertTrue(output.contains("3. Third"))
    }

    @Test
    fun `renders nested list`() {
        val md = """
            - Outer
              - Inner 1
              - Inner 2
            - Back to outer
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("Outer"))
        assertTrue(output.contains("Inner 1"))
    }

    @Test
    fun `nested list has no blank line between item and sub-list`() {
        val md = """
            - Outer
              - Inner 1
              - Inner 2
            - Back to outer
        """.trimIndent()
        val lines = renderer.render(md, 40).map { it.text }
        // "Outer" and "- Inner 1" should be on consecutive lines with no blank between
        val outerIdx = lines.indexOfFirst { it.contains("Outer") }
        val innerIdx = lines.indexOfFirst { it.contains("Inner 1") }
        assertEquals(outerIdx + 1, innerIdx, "sub-list should immediately follow parent item, got lines: $lines")
    }

    @Test
    fun `loose list has blank lines between items`() {
        val md = """
            - First item

            - Second item

            - Third item
        """.trimIndent()
        val lines = renderer.render(md, 40).map { it.text }
        val firstIdx = lines.indexOfFirst { it.contains("First item") }
        val secondIdx = lines.indexOfFirst { it.contains("Second item") }
        // Should have a blank line between items in a loose list
        assertTrue(secondIdx > firstIdx + 1, "loose list items should have spacing, got lines: $lines")
        assertEquals("", lines[firstIdx + 1], "should be a blank line between loose list items")
    }

    @Test
    fun `renders horizontal rule`() {
        val md = """
            Before

            ---

            After
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("─".repeat(40)))
    }

    @Test
    fun `renders inline bold`() {
        val md = "This has **bold** text"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("bold"))
    }

    @Test
    fun `renders inline italic`() {
        val md = "This has *italic* text"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("italic"))
    }

    @Test
    fun `renders inline code`() {
        val md = "Use the `println` function"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("println"))
    }

    @Test
    fun `renders strikethrough`() {
        val md = "This is ~~deleted~~ text"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("deleted"))
    }

    @Test
    fun `renders link with url`() {
        val md = "Visit [Example](https://example.com) for more"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("Example"))
        assertTrue(fullText.contains("https://example.com"))
    }

    @Test
    fun `renders image with alt text`() {
        val md = "![alt text](image.png)"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("alt text"))
        assertTrue(fullText.contains("[image]"))
    }

    @Test
    fun `renders nested bold and italic`() {
        val md = "This is ***bold italic*** text"
        val lines = renderer.render(md, width)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("bold italic"))
    }

    @Test
    fun `renders GFM table`() {
        val md = """
            | Col 1 | Col 2 | Col 3 |
            |-------|:-----:|------:|
            | Left  | Center| Right |
            | A     | B     | C     |
        """.trimIndent()
        val output = render(md, 40)
        assertTrue(output.contains("┌"))
        assertTrue(output.contains("┬"))
        assertTrue(output.contains("┐"))
        assertTrue(output.contains("├"))
        assertTrue(output.contains("┼"))
        assertTrue(output.contains("┤"))
        assertTrue(output.contains("└"))
        assertTrue(output.contains("┴"))
        assertTrue(output.contains("┘"))
        assertTrue(output.contains("Col 1"))
    }

    @Test
    fun `handles empty input`() {
        val lines = renderer.render("", 40)
        assertEquals(1, lines.size)
        assertEquals("", lines[0].text)
    }

    @Test
    fun `handles blank input`() {
        val lines = renderer.render("   ", 40)
        assertEquals(1, lines.size)
        assertEquals("", lines[0].text)
    }

    @Test
    fun `handles narrow width`() {
        val md = "Hello world this is a test"
        val lines = renderer.render(md, 5)
        lines.forEach { line ->
            assertTrue(line.text.length <= 5, "line '${line.text}' exceeds width 5")
        }
    }

    @Test
    fun `renders mixed document`() {
        val md = """
            # Title

            Some **bold** and *italic* text with `code`.

            - Item 1
            - Item 2

            ```python
            print("hello")
            ```

            > A quote

            ---

            End.
        """.trimIndent()
        val output = render(md, 50)
        assertSnapshot("rendering/markdown-commonmark", output)
    }

    @Test
    fun `renders HTML block as dimmed text`() {
        val md = "<div>Some HTML</div>"
        val lines = renderer.render(md, 40)
        val fullText = lines.joinToString("") { it.text }
        assertTrue(fullText.contains("Some HTML"))
    }

    @Test
    fun `renders table with heading after it`() {
        val md = """
            | Feature | Status | Description |
            |---------|:------:|-------------|
            | Core | Done | Basic agent functionality |
            | Web UI | No | Not planned |
            | TUI | Done | Terminal user interface |

            # Blockquotes

            > This is a quote
        """.trimIndent()
        val output = render(md, 60)
        assertSnapshot("rendering/table-with-heading", output)
    }
}
