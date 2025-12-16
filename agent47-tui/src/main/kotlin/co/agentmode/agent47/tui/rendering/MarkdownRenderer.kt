package co.agentmode.agent47.tui.rendering

import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.TerminalColors
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle

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
    val linkStyle: SpanStyle = SpanStyle(color = TerminalColors.CYAN),
    val linkUrlStyle: SpanStyle = SpanStyle(textStyle = TextStyle.Dim),
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
            linkStyle = SpanStyle(color = theme.link),
            linkUrlStyle = SpanStyle(color = theme.linkUrl),
        )
    }
}

/**
 * Renders Markdown text into styled AnnotatedString lines for Mosaic's Text composable.
 *
 * Handles headings, code fences, blockquotes, bullet lists, horizontal rules,
 * and inline styles (bold, italic, strikethrough, inline code).
 */
public class MarkdownRenderer(
    private val theme: MarkdownTheme = MarkdownTheme(),
) {
    /**
     * Renders Markdown source into a list of AnnotatedString lines, each wrapped to [width].
     */
    public fun render(markdown: String, width: Int): List<AnnotatedString> {
        if (width <= 0) return listOf(annotated(""))
        if (markdown.isBlank()) return listOf(annotated(""))

        val output = mutableListOf<AnnotatedString>()
        var inCodeFence = false
        var codeFenceLanguage: String? = null

        markdown.split("\n").forEach { rawLine ->
            if (rawLine.trimStart().startsWith("```")) {
                if (!inCodeFence) {
                    inCodeFence = true
                    codeFenceLanguage = rawLine.trim().removePrefix("```").trim().ifBlank { null }
                    val label = codeFenceLanguage ?: ""
                    output += listOf(buildAnnotatedString {
                        withStyle(theme.codeBlockBorderStyle) {
                            append("┌")
                            if (label.isNotEmpty()) {
                                append("─ ")
                                append(label)
                                append(" ")
                            }
                            val used = 1 + if (label.isNotEmpty()) 3 + label.length else 0
                            val fill = (width - used).coerceAtLeast(0)
                            append("─".repeat(fill))
                        }
                    })
                } else {
                    inCodeFence = false
                    codeFenceLanguage = null
                    output += listOf(buildAnnotatedString {
                        withStyle(theme.codeBlockBorderStyle) {
                            append("└")
                            append("─".repeat((width - 1).coerceAtLeast(0)))
                        }
                    })
                }
                return@forEach
            }

            if (inCodeFence) {
                val prefixWidth = 2
                val contentWidth = (width - prefixWidth).coerceAtLeast(1)
                val padded = rawLine.take(contentWidth).padEnd(contentWidth)
                output += listOf(buildAnnotatedString {
                    withStyle(theme.codeBlockBorderStyle) { append("│ ") }
                    withStyle(theme.codeBlockStyle) { append(padded) }
                })
                return@forEach
            }

            val heading = HEADING_REGEX.matchEntire(rawLine)
            if (heading != null) {
                val level = heading.groupValues[1].length
                val text = heading.groupValues[2].trim()
                val prefix = "#".repeat(level) + " "
                output += wrapWithPrefix(
                    text = applyInlineStyles(text),
                    width = width,
                    firstPrefix = annotated(prefix, theme.headingStyle),
                    restPrefix = annotated(" ".repeat(prefix.length)),
                )
                return@forEach
            }

            val quote = QUOTE_REGEX.matchEntire(rawLine)
            if (quote != null) {
                val quoteText = applyInlineStyles(quote.groupValues[1])
                val styledQuote = buildAnnotatedString {
                    withStyle(theme.quoteStyle) { append(quoteText) }
                }
                output += wrapWithPrefix(
                    text = styledQuote,
                    width = width,
                    firstPrefix = annotated("| ", theme.quoteBorderStyle),
                    restPrefix = annotated("| ", theme.quoteBorderStyle),
                )
                return@forEach
            }

            val bullet = BULLET_REGEX.matchEntire(rawLine)
            if (bullet != null) {
                val indent = bullet.groupValues[1]
                val bulletPrefix = buildAnnotatedString {
                    append(indent)
                    withStyle(theme.listBulletStyle) { append("- ") }
                }
                val continuationPrefix = annotated("$indent  ")
                output += wrapWithPrefix(
                    text = applyInlineStyles(bullet.groupValues[3]),
                    width = width,
                    firstPrefix = bulletPrefix,
                    restPrefix = continuationPrefix,
                )
                return@forEach
            }

            val orderedItem = ORDERED_LIST_REGEX.matchEntire(rawLine)
            if (orderedItem != null) {
                val indent = orderedItem.groupValues[1]
                val number = orderedItem.groupValues[2]
                val numberPrefix = buildAnnotatedString {
                    append(indent)
                    withStyle(theme.listBulletStyle) { append("$number. ") }
                }
                val continuationIndent = indent + " ".repeat(number.length + 2)
                val continuationPrefix = annotated(continuationIndent)
                output += wrapWithPrefix(
                    text = applyInlineStyles(orderedItem.groupValues[3]),
                    width = width,
                    firstPrefix = numberPrefix,
                    restPrefix = continuationPrefix,
                )
                return@forEach
            }

            if (rawLine.trim().matches(HR_REGEX)) {
                output += listOf(
                    annotated("-".repeat(width.coerceAtLeast(3)), theme.hrStyle),
                )
                return@forEach
            }

            if (rawLine.isBlank()) {
                output += annotated("")
            } else {
                output += wrapAnnotated(applyInlineStyles(rawLine), width)
            }
        }

        return if (output.isEmpty()) listOf(annotated("")) else output
    }

    /**
     * Applies inline Markdown styles (bold, italic, strikethrough, inline code) to text,
     * returning an AnnotatedString with the appropriate SpanStyles applied.
     */
    private fun applyInlineStyles(text: String): AnnotatedString {
        if (text.isEmpty()) return annotated("")

        val codeTokens = mutableMapOf<String, AnnotatedString>()
        var tokenIndex = 0

        // Replace inline code with tokens to protect from further processing
        var transformed = INLINE_CODE_REGEX.replace(text) { match ->
            val token = "\u0000code_${tokenIndex++}\u0000"
            codeTokens[token] = annotated(match.groupValues[1], theme.inlineCodeStyle)
            token
        }

        // Apply links [text](url), before bold/italic to prevent interference
        transformed = LINK_REGEX.replace(transformed) { match ->
            val linkText = match.groupValues[1]
            val linkUrl = match.groupValues[2]
            val token = "\u0000link_${tokenIndex++}\u0000"
            codeTokens[token] = buildAnnotatedString {
                withStyle(theme.linkStyle) { append(linkText) }
                withStyle(theme.linkUrlStyle) { append(" ($linkUrl)") }
            }
            token
        }

        // Apply bold
        transformed = BOLD_REGEX.replace(transformed) { match ->
            val token = "\u0000bold_${tokenIndex++}\u0000"
            codeTokens[token] = buildAnnotatedString {
                withStyle(theme.boldStyle) { append(resolveTokens(match.groupValues[2], codeTokens)) }
            }
            token
        }

        // Apply italic
        transformed = ITALIC_REGEX.replace(transformed) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            if (value.isBlank()) {
                match.value
            } else {
                val token = "\u0000italic_${tokenIndex++}\u0000"
                codeTokens[token] = buildAnnotatedString {
                    withStyle(theme.italicStyle) { append(resolveTokens(value, codeTokens)) }
                }
                prefix + token
            }
        }

        // Apply strikethrough
        transformed = STRIKETHROUGH_REGEX.replace(transformed) { match ->
            val token = "\u0000strike_${tokenIndex++}\u0000"
            codeTokens[token] = buildAnnotatedString {
                withStyle(theme.strikethroughStyle) { append(resolveTokens(match.groupValues[1], codeTokens)) }
            }
            token
        }

        return resolveTokens(transformed, codeTokens)
    }

    private companion object {
        private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.*)$")
        private val BULLET_REGEX = Regex("^(\\s*)([-*+])\\s+(.*)$")
        private val QUOTE_REGEX = Regex("^>\\s?(.*)$")
        private val HR_REGEX = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
        private val ORDERED_LIST_REGEX = Regex("^(\\s*)(\\d+)\\.\\s+(.*)$")
        private val INLINE_CODE_REGEX = Regex("`([^`]+)`")
        private val LINK_REGEX = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")
        private val BOLD_REGEX = Regex("(\\*\\*|__)(.+?)\\1")
        private val ITALIC_REGEX = Regex("(^|[^*])\\*(?!\\*)([^*]+)\\*(?!\\*)")
        private val STRIKETHROUGH_REGEX = Regex("~~(.+?)~~")
        private val TOKEN_REGEX = Regex("\u0000\\w+_\\d+\u0000")

        private fun resolveTokens(
            text: String,
            tokens: Map<String, AnnotatedString>,
        ): AnnotatedString = buildAnnotatedString {
            var remaining = text
            while (remaining.isNotEmpty()) {
                val nextToken = TOKEN_REGEX.find(remaining)
                if (nextToken == null) {
                    append(remaining)
                    break
                }
                append(remaining.substring(0, nextToken.range.first))
                val styled = tokens[nextToken.value]
                if (styled != null) {
                    append(styled)
                } else {
                    append(nextToken.value)
                }
                remaining = remaining.substring(nextToken.range.last + 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AnnotatedString wrapping utilities
// ---------------------------------------------------------------------------

/**
 * Wraps an AnnotatedString to a maximum width, splitting at character boundaries
 * and preserving SpanStyle ranges across line breaks.
 */
public fun wrapAnnotated(text: AnnotatedString, width: Int): List<AnnotatedString> {
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
        val cellWidth = codePointCellWidth(codePoint)

        if (col > 0 && col + cellWidth > width) {
            lines += text.subSequence(lineStart, i)
            lineStart = i
            col = 0
        }

        col += cellWidth
        i += charCount
    }

    if (lineStart <= plainText.length) {
        lines += text.subSequence(lineStart, plainText.length)
    }

    return lines.ifEmpty { listOf(annotated("")) }
}

/**
 * Wraps an AnnotatedString with prefix strings for the first and continuation lines,
 * similar to wrapWithPrefix but operating on AnnotatedStrings.
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

    val wrappedFirst = wrapAnnotated(text, firstContentWidth)
    if (wrappedFirst.isEmpty()) return listOf(firstPrefix)

    val output = mutableListOf<AnnotatedString>()

    wrappedFirst.forEachIndexed { index, line ->
        if (index == 0) {
            output += firstPrefix + line
        } else {
            val rewrapped = wrapAnnotated(line, restContentWidth)
            if (rewrapped.isEmpty()) {
                output += restPrefix
            } else {
                rewrapped.forEach { continuation ->
                    output += restPrefix + continuation
                }
            }
        }
    }

    return output
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
