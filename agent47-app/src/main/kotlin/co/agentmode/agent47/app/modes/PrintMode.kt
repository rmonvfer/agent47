package co.agentmode.agent47.app.modes

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.app.bootstrap.AgentRuntime
import co.agentmode.agent47.app.cli.printError

internal suspend fun runPrintMode(runtime: AgentRuntime, userMessage: UserMessage) {
    val client = runtime.client
    val terminal = runtime.terminal
    val model = runtime.resolvedModel
    val sessionManager = runtime.sessionTracker.current

    client.prompt(listOf(userMessage))
    client.waitForIdle()

    val messages = client.state.messages
    val lastAssistant = messages.lastOrNull { it is AssistantMessage } as? AssistantMessage

    if (lastAssistant != null) {
        val text = lastAssistant.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
            .ifBlank { "(assistant returned no text)" }

        terminal.println(text)

        sessionManager?.appendMessage(lastAssistant)

        val usage = lastAssistant.usage
        if (usage.cost.total > 0.0) {
            terminal.printError(
                $$"""[$${model.name}] tokens: $${usage.input}in/$${usage.output}out, cost: \$$${
                    String.format(
                        "%.4f",
                        usage.cost.total
                    )
                }""",
            )
        }
    } else {
        terminal.println("No assistant response was produced.")
    }
}
