package co.agentmode.agent47.tui.components

import androidx.compose.runtime.*
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.rendering.annotated
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/**
 * Represents a single entry in the chat history, keyed for identity-based updates.
 */
@Stable
public class ChatHistoryEntry(
    public val key: String,
    message: Message? = null,
    toolExecution: ToolExecutionView? = null,
) {
    public var message: Message? by mutableStateOf(message)
        internal set
    public var toolExecution: ToolExecutionView? by mutableStateOf(toolExecution)
        internal set
}

/**
 * Holds the full state of the chat history: entries, scroll offset, and
 * per-item collapse toggles. Designed to be driven by agent events from
 * outside Compose (e.g., a LaunchedEffect collecting a Flow).
 *
 * All mutations trigger recomposition automatically because the backing
 * fields are Compose snapshot-state objects.
 */
@Stable
public class ChatHistoryState {
    internal val entries = mutableStateListOf<ChatHistoryEntry>()
    internal var scrollTopLine by mutableIntStateOf(0)
    internal val toolCollapsedState = mutableStateMapOf<String, Boolean>()
    internal val thinkingCollapsedState = mutableStateMapOf<String, Boolean>()

    private var activeAssistantKey: String? = null
    private var assistantSequence by mutableLongStateOf(0L)
    internal var pinnedToBottom by mutableStateOf(true)
    internal var version by mutableIntStateOf(0)
        private set

