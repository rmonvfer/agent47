package co.agentmode.agent47.tui.editor

import co.agentmode.agent47.tui.input.Key
import co.agentmode.agent47.tui.input.KeyboardEvent
import co.agentmode.agent47.tui.testing.SnapshotAssertions.assertSnapshot
import kotlin.test.Test

class EditorSnapshotTest {
    @Test
    fun `renders wrapped editor with scroll markers`() {
        val editor = Editor(
            slashCommands = listOf("/help", "/model", "/thinking", "/exit"),
        )

        editor.setText(
            """
            /help shows commands
            this is a very long line that must wrap when rendered inside narrow editor width
            third line
            fourth line
            fifth line
            sixth line
        """.trimIndent(),
        )

        repeat(5) {
            editor.handle(KeyboardEvent(Key.ArrowDown))
        }
        editor.handle(KeyboardEvent(Key.Home))

        val render = editor.render(width = 34, height = 5)
        val snapshot = buildString {
            appendLine("cursor=${render.cursorRow},${render.cursorColumn}")
            appendLine("top=${render.topMarkerVisible} bottom=${render.bottomMarkerVisible}")
            append(render.lines.joinToString("\n"))
        }
        assertSnapshot("editor/render-scroll", snapshot)
    }

    @Test
    fun `undo redo and kill yank remain stable`() {
        val editor = Editor()
        "hello world".forEach { ch ->
            editor.handle(KeyboardEvent(Key.Character(ch), text = ch.toString()))
        }

        editor.handle(KeyboardEvent(Key.Character('w'), ctrl = true))
        editor.handle(KeyboardEvent(Key.Character('y'), ctrl = true))
        editor.handle(KeyboardEvent(Key.Character('z'), ctrl = true))
        editor.handle(KeyboardEvent(Key.Character('z'), ctrl = true, shift = true))

        val render = editor.render(width = 24, height = 3)
        val snapshot = buildString {
            appendLine("cursor=${render.cursorRow},${render.cursorColumn}")
            append(render.lines.joinToString("\n"))
        }
        assertSnapshot("editor/undo-redo-kill-yank", snapshot)
    }
}
