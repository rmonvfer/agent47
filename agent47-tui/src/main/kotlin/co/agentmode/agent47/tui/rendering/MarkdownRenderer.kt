package co.agentmode.agent47.tui.rendering

import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.TerminalColors
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle
import com.jakewharton.mosaic.ui.UnderlineStyle
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * Creates an unstyled AnnotatedString from a plain string.
 */
public fun annotated(text: String): AnnotatedString = buildAnnotatedString { append(text) }

/**
 * Creates a styled AnnotatedString from a plain string with a SpanStyle applied to the whole text.
 */
public fun annotated(text: String, style: SpanStyle): AnnotatedString = AnnotatedString(text, style)

/**
 * Theme for Mosaic Markdown rendering, expressed as SpanStyles.
 */
public data class MarkdownTheme(
    val headingStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Bold),
    val boldStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Bold),
    val italicStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Italic),
    val strikethroughStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Strikethrough),
    val inlineCodeStyle: SpanStyle = SpanStyle(background = TerminalColors.BRIGHT_BLACK),
    val codeBlockStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val codeBlockBorderStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val quoteStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim + TextStyle.Italic),
    val quoteBorderStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val hrStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val listBulletStyle: SpanStyle = SpanStyle(color = TerminalColors.CYAN),
    val linkStyle: SpanStyle = SpanStyle(color = TerminalColors.CYAN, underlineStyle = UnderlineStyle.Straight),
    val linkUrlStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val tableBorderStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
    val tableHeaderStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Bold),
    val codeBlockIndent: String = "  ",
) {
    public companion object {
        /**
         * Creates a MarkdownTheme derived from a MosaicTheme's color palette.
         */
        public fun fromTheme(theme: ThemeConfig): MarkdownTheme = MarkdownTheme(
            headingStyle = SpanStyle(textStyle = TextStyle.Bold),
            boldStyle = SpanStyle(textStyle = TextStyle.Bold),
            italicStyle = SpanStyle(textStyle = TextStyle.Italic),
            strikethroughStyle = SpanStyle(textStyle = TextStyle.Strikethrough),
            inlineCodeStyle = SpanStyle(background = theme.inlineCodeBg),
            codeBlockStyle = SpanStyle(color = theme.codeBlockFg, background = theme.codeBlockBg),
            codeBlockBorderStyle = SpanStyle(color = theme.codeBlockFg, textStyle = TextStyle.Dim),
            quoteStyle = SpanStyle(textStyle = TextStyle.Dim + TextStyle.Italic),
            quoteBorderStyle = SpanStyle(textStyle = TextStyle.Dim),
            hrStyle = SpanStyle(textStyle = TextStyle.Dim),
            listBulletStyle = SpanStyle(color = theme.colors.accent),
            linkStyle = SpanStyle(color = theme.link, underlineStyle = UnderlineStyle.Straight),
            linkUrlStyle = SpanStyle(color = theme.linkUrl),
        )
    }
}

/**
 * Renders Markdown text into styled AnnotatedString lines for Mosaic's Text composable.
 *
 * Uses CommonMark AST parsing with GFM extensions for tables, strikethrough,
 * and autolinks. Handles headings, code fences, blockquotes, nested lists,
 * horizontal rules, tables, and inline styles (bold, italic, strikethrough,
 * inline code, links, images).
 */
public class MarkdownRenderer(
    private val theme: MarkdownTheme = MarkdownTheme(),
) {
    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                AutolinkExtension.create(),
            ),
        )
        .build()

    /**
     * Renders Markdown source into a list of AnnotatedString lines, each wrapped to [width].
     */
    public fun render(markdown: String, width: Int): List<AnnotatedString> {
        if (width <= 0) return listOf(annotated(""))
        if (markdown.isBlank()) return listOf(annotated(""))

        val document = parser.parse(markdown)
        val renderer = BlockRenderer(theme, width)
        document.accept(renderer)

        val output = renderer.lines
        return if (output.isEmpty()) listOf(annotated("")) else output
    }
}

