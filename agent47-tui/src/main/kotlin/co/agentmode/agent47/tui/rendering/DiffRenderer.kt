package co.agentmode.agent47.tui.rendering

import co.agentmode.agent47.tui.theme.ThemeConfig
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

/**
 * Builds line-numbered diff output as AnnotatedString lines for Mosaic's Text composable.
 *
 * Each changed line is prefixed with its sign and line number (`+42 `, `-42 `, ` 42 `)
 * and colored (green added, red removed, gray context) — no `@@` hunk headers or file
 * paths are shown. When a change replaces exactly one line with one line, the differing
 * words are highlighted with reverse video on top of the line's color.
 */
public class DiffRenderer(
    private val theme: ThemeConfig = ThemeConfig.DEFAULT,
) {
    public fun render(
        original: String,
        revised: String,
        width: Int,
        contextLines: Int = 3,
        fromFile: String = "a/content",
        toFile: String = "b/content",
    ): List<AnnotatedString> {
        val oldLines = original.split("\n")
        val newLines = revised.split("\n")
        val patch = DiffUtils.diff(oldLines, newLines)
        val unified = UnifiedDiffUtils.generateUnifiedDiff(fromFile, toFile, oldLines, patch, contextLines)

        if (unified.isEmpty()) {
            return listOf(annotated("(no changes)", SpanStyle(color = theme.colors.muted)))
        }

        val output = mutableListOf<AnnotatedString>()
        var oldLine = 0
        var newLine = 0
        var i = 0
        while (i < unified.size) {
            val line = unified[i]
            when {
                line.startsWith("+++") || line.startsWith("---") -> {
                    // File headers are not shown in this style
                }
                line.startsWith("@@") -> {
                    HUNK_HEADER.find(line)?.let { m ->
                        oldLine = m.groupValues[1].toInt()
                        newLine = m.groupValues[2].toInt()
                    }
                }
                line.startsWith("-") -> {
                    val next = unified.getOrNull(i + 1)
                    val prevIsRemoval = i > 0 && unified[i - 1].startsWith("-")
                    val afterNext = unified.getOrNull(i + 2)
                    val isolatedReplacement = next != null && next.startsWith("+") &&
                        !prevIsRemoval && (afterNext == null || !afterNext.startsWith("+"))

                    if (isolatedReplacement) {
                        val (removed, added) = wordDiffLines(
                            oldLine, detab(line.substring(1)),
                            newLine, detab(next!!.substring(1)),
                        )
                        output += wrapMaybe(removed, width)
                        output += wrapMaybe(added, width)
                        oldLine++
                        newLine++
                        i += 2
                        continue
                    }

                    output += wrapMaybe(diffLine("-", oldLine, detab(line.substring(1)), theme.toolDiffRemoved), width)
                    oldLine++
                }
                line.startsWith("+") -> {
                    output += wrapMaybe(diffLine("+", newLine, detab(line.substring(1)), theme.toolDiffAdded), width)
                    newLine++
                }
                else -> {
                    val raw = if (line.startsWith(" ")) line.substring(1) else line
                    output += wrapMaybe(diffLine(" ", newLine, detab(raw), theme.toolDiffContext), width)
                    oldLine++
                    newLine++
                }
            }
            i++
        }
        return output
    }

    private fun diffLine(sign: String, num: Int, content: String, color: Color): AnnotatedString =
        annotated("$sign$num $content", SpanStyle(color = color))

    /**
     * Builds the removed/added line pair for a one-for-one replacement, highlighting the
     * word runs that actually differ with reverse video over the line's base color.
     */
    private fun wordDiffLines(
        oldNum: Int,
        oldContent: String,
        newNum: Int,
        newContent: String,
    ): Pair<AnnotatedString, AnnotatedString> {
        val oldTokens = tokenize(oldContent)
        val newTokens = tokenize(newContent)
        val oldChanged = BooleanArray(oldTokens.size)
        val newChanged = BooleanArray(newTokens.size)
        runCatching {
            val tokenPatch = DiffUtils.diff(oldTokens, newTokens)
            for (delta in tokenPatch.deltas) {
                val s = delta.source
                for (k in s.position until s.position + s.lines.size) {
                    if (k in oldChanged.indices) oldChanged[k] = true
                }
                val t = delta.target
                for (k in t.position until t.position + t.lines.size) {
                    if (k in newChanged.indices) newChanged[k] = true
                }
            }
        }

        val removed = buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolDiffRemoved)) { append("-$oldNum ") }
            oldTokens.forEachIndexed { idx, tok ->
                val style = if (oldChanged[idx]) {
                    SpanStyle(color = theme.toolDiffRemoved, textStyle = TextStyle.Invert)
                } else {
                    SpanStyle(color = theme.toolDiffRemoved)
                }
                withStyle(style) { append(tok) }
            }
        }
        val added = buildAnnotatedString {
            withStyle(SpanStyle(color = theme.toolDiffAdded)) { append("+$newNum ") }
            newTokens.forEachIndexed { idx, tok ->
                val style = if (newChanged[idx]) {
                    SpanStyle(color = theme.toolDiffAdded, textStyle = TextStyle.Invert)
                } else {
                    SpanStyle(color = theme.toolDiffAdded)
                }
                withStyle(style) { append(tok) }
            }
        }
        return removed to added
    }

    private fun wrapMaybe(line: AnnotatedString, width: Int): List<AnnotatedString> =
        if (width > 0) wrapAnnotatedRaw(line, width) else listOf(line)

    private companion object {
        private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
        private val TOKEN = Regex("""\s+|\S+""")

        private fun detab(s: String): String = s.replace("\t", "   ")

        private fun tokenize(s: String): List<String> =
            if (s.isEmpty()) emptyList() else TOKEN.findAll(s).map { it.value }.toList()
    }
}
