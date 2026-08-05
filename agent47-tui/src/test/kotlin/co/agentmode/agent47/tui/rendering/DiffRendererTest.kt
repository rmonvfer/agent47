package co.agentmode.agent47.tui.rendering

import kotlin.test.Test
import kotlin.test.assertTrue

class DiffRendererTest {
    @Test
    fun `line numbers shift to the file position of the first change`() {
        val lines = DiffRenderer()
            .render("alpha\nbeta\ngamma", "alpha\nBETA\ngamma", width = 0, firstChangedLine = 42)
            .map { it.text }

        assertTrue(lines.any { it.startsWith("-42 ") }, lines.toString())
        assertTrue(lines.any { it.startsWith("+42 ") }, lines.toString())
        assertTrue(lines.any { it.startsWith(" 41 alpha") }, lines.toString())
    }

    @Test
    fun `line numbers stay snippet relative without a file position`() {
        val lines = DiffRenderer().render("a\nb", "a\nc", width = 0).map { it.text }

        assertTrue(lines.any { it.startsWith("-2 ") }, lines.toString())
        assertTrue(lines.any { it.startsWith("+2 ") }, lines.toString())
    }
}
