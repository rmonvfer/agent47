package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import co.agentmode.agent47.ui.core.util.formatTokens
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlin.math.roundToInt

/**
 * Data model for the Mosaic status bar / footer.
 */
public data class MosaicStatusBarState(
    val cwdName: String,
    val cwdPath: String,
    val branch: String?,
    val modelId: String?,
    val thinking: Boolean,
    val thinkingLabel: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val cost: Double?,
    val contextTokens: Int?,
    val contextWindow: Int?,
    val busy: Boolean = false,
    val spinnerFrame: Int = 0,
)

/**
 * Renders the ohm-style two-line footer, entirely in the dim color with no background:
 *
 *   `~/path/to/cwd (branch)`
 *   `↑<in> ↓<out> $<cost> <ctx%>%/<window>` … right-aligned `<model> · <thinking>`
 *
 * The context-percentage segment is colored (warning past 70%, error past 90%); everything
 * else is dim. Segments only appear when their value is present.
 */
@Composable
public fun StatusBar(
    state: MosaicStatusBarState,
    width: Int,
) {
    val theme = LocalThemeConfig.current
    val dim = SpanStyle(color = theme.colors.dim)

    // Line 1 — working directory and git branch.
    val line1Text = buildString {
        append(state.cwdPath)
        if (!state.branch.isNullOrBlank()) append(" (${state.branch})")
    }
    val line1 = annotated(truncatePlain(line1Text, width), dim)

    // Line 2 left — token / cost / context stats.
    val leftParts = mutableListOf<AnnotatedString>()
    state.inputTokens?.takeIf { it > 0 }?.let { leftParts += annotated("↑${formatTokens(it.toLong())}", dim) }
    state.outputTokens?.takeIf { it > 0 }?.let { leftParts += annotated("↓${formatTokens(it.toLong())}", dim) }
    state.cost?.takeIf { it > 0.0 }?.let { leftParts += annotated("$" + "%.4f".format(it), dim) }
    contextPercent(state.contextTokens, state.contextWindow)?.let { pct ->
        val ctxColor = when {
            pct > 90 -> theme.colors.error
            pct > 70 -> theme.colors.warning
            else -> theme.colors.dim
        }
        val window = state.contextWindow?.let { formatTokens(it.toLong()) } ?: "?"
        leftParts += annotated("$pct%/$window", SpanStyle(color = ctxColor))
    }
    val left = joinParts(leftParts, annotated(" "))
    val leftLen = left.text.length

    // Line 2 right — model and thinking level, right-aligned.
    val rightText = buildString {
        append(state.modelId ?: "-")
        state.thinkingLabel?.let { append(" · $it") }
    }
    val rightBudget = (width - leftLen - 2).coerceAtLeast(0)
    val rightShown = truncatePlain(rightText, rightBudget)
    val gap = (width - leftLen - rightShown.length).coerceAtLeast(1)

    val line2 = buildAnnotatedString {
        append(left)
        append(" ".repeat(gap))
        withStyle(dim) { append(rightShown) }
    }

    Column(modifier = Modifier.fillMaxWidth().height(2)) {
        Text(line1)
        Text(line2)
    }
}

private fun joinParts(
    parts: List<AnnotatedString>,
    separator: AnnotatedString,
): AnnotatedString = buildAnnotatedString {
    parts.forEachIndexed { index, part ->
        if (index > 0) append(separator)
        append(part)
    }
}

private fun contextPercent(tokens: Int?, window: Int?): Int? {
    if (tokens == null || window == null || window <= 0) return null
    return ((tokens.toDouble() / window.toDouble()) * 100.0)
        .roundToInt()
        .coerceAtLeast(0)
}

private fun truncatePlain(text: String, maxWidth: Int, ellipsis: String = "…"): String {
    if (text.length <= maxWidth) return text
    val available = (maxWidth - ellipsis.length).coerceAtLeast(0)
    return if (available == 0) ellipsis.take(maxWidth) else text.take(available) + ellipsis
}
