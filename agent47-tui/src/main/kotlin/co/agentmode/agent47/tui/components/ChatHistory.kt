package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.rendering.wrapAnnotated
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.layout.height
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.tools.BatchToolCallResult
import co.agentmode.agent47.coding.core.tools.ToolDetails
import co.agentmode.agent47.coding.core.tools.TodoItem
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import co.agentmode.agent47.ui.core.util.summarizeToolArguments
import co.agentmode.agent47.ui.core.util.summarizeToolOutput
import co.agentmode.agent47.ui.core.util.formatDuration
import co.agentmode.agent47.ui.core.util.formatTokens
import co.agentmode.agent47.ui.core.util.findLastUserPromptText

/**
 * Creates and remembers a [ChatHistoryState] across recompositions.
 */
@Composable
public fun rememberChatHistoryState(): ChatHistoryState = remember { ChatHistoryState() }

/**
 * Renders the chat history as a fixed-height viewport with manual scrolling.
 *
 * All entries are flattened into a list of styled text lines, and only the
 * visible window (controlled by [ChatHistoryState.scrollTopLine]) is emitted
 * as Mosaic [Text] nodes inside a [Column] with a constrained [height].
 *
 * When content extends beyond the viewport, the first and/or last visible
 * lines are replaced with scroll indicators showing the count of hidden lines.
 */
@Composable
public fun ChatHistory(
    state: ChatHistoryState,
    width: Int,
    height: Int,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    version: Int = 0,
    spinnerFrame: Int = 0,
    cwd: String = "",
) {
    val theme = LocalThemeConfig.current
    val viewportHeight = height.coerceAtLeast(1)

    // Flatten all entries into rendered lines
    val allLines = buildList {
        state.entries.forEachIndexed { index, entry ->
            val entryLines = renderEntry(entry, width, state, markdownRenderer, diffRenderer, theme, spinnerFrame)
            addAll(entryLines)
            if (index != state.entries.lastIndex) {
                val nextEntry = state.entries[index + 1]
                val nextIsCommandResult = (nextEntry.message as? CustomMessage)?.customType == "command_result"
                if (!nextIsCommandResult) {
                    add(annotated(""))
                }
            }
        }
    }

    // Snap to bottom when pinned or explicitly requested, then clamp
    val maxScroll = (allLines.size - viewportHeight).coerceAtLeast(0)
    if (state.pinnedToBottom || state.scrollToBottom) {
        state.scrollTopLine = maxScroll
        state.scrollToBottom = false
    }
    if (state.scrollTopLine > maxScroll) {
        state.scrollTopLine = maxScroll
    }
    if (!state.pinnedToBottom && state.scrollTopLine >= maxScroll) {
        state.pinnedToBottom = true
    }

    val safeTop = state.scrollTopLine.coerceIn(0, maxScroll)
    val hasAbove = safeTop > 0
    val hasBelow = (safeTop + viewportHeight) < allLines.size

    // Reserve lines for scroll markers so they don't replace content
    val markerAboveHeight = if (hasAbove) 1 else 0
    val markerBelowHeight = if (hasBelow) 1 else 0
    val contentHeight = (viewportHeight - markerAboveHeight - markerBelowHeight).coerceAtLeast(0)

    val contentStart = safeTop + markerAboveHeight
    val contentEnd = (contentStart + contentHeight).coerceAtMost(allLines.size)
    val visibleLines = if (state.entries.isEmpty()) {
        renderBannerViewport(viewportHeight, width, cwd, theme)
    } else if (allLines.isEmpty()) {
        List(viewportHeight) { annotated("") }
    } else {
        buildList {
            if (hasAbove) {
                val pinnedPrompt = findLastUserPromptText(state.entries)
                if (pinnedPrompt != null) {
                    add(userPromptScrollMarker(pinnedPrompt, width, contentStart, theme))
                } else {
                    add(scrollMarker(width, hiddenLines = contentStart, up = true, theme.colors.muted))
                }
            }

            val contentSlice = allLines.subList(contentStart, contentEnd)
            val belowMarkerCount = if (hasBelow) 1 else 0
            val usedRows = size + contentSlice.size + belowMarkerCount
            val topPadding = (viewportHeight - usedRows).coerceAtLeast(0)

            // Pad at top to push content to the bottom of the viewport,
            // so messages appear just above the editor like a chat UI.
            if (topPadding > 0) {
                addAll(List(topPadding) { annotated("") })
            }

            addAll(contentSlice)

            if (hasBelow) {
                val hiddenBelow = allLines.size - contentEnd
                add(scrollMarker(width, hiddenLines = hiddenBelow, up = false, theme.colors.muted))
            }
        }
    }

    Text(
        buildAnnotatedString {
            visibleLines.forEachIndexed { index, line ->
                append(line)
                if (index < visibleLines.lastIndex) append('\n')
            }
        },
        modifier = Modifier.height(viewportHeight),
    )
}

