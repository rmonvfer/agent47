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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import co.agentmode.agent47.gui.theme.AppColors
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import co.agentmode.agent47.ui.core.util.formatDuration
import co.agentmode.agent47.ui.core.util.summarizeToolArguments
import com.woowla.compose.icon.collections.tabler.Tabler
import com.woowla.compose.icon.collections.tabler.tabler.Outline
import com.woowla.compose.icon.collections.tabler.tabler.outline.*
import org.jetbrains.jewel.foundation.modifier.onHover
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.typography
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.InlineInformationBanner
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

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
        Box(modifier = modifier) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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

            VerticalScrollbar(
                scrollState = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun BannerView(cwd: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Agent 47",
                style = JewelTheme.typography.h0TextStyle,
                color = AppColors.textPrimary,
            )
            if (cwd.isNotBlank()) {
                Text(
                    text = cwd,
                    color = AppColors.textSecondary,
                )
            }
            Text(
                text = "Target acquired. Awaiting instructions.",
                color = AppColors.textSecondary,
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
        CircularProgressIndicator(modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        val displayLabel = label.ifBlank { "Thinking" }
        Text(
            text = "$displayLabel...",
            color = AppColors.textSecondary,
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
                .background(AppColors.userMessageBackground)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(AppColors.thinkingBackground),
    ) {
        var thinkingHovered by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onHover { thinkingHovered = it }
                .background(if (thinkingHovered) AppColors.hoverBackground else Color.Transparent)
                .clickable { chatState.thinkingCollapsedState[entryKey] = !isCollapsed }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCollapsed) Tabler.Outline.ChevronRight else Tabler.Outline.ChevronDown,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                modifier = Modifier.size(12.dp),
                tint = AppColors.textMuted,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "thinking ($charCount chars)",
                color = AppColors.textMuted,
                fontStyle = FontStyle.Italic,
            )
        }

        AnimatedVisibility(visible = !isCollapsed) {
            Text(
                text = thinking,
                color = AppColors.textMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = JewelTheme.consoleTextStyle,
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
        execution.pending -> AppColors.warning
        execution.isError -> AppColors.error
        else -> AppColors.success
    }

    val isCollapsed = chatState.toolCollapsedState[entryKey] ?: execution.collapsed
    val argSummary = summarizeToolArguments(execution.toolName, execution.arguments, 80)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(AppColors.subtleBackground)
            .padding(1.dp),
    ) {
        // Header row: status icon + tool name + argument summary
        var toolHeaderHovered by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onHover { toolHeaderHovered = it }
                .background(if (toolHeaderHovered) AppColors.hoverBackground else Color.Transparent)
                .clickable {
                    chatState.toolCollapsedState[entryKey] = !isCollapsed
                }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status icon
            when {
                execution.pending -> CircularProgressIndicator(modifier = Modifier.size(12.dp))
                execution.isError -> Icon(
                    imageVector = Tabler.Outline.X,
                    contentDescription = "Error",
                    modifier = Modifier.size(12.dp),
                    tint = statusColor,
                )
                else -> Icon(
                    imageVector = Tabler.Outline.Check,
                    contentDescription = "Success",
                    modifier = Modifier.size(12.dp),
                    tint = statusColor,
                )
            }
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
                    color = AppColors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            // Elapsed time for pending tools
            if (execution.pending && execution.startedAt > 0) {
                val elapsed = System.currentTimeMillis() - execution.startedAt
                Text(
                    text = formatDuration(elapsed),
                    color = AppColors.textMuted,
                    style = JewelTheme.typography.small,
                )
            }

            // Collapse indicator
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = if (isCollapsed) Tabler.Outline.ChevronRight else Tabler.Outline.ChevronDown,
                contentDescription = if (isCollapsed) "Expand" else "Collapse",
                modifier = Modifier.size(12.dp),
                tint = AppColors.textDim,
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
                        color = AppColors.textSecondary,
                        style = JewelTheme.consoleTextStyle,
                    )
                    if (execution.output.lines().size > 20) {
                        Text(
                            text = "... ${execution.output.lines().size - 20} more lines",
                            color = AppColors.textDim,
                            style = JewelTheme.typography.small,
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
            CircularProgressIndicator(modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${progress.agent}: ${progress.status}",
                color = AppColors.textSecondary,
                style = JewelTheme.typography.medium,
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
    val statusColor = when {
        result.aborted -> AppColors.warning
        result.exitCode == 0 -> AppColors.success
        else -> AppColors.error
    }

    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (result.exitCode == 0 && !result.aborted) Tabler.Outline.Check else Tabler.Outline.X,
            contentDescription = if (result.exitCode == 0) "Success" else "Failed",
            modifier = Modifier.size(12.dp),
            tint = statusColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${result.agent}/${result.id}",
            color = AppColors.textSecondary,
            style = JewelTheme.typography.medium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${result.durationMs}ms | ${result.tokens} tokens",
            color = AppColors.textMuted,
            style = JewelTheme.typography.small,
        )
    }
    if (result.output.isNotBlank()) {
        val preview = result.output.lines().take(3).joinToString("\n")
        Text(
            text = preview,
            color = AppColors.textMuted,
            style = JewelTheme.typography.small,
            modifier = Modifier.padding(start = 20.dp),
        )
    }
}

@Composable
private fun TodoDetails(details: ToolDetails.Todo) {
    details.items.forEach { item ->
        val statusColor = when (item.status) {
            "completed" -> AppColors.success
            "in_progress" -> AppColors.info
            else -> AppColors.textMuted
        }

        Row(
            modifier = Modifier.padding(vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.status) {
                "completed" -> Icon(
                    imageVector = Tabler.Outline.Check,
                    contentDescription = "Completed",
                    modifier = Modifier.size(12.dp),
                    tint = statusColor,
                )
                "in_progress" -> CircularProgressIndicator(modifier = Modifier.size(12.dp))
                else -> Icon(
                    imageVector = Tabler.Outline.Point,
                    contentDescription = "Pending",
                    modifier = Modifier.size(12.dp),
                    tint = statusColor,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = item.content,
                color = AppColors.textSecondary,
                style = JewelTheme.typography.medium,
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
        color = if (failureCount > 0) AppColors.error else AppColors.success,
        style = JewelTheme.typography.medium,
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
                .background(AppColors.subtleBackground)
                .padding(8.dp),
        ) {
            Text(
                text = text,
                color = AppColors.textSecondary,
                style = JewelTheme.consoleTextStyle,
            )
        }
    }
}

@Composable
private fun CompactionSummaryView(message: CompactionSummaryMessage) {
    InlineInformationBanner(
        text = "Context compacted (${message.tokensBefore} tokens before)",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BranchSummaryView(message: BranchSummaryMessage) {
    InlineInformationBanner(
        text = "Branch summary: ${message.summary.take(120)}",
        modifier = Modifier.fillMaxWidth(),
    )
}
