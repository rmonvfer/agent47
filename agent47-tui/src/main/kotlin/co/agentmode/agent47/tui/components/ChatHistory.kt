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
import com.jakewharton.mosaic.layout.width
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.coding.core.agents.SubAgentResult
import co.agentmode.agent47.coding.core.tools.ToolDetails
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import co.agentmode.agent47.ui.core.state.ChatHistoryEntry
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import co.agentmode.agent47.ui.core.util.summarizeToolArguments
import co.agentmode.agent47.ui.core.util.summarizeToolOutput
import co.agentmode.agent47.ui.core.util.formatDuration

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
 * Message types are distinguished by full-width background tint rather than by
 * prefix glyphs: user prompts and tool panels are solid colored blocks, and
 * extension/summary messages get a violet panel with a bracketed label.
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
            val entryLines = renderEntry(entry, width, state, markdownRenderer, theme)
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
    val visibleLines = if (state.entries.isEmpty() || allLines.isEmpty()) {
        // ohm shows no persistent banner — an empty transcript is just blank space
        // above the input, so messages grow up from the editor.
        List(viewportHeight) { annotated("") }
    } else {
        buildList {
            if (hasAbove) {
                add(scrollMarker(width, hiddenLines = contentStart, up = true, theme.colors.muted))
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
        // Constrain to the terminal width so a stray over-wide line can never stretch
        // the transcript column past the screen and break the layout.
        modifier = Modifier.width(width).height(viewportHeight),
    )
}

// ---------------------------------------------------------------------------
// Full-width background-block primitives (ohm's Box(bgFn) equivalent)
// ---------------------------------------------------------------------------

/**
 * Lays out a single line as a full-width block of [bg]: [paddingX] columns of
 * tinted margin on each side, the styled content, and tinted fill to [width].
 * The content's own foreground spans merge over the block background.
 */
private fun bgLine(
    content: AnnotatedString,
    width: Int,
    bg: Color,
    paddingX: Int = 1,
): AnnotatedString {
    val avail = (width - 2 * paddingX).coerceAtLeast(0)
    // Clamp so the block is always exactly `width` wide — an over-long content line
    // would otherwise push the tinted block past the terminal edge.
    val fitted = clampToWidth(content, avail)
    val padRight = (avail - fitted.text.length).coerceAtLeast(0)
    return buildAnnotatedString {
        withStyle(SpanStyle(background = bg)) {
            append(" ".repeat(paddingX))
            append(fitted)
            append(" ".repeat(padRight + paddingX))
        }
    }
}

/** Truncates an AnnotatedString to at most [maxLen] characters, preserving span ranges. */
private fun clampToWidth(content: AnnotatedString, maxLen: Int): AnnotatedString {
    if (content.text.length <= maxLen) return content
    val sub = content.text.substring(0, maxLen)
    return buildAnnotatedString {
        append(sub)
        for (range in content.spanStyles) {
            val start = range.start.coerceIn(0, maxLen)
            val end = range.end.coerceIn(0, maxLen)
            if (start < end) addStyle(range.item, start, end)
        }
    }
}

/**
 * Wraps [content] lines in a tinted block with one blank tinted line above and
 * below (ohm's paddingY = 1).
 */
private fun bgBlock(
    width: Int,
    bg: Color,
    content: List<AnnotatedString>,
): List<AnnotatedString> = buildList {
    add(bgLine(annotated(""), width, bg))
    content.forEach { add(bgLine(it, width, bg)) }
    add(bgLine(annotated(""), width, bg))
}

/**
 * Left gutter (in columns) applied to un-tinted transcript content — assistant text,
 * thinking, command output — so it lines up with the tinted user/tool blocks, which
 * carry the same paddingX.
 */
private const val CONTENT_PAD_X = 1

private fun indentLines(lines: List<AnnotatedString>, spaces: Int = CONTENT_PAD_X): List<AnnotatedString> {
    if (spaces <= 0) return lines
    val pad = annotated(" ".repeat(spaces))
    return lines.map { pad + it }
}

private fun renderEntry(
    entry: ChatHistoryEntry,
    width: Int,
    state: ChatHistoryState,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val toolExec = entry.toolExecution
    if (toolExec != null) {
        val collapsed = state.toolCollapsedState[entry.key] ?: toolExec.collapsed
        return renderToolExecutionLines(toolExec.copy(collapsed = collapsed), width, theme)
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
        is CustomMessage -> renderCustomMessageLines(msg, width, theme)
        else -> emptyList()
    }
}

private fun renderUserMessageLines(
    message: UserMessage,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val content = buildList {
        message.content.forEach { block ->
            when (block) {
                is TextContent -> addAll(markdownRenderer.render(block.text, innerWidth))
                else -> add(annotated("[${block.type}]", SpanStyle(color = theme.colors.muted)))
            }
        }
    }
    return bgBlock(width, theme.userMessageBg, content)
}

private fun renderAssistantMessageLines(
    message: AssistantMessage,
    width: Int,
    markdownRenderer: MarkdownRenderer,
    thinkingCollapsed: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val contentWidth = (width - 2 * CONTENT_PAD_X).coerceAtLeast(1)
    val raw = buildList {
        if (message.stopReason == StopReason.ERROR) {
            val errorText = message.errorMessage ?: "Unknown error"
            errorText.split("\n").forEach { line ->
                wrapAnnotated(annotated(line, SpanStyle(color = theme.colors.error)), contentWidth)
                    .forEach { add(it) }
            }
            return@buildList
        }

        message.content.forEach { block ->
            when (block) {
                is TextContent -> addAll(markdownRenderer.render(block.text, contentWidth))
                is ThinkingContent -> addAll(renderThinkingLines(block, contentWidth, thinkingCollapsed, theme))
                is ToolCall -> {} // Tool calls are rendered via ToolExecutionView entries
                else -> add(annotated("[${block.type}]", SpanStyle(color = theme.colors.muted)))
            }
        }
    }
    return indentLines(raw)
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
            wrapAnnotated(
                annotated(line, SpanStyle(color = theme.thinkingText, textStyle = TextStyle.Italic)),
                width,
            ).forEach { add(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Unified sub-agent entry for task rendering (merges completed + active)
// ---------------------------------------------------------------------------

private sealed class SubAgentEntry {
    data class Completed(val index: Int, val result: SubAgentResult) : SubAgentEntry()
    data class Active(val index: Int, val progress: SubAgentProgress) : SubAgentEntry()
}

// ---------------------------------------------------------------------------
// Pending activity labels
// ---------------------------------------------------------------------------

private fun pendingActivityLabel(toolName: String): String = when (toolName.lowercase()) {
    "read" -> "Reading…"
    "write" -> "Writing…"
    "edit", "multiedit" -> "Editing…"
    "bash" -> "Running…"
    "grep" -> "Searching…"
    "glob", "find" -> "Searching…"
    "ls" -> "Listing…"
    "task" -> "Running…"
    "batch" -> "Running…"
    "todocreate", "todoupdate", "todowrite", "todoread" -> "Updating…"
    else -> "Running…"
}

private fun elapsedSuffix(startedAt: Long): String {
    if (startedAt <= 0) return ""
    val seconds = (System.currentTimeMillis() - startedAt) / 1000
    return if (seconds > 0) " (${seconds}s)" else ""
}

/** Background tint of a tool panel, keyed to its execution state. */
private fun toolBg(execution: ToolExecutionView, theme: ThemeConfig): Color = when {
    execution.pending -> theme.toolPendingBg
    execution.isError -> theme.toolErrorBg
    else -> theme.toolSuccessBg
}

// ---------------------------------------------------------------------------
// Main tool execution renderer (dispatcher)
// ---------------------------------------------------------------------------

private fun renderToolExecutionLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val details = execution.details
    val name = execution.toolName.lowercase()

    if (details is ToolDetails.SubAgent || name == "task") return renderTaskToolLines(execution, width, theme)
    if (details is ToolDetails.Batch || name == "batch") return renderBatchToolLines(execution, width, theme)
    if (details is ToolDetails.Todo || name in listOf("todocreate", "todoupdate", "todowrite", "todoread")) {
        return renderTodoToolLines(execution, width, theme)
    }
    if (name == "bash") return renderBashToolLines(execution, width, theme)

    return renderRegularToolLines(execution, width, theme)
}

// ---------------------------------------------------------------------------
// Regular tool rendering (read, edit, grep, etc.)
// ---------------------------------------------------------------------------

private fun renderRegularToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val content = buildList {
        // Header: bold tool name + dim argument summary
        val argBudget = (innerWidth - execution.toolName.length - 1).coerceAtLeast(8)
        val argSummary = if (execution.arguments.isNotBlank()) {
            summarizeToolArguments(execution.toolName, execution.arguments, argBudget)
        } else ""
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) { append(execution.toolName) }
            if (argSummary.isNotEmpty()) {
                append(" ")
                withStyle(SpanStyle(color = theme.colors.muted)) { append(argSummary) }
            }
        })

        // Body: state conveyed by the panel's background tint, not an icon
        when {
            execution.pending -> {
                val label = pendingActivityLabel(execution.toolName)
                add(annotated("$label${elapsedSuffix(execution.startedAt)}", SpanStyle(color = theme.colors.muted)))
            }
            !execution.collapsed -> {
                if (execution.output.isBlank()) {
                    add(annotated(if (execution.isError) "Error" else "Done", SpanStyle(color = theme.colors.muted)))
                } else {
                    val lines = execution.output.split("\n")
                    val limit = 80
                    lines.take(limit).forEach { line ->
                        add(annotated(line.take(innerWidth), SpanStyle(color = theme.toolOutput)))
                    }
                    if (lines.size > limit) {
                        add(annotated("… ${lines.size - limit} more lines", SpanStyle(color = theme.colors.muted)))
                    }
                }
            }
            else -> {
                val summary = summarizeToolOutput(execution.toolName, execution.output, execution.details, execution.isError)
                add(annotated(summary, SpanStyle(color = if (execution.isError) theme.colors.error else theme.colors.muted)))
            }
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

// ---------------------------------------------------------------------------
// Bash rendering (ohm frames shell commands with green horizontal rules)
// ---------------------------------------------------------------------------

private fun renderBashToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    // ohm frames bash with full-width green rules; the command and output are inset by
    // CONTENT_PAD_X so they line up with the tinted blocks above and below.
    val ruleColor = theme.bashModeBorder
    val rule = annotated("─".repeat(width.coerceAtLeast(1)), SpanStyle(color = ruleColor))
    val pad = " ".repeat(CONTENT_PAD_X)
    val contentWidth = (width - 2 * CONTENT_PAD_X).coerceAtLeast(1)
    val command = if (execution.arguments.isNotBlank()) {
        summarizeToolArguments("bash", execution.arguments, (contentWidth - 2).coerceAtLeast(1))
    } else ""

    add(rule)
    add(buildAnnotatedString {
        append(pad)
        withStyle(SpanStyle(color = ruleColor, textStyle = TextStyle.Bold)) {
            append("$ ")
            append(command.take((contentWidth - 2).coerceAtLeast(1)))
        }
    })
    when {
        execution.pending -> add(
            annotated("$pad${pendingActivityLabel("bash")}${elapsedSuffix(execution.startedAt)}", SpanStyle(color = theme.colors.muted)),
        )
        execution.output.isNotBlank() -> {
            add(annotated(""))
            val lines = execution.output.split("\n")
            val shown = lines.takeLast(20)
            val hidden = lines.size - shown.size
            shown.forEach { line ->
                add(annotated(pad + line.take(contentWidth), SpanStyle(color = theme.colors.muted)))
            }
            if (hidden > 0) {
                add(annotated("$pad… $hidden more lines", SpanStyle(color = theme.colors.muted)))
            }
        }
        execution.isError -> add(annotated("${pad}Error", SpanStyle(color = theme.colors.error)))
    }
    add(rule)
}

// ---------------------------------------------------------------------------
// Task tool rendering (sub-agents, flattened into the panel body)
// ---------------------------------------------------------------------------

private fun renderTaskToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val subAgent = execution.details as? ToolDetails.SubAgent
    val results = subAgent?.results ?: emptyList()
    val progressList = subAgent?.activeProgressList?.ifEmpty { listOfNotNull(subAgent.activeProgress) } ?: emptyList()

    val completedIds = results.map { it.id }.toSet()
    val entries = buildList {
        results.forEachIndexed { i, r -> add(SubAgentEntry.Completed(i, r)) }
        progressList.filter { it.id !in completedIds }.forEachIndexed { i, p ->
            add(SubAgentEntry.Active(results.size + i, p))
        }
    }
    val total = entries.size.coerceAtLeast(1)
    val innerWidth = (width - 2).coerceAtLeast(1)
    // Align the agent-name column to the widest name (clamped to a sane range).
    val nameWidth = entries.maxOfOrNull { agentName(it).length }?.coerceIn(6, 14) ?: 6

    val content = buildList {
        val headerLabel = if (execution.pending) {
            "Running $total agent${if (total != 1) "s" else ""}…"
        } else {
            "$total agent${if (total != 1) "s" else ""} finished"
        }
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) { append(headerLabel) }
        })

        if (entries.isEmpty()) {
            add(annotated("  Working…${elapsedSuffix(execution.startedAt)}", SpanStyle(color = theme.colors.muted)))
        }

        entries.forEach { entry ->
            when (entry) {
                is SubAgentEntry.Completed -> {
                    val r = entry.result
                    val hasError = r.exitCode != 0 || r.error != null
                    val statusColor = when {
                        r.aborted -> theme.colors.warning
                        hasError -> theme.colors.error
                        else -> theme.colors.success
                    }
                    // Running agents show live activity; finished ones show what they did.
                    val activity = when {
                        r.aborted -> "aborted"
                        hasError -> r.error?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: "failed"
                        else -> (r.description ?: r.task).ifBlank { "done" }
                    }
                    val activityColor = if (r.aborted || hasError) statusColor else theme.colors.muted
                    add(
                        agentLine(
                            r.agent, nameWidth, activity, activityColor,
                            formatDuration(r.durationMs), statusColor, innerWidth, theme,
                        ),
                    )

                    if (!execution.collapsed && !hasError && r.output.isNotBlank()) {
                        val maxLine = (innerWidth - 4).coerceAtLeast(8)
                        r.output.lines().filter { it.isNotBlank() }.take(5).forEach { line ->
                            add(annotated("    ${line.take(maxLine)}", SpanStyle(color = theme.colors.dim)))
                        }
                    }
                }

                is SubAgentEntry.Active -> {
                    val p = entry.progress
                    val activity = when {
                        p.currentTool != null -> "Running ${p.currentTool}…"
                        p.toolCount > 0 -> "Thinking… (${p.toolCount} tools)"
                        else -> "Thinking…"
                    }
                    val elapsed = if (p.durationMs > 0) formatDuration(p.durationMs) else ""
                    add(
                        agentLine(
                            p.agent, nameWidth, activity, theme.colors.muted,
                            elapsed, theme.colors.accent, innerWidth, theme,
                        ),
                    )
                }
            }
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

private fun agentName(entry: SubAgentEntry): String = when (entry) {
    is SubAgentEntry.Completed -> entry.result.agent
    is SubAgentEntry.Active -> entry.progress.agent
}

/**
 * Renders one sub-agent as a single columnar line — `  <name>   <activity>       <elapsed>` —
 * with the name colored by status and the elapsed time right-aligned within the panel.
 */
private fun agentLine(
    name: String,
    nameWidth: Int,
    activity: String,
    activityColor: Color,
    elapsed: String,
    statusColor: Color,
    innerWidth: Int,
    theme: ThemeConfig,
): AnnotatedString {
    val namePadded = name.take(nameWidth).padEnd(nameWidth)
    val activityBudget = (innerWidth - 2 - nameWidth - 2 - elapsed.length - 1).coerceAtLeast(0)
    val activityShown = if (activity.length > activityBudget) {
        activity.take((activityBudget - 1).coerceAtLeast(0)) + "…"
    } else {
        activity
    }
    val gap = (innerWidth - 2 - nameWidth - 2 - activityShown.length - elapsed.length).coerceAtLeast(1)
    return buildAnnotatedString {
        append("  ")
        withStyle(SpanStyle(color = statusColor, textStyle = TextStyle.Bold)) { append(namePadded) }
        append("  ")
        withStyle(SpanStyle(color = activityColor)) { append(activityShown) }
        if (elapsed.isNotEmpty()) {
            append(" ".repeat(gap))
            withStyle(SpanStyle(color = theme.colors.dim)) { append(elapsed) }
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
): List<AnnotatedString> {
    val batch = execution.details as? ToolDetails.Batch
    val results = batch?.results ?: emptyList()
    val innerWidth = (width - 2).coerceAtLeast(1)

    val content = buildList {
        if (execution.pending) {
            val count = results.size.takeIf { it > 0 }
            val label = if (count != null) "Running $count batch calls…" else "Running batch…"
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) { append(label) }
            })
            add(annotated("Running…${elapsedSuffix(execution.startedAt)}", SpanStyle(color = theme.colors.muted)))
        } else {
            val successCount = results.count { it.success }
            val failCount = results.size - successCount
            add(buildAnnotatedString {
                withStyle(SpanStyle(color = theme.colors.success, textStyle = TextStyle.Bold)) { append("$successCount") }
                withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) {
                    append("/${results.size} batch calls succeeded")
                }
                if (failCount > 0) {
                    withStyle(SpanStyle(color = theme.colors.muted)) { append(", ") }
                    withStyle(SpanStyle(color = theme.colors.error, textStyle = TextStyle.Bold)) { append("$failCount failed") }
                }
            })

            results.forEach { result ->
                val nameColor = if (result.success) theme.colors.success else theme.colors.error
                add(buildAnnotatedString {
                    append("  ")
                    withStyle(SpanStyle(color = nameColor)) { append(result.toolName) }
                    if (!result.success && result.output.isNotBlank()) {
                        val errLine = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
                        if (errLine.isNotEmpty()) {
                            append(" ")
                            val budget = (innerWidth - result.toolName.length - 4).coerceAtLeast(8)
                            withStyle(SpanStyle(color = theme.colors.error)) { append(errLine.take(budget)) }
                        }
                    }
                })
            }
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

// ---------------------------------------------------------------------------
// Todo tool rendering (textual checkboxes, ohm-style)
// ---------------------------------------------------------------------------

private fun renderTodoToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val content = buildList {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) { append(execution.toolName) }
        })

        if (execution.pending) {
            add(annotated("Updating…", SpanStyle(color = theme.colors.muted)))
        } else {
            val items = (execution.details as? ToolDetails.Todo)?.items ?: emptyList()
            if (items.isEmpty()) {
                add(annotated("Done", SpanStyle(color = theme.colors.muted)))
            } else {
                items.forEach { item ->
                    val (marker, markerColor) = when (item.status) {
                        "completed" -> "[x]" to theme.todoCompleted
                        "in_progress" -> "[~]" to theme.todoInProgress
                        "cancelled" -> "[-]" to theme.todoCancelled
                        else -> "[ ]" to theme.todoPending
                    }
                    val done = item.status == "completed" || item.status == "cancelled"
                    val contentStyle = if (done) {
                        SpanStyle(color = theme.colors.muted, textStyle = TextStyle.Strikethrough)
                    } else {
                        SpanStyle(color = theme.markdownText)
                    }
                    val maxContent = (innerWidth - 6).coerceAtLeast(8)
                    add(buildAnnotatedString {
                        append("  ")
                        withStyle(SpanStyle(color = markerColor)) { append(marker) }
                        append(" ")
                        withStyle(contentStyle) { append(item.content.take(maxContent)) }
                    })
                }
            }
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

private fun renderBashExecutionLines(
    message: BashExecutionMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val ruleColor = theme.bashModeBorder
    val rule = annotated("─".repeat(width.coerceAtLeast(1)), SpanStyle(color = ruleColor))
    add(rule)
    add(buildAnnotatedString {
        withStyle(SpanStyle(color = ruleColor, textStyle = TextStyle.Bold)) {
            append("$ ")
            append(message.command.take((width - 2).coerceAtLeast(1)))
        }
    })
    val exitCode = message.exitCode
    val exitColor = if (exitCode == null || exitCode == 0) theme.colors.muted else theme.colors.error
    add(annotated("(exit ${exitCode ?: "?"})", SpanStyle(color = exitColor)))
    add(rule)
}

// ---------------------------------------------------------------------------
// Labeled violet panels (branch/compaction/custom extension messages)
// ---------------------------------------------------------------------------

private fun labeledBlock(
    label: String,
    body: List<AnnotatedString>,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val content = buildList {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.customMessageLabel, textStyle = TextStyle.Bold)) { append("[$label]") }
        })
        if (body.isNotEmpty()) {
            add(annotated(""))
            addAll(body)
        }
    }
    return bgBlock(width, theme.customMessageBg, content)
}