private fun renderEntry(
    entry: ChatHistoryEntry,
    width: Int,
    state: ChatHistoryState,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    theme: ThemeConfig,
    spinnerFrame: Int = 0,
): List<AnnotatedString> {
    val toolExec = entry.toolExecution
    if (toolExec != null) {
        val collapsed = state.toolCollapsedState[entry.key] ?: toolExec.collapsed
        return renderToolExecutionLines(
            toolExec.copy(collapsed = collapsed),
            width,
            markdownRenderer,
            diffRenderer,
            theme,
            spinnerFrame,
        )
    }

    return when (val msg = entry.message) {
        is UserMessage -> renderUserMessageLines(msg, width, markdownRenderer, theme)
        is AssistantMessage -> {
            val thinkingCollapsed = state.thinkingCollapsedState[entry.key] ?: true
            renderAssistantMessageLines(msg, width, markdownRenderer, thinkingCollapsed, theme)
        }

        is BashExecutionMessage -> renderBashExecutionLines(msg, width, theme)
        is BranchSummaryMessage -> renderBranchSummaryLines(msg, width, theme)
        is CompactionSummaryMessage -> renderCompactionSummaryLines(msg, width, theme)
        is CustomMessage -> renderCustomMessageLines(msg, width, markdownRenderer, theme)
        else -> emptyList()
    }
}

private fun renderUserMessageLines(
    message: UserMessage,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val prompt = "❯ "
    val contentWidth = width - prompt.length
    val style = SpanStyle(
        color = com.jakewharton.mosaic.ui.Color.White,
        background = theme.userMessageBg,
    )
    var isFirstVisualLine = true
    message.content.forEach { block ->
        when (block) {
            is TextContent -> {
                block.text.split("\n").forEach { line ->
                    val wrapped = wrapAnnotated(annotated(line), contentWidth).map { it.text }
                    wrapped.forEach { segment ->
                        val prefix = if (isFirstVisualLine) prompt else " ".repeat(prompt.length)
                        isFirstVisualLine = false
                        add(buildAnnotatedString {
                            withStyle(SpanStyle(color = theme.colors.muted, background = theme.userMessageBg)) { append(prefix) }
                            withStyle(style) { append(segment) }
                        })
                    }
                }
            }
            else -> add(annotated("[${block.type}]", SpanStyle(color = theme.colors.muted)))
        }
    }
}

private fun renderAssistantMessageLines(
    message: AssistantMessage,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    thinkingCollapsed: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    if (message.stopReason == StopReason.ERROR) {
        val errorText = message.errorMessage ?: "Unknown error"
        errorText.split("\n").forEach { line ->
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.error)) {
                    append("\u2717 ")
                    append(line)
                }
            })
        }
        return@buildList
    }

    message.content.forEach { block ->
        when (block) {
            is TextContent -> addAll(markdownRenderer.render(block.text, width))
            is ThinkingContent -> addAll(renderThinkingLines(block, width, thinkingCollapsed, theme))
            is ToolCall -> {} // Tool calls are rendered via ToolExecutionView entries
            else -> add(annotated("[${block.type}]", SpanStyle(color = theme.colors.muted)))
        }
    }
}

private fun renderThinkingLines(
    block: ThinkingContent,
    width: Int,
    collapsed: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val text = block.thinking.trim()
    if (text.isBlank()) return emptyList()

    if (collapsed) {
        return listOf(annotated("[+] thinking (${text.length} chars)", SpanStyle(color = theme.colors.muted)))
    }

    return buildList {
        add(annotated("[-] thinking:", SpanStyle(color = theme.colors.muted)))
        text.split("\n").forEach { line ->
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.muted)) { append("  | ") }
                withStyle(SpanStyle(color = theme.thinkingText)) { append(line) }
            })
        }
    }
}

