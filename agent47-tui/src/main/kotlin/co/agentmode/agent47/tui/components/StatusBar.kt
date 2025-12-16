package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import com.jakewharton.mosaic.layout.fillMaxWidth
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Text
import kotlin.math.roundToInt

/**
 * Data model for the Mosaic status bar.
 */
public data class MosaicStatusBarState(
    val cwdName: String,
    val branch: String?,
    val modelId: String?,
    val thinking: Boolean,
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
 * Mosaic composable that renders a single-line status bar split into left and right
 * halves. Left side shows state, model, and thinking. Right side shows context usage,
 * tokens, cost, branch, and working directory. Segments that don't fit are dropped
 * from the right.
 */
@Composable
public fun StatusBar(
    state: MosaicStatusBarState,
    width: Int,
) {
    val theme = LocalThemeConfig.current
    val dimStyle = SpanStyle(color = theme.colors.dim)
    val mutedStyle = SpanStyle(color = theme.colors.muted)
    val accentStyle = SpanStyle(color = theme.colors.accent)

    val separator = buildAnnotatedString {
        withStyle(mutedStyle) { append(" | ") }
    }
    val separatorLen = 3

    // Left section: state + model + thinking
    val leftParts = mutableListOf<AnnotatedString>()

    leftParts += stateIndicator(state.busy, state.spinnerFrame, accentStyle, mutedStyle)

    val modelShort = truncatePlain(state.modelId ?: "-", (width / 4).coerceIn(10, 30))
    leftParts += buildAnnotatedString { withStyle(accentStyle) { append(modelShort) } }

    if (state.thinking) {
        leftParts += buildAnnotatedString { withStyle(mutedStyle) { append("thinking") } }
    }

    // Right section: context + tokens + cost + branch + cwd
    val rightParts = mutableListOf<AnnotatedString>()

    val contextPct = contextPercent(state.contextTokens, state.contextWindow)
    if (contextPct != null) {
        val ctxColor = when {
            contextPct < 50 -> theme.colors.success
            contextPct < 80 -> theme.colors.warning
            else -> theme.colors.error
        }
        rightParts += buildAnnotatedString {
            withStyle(mutedStyle) { append("ctx ") }
            withStyle(SpanStyle(color = ctxColor)) { append("${contextPct}%") }
        }
    }

    if (state.totalTokens != null) {
        rightParts += buildAnnotatedString {
            withStyle(mutedStyle) {
                append("${state.inputTokens ?: 0}/${state.outputTokens ?: 0}/${state.totalTokens}")
            }
        }
    }

    if (state.cost != null) {
        rightParts += buildAnnotatedString {
            withStyle(mutedStyle) { append("$") }
            withStyle(accentStyle) { append("%.4f".format(state.cost)) }
        }
    }

    state.branch?.takeIf { it.isNotBlank() }?.let { branch ->
        val branchShort = truncatePlain(branch, (width / 6).coerceIn(8, 18))
        rightParts += buildAnnotatedString {
            withStyle(mutedStyle) { append(branchShort) }
        }
    }

    val cwdShort = truncatePlain(state.cwdName, (width / 5).coerceIn(8, 24))
    rightParts += buildAnnotatedString { withStyle(mutedStyle) { append(cwdShort) } }

    // Join left
    val left = joinParts(leftParts, separator)
    val leftLen = left.text.length

    // Greedily build right side to fit remaining space
    val rightChosen = mutableListOf<AnnotatedString>()
    var rightLen = 0
    val minGap = 2
    for (part in rightParts) {
        val partLen = part.text.length
        val needed = if (rightChosen.isEmpty()) partLen else rightLen + separatorLen + partLen
        if (leftLen + minGap + needed <= width) {
            rightChosen += part
            rightLen = needed
        }
    }
    val right = joinParts(rightChosen, separator)

    val gap = (width - leftLen - rightLen).coerceAtLeast(1)

    val line = buildAnnotatedString {
        append(left)
        append(" ".repeat(gap))
        append(right)
        val total = leftLen + gap + rightLen
        val padding = (width - total).coerceAtLeast(0)
        if (padding > 0) append(" ".repeat(padding))
    }

    Box(modifier = Modifier.fillMaxWidth().height(1)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(background = theme.statusBarBg)) {
                    append(line)
                }
            },
        )
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

private fun stateIndicator(
    busy: Boolean,
    spinnerFrame: Int,
    accentStyle: SpanStyle,
    mutedStyle: SpanStyle,
): AnnotatedString {
    if (!busy) return buildAnnotatedString { withStyle(mutedStyle) { append("idle") } }
    return buildAnnotatedString { withStyle(accentStyle) { append("run") } }
}

private fun contextPercent(tokens: Int?, window: Int?): Int? {
    if (tokens == null || window == null || window <= 0) return null
    return ((tokens.toDouble() / window.toDouble()) * 100.0)
        .roundToInt()
        .coerceAtLeast(0)
}

private fun truncatePlain(text: String, maxWidth: Int, ellipsis: String = "..."): String {
    if (text.length <= maxWidth) return text
    val available = (maxWidth - ellipsis.length).coerceAtLeast(0)
    return if (available == 0) ellipsis.take(maxWidth) else text.take(available) + ellipsis
}
