package co.agentmode.agent47.gui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.gui.rendering.MarkdownView
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import co.agentmode.agent47.ui.core.util.formatDuration
import co.agentmode.agent47.ui.core.util.summarizeToolArguments
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
public fun ChatPanel(
    state: ChatHistoryState,
    listState: LazyListState = rememberLazyListState(),
    isStreaming: Boolean = false,
    activityLabel: String = "",
    cwd: String = "",
    modifier: Modifier = Modifier,
) {
    val version = state.version

    LaunchedEffect(version) {
        if (state.pinnedToBottom && state.entries.isNotEmpty()) {
            listState.animateScrollToItem(state.entries.lastIndex)
        }
    }

    if (state.entries.isEmpty()) {
        BannerView(cwd = cwd, modifier = modifier)
    } else {
        LazyColumn(
            modifier = modifier,
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.entries, key = { it.key }) { entry ->
                MessageView(entry = entry, chatState = state)
            }

            if (isStreaming) {
                item(key = "activity-indicator") {
                    ActivityIndicator(label = activityLabel)
                }
            }
        }
    }
}

@Composable
private fun BannerView(cwd: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Agent 47",
                style = JewelTheme.defaultTextStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                color = JewelTheme.globalColors.text.normal,
            )
            if (cwd.isNotBlank()) {
                Text(
                    text = cwd,
                    color = JewelTheme.globalColors.text.info,
                )
            }
            Text(
                text = "Target acquired. Awaiting instructions.",
                color = JewelTheme.globalColors.text.info,
            )
        }
    }
}

