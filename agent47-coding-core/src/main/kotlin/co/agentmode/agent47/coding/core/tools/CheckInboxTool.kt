package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.InboxMessage
import kotlinx.serialization.json.JsonObject

/**
 * Collects results and messages from background sub-agents launched with the `task` tool.
 * Draining the inbox is idempotent per message — each result/message is returned once.
 */
public class CheckInboxTool(
    private val backgroundAgents: BackgroundAgents,
) : AgentTool<Unit> {

    override val label: String = "check_inbox"

    override val definition: ToolDefinition = toolDefinition(
        "check_inbox",
        "Collect results and messages from background sub-agents launched with the `task` tool. " +
            "Returns agents that have finished (with their output), messages agents have sent you, " +
            "and which agents are still running. Call it after launching background agents, then " +
            "again later to pick up more as they complete.",
    ) {}

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<Unit>?,
    ): AgentToolResult<Unit> {
        val messages = backgroundAgents.drainInbox()
        val running = backgroundAgents.runningStatus()

        val text = buildString {
            if (messages.isEmpty() && running.isEmpty()) {
                append("No background agents are running and the inbox is empty.")
                return@buildString
            }

            val finished = messages.filter { it.kind != InboxMessage.Kind.MESSAGE }
            val notes = messages.filter { it.kind == InboxMessage.Kind.MESSAGE }

            if (finished.isNotEmpty()) {
                appendLine("Finished:")
                finished.forEach {
                    appendLine(it.text)
                    appendLine()
                }
            }
            if (notes.isNotEmpty()) {
                appendLine("Messages:")
                notes.forEach { appendLine("- ${it.text}") }
                appendLine()
            }
            if (running.isNotEmpty()) {
                appendLine("Still running:")
                running.forEach { r ->
                    val activity = r.progress?.let { p -> p.currentTool?.let { "running $it" } ?: "working" } ?: "starting"
                    appendLine("- ${r.id} (${r.agentName}): $activity")
                }
            }
        }.trimEnd()

        return AgentToolResult(content = listOf(TextContent(text = text)), details = Unit)
    }
}
