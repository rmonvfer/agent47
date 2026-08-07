package co.agentmode.agent47.tui.editor

import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EditorPasteTest {
    @Test
    fun `insertPastedText inserts multi-line content atomically in one undo step`() {
        val editor = Editor()
        editor.handle(KeyboardEvent(Key.Character('x'), text = "x"))

        editor.insertPastedText("a\nb\nc")
        assertEquals("xa\nb\nc", editor.text())

        editor.handle(KeyboardEvent(Key.Character('z'), ctrl = true))
        assertEquals("x", editor.text())
    }

    @Test
    fun `insertPastedText normalizes line endings, expands tabs, and drops other control bytes`() {
        val editor = Editor()
        val stray = Char(1)

        editor.insertPastedText("a\r\nb\tc" + stray + "d")

        assertEquals("a\nb    cd", editor.text())
    }

    @Test
    fun `insertPastedText never applies an open autocomplete selection to an embedded newline`() {
        val editor = Editor(slashCommands = listOf("/help"))
        editor.insertPastedText("/he\nrest of the paste")

        assertEquals("/he\nrest of the paste", editor.text())
        assertFalse(editor.hasAutocompletePopup())
    }

    @Test
    fun `insertPastedText of an empty string is a no-op`() {
        val editor = Editor()
        editor.handle(KeyboardEvent(Key.Character('x'), text = "x"))

        editor.insertPastedText("")

        assertEquals("x", editor.text())
    }

    @Test
    fun `a paste beyond the line threshold becomes a placeholder that expands on submit`() {
        val editor = Editor()
        val content = (1..16).joinToString("\n") { "line $it" }

        editor.insertPastedText(content)

        assertEquals("[paste #1 +16 lines]", editor.text())
        assertEquals(content, editor.expandedText())
    }

    @Test
    fun `a paste beyond the char threshold becomes a chars placeholder`() {
        val editor = Editor()
        val content = "x".repeat(1500)

        editor.insertPastedText(content)

        assertEquals("[paste #1 1500 chars]", editor.text())
        assertEquals(content, editor.expandedText())
    }

    @Test
    fun `a paste within both thresholds is inserted directly`() {
        val editor = Editor()

        editor.insertPastedText("small paste")

        assertEquals("small paste", editor.text())
        assertEquals("small paste", editor.expandedText())
    }

    @Test
    fun `backspace after a placeholder deletes it whole and drops the stored paste`() {
        val editor = Editor()
        editor.insertPastedText("y".repeat(1500))

        editor.handle(KeyboardEvent(Key.Backspace))

        assertEquals("", editor.text())
        assertEquals("", editor.expandedText())
    }

    @Test
    fun `delete before a placeholder deletes it whole`() {
        val editor = Editor()
        editor.insertPastedText("y".repeat(1500))
        repeat(editor.text().length) { editor.handle(KeyboardEvent(Key.ArrowLeft)) }

        editor.handle(KeyboardEvent(Key.Delete))

        assertEquals("", editor.text())
        assertEquals("", editor.expandedText())
    }

    @Test
    fun `backspace on hand-typed marker text deletes a single character`() {
        val editor = Editor()
        for (char in "[paste #9 +5 lines]") {
            editor.handle(KeyboardEvent(Key.Character(char), text = char.toString()))
        }

        editor.handle(KeyboardEvent(Key.Backspace))

        assertEquals("[paste #9 +5 lines", editor.text())
    }

    @Test
    fun `expanding leaves a marker alone once setText has cleared the store`() {
        val editor = Editor()
        editor.insertPastedText("z".repeat(1500))
        val marker = editor.text()

        editor.setText(marker)

        assertEquals(marker, editor.expandedText())
    }

    @Test
    fun `undo removes a placeholder in one step`() {
        val editor = Editor()
        editor.handle(KeyboardEvent(Key.Character('x'), text = "x"))
        editor.insertPastedText("w".repeat(1500))

        editor.handle(KeyboardEvent(Key.Character('z'), ctrl = true))

        assertEquals("x", editor.text())
    }

    @Test
    fun `a pasted file path is spaced off from a word before the cursor`() {
        val editor = Editor()
        for (char in "see") {
            editor.handle(KeyboardEvent(Key.Character(char), text = char.toString()))
        }

        editor.insertPastedText("/tmp/notes.txt")

        assertEquals("see /tmp/notes.txt", editor.text())
    }

    @Test
    fun `a pasted file path at the start of the line is inserted as-is`() {
        val editor = Editor()

        editor.insertPastedText("/tmp/notes.txt")

        assertEquals("/tmp/notes.txt", editor.text())
    }
}