private fun bodyLines(text: String, width: Int, theme: ThemeConfig): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    return buildList {
        text.split("\n").forEach { line ->
            wrapAnnotated(annotated(line, SpanStyle(color = theme.customMessageText)), innerWidth).forEach { add(it) }
        }
    }
}

private fun renderBranchSummaryLines(
    message: BranchSummaryMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = labeledBlock("branch", bodyLines(message.summary, width, theme), width, theme)

private fun renderCompactionSummaryLines(
    message: CompactionSummaryMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val body = buildList {
        add(annotated("Compacted from ${message.tokensBefore} tokens", SpanStyle(color = theme.customMessageText)))
        addAll(bodyLines(message.summary, width, theme))
    }
    return labeledBlock("compaction", body, width, theme)
}

private fun renderCustomMessageLines(
    message: CustomMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    if (!message.display) return emptyList()
    if (message.customType == "command_result") return renderCommandResultLines(message, width, theme)

    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    return labeledBlock(message.customType, bodyLines(text, width, theme), width, theme)
}

private fun renderCommandResultLines(
    message: CustomMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val contentWidth = (width - 2 * CONTENT_PAD_X).coerceAtLeast(1)
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val raw = buildList {
        text.split("\n").forEach { line ->
            wrapAnnotated(annotated(line, SpanStyle(color = theme.colors.muted)), contentWidth).forEach { add(it) }
        }
    }
    return indentLines(raw)
}

private fun scrollMarker(
    width: Int,
    hiddenLines: Int,
    up: Boolean,
    color: Color,
): AnnotatedString {
    val arrow = if (up) "↑" else "↓"
    val text = "$arrow $hiddenLines more"
    return annotated(text.padEnd(width), SpanStyle(color = color))
}
