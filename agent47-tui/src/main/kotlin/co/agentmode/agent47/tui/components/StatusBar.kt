package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.theme.LocalThemeConfig
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

/** Data model for the two-line footer beneath the editor. */
public data class MosaicStatusBarState(
    val cwdPath: String,
    val branch: String?,
    val providerId: String?,
    val availableProviderCount: Int,
    val modelId: String?,
    val modelSupportsReasoning: Boolean,
    val thinkingLabel: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val latestCacheHitRate: Double?,
    val cost: Double,
    val usingSubscription: Boolean,
    val contextTokens: Int?,
    val contextWindow: Int,
    val autoCompactEnabled: Boolean,
    val showUsage: Boolean,
)

/** Renders the working directory and ohm-compatible usage/model status line. */
@Composable
public fun StatusBar(
    state: MosaicStatusBarState,
    width: Int,
) {
    val theme = LocalThemeConfig.current
    val lines = renderStatusBar(state, width, theme.colors.dim, theme.colors.warning, theme.colors.error)
    Column(modifier = Modifier.fillMaxWidth().height(2)) {
        Text(lines.first)
        Text(lines.second)
    }
}

internal fun renderStatusBar(
    state: MosaicStatusBarState,
    width: Int,
    dimColor: com.jakewharton.mosaic.ui.Color,
    warningColor: com.jakewharton.mosaic.ui.Color,
    errorColor: com.jakewharton.mosaic.ui.Color,
): Pair<AnnotatedString, AnnotatedString> {
    val dim = SpanStyle(color = dimColor)
    val path = buildString {
        append(state.cwdPath)
        if (!state.branch.isNullOrBlank()) append(" (${state.branch})")
    }
    val first = annotated(truncatePlain(path, width), dim)

    val parts = mutableListOf<AnnotatedString>()
    if (state.showUsage) {
        if (state.inputTokens > 0) parts += annotated("↑${formatTokens(state.inputTokens.toLong())}", dim)
        if (state.outputTokens > 0) parts += annotated("↓${formatTokens(state.outputTokens.toLong())}", dim)
        if (state.cacheReadTokens > 0) parts += annotated("R${formatTokens(state.cacheReadTokens.toLong())}", dim)
        if (state.cacheWriteTokens > 0) parts += annotated("W${formatTokens(state.cacheWriteTokens.toLong())}", dim)
        if ((state.cacheReadTokens > 0 || state.cacheWriteTokens > 0) && state.latestCacheHitRate != null) {
            parts += annotated("CH${"%.1f".format(state.latestCacheHitRate)}%", dim)
        }
        if (state.cost > 0.0 || state.usingSubscription) {
            val subscription = if (state.usingSubscription) " (sub)" else ""
            parts += annotated("$${"%.3f".format(state.cost)}$subscription", dim)
        }

        val percent = contextPercent(state.contextTokens, state.contextWindow)
        val percentText = percent?.let { "${"%.1f".format(it)}%" } ?: "?"
        val window = formatTokens(state.contextWindow.toLong())
        val auto = if (state.autoCompactEnabled) " (auto)" else ""
        val contextColor = when {
            percent != null && percent > 90.0 -> errorColor
            percent != null && percent > 70.0 -> warningColor
            else -> dimColor
        }
        parts += annotated("$percentText/$window$auto", SpanStyle(color = contextColor))
    }
    val left = joinParts(parts, annotated(" "))

    val model = state.modelId ?: "no-model"
    val modelWithThinking = if (state.modelSupportsReasoning) {
        "$model • ${if (state.thinkingLabel == "off") "thinking off" else state.thinkingLabel}"
    } else {
        model
    }
    val providerModel = if (state.availableProviderCount > 1 && state.providerId != null) {
        "(${state.providerId}) $modelWithThinking"
    } else {
        modelWithThinking
    }
    val right = if (left.text.length + 2 + providerModel.length <= width) providerModel else modelWithThinking
    val rightBudget = (width - left.text.length - 2).coerceAtLeast(0)
    val shownRight = truncatePlain(right, rightBudget, "")
    val gap = (width - left.text.length - shownRight.length).coerceAtLeast(0)
    val second = buildAnnotatedString {
        append(left)
        append(" ".repeat(gap))
        withStyle(dim) { append(shownRight) }
    }
    return first to second
}

private fun joinParts(parts: List<AnnotatedString>, separator: AnnotatedString): AnnotatedString =
    buildAnnotatedString {
        parts.forEachIndexed { index, part ->
            if (index > 0) append(separator)
            append(part)
        }
    }

private fun formatTokens(count: Long): String = when {
    count < 1_000 -> count.toString()
    count < 10_000 -> "%.1fk".format(count / 1_000.0)
    count < 1_000_000 -> "${(count / 1_000.0).roundToInt()}k"
    count < 10_000_000 -> "%.1fM".format(count / 1_000_000.0)
    else -> "${(count / 1_000_000.0).roundToInt()}M"
}

private fun contextPercent(tokens: Int?, window: Int): Double? {
    if (tokens == null || window <= 0) return null
    return ((tokens.toDouble() / window.toDouble()) * 1_000.0).roundToInt() / 10.0
}

private fun truncatePlain(text: String, maxWidth: Int, ellipsis: String = "…"): String {
    if (text.length <= maxWidth) return text
    val available = (maxWidth - ellipsis.length).coerceAtLeast(0)
    return if (available == 0) ellipsis.take(maxWidth) else text.take(available) + ellipsis
}