// ---------------------------------------------------------------------------
// Tree-drawing constants for compound tools (task, batch)
// ---------------------------------------------------------------------------

private const val TREE_BRANCH = "   \u251C\u2500 "
private const val TREE_LAST   = "   \u2514\u2500 "
private const val TREE_PIPE   = "   \u2502  "
private const val TREE_BLANK  = "      "

private fun treePrefix(index: Int, total: Int): String =
    if (index < total - 1) TREE_BRANCH else TREE_LAST

private fun treeContinuation(index: Int, total: Int): String =
    if (index < total - 1) TREE_PIPE else TREE_BLANK

// ---------------------------------------------------------------------------
// Unified sub-agent entry for tree rendering (merges completed + active)
// ---------------------------------------------------------------------------

private sealed class SubAgentEntry {
    data class Completed(val index: Int, val result: SubAgentResult) : SubAgentEntry()
    data class Active(val index: Int, val progress: SubAgentProgress) : SubAgentEntry()
}

// ---------------------------------------------------------------------------
// Pending activity labels
// ---------------------------------------------------------------------------

private fun pendingActivityLabel(toolName: String): String = when (toolName.lowercase()) {
    "read" -> "Reading\u2026"
    "write" -> "Writing\u2026"
    "edit", "multiedit" -> "Editing\u2026"
    "bash" -> "Running\u2026"
    "grep" -> "Searching\u2026"
    "glob", "find" -> "Searching\u2026"
    "ls" -> "Listing\u2026"
    "task" -> "Running\u2026"
    "batch" -> "Running\u2026"
    "todocreate", "todoupdate", "todowrite", "todoread" -> "Updating\u2026"
    else -> "Running\u2026"
}

// ---------------------------------------------------------------------------
// Status icon helpers
// ---------------------------------------------------------------------------

private const val ICON_SUCCESS = "\u2713"
private const val ICON_ERROR   = "\u2717"
private const val ICON_PENDING = "\u25CF"

// ---------------------------------------------------------------------------
// Main tool execution renderer (dispatcher)
// ---------------------------------------------------------------------------

private fun renderToolExecutionLines(
    execution: ToolExecutionView,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    theme: ThemeConfig,
    spinnerFrame: Int = 0,
): List<AnnotatedString> {
    val details = execution.details

    // Delegate compound tools to dedicated renderers
    if (details is ToolDetails.SubAgent || execution.toolName.lowercase() == "task") {
        return renderTaskToolLines(execution, width, markdownRenderer, theme, spinnerFrame)
    }
    if (details is ToolDetails.Batch || execution.toolName.lowercase() == "batch") {
        return renderBatchToolLines(execution, width, theme, spinnerFrame)
    }
    if (details is ToolDetails.Todo || execution.toolName.lowercase() in listOf("todocreate", "todoupdate", "todowrite", "todoread")) {
        return renderTodoToolLines(execution, width, theme, spinnerFrame)
    }

    return renderRegularToolLines(execution, width, theme, spinnerFrame)
}

// ---------------------------------------------------------------------------
// Regular tool rendering (read, edit, bash, grep, etc.)
// ---------------------------------------------------------------------------

private fun renderRegularToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
    spinnerFrame: Int,
): List<AnnotatedString> = buildList {
    val connector = "  \u23BF  "
    val connectorWidth = connector.length

    // Status icon + color
    val (icon, iconColor) = when {
        execution.pending -> ICON_PENDING to breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        execution.isError -> ICON_ERROR to theme.colors.error
        else -> ICON_SUCCESS to theme.colors.success
    }

    // Header: "[icon] toolName argSummary"
    val argBudget = (width - execution.toolName.length - 4).coerceAtLeast(10)
    val argSummary = if (execution.arguments.isNotBlank()) {
        summarizeToolArguments(execution.toolName, execution.arguments, argBudget)
    } else ""

    add(buildAnnotatedString {
        withStyle(SpanStyle(color = iconColor)) { append(icon) }
        append(" ")
        withStyle(SpanStyle(color = theme.toolTitle)) { append(execution.toolName) }
        if (argSummary.isNotEmpty()) {
            append(" ")
            withStyle(SpanStyle(color = theme.colors.dim)) { append(argSummary) }
        }
    })

    // Body based on state
    when {
        execution.pending -> addAll(renderPendingBody(execution, connector, connectorWidth, theme, spinnerFrame))
        !execution.collapsed -> addAll(renderExpandedBody(execution, connector, connectorWidth, width, theme))
        else -> addAll(renderCollapsedBody(execution, connector, theme))
    }
}

