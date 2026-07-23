package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.SelectItem
import java.nio.file.Files
import java.nio.file.Path

internal fun openSubAgentResultOverlay(
    chatHistoryState: ChatHistoryState,
    overlayHostState: OverlayHostState,
    appendSystemMessage: (String) -> Unit,
) {
    val lastTaskExecution = chatHistoryState.entries.asReversed()
        .firstOrNull { entry ->
            val details = entry.toolExecution?.details
            details is ToolDetails.SubAgent && details.results.isNotEmpty()
        }?.toolExecution

    val subAgent = lastTaskExecution?.details as? ToolDetails.SubAgent
    if (subAgent == null || subAgent.results.isEmpty()) {
        appendSystemMessage("No sub-agent results available.")
        return
    }

    val results = subAgent.results
    if (results.size == 1) {
        showSubAgentResult(results.first(), overlayHostState)
        return
    }

    val options = results.map { result ->
        val status = when {
            result.aborted -> "ABORTED"
            result.error != null -> "ERROR"
            result.exitCode == 0 -> "OK"
            else -> "FAILED"
        }
        SelectItem(
            label = "[$status] ${result.agent}/${result.id} - ${result.description ?: result.task.take(40)}",
            value = result,
        )
    }

    overlayHostState.push(
        title = "Sub-Agent Results",
        items = options,
        selectedIndex = 0,
        onSubmit = { result -> showSubAgentResult(result, overlayHostState) },
    )
}

@Suppress("CyclomaticComplexMethod")
internal fun showSubAgentResult(
    result: SubAgentResult,
    overlayHostState: OverlayHostState,
) {
    val lines = buildList {
        add("Agent: ${result.agent}  ID: ${result.id}")
        add("Task: ${result.description ?: result.task.take(80)}")
        add("Status: ${if (result.exitCode == 0) "SUCCESS" else "FAILED"}  Duration: ${result.durationMs}ms  Tokens: ${result.tokens}")
        if (result.error != null) {
            add("Error: ${result.error}")
        }
        add("")

        // Load session file if available
        val sessionPath = result.sessionFile?.let { Path.of(it) }
        if (sessionPath != null && Files.exists(sessionPath)) {
            add("--- Session Transcript ---")
            add("")
            val sessionManager = runCatching {
                SessionManager(sessionPath)
            }.getOrNull()
            if (sessionManager != null) {
                val context = sessionManager.buildContext()
                context.messages.forEach { message ->
                    when (message) {
                        is AssistantMessage -> {
                            add("[assistant]")
                            message.content.filterIsInstance<TextContent>().forEach { block ->
                                block.text.lines().forEach { line -> add("  $line") }
                            }
                            add("")
                        }
                        is UserMessage -> {
                            add("[user]")
                            message.content.filterIsInstance<TextContent>().forEach { block ->
                                block.text.lines().forEach { line -> add("  $line") }
                            }
                            add("")
                        }
                        is ToolResultMessage -> {
                            val output = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                            val preview = output.lines().take(5).joinToString("\n")
                            add("[tool:${message.toolName}] ${if (message.isError) "ERROR" else "OK"}")
                            preview.lines().forEach { line -> add("  $line") }
                            if (output.lines().size > 5) add("  ... ${output.lines().size - 5} more lines")
                            add("")
                        }
                        else -> {}
                    }
                }
            } else {
                add("(failed to load session file)")
            }
        } else {
            // Fallback: show output
            add("--- Output ---")
            add("")
            result.output.lines().forEach { line -> add(line) }
        }
    }

    overlayHostState.pushScrollableText(
        title = "Sub-Agent: ${result.agent}/${result.id}",
        lines = lines,
    )
}
