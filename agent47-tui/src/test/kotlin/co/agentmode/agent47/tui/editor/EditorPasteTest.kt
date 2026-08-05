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
}