private fun renderPendingBody(
    execution: ToolExecutionView,
    connector: String,
    connectorWidth: Int,
    theme: ThemeConfig,
    spinnerFrame: Int,
): List<AnnotatedString> = buildList {
    val label = pendingActivityLabel(execution.toolName)
    val elapsed = if (execution.startedAt > 0) {
        val seconds = (System.currentTimeMillis() - execution.startedAt) / 1000
        if (seconds > 0) " (${seconds}s)" else ""
    } else ""
    val breathColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
        withStyle(SpanStyle(color = breathColor)) { append("$label$elapsed") }
    })
}

private fun renderExpandedBody(
    execution: ToolExecutionView,
    connector: String,
    connectorWidth: Int,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    if (execution.output.isBlank()) {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
            withStyle(SpanStyle(color = theme.colors.muted)) {
                append(if (execution.isError) "Error" else "Done")
            }
        })
        return@buildList
    }

    val lines = execution.output.split("\n")
    val limit = 80
    val contentWidth = (width - connectorWidth).coerceAtLeast(10)
    val shown = lines.take(limit)
    shown.forEachIndexed { index, line ->
        add(buildAnnotatedString {
            if (index == 0) {
                withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
            } else {
                append(" ".repeat(connectorWidth))
            }
            append(line.take(contentWidth))
        })
    }
    if (lines.size > limit) {
        add(annotated("${" ".repeat(connectorWidth)}\u2026 ${lines.size - limit} more lines", SpanStyle(color = theme.colors.muted)))
    }
}

private fun renderCollapsedBody(
    execution: ToolExecutionView,
    connector: String,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val summary = summarizeToolOutput(execution.toolName, execution.output, execution.details, execution.isError)
    val summaryColor = if (execution.isError) theme.colors.error else theme.colors.muted
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
        withStyle(SpanStyle(color = summaryColor)) { append(summary) }
    })
}

// ---------------------------------------------------------------------------
// Task tool rendering (sub-agent tree)
// ---------------------------------------------------------------------------

