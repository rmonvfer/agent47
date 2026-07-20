package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import kotlinx.serialization.json.JsonObject

/**
 * Sends a message to a running background agent (to steer it) or to `orchestrator`. The same
 * tool is given to the orchestrator (from = "orchestrator") and to each sub-agent (from = its
 * own id), so agents can message the orchestrator and each other.
 */
public class SendMessageTool(
    private val backgroundAgents: BackgroundAgents,
    private val from: String,
) : AgentTool<Unit> {

    override val label: String = "send_message"

    override val definition: ToolDefinition = toolDefinition(
        "send_message",
        "Send a message to a running background agent by its id to steer it, or to " +
            "\"orchestrator\" to notify the main agent. The recipient picks it up on its next step; " +
            "the orchestrator reads it via check_inbox.",
    ) {
        string("to") { required = true; description = "Target agent id, or \"orchestrator\"" }
        string("message") { required = true; description = "The message to deliver" }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<Unit>?,
    ): AgentToolResult<Unit> {
        val to = parameters.string("to") ?: return textResult("Error: missing 'to'")
        val message = parameters.string("message") ?: return textResult("Error: missing 'message'")

        val delivered = backgroundAgents.post(from = from, to = to, text = message)
        return textResult(
            if (delivered) {
                "Message delivered to $to."
            } else {
                "Could not deliver to '$to' — no such running agent. Use check_inbox to see who is still running."
            },
        )
    }

    private fun textResult(text: String): AgentToolResult<Unit> =
        AgentToolResult(content = listOf(TextContent(text = text)), details = Unit)
}
