package co.agentmode.agent47.coding.core.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolsTest {
    @Test
    fun `write then read returns file contents`() = runTest {
        val cwd = createTempDirectory("agent47-tools")
        val writeTool = WriteTool(cwd)
        val readTool = ReadTool(cwd)

        writeTool.execute(
            toolCallId = "1",
            parameters = buildJsonObject {
                put("path", "notes/test.txt")
                put("content", "hello\nworld")
            },
        )

        val result = readTool.execute(
            toolCallId = "2",
            parameters = buildJsonObject {
                put("path", "notes/test.txt")
            },
        )

        val text = result.content.filterIsInstance<co.agentmode.agent47.ai.types.TextContent>().joinToString("\n") { it.text }
        assertTrue(text.contains("hello"))
        assertTrue(text.contains("world"))
    }

    @Test
    fun `edit tool replaces exact text and writes diff details`() = runTest {
        val cwd = createTempDirectory("agent47-edit")
        val file = cwd.resolve("a.txt")
        file.writeText("alpha\nbeta\ngamma\n")

        val editTool = EditTool(cwd)
        val result = editTool.execute(
            toolCallId = "edit-1",
            parameters = buildJsonObject {
                put("path", "a.txt")
                put("oldText", "beta")
                put("newText", "BETA")
            },
        )

        assertEquals("alpha\nBETA\ngamma\n", file.readText())
        assertEquals(result.details?.containsKey("diff"), true)
    }

    @Test
    fun `find and grep discover matching files and content`() = runTest {
        val cwd = createTempDirectory("agent47-findgrep")
        cwd.resolve("src").toFile().mkdirs()
        cwd.resolve("src/app.kt").writeText("fun main() = println(\"hello\")\n")
        cwd.resolve("src/lib.kt").writeText("fun helper() = println(\"world\")\n")

        val findTool = FindTool(cwd)
        val grepTool = GrepTool(cwd)

        val find = findTool.execute(
            toolCallId = "f1",
            parameters = buildJsonObject {
                put("pattern", "**/*.kt")
                put("path", "src")
            },
        )

        val findText = find.content.filterIsInstance<co.agentmode.agent47.ai.types.TextContent>().joinToString("\n") { it.text }
        assertTrue(findText.contains("app.kt"))
        assertTrue(findText.contains("lib.kt"))

        val grep = grepTool.execute(
            toolCallId = "g1",
            parameters = buildJsonObject {
                put("pattern", "println")
                put("path", "src")
                put("literal", true)
            },
        )

        val grepText = grep.content.filterIsInstance<co.agentmode.agent47.ai.types.TextContent>().joinToString("\n") { it.text }
        assertTrue(grepText.contains("app.kt"))
        assertTrue(grepText.contains("lib.kt"))
    }
}