private fun renderTaskToolLines(
    execution: ToolExecutionView,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
    spinnerFrame: Int,
): List<AnnotatedString> = buildList {
    val subAgent = execution.details as? ToolDetails.SubAgent
    val results = subAgent?.results ?: emptyList()
    val progressList = subAgent?.activeProgressList?.ifEmpty { listOfNotNull(subAgent.activeProgress) } ?: emptyList()

    // Merge completed results and active progress into ordered entries
    val completedIds = results.map { it.id }.toSet()
    val entries = buildList<SubAgentEntry> {
        results.forEachIndexed { i, r -> add(SubAgentEntry.Completed(i, r)) }
        progressList.filter { it.id !in completedIds }.forEachIndexed { i, p ->
            add(SubAgentEntry.Active(results.size + i, p))
        }
    }
    val totalAgents = entries.size.coerceAtLeast(1)

    // Status icon + color for the header
    val (headerIcon, headerColor) = when {
        execution.pending -> ICON_PENDING to breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        execution.isError -> ICON_ERROR to theme.colors.error
        else -> ICON_SUCCESS to theme.colors.success
    }

    // Header line
    val headerLabel = if (execution.pending) {
        "Running $totalAgents agent${if (totalAgents != 1) "s" else ""}\u2026"
    } else {
        "$totalAgents agent${if (totalAgents != 1) "s" else ""} finished"
    }

    add(buildAnnotatedString {
        withStyle(SpanStyle(color = headerColor)) { append(headerIcon) }
        append(" ")
        withStyle(SpanStyle(color = theme.toolTitle)) { append(headerLabel) }
    })

    // Fallback if no entries yet
    if (entries.isEmpty()) {
        val elapsed = if (execution.startedAt > 0) {
            val seconds = (System.currentTimeMillis() - execution.startedAt) / 1000
            if (seconds > 0) " (${seconds}s)" else ""
        } else ""
        val breathColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append("  \u23BF  ") }
            withStyle(SpanStyle(color = breathColor)) { append("Running\u2026$elapsed") }
        })
        return@buildList
    }

    // Tree nodes
    entries.forEachIndexed { idx, entry ->
        val prefix = treePrefix(idx, entries.size)
        val continuation = treeContinuation(idx, entries.size)

        when (entry) {
            is SubAgentEntry.Completed -> {
                val result = entry.result
                val (icon, iconColor) = when {
                    result.aborted -> "\u2298" to theme.colors.warning
                    result.exitCode != 0 || result.error != null -> ICON_ERROR to theme.colors.error
                    else -> ICON_SUCCESS to theme.colors.success
                }
                val desc = result.description ?: result.task
                val durationStr = formatDuration(result.durationMs)

                if (execution.collapsed) {
                    // Collapsed: compact one-line per agent
                    val maxDesc = (width - prefix.length - result.agent.length - durationStr.length - 10).coerceAtLeast(10)
                    add(buildAnnotatedString {
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(prefix) }
                        withStyle(SpanStyle(color = iconColor)) { append(icon) }
                        append(" ")
                        withStyle(SpanStyle(color = theme.colors.accentBright, textStyle = TextStyle.Bold)) {
                            append(result.agent)
                        }
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(" \u00B7 ") }
                        withStyle(SpanStyle(color = theme.colors.muted)) {
                            append(desc.take(maxDesc))
                            if (desc.length > maxDesc) append("\u2026")
                        }
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(" ($durationStr)") }
                    })
                } else {
                    // Expanded: agent header with stats
                    val tokenStr = if (result.tokens > 0) formatTokens(result.tokens) else ""
                    val meta = listOfNotNull(
                        "${result.output.lines().count { it.isNotBlank() }} tools",
                        tokenStr.ifEmpty { null },
                    ).joinToString(" \u00B7 ")

                    val maxDesc = (width - prefix.length - result.agent.length - 8).coerceAtLeast(10)
                    add(buildAnnotatedString {
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(prefix) }
                        withStyle(SpanStyle(color = iconColor)) { append(icon) }
                        append(" ")
                        withStyle(SpanStyle(color = theme.colors.accentBright, textStyle = TextStyle.Bold)) {
                            append(result.agent)
                        }
                        append(" ")
                        withStyle(SpanStyle(color = theme.colors.muted)) {
                            append("(")
                            append(desc.take(maxDesc))
                            if (desc.length > maxDesc) append("\u2026")
                            append(")")
                        }
                        if (meta.isNotEmpty()) {
                            withStyle(SpanStyle(color = theme.colors.dim)) { append(" \u00B7 $meta") }
                        }
                    })

                    // Status sub-line
                    val errorText = result.error
                    val statusText = if (errorText != null) errorText.take(60) else "Done ($durationStr)"
                    val statusColor = if (errorText != null) theme.colors.error else theme.colors.muted
                    add(buildAnnotatedString {
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(continuation) }
                        withStyle(SpanStyle(color = theme.colors.dim)) { append("\u23BF  ") }
                        withStyle(SpanStyle(color = statusColor)) { append(statusText) }
                    })

                    // Output preview for expanded completed entries (first 5 lines)
                    if (result.output.isNotBlank() && errorText == null) {
                        val previewLines = result.output.lines().filter { it.isNotBlank() }.take(5)
                        val maxLine = (width - continuation.length - 3).coerceAtLeast(20)
                        previewLines.forEach { line ->
                            add(buildAnnotatedString {
                                withStyle(SpanStyle(color = theme.colors.dim)) { append(continuation) }
                                append("   ")
                                withStyle(SpanStyle(color = theme.colors.muted)) { append(line.take(maxLine)) }
                            })
                        }
                        val totalLines = result.output.lines().count { it.isNotBlank() }
                        if (totalLines > 5) {
                            add(buildAnnotatedString {
                                withStyle(SpanStyle(color = theme.colors.muted)) {
                                    append(continuation)
                                    append("   \u2026 ${totalLines - 5} more lines")
                                }
                            })
                        }
                    }
                }
            }

            is SubAgentEntry.Active -> {
                val progress = entry.progress
                val breathColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
                val tokenStr = if (progress.tokens > 0) formatTokens(progress.tokens) else ""
                val meta = listOfNotNull(
                    "${progress.toolCount} tools",
                    tokenStr.ifEmpty { null },
                ).joinToString(" \u00B7 ")

                val desc = progress.status.ifBlank { progress.currentTool ?: "" }
                val maxDesc = (width - prefix.length - progress.agent.length - 8).coerceAtLeast(10)
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.dim)) { append(prefix) }
                    withStyle(SpanStyle(color = breathColor)) { append(ICON_PENDING) }
                    append(" ")
                    withStyle(SpanStyle(color = theme.colors.accentBright, textStyle = TextStyle.Bold)) {
                        append(progress.agent)
                    }
                    if (desc.isNotBlank()) {
                        append(" ")
                        withStyle(SpanStyle(color = theme.colors.muted)) {
                            append("(")
                            append(desc.take(maxDesc))
                            if (desc.length > maxDesc) append("\u2026")
                            append(")")
                        }
                    }
                    if (meta.isNotEmpty()) {
                        withStyle(SpanStyle(color = theme.colors.dim)) { append(" \u00B7 $meta") }
                    }
                })

                // Activity sub-line
                val activity = progress.currentTool?.let { "Running $it\u2026" } ?: "Working\u2026"
                val elapsed = if (progress.durationMs > 0) " (${formatDuration(progress.durationMs)})" else ""
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.dim)) { append(continuation) }
                    withStyle(SpanStyle(color = theme.colors.dim)) { append("\u23BF  ") }
                    withStyle(SpanStyle(color = breathColor)) { append("$activity$elapsed") }
                })
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Batch tool rendering
// ---------------------------------------------------------------------------

