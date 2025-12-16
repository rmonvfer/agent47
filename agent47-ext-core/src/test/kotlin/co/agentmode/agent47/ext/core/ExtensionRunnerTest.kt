package co.agentmode.agent47.ext.core

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionRunnerTest {
    @Test
    fun `runner invokes before and after hooks`() = runTest {
        val runner = ExtensionRunner()
        var afterCount = 0

        runner.load(
            ExtensionDefinition(
                id = "test-ext",
                beforeAgent = { context ->
                    context.messages + UserMessage(content = listOf(TextContent(text = "injected")), timestamp = 2L)
                },
                afterAgent = {
                    afterCount += 1
                },
                registerCommands = { registry ->
                    registry.registerCommand("/test", "test command")
                },
            ),
        )

        val before =
            runner.runBeforeAgent(listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L)))
        assertEquals(2, before.size)
        runner.runAfterAgent(before)
        assertEquals(1, afterCount)

        val commandNames = runner.commands().map { it.name }
        assertTrue(commandNames.contains("/test"))
    }
}
