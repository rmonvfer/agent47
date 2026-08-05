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
import co.agentmode.agent47.ext.core.RegisteredMessageRenderer
import co.agentmode.agent47.ext.core.RegisteredToolRenderer
import co.agentmode.agent47.ext.core.ToolRenderData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * as Mosaic [Text] nodes inside a column with a constrained [height].
 *
 * Message types are distinguished by full-width background tint rather than by
 * prefix glyphs: user prompts and tool panels are solid colored blocks, and
 * extension/summary messages get a violet panel with a bracketed label.
 */
@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
public fun ChatHistory(
    state: ChatHistoryState,
    width: Int,
    height: Int,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    version: Int = 0,
    spinnerFrame: Int = 0,
    cwd: String = "",
    toolRenderers: List<RegisteredToolRenderer> = emptyList(),
    messageRenderers: List<RegisteredMessageRenderer> = emptyList(),
    introLines: List<AnnotatedString> = emptyList(),
) {
    val theme = LocalThemeConfig.current
    val viewportHeight = height.coerceAtLeast(1)

    // Flatten all entries into rendered lines. Each rendered entry owns one leading blank
    // line; entries that render no lines contribute no spacing either, so invisible entries
    // (an assistant round that only carried tool calls, a hidden custom message) can never
    // widen the gap between their neighbors.
    val allLines = buildList {
        addAll(introLines)
        state.entries.forEach { entry ->
            val entryLines = renderEntry(
                entry,
                width,
                state,
                markdownRenderer,
                diffRenderer,
                theme,
                toolRenderers,
                messageRenderers,
            )
            if (entryLines.isEmpty()) return@forEach
            val isCommandResult = (entry.message as? CustomMessage)?.customType == "command_result"
            if (isNotEmpty() && !isCommandResult) {
                add(annotated(""))
            }
            addAll(entryLines)
        }
    }

    // Snap to bottom when pinned or explicitly requested, then clamp
    val maxScroll = (allLines.size - viewportHeight).coerceAtLeast(0)
    val startupOnly = introLines.isNotEmpty() && state.entries.isEmpty()
    if (!startupOnly && (state.pinnedToBottom || state.scrollToBottom)) {
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
    val visibleLines = if (allLines.isEmpty()) {
        List(viewportHeight) { annotated("") }
    } else {
        buildList {
            if (hasAbove) {
                add(scrollMarker(width, hiddenLines = contentStart, up = true, theme.colors.muted))
            }

            val contentSlice = allLines.subList(contentStart, contentEnd)
            val belowMarkerCount = if (hasBelow) 1 else 0
            val usedRows = size + contentSlice.size + belowMarkerCount
            val availablePadding = (viewportHeight - usedRows).coerceAtLeast(0)
            val anchorToTop = startupOnly && safeTop == 0

            // Startup information begins at the top of an empty transcript. Conversation
            // content remains bottom-aligned so the newest message stays next to the editor.
            if (!anchorToTop && availablePadding > 0) {
                addAll(List(availablePadding) { annotated("") })
            }

            addAll(contentSlice)

            if (hasBelow) {
                val hiddenBelow = allLines.size - contentEnd
                add(scrollMarker(width, hiddenLines = hiddenBelow, up = false, theme.colors.muted))
            }
            if (anchorToTop && availablePadding > 0) {
                addAll(List(availablePadding) { annotated("") })
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
        color = theme.markdownText,
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
 * [foreground] supplies the default text color; the content's explicit foreground
 * spans override it.
 */
private fun bgLine(
    content: AnnotatedString,
    width: Int,
    bg: Color,
    foreground: Color = Color.Unspecified,
    paddingX: Int = 1,
): AnnotatedString {
    val avail = (width - 2 * paddingX).coerceAtLeast(0)
    // Clamp so the block is always exactly `width` wide — an over-long content line
    // would otherwise push the tinted block past the terminal edge.
    val fitted = clampToWidth(content, avail)
    val padRight = (avail - fitted.text.length).coerceAtLeast(0)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = foreground, background = bg)) {
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
    foreground: Color = Color.Unspecified,
): List<AnnotatedString> = buildList {
    add(bgLine(annotated(""), width, bg, foreground))
    content.forEach { add(bgLine(it, width, bg, foreground)) }
    add(bgLine(annotated(""), width, bg, foreground))
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

@Suppress("LongParameterList")
private fun renderEntry(
    entry: ChatHistoryEntry,
    width: Int,
    state: ChatHistoryState,
    markdownRenderer: MarkdownRenderer,
    diffRenderer: DiffRenderer,
    theme: ThemeConfig,
    toolRenderers: List<RegisteredToolRenderer>,
    messageRenderers: List<RegisteredMessageRenderer>,
): List<AnnotatedString> {
    val toolExec = entry.toolExecution
    return if (toolExec != null) {
        val collapsed = state.toolCollapsedState[entry.key] ?: toolExec.collapsed
        val custom = renderWithExtension(
            toolRenderers.lastOrNull { it.toolName == toolExec.toolName },
            ToolRenderData(
                toolCallId = toolExec.toolCallId,
                toolName = toolExec.toolName,
                arguments = toolExec.arguments,
                output = toolExec.output,
                details = toolExec.details,
                isError = toolExec.isError,
                pending = toolExec.pending,
                collapsed = collapsed,
            ),
            width,
        )
        custom?.trimBlankEdges()?.map(::annotated)
            ?: renderToolExecutionLines(toolExec.copy(collapsed = collapsed), width, theme, diffRenderer)
    } else {
        when (val msg = entry.message) {
            is UserMessage -> renderUserMessageLines(msg, width, markdownRenderer, theme)
            is AssistantMessage -> {
                val thinkingCollapsed = state.thinkingCollapsedState[entry.key] ?: true
                renderAssistantMessageLines(msg, width, markdownRenderer, thinkingCollapsed, theme)
            }

            is BashExecutionMessage -> renderBashExecutionLines(msg, width, theme)
            is BranchSummaryMessage -> renderBranchSummaryLines(msg, width, theme)
            is CompactionSummaryMessage -> renderCompactionSummaryLines(msg, width, theme)
            is CustomMessage -> {
                val custom = runCatching {
                    messageRenderers.lastOrNull { it.customType == msg.customType }
                        ?.renderer
                        ?.render(msg, width)
                }.getOrNull()
                custom?.trimBlankEdges()?.map(::annotated) ?: renderCustomMessageLines(msg, width, theme)
            }
            else -> emptyList()
        }
    }
}

/**
 * Blank lines at the edges of extension-rendered output would stack onto the
 * transcript's own entry spacing, so only the interior lines are kept.
 */
private fun List<String>.trimBlankEdges(): List<String> =
    dropWhile(String::isBlank).dropLastWhile(String::isBlank)

internal fun renderWithExtension(
    renderer: RegisteredToolRenderer?,
    data: ToolRenderData,
    width: Int,
): List<String>? = runCatching {
    renderer?.renderer?.render(data, width)
}.getOrNull()

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
    return bgBlock(
        width = width,
        bg = theme.userMessageBg,
        content = content,
        foreground = theme.userMessageText,
    )
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

/**
 * Collapsed body budget per tool: 0 keeps the header alone, null falls back to the
 * one-line output summary.
 */
private fun collapsedPreviewLines(toolName: String): Int? = when (toolName.lowercase()) {
    "read" -> 0
    "ls", "find", "glob" -> 20
    "grep" -> 15
    "write" -> 10
    else -> null
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
    diffRenderer: DiffRenderer,
): List<AnnotatedString> {
    val details = execution.details
    val name = execution.toolName.lowercase()

    return when {
        details is ToolDetails.SubAgent || name == "task" -> renderTaskToolLines(execution, width, theme)
        details is ToolDetails.Batch || name == "batch" -> renderBatchToolLines(execution, width, theme)
        details is ToolDetails.Todo || name in listOf("todocreate", "todoupdate", "todowrite", "todoread") ->
            renderTodoToolLines(execution, width, theme)
        name == "bash" -> renderBashToolLines(execution, width, theme)
        name == "edit" || name == "multiedit" ->
            renderEditToolLines(execution, width, theme, diffRenderer)
                ?: renderRegularToolLines(execution, width, theme)
        else -> renderRegularToolLines(execution, width, theme)
    }
}

// ---------------------------------------------------------------------------
// Edit tool rendering (the diff is always visible, computed from the call
// arguments so it shows while the edit is still executing)
// ---------------------------------------------------------------------------

private const val EDIT_DIFF_LINE_LIMIT = 120

private fun renderEditToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
    diffRenderer: DiffRenderer,
): List<AnnotatedString>? {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val arguments = runCatching { Json.parseToJsonElement(execution.arguments).jsonObject }.getOrNull()
        ?: return null
    val diffLines = editDiffLines(execution.toolName, arguments, innerWidth, diffRenderer)
    return if (diffLines.isEmpty()) null else renderEditToolBlock(execution, arguments, diffLines, width, theme)
}

@Suppress("LongParameterList")
private fun renderEditToolBlock(
    execution: ToolExecutionView,
    arguments: JsonObject,
    diffLines: List<AnnotatedString>,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val path = arguments["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val content = buildList {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) { append(execution.toolName) }
            if (path.isNotEmpty()) {
                append(" ")
                withStyle(SpanStyle(color = theme.colors.muted)) { append(path.take((innerWidth - execution.toolName.length - 1).coerceAtLeast(8))) }
            }
        })
        add(annotated(""))
        addAll(diffLines.take(EDIT_DIFF_LINE_LIMIT))
        if (diffLines.size > EDIT_DIFF_LINE_LIMIT) {
            add(annotated("… ${diffLines.size - EDIT_DIFF_LINE_LIMIT} more diff lines", SpanStyle(color = theme.colors.muted)))
        }
        when {
            execution.pending -> add(annotated("Editing…${elapsedSuffix(execution.startedAt)}", SpanStyle(color = theme.colors.muted)))
            execution.isError -> {
                val error = execution.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Error"
                add(annotated(error.take(innerWidth), SpanStyle(color = theme.colors.error)))
            }
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

private fun editDiffLines(
    toolName: String,
    arguments: JsonObject,
    width: Int,
    diffRenderer: DiffRenderer,
): List<AnnotatedString> = when (toolName.lowercase()) {
    "edit" -> {
        val oldText = arguments["oldText"]?.jsonPrimitive?.contentOrNull
        val newText = arguments["newText"]?.jsonPrimitive?.contentOrNull
        if (oldText != null && newText != null) diffRenderer.render(oldText, newText, width) else emptyList()
    }

    "multiedit" -> {
        val edits = runCatching { arguments["edits"]?.jsonArray }.getOrNull().orEmpty()
        buildList {
            edits.forEachIndexed { index, edit ->
                val fields = runCatching { edit.jsonObject }.getOrNull() ?: return@forEachIndexed
                val oldText = fields["oldText"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val newText = fields["newText"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                if (index > 0 && isNotEmpty()) add(annotated(""))
                addAll(diffRenderer.render(oldText, newText, width))
            }
        }
    }

    else -> emptyList()
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
            else -> addAll(collapsedToolBody(execution, innerWidth, theme))
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
}

/**
 * Collapsed body of a regular tool: an error summary, a per-tool output preview, or a
 * one-line output summary for tools without a preview budget.
 */
private fun collapsedToolBody(
    execution: ToolExecutionView,
    innerWidth: Int,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    if (execution.isError) {
        val summary = summarizeToolOutput(execution.toolName, execution.output, execution.details, true)
        add(annotated(summary, SpanStyle(color = theme.colors.error)))
        return@buildList
    }
    val preview = collapsedPreviewLines(execution.toolName)
    when {
        preview == null -> {
            val summary = summarizeToolOutput(execution.toolName, execution.output, execution.details, false)
            add(annotated(summary, SpanStyle(color = theme.colors.muted)))
        }
        preview == 0 || execution.output.isBlank() -> {}
        else -> {
            val lines = execution.output.split("\n")
            lines.take(preview).forEach { line ->
                add(annotated(line.take(innerWidth), SpanStyle(color = theme.toolOutput)))
            }
            if (lines.size > preview) {
                add(annotated("… ${lines.size - preview} more lines", SpanStyle(color = theme.colors.muted)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Bash tool rendering (a tinted card like every other tool; the green
// rule framing belongs to user-typed bash-mode executions only)
// ---------------------------------------------------------------------------

private const val BASH_COLLAPSED_LINES = 5
private const val BASH_EXPANDED_LINES = 80

private fun renderBashToolLines(
    execution: ToolExecutionView,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val innerWidth = (width - 2).coerceAtLeast(1)
    val command = if (execution.arguments.isNotBlank()) {
        summarizeToolArguments("bash", execution.arguments, (innerWidth - 2).coerceAtLeast(1))
    } else ""

    val content = buildList {
        add(buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolTitle, textStyle = TextStyle.Bold)) {
                append("$ ")
                append(command)
            }
        })
        when {
            execution.pending -> add(
                annotated(
                    "${pendingActivityLabel("bash")}${elapsedSuffix(execution.startedAt)}",
                    SpanStyle(color = theme.colors.muted),
                ),
            )
            execution.output.isNotBlank() -> {
                add(annotated(""))
                val lines = execution.output.split("\n")
                val limit = if (execution.collapsed) BASH_COLLAPSED_LINES else BASH_EXPANDED_LINES
                val shown = lines.takeLast(limit)
                val hidden = lines.size - shown.size
                if (hidden > 0) {
                    add(annotated("… $hidden earlier lines", SpanStyle(color = theme.colors.muted)))
                }
                shown.forEach { line ->
                    add(annotated(line.take(innerWidth), SpanStyle(color = theme.toolOutput)))
                }
            }
            execution.isError -> add(annotated("Error", SpanStyle(color = theme.colors.error)))
        }
    }
    return bgBlock(width, toolBg(execution, theme), content)
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
    // A settled task card with no sub-agent entries is a launch receipt: the agents run in
    // the background registry and report through the agents panel and check_inbox, so the
    // card shows the tool's own summary instead of inventing a completion count.
    if (!execution.pending && entries.isEmpty()) {
        return renderRegularToolLines(execution, width, theme)
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
    if (message.customType == "command_result" || message.customType == "system_note") {
        return renderCommandResultLines(message, width, theme)
    }
    if (message.customType == "system_error") return renderSystemErrorLines(message, width, theme)

    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    return labeledBlock(message.customType, bodyLines(text, width, theme), width, theme)
}

private fun renderSystemErrorLines(
    message: CustomMessage,
    width: Int,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val contentWidth = (width - 2 * CONTENT_PAD_X).coerceAtLeast(1)
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val raw = buildList {
        "Error: $text".split("\n").forEach { line ->
            wrapAnnotated(annotated(line, SpanStyle(color = theme.colors.error)), contentWidth).forEach { add(it) }
        }
    }
    return indentLines(raw)
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