private fun renderBatchToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
    spinnerFrame: Int,
): List<AnnotatedString> = buildList {
    val batch = execution.details as? ToolDetails.Batch
    val results = batch?.results ?: emptyList()

    val (headerIcon, headerColor) = when {
        execution.pending -> ICON_PENDING to breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        execution.isError -> ICON_ERROR to theme.colors.error
        else -> ICON_SUCCESS to theme.colors.success
    }

    if (execution.pending) {
        val count = results.size.let { if (it > 0) it else null }
        val label = if (count != null) "Running $count batch calls\u2026" else "Running batch\u2026"
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = headerColor)) { append(headerIcon) }
            append(" ")
            withStyle(SpanStyle(color = theme.toolTitle)) { append(label) }
        })
        val elapsed = if (execution.startedAt > 0) {
            val seconds = (System.currentTimeMillis() - execution.startedAt) / 1000
            if (seconds > 0) " (${seconds}s)" else ""
        } else ""
        val breathColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append("  \u23BF  ") }
            withStyle(SpanStyle(color = breathColor)) { append("Running\u2026$elapsed") }
        })
        return@buildList
    }

    // Header: "✓ N/M batch calls succeeded"
    val successCount = results.count { it.success }
    val failCount = results.size - successCount
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = headerColor)) { append(headerIcon) }
        append(" ")
        withStyle(SpanStyle(color = theme.colors.success)) { append("$successCount") }
        withStyle(SpanStyle(color = theme.colors.muted)) { append("/${results.size} batch calls succeeded") }
        if (failCount > 0) {
            withStyle(SpanStyle(color = theme.colors.muted)) { append(", ") }
            withStyle(SpanStyle(color = theme.colors.error)) { append("$failCount failed") }
        }
    })

    // Tree nodes for each result
    results.forEachIndexed { idx, result ->
        val prefix = treePrefix(idx, results.size)
        val (icon, iconColor) = if (result.success) {
            ICON_SUCCESS to theme.colors.success
        } else {
            ICON_ERROR to theme.colors.error
        }

        val argBudget = (width - prefix.length - result.toolName.length - 4).coerceAtLeast(10)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append(prefix) }
            withStyle(SpanStyle(color = iconColor)) { append(icon) }
            append(" ")
            withStyle(SpanStyle(color = theme.toolTitle)) { append(result.toolName) }
            if (result.output.isNotBlank() && !result.success) {
                val errLine = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                if (errLine.isNotEmpty()) {
                    append(" ")
                    withStyle(SpanStyle(color = theme.colors.error)) { append(errLine.take(argBudget)) }
                }
            }
        })
    }
}

// ---------------------------------------------------------------------------
// Todo tool rendering
// ---------------------------------------------------------------------------

