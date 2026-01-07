package co.agentmode.agent47.coding.core.commands

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlashCommandExpanderTest {

    private val commands = listOf(
        SlashCommand(
            name = "review",
            description = "Review a file",
            content = "Review the file at \$1 for bugs and issues.",
            source = SlashCommandSource.USER,
        ),
        SlashCommand(
            name = "explain",
            description = "Explain code between lines",
            content = "Read \$1 from line \$2 to line \$3 and explain what the code does.",
            source = SlashCommandSource.USER,
        ),
        SlashCommand(
            name = "deploy",
            description = "Deploy a service",
            content = "Deploy \$@ to production.",
            source = SlashCommandSource.USER,
        ),
        SlashCommand(
            name = "status",
            description = "Show status",
            content = "Show the current project status.",
            source = SlashCommandSource.USER,
        ),
    )

    @Test
    fun `expand substitutes single positional argument`() {
        val result = SlashCommandExpander.expand("/review src/App.kt", commands)
        assertEquals("Review the file at src/App.kt for bugs and issues.", result)
    }

    @Test
    fun `expand substitutes multiple positional arguments`() {
        val result = SlashCommandExpander.expand("/explain src/server.kt 45 80", commands)
        assertEquals("Read src/server.kt from line 45 to line 80 and explain what the code does.", result)
    }

    @Test
    fun `expand substitutes dollar-at with all arguments`() {
        val result = SlashCommandExpander.expand("/deploy api worker cron", commands)
        assertEquals("Deploy api worker cron to production.", result)
    }

    @Test
    fun `expand returns null for unknown command`() {
        val result = SlashCommandExpander.expand("/nonexistent arg1", commands)
        assertNull(result)
    }

    @Test
    fun `expand returns null for non-slash text`() {
        val result = SlashCommandExpander.expand("just some text", commands)
        assertNull(result)
    }

    @Test
    fun `expand handles command with no arguments`() {
        val result = SlashCommandExpander.expand("/status", commands)
        assertEquals("Show the current project status.", result)
    }

    @Test
    fun `expand is case-insensitive on command name`() {
        val result = SlashCommandExpander.expand("/REVIEW src/App.kt", commands)
        assertEquals("Review the file at src/App.kt for bugs and issues.", result)
    }

    @Test
    fun `expand cleans up unused positional placeholders`() {
        val result = SlashCommandExpander.expand("/explain src/server.kt", commands)
        // $1 is replaced, $2 and $3 have no args so they should be cleaned up
        assertTrue(!result!!.contains("\$2"))
        assertTrue(!result.contains("\$3"))
    }

    @Test
    fun `parseArgs splits on whitespace`() {
        val args = SlashCommandExpander.parseArgs("one two three")
        assertEquals(listOf("one", "two", "three"), args)
    }

    @Test
    fun `parseArgs handles double-quoted strings`() {
        val args = SlashCommandExpander.parseArgs("""one "hello world" three""")
        assertEquals(listOf("one", "hello world", "three"), args)
    }

    @Test
    fun `parseArgs handles single-quoted strings`() {
        val args = SlashCommandExpander.parseArgs("one 'hello world' three")
        assertEquals(listOf("one", "hello world", "three"), args)
    }

    @Test
    fun `parseArgs handles backslash escapes`() {
        val args = SlashCommandExpander.parseArgs("""hello\ world""")
        assertEquals(listOf("hello world"), args)
    }

    @Test
    fun `parseArgs returns empty list for blank input`() {
        val args = SlashCommandExpander.parseArgs("")
        assertEquals(emptyList(), args)
    }

    @Test
    fun `substituteArgs replaces dollar-ARGUMENTS`() {
        val result = SlashCommandExpander.substituteArgs("Run \$ARGUMENTS now.", listOf("test", "suite"))
        assertEquals("Run test suite now.", result)
    }
}