    public fun appendMessage(message: Message) {
        if (message is AssistantMessage) {
            startAssistantMessage(message)
            return
        }
        val key = messageKey(message)
        if (entries.any { it.key == key }) return
        entries += ChatHistoryEntry(
            key = key,
            message = message,
            toolExecution = toolExecutionFor(message),
        )
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateMessage(message: Message) {
        if (message is AssistantMessage) {
            updateAssistantMessage(message)
            return
        }
        val key = messageKey(message)
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) {
            appendMessage(message)
            return
        }
        entries[index].message = message
        if (message is ToolResultMessage) {
            entries[index].toolExecution = toolExecutionFor(message)
                ?: entries[index].toolExecution
        }
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun startAssistantMessage(message: AssistantMessage) {
        val key = nextAssistantKey()
        entries += ChatHistoryEntry(key = key, message = message)
        activeAssistantKey = key
        thinkingCollapsedState[key] = true
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateAssistantMessage(message: AssistantMessage) {
        val activeKey = activeAssistantKey
        val activeIndex = if (activeKey != null) entries.indexOfFirst { it.key == activeKey } else -1
        val index = if (activeIndex >= 0) {
            activeIndex
        } else {
            entries.indexOfLast { it.message is AssistantMessage }
        }
        if (index < 0) {
            startAssistantMessage(message)
            return
        }
        entries[index].message = message
        activeAssistantKey = entries[index].key
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun endAssistantMessage(message: AssistantMessage) {
        updateAssistantMessage(message)
        activeAssistantKey = null
    }

    public fun appendToolExecution(execution: ToolExecutionView) {
        val key = "tool:${execution.toolCallId}"
        toolCollapsedState.putIfAbsent(key, defaultToolCollapsed(execution.toolName))
        entries += ChatHistoryEntry(key = key, toolExecution = execution)
        version++
        if (pinnedToBottom) scrollToBottom = true
    }

    public fun updateToolExecution(execution: ToolExecutionView) {
        val key = "tool:${execution.toolCallId}"
        val index = entries.indexOfFirst { it.key == key }
        if (index < 0) {
            appendToolExecution(execution)
            return
        }
        val existing = entries[index].toolExecution
        val preservedStartedAt = if (execution.startedAt == 0L && existing != null) existing.startedAt else execution.startedAt
        entries[index].toolExecution = execution.copy(
            collapsed = toolCollapsedState[key] ?: execution.collapsed,
            startedAt = preservedStartedAt,
        )
    }

    public fun scrollUp(lines: Int = 1) {
        scrollTopLine = (scrollTopLine - lines.coerceAtLeast(1)).coerceAtLeast(0)
        pinnedToBottom = false
    }

    public fun scrollDown(lines: Int = 1) {
        scrollTopLine += lines.coerceAtLeast(1)
    }

    public fun toggleLatestToolCollapsed(): Boolean {
        val entry = entries.asReversed().firstOrNull { it.toolExecution != null } ?: return false
        val key = entry.key
        val current = toolCollapsedState[key] ?: false
        toolCollapsedState[key] = !current
        return true
    }

    public fun toggleLatestThinkingCollapsed(): Boolean {
        val entry = entries.asReversed().firstOrNull { e ->
            val msg = e.message
            msg is AssistantMessage && msg.content.any { it is ThinkingContent }
        } ?: return false
        val key = entry.key
        val current = thinkingCollapsedState[key] ?: true
        thinkingCollapsedState[key] = !current
        return true
    }

    public fun hasEntries(): Boolean = entries.isNotEmpty()

    /**
     * Flag consumed by the composable to auto-scroll to the bottom on next frame.
     * Reset after the scroll is applied.
     */
    internal var scrollToBottom by mutableStateOf(false)

    private fun messageKey(message: Message): String = when (message) {
        is ToolResultMessage -> "tool:${message.toolCallId}"
        else -> "${message.role}:${message.timestamp}"
    }

    private fun nextAssistantKey(): String {
        assistantSequence += 1
        return "assistant-stream:$assistantSequence"
    }

    private fun toolExecutionFor(message: Message): ToolExecutionView? = when (message) {
        is ToolResultMessage -> ToolExecutionView.fromToolResult(
            result = message,
            collapsed = defaultToolCollapsed(message.toolName),
        )

        else -> null
    }

    private fun defaultToolCollapsed(@Suppress("UNUSED_PARAMETER") toolName: String): Boolean = true
}

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
                add(annotated(""))
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
    message.content.forEach { block ->
        when (block) {
            is TextContent -> {
                block.text.split("\n").forEachIndexed { index, line ->
                    val prefix = if (index == 0) prompt else " ".repeat(prompt.length)
                    add(buildAnnotatedString {
                        withStyle(SpanStyle(color = theme.colors.muted, background = theme.userMessageBg)) { append(prefix) }
                        withStyle(style) { append(line.take(contentWidth)) }
                    })
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

private fun renderToolExecutionLines(
    execution: ToolExecutionView,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    theme: ThemeConfig,
    spinnerFrame: Int = 0,
): List<AnnotatedString> = buildList {
    val connector = "  ⎿  "
    val connectorWidth = connector.length

    // Tool name color encodes status; smooth breathing pulse while pending
    val nameColor = when {
        execution.pending -> breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
        execution.isError -> theme.colors.error
        else -> theme.colors.success
    }

    val argBudget = (width - execution.toolName.length - 1).coerceAtLeast(10)
    val argSummary = if (execution.arguments.isNotBlank()) {
        summarizeToolArguments(execution.toolName, execution.arguments, argBudget)
    } else ""

    // Header: tool name colored by status, args in dim
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = nameColor)) { append(execution.toolName) }
        if (argSummary.isNotEmpty()) {
            append(" ")
            withStyle(SpanStyle(color = theme.colors.dim)) { append(argSummary) }
        }
    })

    // Body
    if (execution.pending) {
        val subAgent = execution.details as? ToolDetails.SubAgent
        if (subAgent != null) {
            // Render completed sub-agents
            if (subAgent.results.isNotEmpty()) {
                addAll(renderSubAgentResults(subAgent.results, width, markdownRenderer, theme))
            }
            // Render active sub-agent progress lines
            val progressList = subAgent.activeProgressList.ifEmpty { listOfNotNull(subAgent.activeProgress) }
            progressList.forEach { progress ->
                val progressColor = breathingColor(spinnerFrame, theme.colors.dim, theme.colors.accentBright)
                val toolInfo = progress.currentTool?.let { " \u2192 $it" } ?: ""
                val meta = "${progress.toolCount} tools, ${progress.tokens}tok"
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.muted)) { append("  ") }
                    withStyle(SpanStyle(color = progressColor)) { append("\u25CB") }
                    append(" ")
                    withStyle(SpanStyle(color = theme.colors.accentBright)) { append("${progress.agent}/${progress.id}") }
                    append(" ")
                    withStyle(SpanStyle(color = theme.colors.muted)) { append(progress.status) }
                    withStyle(SpanStyle(color = theme.colors.dim)) { append("$toolInfo ($meta)") }
                })
            }
            // Fallback if no progress available yet
            if (subAgent.results.isEmpty() && progressList.isEmpty()) {
                val elapsed = if (execution.startedAt > 0) {
                    val seconds = (System.currentTimeMillis() - execution.startedAt) / 1000
                    " (${seconds}s)"
                } else ""
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
                    withStyle(SpanStyle(color = theme.colors.muted)) { append("Running…$elapsed") }
                })
            }
        } else {
            val elapsed = if (execution.startedAt > 0) {
                val seconds = (System.currentTimeMillis() - execution.startedAt) / 1000
                " (${seconds}s)"
            } else ""
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
                withStyle(SpanStyle(color = theme.colors.muted)) { append("Running…$elapsed") }
            })
        }
    } else if (!execution.collapsed) {
        val rendered = when (val details = execution.details) {
            is ToolDetails.Todo -> if (details.items.isNotEmpty()) renderTodoList(details.items, width, theme) else null
            is ToolDetails.SubAgent -> if (details.results.isNotEmpty()) renderSubAgentResults(details.results, width, markdownRenderer, theme) else null
            is ToolDetails.Batch -> if (details.results.isNotEmpty()) renderBatchResults(details.results, width, theme) else null
            else -> null
        }

        if (rendered != null) {
            addAll(rendered)
        } else if (execution.output.isNotBlank()) {
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
                add(annotated("${" ".repeat(connectorWidth)}… ${lines.size - limit} more lines", SpanStyle(color = theme.colors.muted)))
            }
        } else {
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
                withStyle(SpanStyle(color = theme.colors.muted)) {
                    append(if (execution.isError) "Error" else "Done")
                }
            })
        }
    } else {
        // Collapsed: show a preview of the output with an expand hint
        if (execution.output.isBlank()) {
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.dim)) { append(connector) }
                withStyle(SpanStyle(color = theme.colors.muted)) {
                    append(if (execution.isError) "Error" else "Done")
                }
            })
        } else {
            val lines = execution.output.split("\n")
            val previewLimit = 5
            val contentWidth = (width - connectorWidth).coerceAtLeast(10)
            val shown = lines.take(previewLimit)
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
            val hidden = lines.size - shown.size
            if (hidden > 0) {
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.muted)) {
                        append(" ".repeat(connectorWidth))
                        append("\u2026 +$hidden lines (ctrl+e to expand)")
                    }
                })
            }
        }
    }
}