private fun renderTodoToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
    spinnerFrame: Int,
): List<AnnotatedString> = buildList {
    val (headerIcon, headerColor) = when {
        execution.pending -> ICON_PENDING to breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        execution.isError -> ICON_ERROR to theme.colors.error
        else -> ICON_SUCCESS to theme.colors.success
    }

    // Header
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = headerColor)) { append(headerIcon) }
        append(" ")
        withStyle(SpanStyle(color = theme.toolTitle)) { append(execution.toolName) }
    })

    if (execution.pending) {
        val breathColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append("  \u23BF  ") }
            withStyle(SpanStyle(color = breathColor)) { append("Updating\u2026") }
        })
        return@buildList
    }

    // Todo items under connector
    val todo = execution.details as? ToolDetails.Todo
    val items = todo?.items ?: emptyList()
    if (items.isEmpty()) {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append("  \u23BF  ") }
            withStyle(SpanStyle(color = theme.colors.muted)) { append("Done") }
        })
        return@buildList
    }

    items.forEachIndexed { index, item ->
        val (statusIcon, statusColor) = when (item.status) {
            "completed" -> ICON_SUCCESS to theme.todoCompleted
            "in_progress" -> "\u25CB" to theme.todoInProgress
            "cancelled" -> ICON_ERROR to theme.todoCancelled
            else -> "\u2022" to theme.todoPending
        }
        val priorityColor = when (item.priority) {
            "high" -> theme.todoPriorityHigh
            "medium" -> theme.todoPriorityMedium
            else -> theme.todoPriorityLow
        }
        val priorityLabel = when (item.priority) {
            "high" -> "!"
            "medium" -> "-"
            else -> " "
        }
        val strikethrough = item.status == "completed" || item.status == "cancelled"
        val maxContent = (width - 12).coerceAtLeast(20)
        val connectorStr = if (index == 0) "  \u23BF  " else "     "

        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.dim)) { append(connectorStr) }
            withStyle(SpanStyle(color = statusColor)) { append(statusIcon) }
            append(" ")
            withStyle(SpanStyle(color = priorityColor)) { append("[$priorityLabel]") }
            append(" ")
            val contentStyle = if (strikethrough) {
                SpanStyle(color = theme.colors.muted)
            } else {
                SpanStyle()
            }
            withStyle(contentStyle) { append(item.content.take(maxContent)) }
        })
    }
}

private fun renderBashExecutionLines(
    message: BashExecutionMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = theme.toolTitle)) { append("bash") }
        append(" ")
        append(message.command.take(width - 5))
    })
    val exitStr = message.exitCode?.toString() ?: "?"
    add(annotated("exit: $exitStr", SpanStyle(color = theme.colors.muted)))
}

private fun renderBranchSummaryLines(
    message: BranchSummaryMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    add(annotated("Branch from: ${message.fromId}", SpanStyle(color = theme.colors.muted)))
    add(annotated(message.summary.take(width), SpanStyle(color = theme.colors.dim)))
}

private fun renderCompactionSummaryLines(
    message: CompactionSummaryMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    add(annotated("Context compacted (was ${message.tokensBefore} tokens)", SpanStyle(color = theme.colors.muted)))
    add(annotated(message.summary.take(width), SpanStyle(color = theme.colors.dim)))
}

private fun renderCustomMessageLines(
    message: CustomMessage,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
): List<AnnotatedString> {
    if (!message.display) return emptyList()
    if (message.customType == "command_result") {
        return renderCommandResultLines(message, width, theme)
    }
    return buildList {
        add(annotated("[${message.customType}]", SpanStyle(color = theme.colors.muted)))
        message.content.filterIsInstance<TextContent>().forEach { block ->
            addAll(markdownRenderer.render(block.text, width))
        }
    }
}

private fun renderCommandResultLines(
    message: CustomMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val lines = text.lines()
    return buildList {
        lines.forEachIndexed { index, line ->
            add(buildAnnotatedString {
                if (index == 0) {
                    withStyle(SpanStyle(color = theme.colors.dim)) { append("  \u23BF  ") }
                } else {
                    append("     ")
                }
                withStyle(SpanStyle(color = theme.colors.muted)) { append(line.take(width - 5)) }
            })
        }
    }
}

private fun scrollMarker(
    width: Int,
    hiddenLines: Int,
    up: Boolean,
    color: com.jakewharton.mosaic.ui.Color,
): AnnotatedString {
    val arrow = if (up) "▲" else "▼"
    val text = "$arrow $hiddenLines more"
    val padded = text.padEnd(width)
    return annotated(padded, SpanStyle(color = color))
}

