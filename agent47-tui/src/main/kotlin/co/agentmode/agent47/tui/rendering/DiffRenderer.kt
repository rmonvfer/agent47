package co.agentmode.agent47.tui.rendering

import co.agentmode.agent47.tui.theme.ThemeConfig
import com.github.difflib.DiffUtils
import com.github.difflib.UnifiedDiffUtils
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle

/**
 * Builds colorized unified diff output as AnnotatedString lines for Mosaic's Text composable.
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
        unified.forEach { line: String ->
            val colored = colorLine(line)
            if (width > 0) {
                output += wrapAnnotated(colored, width)
            } else {
                output += colored
            }
        }
        return output
    }

    private fun colorLine(line: String): AnnotatedString {
        val style = when {
            line.startsWith("+++") || line.startsWith("---") -> SpanStyle(color = theme.colors.muted)
            line.startsWith("@@") -> SpanStyle(color = theme.colors.accentBright)
            line.startsWith("+") -> SpanStyle(color = theme.toolDiffAdded)
            line.startsWith("-") -> SpanStyle(color = theme.toolDiffRemoved)
            else -> SpanStyle(color = theme.toolDiffContext)
        }
        return annotated(line, style)
    }
}