/**
 * Visits block-level CommonMark nodes and accumulates rendered lines.
 */
private class BlockRenderer(
    private val theme: MarkdownTheme,
    private val width: Int,
    private val indent: Int = 0,
) : AbstractVisitor() {

    val lines = mutableListOf<AnnotatedString>()

    override fun visit(paragraph: Paragraph) {
        val inlines = collectInlines(paragraph, theme)
        lines += wrapAnnotated(inlines, effectiveWidth())
        addBlankLineAfterBlock(paragraph)
    }

    override fun visit(heading: Heading) {
        val prefix = "#".repeat(heading.level) + " "
        val inlines = collectInlines(heading, theme)
        val styledInlines = buildAnnotatedString {
            withStyle(theme.headingStyle) { append(inlines) }
        }
        lines += wrapWithPrefix(
            text = styledInlines,
            width = effectiveWidth(),
            firstPrefix = annotated(prefix, theme.headingStyle),
            restPrefix = annotated(" ".repeat(prefix.length)),
        )
        addBlankLineAfterBlock(heading)
    }

    override fun visit(fencedCodeBlock: FencedCodeBlock) {
        val w = effectiveWidth()
        val language = fencedCodeBlock.info?.trim()?.ifBlank { null }
        lines += renderCodeBlockTop(language, w)
        val literal = fencedCodeBlock.literal.trimEnd('\n')
        literal.split("\n").forEach { codeLine ->
            lines += renderCodeBlockLine(codeLine, w)
        }
        lines += renderCodeBlockBottom(w)
        addBlankLineAfterBlock(fencedCodeBlock)
    }

    override fun visit(indentedCodeBlock: IndentedCodeBlock) {
        val w = effectiveWidth()
        lines += renderCodeBlockTop(null, w)
        val literal = indentedCodeBlock.literal.trimEnd('\n')
        literal.split("\n").forEach { codeLine ->
            lines += renderCodeBlockLine(codeLine, w)
        }
        lines += renderCodeBlockBottom(w)
        addBlankLineAfterBlock(indentedCodeBlock)
    }

    override fun visit(blockQuote: BlockQuote) {
        val innerWidth = (effectiveWidth() - 2).coerceAtLeast(1)
        val innerRenderer = BlockRenderer(theme, innerWidth)
        visitChildrenWith(blockQuote, innerRenderer)
        // Remove trailing blank lines from blockquote content
        while (innerRenderer.lines.lastOrNull()?.text?.isBlank() == true) {
            innerRenderer.lines.removeAt(innerRenderer.lines.lastIndex)
        }
        val border = annotated("│ ", theme.quoteBorderStyle)
        innerRenderer.lines.forEach { line ->
            lines += buildAnnotatedString {
                append(border)
                withStyle(theme.quoteStyle) { append(line) }
            }
        }
        addBlankLineAfterBlock(blockQuote)
    }

    override fun visit(bulletList: BulletList) {
        val loose = !bulletList.isTight
        var child = bulletList.firstChild
        while (child != null) {
            if (child is ListItem) {
                if (loose && child !== bulletList.firstChild) {
                    lines += annotated("")
                }
                renderListItem(child, "- ")
            }
            child = child.next
        }
        addBlankLineAfterBlock(bulletList)
    }

    override fun visit(orderedList: OrderedList) {
        val loose = !orderedList.isTight
        @Suppress("DEPRECATION")
        var number = orderedList.startNumber
        var child = orderedList.firstChild
        while (child != null) {
            if (child is ListItem) {
                if (loose && child !== orderedList.firstChild) {
                    lines += annotated("")
                }
                renderListItem(child, "$number. ")
                number++
            }
            child = child.next
        }
        addBlankLineAfterBlock(orderedList)
    }

    override fun visit(thematicBreak: ThematicBreak) {
        lines += annotated("─".repeat(effectiveWidth().coerceAtLeast(3)), theme.hrStyle)
        addBlankLineAfterBlock(thematicBreak)
    }

    override fun visit(htmlBlock: HtmlBlock) {
        val literal = htmlBlock.literal.trimEnd('\n')
        literal.split("\n").forEach { line ->
            lines += wrapAnnotated(
                annotated(line, SpanStyle(textStyle = TextStyle.Dim)),
                effectiveWidth(),
            )
        }
        addBlankLineAfterBlock(htmlBlock)
    }

    override fun visit(customBlock: org.commonmark.node.CustomBlock) {
        when (customBlock) {
            is TableBlock -> renderTable(customBlock)
            else -> visitChildren(customBlock)
        }
    }

    private fun renderListItem(item: ListItem, marker: String) {
        val w = effectiveWidth()
        val bulletPrefix = buildAnnotatedString {
            withStyle(theme.listBulletStyle) { append(marker) }
        }
        val continuationPrefix = annotated(" ".repeat(marker.length))

        val firstChild = item.firstChild
        if (firstChild is Paragraph && firstChild.next == null) {
            // Simple list item: single paragraph, render inline
            val inlines = collectInlines(firstChild, theme)
            lines += wrapWithPrefix(
                text = inlines,
                width = w,
                firstPrefix = bulletPrefix,
                restPrefix = continuationPrefix,
            )
        } else {
            // Complex list item: multiple children (nested lists, paragraphs)
            val innerWidth = (w - marker.length).coerceAtLeast(1)
            val innerRenderer = BlockRenderer(theme, innerWidth)
            visitChildrenWith(item, innerRenderer)
            // Remove trailing blank lines
            while (innerRenderer.lines.lastOrNull()?.text?.isBlank() == true) {
                innerRenderer.lines.removeAt(innerRenderer.lines.lastIndex)
            }
            innerRenderer.lines.forEachIndexed { idx, line ->
                val prefix = if (idx == 0) bulletPrefix else continuationPrefix
                lines += prefix + line
            }
        }
    }

    private fun renderTable(table: TableBlock) {
        val headerRows = mutableListOf<List<AnnotatedString>>()
        val bodyRows = mutableListOf<List<AnnotatedString>>()
        val alignments = mutableListOf<TableCell.Alignment?>()

        var child = table.firstChild
        while (child != null) {
            when (child) {
                is TableHead -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val cells = mutableListOf<AnnotatedString>()
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    cells += collectInlines(cell, theme)
                                    if (headerRows.isEmpty()) {
                                        alignments += cell.alignment
                                    }
                                }
                                cell = cell.next
                            }
                            headerRows += cells
                        }
                        row = row.next
                    }
                }
                is TableBody -> {
                    var row = child.firstChild
                    while (row != null) {
                        if (row is TableRow) {
                            val cells = mutableListOf<AnnotatedString>()
                            var cell = row.firstChild
                            while (cell != null) {
                                if (cell is TableCell) {
                                    cells += collectInlines(cell, theme)
                                }
                                cell = cell.next
                            }
                            bodyRows += cells
                        }
                        row = row.next
                    }
                }
            }
            child = child.next
        }

        val colCount = alignments.size.coerceAtLeast(1)
        val allRows = headerRows + bodyRows

        // Compute column widths based on terminal cell width, with a minimum of 3
        val colWidths = IntArray(colCount) { 3 }
        allRows.forEach { row ->
            row.forEachIndexed { col, cell ->
                if (col < colCount) {
                    colWidths[col] = maxOf(colWidths[col], cellWidth(cell.text))
                }
            }
        }

        // Scale columns to fit within available width
        // Each row: │ content │ content │ = (colCount + 1) borders + (2 * colCount) padding
        val totalChrome = 3 * colCount + 1
        val availableContent = (effectiveWidth() - totalChrome).coerceAtLeast(colCount * 3)
        val rawTotal = colWidths.sum()
        if (rawTotal > availableContent) {
            val scale = availableContent.toDouble() / rawTotal
            for (i in colWidths.indices) {
                colWidths[i] = (colWidths[i] * scale).toInt().coerceAtLeast(3)
            }
        }

        fun borderLine(left: String, mid: String, right: String): AnnotatedString {
            return buildAnnotatedString {
                withStyle(theme.tableBorderStyle) {
                    append(left)
                    colWidths.forEachIndexed { idx, w ->
                        append("─".repeat(w + 2))
                        if (idx < colWidths.lastIndex) append(mid)
                    }
                    append(right)
                }
            }
        }

        fun dataRow(cells: List<AnnotatedString>, style: SpanStyle? = null): AnnotatedString {
            return buildAnnotatedString {
                withStyle(theme.tableBorderStyle) { append("│") }
                for (col in 0 until colCount) {
                    append(" ")
                    val cellText = cells.getOrNull(col) ?: annotated("")
                    val maxW = colWidths[col]
                    val cellDisplayWidth = cellWidth(cellText.text)
                    val truncated = if (cellDisplayWidth > maxW) {
                        truncateToWidth(cellText, maxW)
                    } else {
                        cellText
                    }
                    val truncatedWidth = cellWidth(truncated.text)
                    val padding = (maxW - truncatedWidth).coerceAtLeast(0)
                    val alignment = alignments.getOrNull(col)
                    val (leftPad, rightPad) = when (alignment) {
                        TableCell.Alignment.CENTER -> (padding / 2) to (padding - padding / 2)
                        TableCell.Alignment.RIGHT -> padding to 0
                        else -> 0 to padding
                    }
                    append(" ".repeat(leftPad))
                    if (style != null) {
                        withStyle(style) { append(truncated) }
                    } else {
                        append(truncated)
                    }
                    append(" ".repeat(rightPad))
                    append(" ")
                    withStyle(theme.tableBorderStyle) { append("│") }
                }
            }
        }

        lines += borderLine("┌", "┬", "┐")
        headerRows.forEach { row ->
            lines += dataRow(row, theme.tableHeaderStyle)
        }
        if (bodyRows.isNotEmpty()) {
            lines += borderLine("├", "┼", "┤")
            bodyRows.forEach { row ->
                lines += dataRow(row)
            }
        }
        lines += borderLine("└", "┴", "┘")
        addBlankLineAfterBlock(table)
    }

    private fun renderCodeBlockTop(language: String?, w: Int): AnnotatedString {
        val label = language ?: ""
        return buildAnnotatedString {
            withStyle(theme.codeBlockBorderStyle) {
                append("┌")
                if (label.isNotEmpty()) {
                    append("─ ")
                    append(label)
                    append(" ")
                }
                val used = 1 + if (label.isNotEmpty()) 3 + label.length else 0
                val fill = (w - used).coerceAtLeast(0)
                append("─".repeat(fill))
            }
        }
    }

    private fun renderCodeBlockLine(codeLine: String, w: Int): AnnotatedString {
        val prefixWidth = 2
        val contentWidth = (w - prefixWidth).coerceAtLeast(1)
        val padded = codeLine.take(contentWidth).padEnd(contentWidth)
        return buildAnnotatedString {
            withStyle(theme.codeBlockBorderStyle) { append("│ ") }
            withStyle(theme.codeBlockStyle) { append(padded) }
        }
    }

    private fun renderCodeBlockBottom(w: Int): AnnotatedString {
        return buildAnnotatedString {
            withStyle(theme.codeBlockBorderStyle) {
                append("└")
                append("─".repeat((w - 1).coerceAtLeast(0)))
            }
        }
    }

    private fun effectiveWidth(): Int = (width - indent).coerceAtLeast(1)

    private fun addBlankLineAfterBlock(node: Node) {
        if (node.next == null) return
        // Inside a list item, don't insert a blank line between a paragraph and a following sub-list
        if (node is Paragraph && node.parent is ListItem &&
            (node.next is BulletList || node.next is OrderedList)
        ) return
        lines += annotated("")
    }

    private fun visitChildrenWith(parent: Node, renderer: BlockRenderer) {
        var child = parent.firstChild
        while (child != null) {
            child.accept(renderer)
            child = child.next
        }
    }
}