/**
 * Renders a scroll-up marker that includes the user's prompt text,
 * so the user always sees what they typed even when scrolled to the bottom.
 */
private fun userPromptScrollMarker(
    prompt: String,
    width: Int,
    hiddenLines: Int,
    theme: ThemeConfig,
): AnnotatedString {
    val suffix = " ▲ $hiddenLines"
    val prefixStr = "❯ "
    val maxPromptLen = (width - prefixStr.length - suffix.length).coerceAtLeast(1)
    val truncated = if (prompt.length > maxPromptLen) {
        prompt.take(maxPromptLen - 1) + "\u2026"
    } else {
        prompt
    }

    return buildAnnotatedString {
        withStyle(SpanStyle(color = theme.colors.muted)) { append(prefixStr) }
        withStyle(SpanStyle(color = com.jakewharton.mosaic.ui.Color.White, background = theme.userMessageBg)) {
            append(truncated)
        }
        val used = prefixStr.length + truncated.length + suffix.length
        val gap = (width - used).coerceAtLeast(0)
        if (gap > 0) append(" ".repeat(gap))
        withStyle(SpanStyle(color = theme.colors.muted)) { append(suffix) }
    }
}

private const val BREATHING_CYCLE = 24

private fun breathingColor(
    frame: Int,
    from: com.jakewharton.mosaic.ui.Color,
    to: com.jakewharton.mosaic.ui.Color,
): com.jakewharton.mosaic.ui.Color {
    val phase = frame.mod(BREATHING_CYCLE)
    val half = BREATHING_CYCLE / 2
    val t = if (phase <= half) phase.toFloat() / half else (BREATHING_CYCLE - phase).toFloat() / half
    val (r1, g1, b1) = from
    val (r2, g2, b2) = to
    return com.jakewharton.mosaic.ui.Color(
        red = r1 + (r2 - r1) * t,
        green = g1 + (g2 - g1) * t,
        blue = b1 + (b2 - b1) * t,
    )
}

private const val BARCODE = "▐█ ▌▌█▐ ██▌▐ █▌█▐▐ ▌█ ▐██▌▐"
private const val BARCODE_HEIGHT = 4

private val BANNER_PHRASES = listOf(
    "Target acquired. Awaiting instructions.",
    "Another satisfying contract awaits.",
    "Clean code. No witnesses.",
    "The briefing is ready. What's the target?",
    "Precision is not optional.",
    "Every bug has a bounty.",
    "No loose ends. No leftover TODOs.",
    "Silent execution. Zero side effects.",
    "The scope is clear. Let's move.",
    "Deploying with surgical precision.",
)

private val sessionPhrase: String = BANNER_PHRASES.random()

private fun renderBannerViewport(
    viewportHeight: Int,
    width: Int,
    cwd: String,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val bannerLines = renderBanner(width, cwd, theme)
    val topPad = ((viewportHeight - bannerLines.size) / 2).coerceAtLeast(0)
    return buildList {
        repeat(topPad) { add(annotated("")) }
        addAll(bannerLines)
        val remaining = viewportHeight - size
        repeat(remaining.coerceAtLeast(0)) { add(annotated("")) }
    }
}

private fun renderBanner(
    width: Int,
    cwd: String,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val gap = "   "
    val title = "Agent 47"
    val phrase = sessionPhrase
    val textWidths = listOf(title.length, phrase.length, cwd.length)
    val totalContentWidth = BARCODE.length + gap.length + textWidths.max()
    val leftPad = ((width - totalContentWidth) / 2).coerceAtLeast(0)
    val padStr = " ".repeat(leftPad)

    return buildList {
        for (row in 0 until BARCODE_HEIGHT) {
            add(buildAnnotatedString {
                append(padStr)
                withStyle(SpanStyle(color = com.jakewharton.mosaic.ui.Color.White)) { append(BARCODE) }
                when (row) {
                    0 -> {
                        append(gap)
                        withStyle(SpanStyle(color = theme.markdownText, textStyle = TextStyle.Bold)) {
                            append(title)
                        }
                    }
                    1 -> {
                        append(gap)
                        withStyle(SpanStyle(color = theme.colors.muted)) { append(cwd) }
                    }
                    3 -> {
                        append(gap)
                        withStyle(SpanStyle(color = theme.colors.muted)) { append(phrase) }
                    }
                }
            })
        }
    }
}
