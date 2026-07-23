package co.agentmode.agent47.tui.input

import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandSource
import co.agentmode.agent47.ext.core.RegisteredCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubmitParserTest {
    private val extensionCommand = RegisteredCommand(name = "myext", description = "", handler = { _, _ -> })
    private val fileCommand = SlashCommand(
        name = "greet",
        description = "",
        content = "Hello there",
        source = SlashCommandSource.USER,
    )

    @Test
    fun `known slash commands parse to builtin with args`() {
        assertEquals(Submission.Builtin("/help", emptyList(), "/help"), parseSubmission("/help", emptyList(), emptyList()))
        assertEquals(
            Submission.Builtin("/model", listOf("gpt-4"), "/model gpt-4"),
            parseSubmission("/model gpt-4", emptyList(), emptyList()),
        )
    }

    @Test
    fun `an extension slash command parses to extension with its raw arguments`() {
        val result = parseSubmission("/myext do a thing", listOf(extensionCommand), emptyList())
        assertIs<Submission.Extension>(result)
        assertEquals(extensionCommand, result.command)
        assertEquals("do a thing", result.rawArgs)
        assertEquals("/myext do a thing", result.raw)
    }

    @Test
    fun `a file slash command expands into a prompt`() {
        val result = parseSubmission("/greet", emptyList(), listOf(fileCommand))
        assertEquals(Submission.FileExpansion("Hello there"), result)
    }

    @Test
    fun `an extension command takes priority over a file command of the same name`() {
        val ext = RegisteredCommand(name = "greet", description = "", handler = { _, _ -> })
        val result = parseSubmission("/greet", listOf(ext), listOf(fileCommand))
        assertIs<Submission.Extension>(result)
        assertEquals(ext, result.command)
    }

    @Test
    fun `an unrecognized slash command parses to unknown`() {
        assertEquals(Submission.UnknownSlash("/nope", "/nope"), parseSubmission("/nope", emptyList(), emptyList()))
    }

    @Test
    fun `a bang prefix parses to a bash command`() {
        assertEquals(Submission.Bash("ls -la"), parseSubmission("!ls -la", emptyList(), emptyList()))
    }

    @Test
    fun `a bare bang parses to a blank bash command`() {
        assertEquals(Submission.Bash(""), parseSubmission("!", emptyList(), emptyList()))
        assertEquals(Submission.Bash(""), parseSubmission("!   ", emptyList(), emptyList()))
    }

    @Test
    fun `plain text parses to a prompt`() {
        assertEquals(Submission.Prompt("hello world"), parseSubmission("hello world", emptyList(), emptyList()))
    }
}