private fun renderTodoList(
    items: List<TodoItem>,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    items.forEach { item ->
        val (icon, statusColor) = when (item.status) {
            "completed" -> "\u2713" to theme.todoCompleted
            "in_progress" -> "\u25CB" to theme.todoInProgress
            "cancelled" -> "\u2717" to theme.todoCancelled
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
        val maxContent = (width - 10).coerceAtLeast(20)

        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.muted)) { append("  ") }
            withStyle(SpanStyle(color = statusColor)) { append(icon) }
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

private fun renderSubAgentResults(
    results: List<SubAgentResult>,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    results.forEach { result ->
        val (icon, statusColor) = when {
            result.aborted -> "\u2298" to theme.colors.warning
            result.exitCode != 0 || result.error != null -> "\u2717" to theme.colors.error
            else -> "\u2713" to theme.colors.success
        }

        val durationStr = formatDuration(result.durationMs)
        val tokenStr = if (result.tokens > 0) "${result.tokens}tok" else ""

        // Header line: icon agent-name (duration, tokens)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.muted)) { append("  ") }
            withStyle(SpanStyle(color = statusColor)) { append(icon) }
            append(" ")
            withStyle(SpanStyle(color = theme.colors.accentBright, textStyle = TextStyle.Bold)) {
                append(result.agent)
            }
            val meta = listOfNotNull(durationStr, tokenStr.ifEmpty { null }).joinToString(", ")
            if (meta.isNotEmpty()) {
                append(" ")
                withStyle(SpanStyle(color = theme.colors.muted)) { append("($meta)") }
            }
        })

        // Task description line
        val desc = result.description ?: result.task
        val maxDesc = (width - 6).coerceAtLeast(20)
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.muted)) { append("    ") }
            append(desc.take(maxDesc))
            if (desc.length > maxDesc) append("\u2026")
        })

        // Error line (if any)
        val errorText = result.error
        if (errorText != null) {
            val maxErr = (width - 6).coerceAtLeast(20)
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.muted)) { append("    ") }
                withStyle(SpanStyle(color = theme.colors.error)) {
                    append(errorText.take(maxErr))
                    if (errorText.length > maxErr) append("\u2026")
                }
            })
        }

        // Output preview (first 3 lines, rendered as markdown)
        if (result.output.isNotBlank() && errorText == null) {
            val previewText = result.output.split("\n")
                .filter { it.isNotBlank() }
                .take(10)
                .joinToString("\n")
            val maxLine = (width - 6).coerceAtLeast(20)
            val renderedLines = markdownRenderer.render(previewText, maxLine)
            renderedLines.forEach { line ->
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.muted)) { append("      ") }
                    append(line)
                })
            }
            val totalOutputLines = result.output.split("\n").count { it.isNotBlank() }
            if (totalOutputLines > 10) {
                add(annotated("      ... ${totalOutputLines - 10} more lines", SpanStyle(color = theme.colors.muted)))
            }
        }
    }
}

