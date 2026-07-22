package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.InboxMessage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Collects results and messages from background sub-agents launched with the `task` tool.
 * Draining the inbox is idempotent per message — each result/message is returned once.
 *
 * With `wait`, blocks until at least one agent finishes (or none remain running). With `verbose`,
 * annotates still-running agents with their turn/tool/token stats.
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
    ) {
        boolean("wait") { description = "Block until at least one agent finishes (or none are running). Default: false." }
        boolean("verbose") { description = "Include per-agent turn/tool/token stats for still-running agents. Default: false." }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<Unit>?,
    ): AgentToolResult<Unit> {
        val wait = parameters.boolean("wait") ?: false
        val verbose = parameters.boolean("verbose") ?: false

        val collected = mutableListOf<InboxMessage>()
        collected += backgroundAgents.drainInbox()

        if (wait) {
            val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
            while (collected.none { it.kind != InboxMessage.Kind.MESSAGE } &&
                backgroundAgents.hasRunning() &&
                System.currentTimeMillis() < deadline
            ) {
                delay(POLL_INTERVAL_MS)
                collected += backgroundAgents.drainInbox()
            }
        }

        val running = backgroundAgents.runningStatus()

        val text = buildString {
            if (collected.isEmpty() && running.isEmpty()) {
                append("No background agents are running and the inbox is empty.")
                return@buildString
            }

            val finished = collected.filter { it.kind != InboxMessage.Kind.MESSAGE }
            val notes = collected.filter { it.kind == InboxMessage.Kind.MESSAGE }

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
                    val progress = r.progress
                    val activity = progress?.let { p -> p.currentTool?.let { "running $it" } ?: "working" } ?: "starting"
                    if (verbose && progress != null) {
                        appendLine(
                            "- ${r.id} (${r.agentName}): $activity " +
                                "[turns ${progress.turnCount}, ${progress.toolCount} tools, ${progress.tokens} tokens, " +
                                "${progress.durationMs / 1000}s]",
                        )
                    } else {
                        appendLine("- ${r.id} (${r.agentName}): $activity")
                    }
                }
            }
        }.trimEnd()

        return AgentToolResult(content = listOf(TextContent(text = text)), details = Unit)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 200L
        const val WAIT_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
