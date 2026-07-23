package co.agentmode.agent47.app

import co.agentmode.agent47.app.cli.escapePromptFileArguments
import kotlin.test.Test
import kotlin.test.assertContentEquals

class PromptFileArgumentsTest {
    @Test
    fun `escapes prompt file arguments from Clikt response-file expansion`() {
        assertContentEquals(
            arrayOf("--provider", "openai", "@@image.png", "prompt"),
            escapePromptFileArguments(arrayOf("--provider", "openai", "@image.png", "prompt")),
        )
    }

    @Test
    fun `preserves arguments that are already escaped`() {
        assertContentEquals(
            arrayOf("@@image.png"),
            escapePromptFileArguments(arrayOf("@@image.png")),
        )
    }
}