/**
 * Collects inline nodes from a block node into a single styled AnnotatedString.
 * Uses a style stack for proper nesting of inline styles.
 */
private fun collectInlines(node: Node, theme: MarkdownTheme): AnnotatedString {
    val collector = InlineCollector(theme)
    var child = node.firstChild
    while (child != null) {
        child.accept(collector)
        child = child.next
    }
    return collector.build()
}

/**
 * Visits inline-level CommonMark nodes and builds a single AnnotatedString
 * with properly nested SpanStyles via a style stack.
 */
private class InlineCollector(
    private val theme: MarkdownTheme,
) : AbstractVisitor() {

    private val segments = mutableListOf<Pair<String, List<SpanStyle>>>()
    private val styleStack = mutableListOf<SpanStyle>()

    fun build(): AnnotatedString {
        if (segments.isEmpty()) return annotated("")
        return buildAnnotatedString {
            segments.forEach { (text, styles) ->
                if (styles.isEmpty()) {
                    append(text)
                } else {
                    fun applyStyles(index: Int) {
                        if (index >= styles.size) {
                            append(text)
                        } else {
                            withStyle(styles[index]) {
                                applyStyles(index + 1)
                            }
                        }
                    }
                    applyStyles(0)
                }
            }
        }
    }

    override fun visit(text: Text) {
        segments += text.literal to styleStack.toList()
    }

    override fun visit(code: Code) {
        segments += code.literal to (styleStack + theme.inlineCodeStyle)
    }

    override fun visit(emphasis: Emphasis) {
        styleStack += theme.italicStyle
        visitChildren(emphasis)
        styleStack.removeAt(styleStack.lastIndex)
    }

    override fun visit(strongEmphasis: StrongEmphasis) {
        styleStack += theme.boldStyle
        visitChildren(strongEmphasis)
        styleStack.removeAt(styleStack.lastIndex)
    }

    override fun visit(link: Link) {
        styleStack += theme.linkStyle
        visitChildren(link)
        styleStack.removeAt(styleStack.lastIndex)
        segments += " (${link.destination})" to (styleStack + theme.linkUrlStyle)
    }

    override fun visit(image: Image) {
        val altText = image.firstChild?.let { child ->
            if (child is Text) child.literal else ""
        } ?: ""
        if (altText.isNotEmpty()) {
            segments += altText to styleStack.toList()
        }
        segments += " [image]" to (styleStack + SpanStyle(textStyle = TextStyle.Dim))
    }

    override fun visit(softLineBreak: SoftLineBreak) {
        segments += " " to styleStack.toList()
    }

    override fun visit(hardLineBreak: HardLineBreak) {
        segments += "\n" to styleStack.toList()
    }

    override fun visit(customNode: org.commonmark.node.CustomNode) {
        when (customNode) {
            is Strikethrough -> {
                styleStack += theme.strikethroughStyle
                visitChildren(customNode)
                styleStack.removeAt(styleStack.lastIndex)
            }
            else -> visitChildren(customNode)
        }
    }
}

