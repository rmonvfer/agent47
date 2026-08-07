package co.agentmode.agent47.tui.components

import co.agentmode.agent47.coding.core.tools.BatchToolCallResult
import co.agentmode.agent47.tui.theme.ThemeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchToolLineTest {
    @Test
    fun `a batched call names the file it read and what came back`() {
        val line = renderBatchCallLine(
            result = BatchToolCallResult(
                index = 0,
                toolName = "read",
                output = "one\ntwo\nthree",
                success = true,
                arguments = """{"path":"src/main/kotlin/Editor.kt"}""",
            ),
            innerWidth = 80,
            theme = ThemeConfig(),
        )

        assertEquals("  read src/main/kotlin/Editor.kt — 3 lines", line.text)
    }

    @Test
    fun `a batched grep names its pattern and match count`() {
        val line = renderBatchCallLine(
            result = BatchToolCallResult(
                index = 1,
                toolName = "grep",
                output = "Editor.kt:12:paste\nEditor.kt:40:paste",
                success = true,
                arguments = """{"pattern":"insertPastedText"}""",
            ),
            innerWidth = 80,
            theme = ThemeConfig(),
        )

        assertEquals("  grep insertPastedText — 2 matches", line.text)
    }

    @Test
    fun `a failed call reports its error in place of a result`() {
        val line = renderBatchCallLine(
            result = BatchToolCallResult(
                index = 2,
                toolName = "read",
                output = "Error: no such file: missing.kt",
                success = false,
                arguments = """{"path":"missing.kt"}""",
            ),
            innerWidth = 80,
            theme = ThemeConfig(),
        )

        assertEquals("  read missing.kt — Error: no such file: missing.kt", line.text)
    }

    @Test
    fun `a call without recorded arguments still names its tool and result`() {
        val line = renderBatchCallLine(
            result = BatchToolCallResult(index = 0, toolName = "ls", output = "a\nb", success = true),
            innerWidth = 80,
            theme = ThemeConfig(),
        )

        assertEquals("  ls — 2 entries", line.text)
    }

    @Test
    fun `a narrow card keeps the call inside its width`() {
        val line = renderBatchCallLine(
            result = BatchToolCallResult(
                index = 0,
                toolName = "read",
                output = "one\ntwo",
                success = true,
                arguments = """{"path":"a/very/deep/path/that/keeps/going/File.kt"}""",
            ),
            innerWidth = 24,
            theme = ThemeConfig(),
        )

        assertTrue(line.text.length <= 24, "line exceeds the card width: '${line.text}'")
    }
}