private fun renderBatchResults(
    results: List<BatchToolCallResult>,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val successCount = results.count { it.success }
    val failCount = results.size - successCount

    // Summary line
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = theme.colors.muted)) { append("  ") }
        withStyle(SpanStyle(color = theme.colors.success)) { append("$successCount") }
        withStyle(SpanStyle(color = theme.colors.muted)) { append("/${results.size} succeeded") }
        if (failCount > 0) {
            withStyle(SpanStyle(color = theme.colors.muted)) { append(", ") }
            withStyle(SpanStyle(color = theme.colors.error)) { append("$failCount failed") }
        }
    })

    // Individual results
    results.forEach { result ->
        val (icon, statusColor) = if (result.success) {
            "\u2713" to theme.colors.success
        } else {
            "\u2717" to theme.colors.error
        }

        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.muted)) { append("  ") }
            withStyle(SpanStyle(color = statusColor)) { append(icon) }
            append(" ")
            withStyle(SpanStyle(color = theme.toolTitle)) { append("[${result.index}]") }
            append(" ")
            withStyle(SpanStyle(color = theme.colors.accentBright)) { append(result.toolName) }
        })

        // Output preview (first line)
        if (result.output.isNotBlank()) {
            val firstLine = result.output.split("\n").firstOrNull { it.isNotBlank() } ?: ""
            if (firstLine.isNotEmpty()) {
                val maxLine = (width - 8).coerceAtLeast(20)
                add(buildAnnotatedString {
                    withStyle(SpanStyle(color = theme.colors.muted)) { append("      ") }
                    withStyle(SpanStyle(color = theme.colors.dim)) {
                        append(firstLine.take(maxLine))
                        if (firstLine.length > maxLine) append("\u2026")
                    }
                })
            }
        }
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "${"%.1f".format(ms / 1000.0)}s"
    else -> {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        "${minutes}m${seconds}s"
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
    return buildList {
        add(annotated("[${message.customType}]", SpanStyle(color = theme.colors.muted)))
        message.content.filterIsInstance<TextContent>().forEach { block ->
            addAll(markdownRenderer.render(block.text, width))
        }
    }
}

/**
 * Data model for a tool execution view in the Mosaic TUI.
 */
public data class ToolExecutionView(
    val toolCallId: String,
    val toolName: String,
    val arguments: String = "",
    val output: String = "",
    val details: ToolDetails? = null,
    val isError: Boolean = false,
    val pending: Boolean = false,
    val collapsed: Boolean = false,
    val startedAt: Long = 0L,
) {
    public companion object {
        public fun fromToolResult(
            result: ToolResultMessage,
            arguments: String = "",
            collapsed: Boolean = false,
        ): ToolExecutionView {
            val output = result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .ifBlank { fallbackOutput(result.content) }

            return ToolExecutionView(
                toolCallId = result.toolCallId,
                toolName = result.toolName,
                arguments = arguments,
                output = output,
                details = result.details?.let { ToolDetails.Generic(it) },
                isError = result.isError,
                pending = false,
                collapsed = collapsed,
            )
        }

        private fun fallbackOutput(content: List<ContentBlock>): String {
            if (content.isEmpty()) return ""
            return content.joinToString("\n") { "[${it.type}]" }
        }
    }
}

/**
 * Extracts a concise summary from a tool's JSON arguments string based on the tool name.
 * Returns the most relevant field (file path, command, pattern, etc.) truncated to [maxWidth].
 */
private fun summarizeToolArguments(toolName: String, arguments: String, maxWidth: Int): String {
    val json = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(arguments) as? JsonObject
    }.getOrNull()

    val raw = when (toolName.lowercase()) {
        "read" -> json?.get("file_path")?.jsonPrimitive?.content
        "edit" -> json?.get("file_path")?.jsonPrimitive?.content
        "multiedit" -> json?.get("file_path")?.jsonPrimitive?.content
        "write" -> json?.get("file_path")?.jsonPrimitive?.content
        "bash" -> json?.get("command")?.jsonPrimitive?.content?.replace('\n', ' ')
        "grep" -> json?.get("pattern")?.jsonPrimitive?.content
        "find" -> json?.get("pattern")?.jsonPrimitive?.content
            ?: json?.get("glob")?.jsonPrimitive?.content
        "ls" -> json?.get("path")?.jsonPrimitive?.content ?: "."
        "todowrite" -> json?.get("todos")?.let { "${it}" }?.take(maxWidth)
        "task" -> json?.get("description")?.jsonPrimitive?.content
        else -> null
    } ?: return arguments.take(maxWidth)

    return if (raw.length > maxWidth) raw.take(maxWidth - 1) + "\u2026" else raw
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
 * Extracts the first line of text from the last [UserMessage] in the entries list.
 * Returns null if no user message exists.
 */
private fun findLastUserPromptText(entries: List<ChatHistoryEntry>): String? {
    val msg = entries.lastOrNull { it.message is UserMessage }?.message as? UserMessage ?: return null
    val text = msg.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
    return text.ifBlank { null }
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