// ---------------------------------------------------------------------------
// AnnotatedString wrapping utilities
// ---------------------------------------------------------------------------

/**
 * Wraps an AnnotatedString to a maximum width, splitting at word boundaries
 * and preserving SpanStyle ranges across line breaks.
 *
 * A position is considered breakable after whitespace or after a hyphen.
 * When a word exceeds the available width, falls back to character-level
 * breaking at the current position.
 */
public fun wrapAnnotated(text: AnnotatedString, width: Int): List<AnnotatedString> {
    if (width <= 0 || text.text.isEmpty()) return listOf(text)

    val plainText = text.text
    if (cellWidth(plainText) <= width && !plainText.contains('\n')) return listOf(text)

    val lines = mutableListOf<AnnotatedString>()
    var lineStart = 0
    var col = 0
    var i = 0
    // breakLineEnd: where to end the current line (exclusive index into plainText)
    // breakNextStart: where the next line begins (after skipping whitespace)
    var breakLineEnd = -1
    var breakNextStart = -1

    while (i < plainText.length) {
        if (plainText[i] == '\n') {
            lines += text.subSequence(lineStart, i)
            lineStart = i + 1
            col = 0
            breakLineEnd = -1
            i++
            continue
        }

        val codePoint = plainText.codePointAt(i)
        val charCount = Character.charCount(codePoint)
        val cw = codePointCellWidth(codePoint)

        if (col > 0 && col + cw > width) {
            val overflowIsSpace = plainText[i] == ' ' || plainText[i] == '\t'
            if (overflowIsSpace) {
                // The overflowing character is whitespace — break right before it
                lines += text.subSequence(lineStart, i)
                var skipIdx = i
                while (skipIdx < plainText.length &&
                    (plainText[skipIdx] == ' ' || plainText[skipIdx] == '\t')
                ) skipIdx++
                lineStart = skipIdx
                i = skipIdx
                col = 0
                breakLineEnd = -1
                continue
            }
            if (breakLineEnd > lineStart) {
                lines += text.subSequence(lineStart, breakLineEnd)
                lineStart = breakNextStart
                col = cellWidthRange(plainText, lineStart, i)
                breakLineEnd = -1
            } else {
                lines += text.subSequence(lineStart, i)
                lineStart = i
                col = 0
                breakLineEnd = -1
            }
            continue
        }

        col += cw
        i += charCount

        // Mark breakable positions
        val ch = plainText[i - charCount]
        if (ch == ' ' || ch == '\t') {
            // For whitespace: line ends before the space, next line starts after
            breakLineEnd = i - charCount
            var skip = i
            while (skip < plainText.length && plainText[skip] == ' ') skip++
            breakNextStart = if (skip == i) i else skip
        } else if (ch == '-') {
            // For hyphens: line ends after the hyphen, next line starts right after
            breakLineEnd = i
            breakNextStart = i
        }
    }

    if (lineStart <= plainText.length) {
        lines += text.subSequence(lineStart, plainText.length)
    }

    return lines.ifEmpty { listOf(annotated("")) }
}

