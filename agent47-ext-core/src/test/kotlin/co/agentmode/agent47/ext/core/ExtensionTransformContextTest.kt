package co.agentmode.agent47.ext.core

import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtensionTransformContextTest {

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = listOf(TextContent(text = text)), timestamp = 1L)

    @Test
    fun `transformContext modifies context`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "ctx-ext",
                transformContext = { context ->
                    context.copy(systemPrompt = "transformed: ${context.systemPrompt}")
                },
            ),
        )

        val original = Context(
            systemPrompt = "original",
            messages = listOf(userMessage("hello")),
        )
        val result = runner.transformContext(original)
        assertEquals("transformed: original", result.systemPrompt)
    }

    @Test
    fun `multiple transform hooks chain in order`() = runTest {
        val runner = ExtensionRunner()

        runner.load(
            ExtensionDefinition(
                id = "ext-1",
                transformContext = { context ->
                    context.copy(systemPrompt = "${context.systemPrompt}+first")
                },
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "ext-2",
                transformContext = { context ->
                    context.copy(systemPrompt = "${context.systemPrompt}+second")
                },
            ),
        )

        val original = Context(systemPrompt = "base", messages = emptyList())
        val result = runner.transformContext(original)
        assertEquals("base+first+second", result.systemPrompt)
    }

    @Test
    fun `extensions without transformContext are skipped`() = runTest {
        val runner = ExtensionRunner()

        runner.load(
            ExtensionDefinition(
                id = "no-transform",
                beforeAgent = { ctx -> ctx.messages },
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "with-transform",
                transformContext = { context ->
                    context.copy(systemPrompt = "modified")
                },
            ),
        )

        val original = Context(systemPrompt = "original", messages = emptyList())
        val result = runner.transformContext(original)
        assertEquals("modified", result.systemPrompt)
    }

    @Test
    fun `transformContext preserves messages and tools`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "prompt-only",
                transformContext = { context ->
                    context.copy(systemPrompt = "new-prompt")
                },
            ),
        )

        val msgs = listOf(userMessage("msg1"), userMessage("msg2"))
        val original = Context(systemPrompt = "old", messages = msgs)
        val result = runner.transformContext(original)

        assertEquals("new-prompt", result.systemPrompt)
        assertEquals(2, result.messages.size)
    }
}