@Composable
private fun ActivityIndicator(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        val frames = listOf("\u280B", "\u2819", "\u2839", "\u2838", "\u283C", "\u2834", "\u2826", "\u2827", "\u2807", "\u280F")
        val displayLabel = label.ifBlank { "Thinking" }
        Text(
            text = frames[((System.currentTimeMillis() / 80) % frames.size).toInt()],
            color = Color(0xFF64B5F6),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$displayLabel...",
            color = JewelTheme.globalColors.text.info,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun MessageView(entry: ChatHistoryEntry, chatState: ChatHistoryState) {
    val toolExec = entry.toolExecution
    if (toolExec != null) {
        ToolExecutionCard(toolExec, entryKey = entry.key, chatState = chatState)
        return
    }

    when (val msg = entry.message) {
        is UserMessage -> UserMessageBubble(msg)
        is AssistantMessage -> AssistantMessageBubble(msg, entryKey = entry.key, chatState = chatState)
        is CustomMessage -> {
            if (msg.display) {
                CustomMessageView(msg)
            }
        }
        is CompactionSummaryMessage -> CompactionSummaryView(msg)
        is BranchSummaryMessage -> BranchSummaryView(msg)
        else -> {}
    }
}

@Composable
private fun UserMessageBubble(message: UserMessage) {
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF2D5B8A))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    message: AssistantMessage,
    entryKey: String,
    chatState: ChatHistoryState,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(end = 48.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        message.content.forEach { block ->
            when (block) {
                is TextContent -> {
                    if (block.text.isNotBlank()) {
                        MarkdownView(
                            rawMarkdown = block.text,
                        )
                    }
                }
                is ThinkingContent -> {
                    if (block.thinking.isNotBlank()) {
                        ThinkingBlock(
                            thinking = block.thinking,
                            entryKey = entryKey,
                            chatState = chatState,
                        )
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ThinkingBlock(
    thinking: String,
    entryKey: String,
    chatState: ChatHistoryState,
) {
    val isCollapsed = chatState.thinkingCollapsedState[entryKey] ?: true
    val charCount = thinking.length
    val headerText = if (isCollapsed) {
        "[+] thinking ($charCount chars)"
    } else {
        "[-] thinking ($charCount chars)"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x15808080)),
    ) {
        Text(
            text = headerText,
            color = Color(0xFF9E9E9E),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { chatState.thinkingCollapsedState[entryKey] = !isCollapsed }
                .padding(8.dp),
            fontStyle = FontStyle.Italic,
        )

        AnimatedVisibility(visible = !isCollapsed) {
            Text(
                text = thinking,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ToolExecutionCard(
    execution: ToolExecutionView,
    entryKey: String,
    chatState: ChatHistoryState,
) {
    val statusColor = when {
        execution.pending -> Color(0xFFFFA726)
        execution.isError -> Color(0xFFEF5350)
        else -> Color(0xFF66BB6A)
    }

    val isCollapsed = chatState.toolCollapsedState[entryKey] ?: execution.collapsed
    val argSummary = summarizeToolArguments(execution.toolName, execution.arguments, 80)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x10808080))
            .padding(1.dp),
    ) {
        // Header row: status indicator + tool name + argument summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    chatState.toolCollapsedState[entryKey] = !isCollapsed
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor),
            )
            Spacer(Modifier.width(8.dp))

            // Tool name
            Text(
                text = execution.toolName,
                color = statusColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))

            // Argument summary
            if (argSummary.isNotBlank()) {
                Text(
                    text = argSummary,
                    color = JewelTheme.globalColors.text.info,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            // Elapsed time for pending tools
            if (execution.pending && execution.startedAt > 0) {
                val elapsed = System.currentTimeMillis() - execution.startedAt
                Text(
                    text = formatDuration(elapsed),
                    color = Color(0xFF9E9E9E),
                    fontSize = 11.sp,
                )
            }

            // Collapse indicator
            Text(
                text = if (isCollapsed) " [+]" else " [-]",
                color = Color(0xFF757575),
            )
        }

        // Expanded content
        AnimatedVisibility(visible = !isCollapsed) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Tool-specific detail rendering
                when (val details = execution.details) {
                    is ToolDetails.SubAgent -> SubAgentDetails(details)
                    is ToolDetails.Todo -> TodoDetails(details)
                    is ToolDetails.Batch -> BatchDetails(details)
                    else -> {}
                }

                // Output text
                if (execution.output.isNotBlank()) {
                    val preview = execution.output.lines().take(20).joinToString("\n")
                    Text(
                        text = preview,
                        color = JewelTheme.globalColors.text.info,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                    if (execution.output.lines().size > 20) {
                        Text(
                            text = "... ${execution.output.lines().size - 20} more lines",
                            color = Color(0xFF757575),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubAgentDetails(details: ToolDetails.SubAgent) {
    // Active progress
    details.activeProgressList.forEach { progress ->
        Row(
            modifier = Modifier.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u2022",
                color = Color(0xFF64B5F6),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${progress.agent}: ${progress.status}",
                color = JewelTheme.globalColors.text.info,
                fontSize = 12.sp,
            )
        }
    }

    // Completed results
    details.results.forEach { result ->
        SubAgentResultRow(result)
    }
}

@Composable
private fun SubAgentResultRow(result: SubAgentResult) {
    val statusIcon = when {
        result.aborted -> "\u2716"
        result.error != null -> "\u2716"
        result.exitCode == 0 -> "\u2714"
        else -> "\u2716"
    }
    val statusColor = when {
        result.aborted -> Color(0xFFFFA726)
        result.exitCode == 0 -> Color(0xFF66BB6A)
        else -> Color(0xFFEF5350)
    }

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = statusIcon, color = statusColor, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${result.agent}/${result.id}",
            color = JewelTheme.globalColors.text.info,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${result.durationMs}ms | ${result.tokens} tokens",
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
        )
    }
    if (result.output.isNotBlank()) {
        val preview = result.output.lines().take(3).joinToString("\n")
        Text(
            text = preview,
            color = Color(0xFF9E9E9E),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun TodoDetails(details: ToolDetails.Todo) {
    details.items.forEach { item ->
        val statusIcon = when (item.status) {
            "completed" -> "\u2714"
            "in_progress" -> "\u25CB"
            else -> "\u2022"
        }
        val statusColor = when (item.status) {
            "completed" -> Color(0xFF66BB6A)
            "in_progress" -> Color(0xFF64B5F6)
            else -> Color(0xFF9E9E9E)
        }

        Row(
            modifier = Modifier.padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = statusIcon, color = statusColor, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.content,
                color = JewelTheme.globalColors.text.info,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun BatchDetails(details: ToolDetails.Batch) {
    val successCount = details.results.count { it.success }
    val failureCount = details.results.count { !it.success }
    Text(
        text = "Batch: $successCount succeeded, $failureCount failed",
        color = if (failureCount > 0) Color(0xFFEF5350) else Color(0xFF66BB6A),
        fontSize = 12.sp,
    )
}

@Composable
private fun CustomMessageView(message: CustomMessage) {
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    if (text.isNotBlank()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x10808080))
                .padding(8.dp),
        ) {
            Text(
                text = text,
                color = JewelTheme.globalColors.text.info,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun CompactionSummaryView(message: CompactionSummaryMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x10808080))
            .padding(8.dp),
    ) {
        Text(
            text = "\u2702 Context compacted (${message.tokensBefore} tokens before)",
            color = Color(0xFF9E9E9E),
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun BranchSummaryView(message: BranchSummaryMessage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0x10808080))
            .padding(8.dp),
    ) {
        Text(
            text = "\u2500 Branch summary: ${message.summary.take(120)}",
            color = Color(0xFF9E9E9E),
            fontStyle = FontStyle.Italic,
        )
    }
}