/**
 * Wraps an AnnotatedString to a maximum width using character-level breaking.
 * Preserves SpanStyle ranges across line breaks.
 *
 * Use this for content where character-level precision matters (code blocks, diffs).
 */
public fun wrapAnnotatedRaw(text: AnnotatedString, width: Int): List<AnnotatedString> {
    if (width <= 0 || text.text.isEmpty()) return listOf(text)

    val plainText = text.text
    if (plainText.length <= width && !plainText.contains('\n')) return listOf(text)

    val lines = mutableListOf<AnnotatedString>()
    var lineStart = 0
    var col = 0
    var i = 0

    while (i < plainText.length) {
        if (plainText[i] == '\n') {
            lines += text.subSequence(lineStart, i)
            lineStart = i + 1
            col = 0
            i++
            continue
        }

        val codePoint = plainText.codePointAt(i)
        val charCount = Character.charCount(codePoint)
        val cw = codePointCellWidth(codePoint)

        if (col > 0 && col + cw > width) {
            lines += text.subSequence(lineStart, i)
            lineStart = i
            col = 0
        }

        col += cw
        i += charCount
    }

    if (lineStart <= plainText.length) {
        lines += text.subSequence(lineStart, plainText.length)
    }

    return lines.ifEmpty { listOf(annotated("")) }
}

/**
 * Wraps an AnnotatedString with prefix strings for the first and continuation lines.
 *
 * Wraps the full text at the continuation width first, then checks whether the
 * first line fits within the (potentially narrower) first-prefix width. This
 * avoids splitting a word at the first-width boundary that would rejoin on the
 * next line at the wider continuation width.
 */
public fun wrapWithPrefix(
    text: AnnotatedString,
    width: Int,
    firstPrefix: AnnotatedString,
    restPrefix: AnnotatedString = firstPrefix,
): List<AnnotatedString> {
    if (width <= 0) return listOf(firstPrefix + text)

    val firstPrefixWidth = firstPrefix.text.length
    val restPrefixWidth = restPrefix.text.length
    val firstContentWidth = (width - firstPrefixWidth).coerceAtLeast(1)
    val restContentWidth = (width - restPrefixWidth).coerceAtLeast(1)

    // Wrap at the continuation width first (typically the wider column)
    val wrapped = wrapAnnotated(text, restContentWidth)
    if (wrapped.isEmpty()) return listOf(firstPrefix)

    val output = mutableListOf<AnnotatedString>()
    val firstLine = wrapped[0]

    // If the first line fits the (potentially narrower) first-prefix width, emit directly
    if (cellWidth(firstLine.text) <= firstContentWidth) {
        output += firstPrefix + firstLine
        for (idx in 1 until wrapped.size) {
            output += restPrefix + wrapped[idx]
        }
    } else {
        // First line is too wide for the first prefix — re-wrap it at firstContentWidth
        val rewrappedFirst = wrapAnnotated(firstLine, firstContentWidth)
        rewrappedFirst.forEachIndexed { idx, line ->
            if (idx == 0) {
                output += firstPrefix + line
            } else {
                output += restPrefix + line
            }
        }
        for (idx in 1 until wrapped.size) {
            output += restPrefix + wrapped[idx]
        }
    }

    return output
}

/**
 * Truncates an AnnotatedString to fit within [maxWidth] terminal cells,
 * preserving SpanStyle ranges.
 */
private fun truncateToWidth(text: AnnotatedString, maxWidth: Int): AnnotatedString {
    var width = 0
    var i = 0
    while (i < text.text.length) {
        val codePoint = text.text.codePointAt(i)
        val cw = codePointCellWidth(codePoint)
        if (width + cw > maxWidth) break
        width += cw
        i += Character.charCount(codePoint)
    }
    return text.subSequence(0, i)
}

/**
 * Returns the total terminal cell width of a plain string.
 */
private fun cellWidth(text: String): Int {
    var total = 0
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        total += codePointCellWidth(codePoint)
        i += Character.charCount(codePoint)
    }
    return total
}

/**
 * Returns the terminal cell width of a substring [from, to) without allocating.
 */
private fun cellWidthRange(text: String, from: Int, to: Int): Int {
    var total = 0
    var i = from
    while (i < to && i < text.length) {
        val codePoint = text.codePointAt(i)
        total += codePointCellWidth(codePoint)
        i += Character.charCount(codePoint)
    }
    return total
}

/**
 * Returns the terminal cell width of a Unicode code point.
 * Wide characters (CJK) take 2 cells; combining marks take 0.
 */
private fun codePointCellWidth(codePoint: Int): Int {
    if (codePoint == 0) return 0

    val category = Character.getType(codePoint)
    if (
        category == Character.NON_SPACING_MARK.toInt() ||
        category == Character.ENCLOSING_MARK.toInt() ||
        category == Character.COMBINING_SPACING_MARK.toInt()
    ) return 0

    if (Character.isISOControl(codePoint)) return 0

    return if (isWideCodePoint(codePoint)) 2 else 1
}

private fun isWideCodePoint(codePoint: Int): Boolean {
    return (codePoint in 0x1100..0x115F) ||
        (codePoint in 0x2E80..0xA4CF) ||
        (codePoint in 0xAC00..0xD7A3) ||
        (codePoint in 0xF900..0xFAFF) ||
        (codePoint in 0xFE10..0xFE19) ||
        (codePoint in 0xFE30..0xFE6F) ||
        (codePoint in 0xFF00..0xFF60) ||
        (codePoint in 0xFFE0..0xFFE6)
}

/**
 * Extracts a sub-range from an AnnotatedString, preserving any SpanStyle
 * ranges that overlap with the specified region.
 */
private fun AnnotatedString.subSequence(start: Int, end: Int): AnnotatedString {
    if (start == 0 && end == text.length) return this
    val subText = text.substring(start, end)
    if (spanStyles.isEmpty()) return annotated(subText)

    return buildAnnotatedString {
        append(subText)
        for (range in spanStyles) {
            val newStart = (range.start - start).coerceAtLeast(0)
            val newEnd = (range.end - start).coerceAtMost(subText.length)
            if (newStart < newEnd) {
                addStyle(range.item, newStart, newEnd)
            }
        }
    }
}
