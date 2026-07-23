package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.Text

@Composable
internal fun ActivityLine(
    spinnerFrame: Int,
    label: String,
    width: Int,
) {
    val theme = LocalThemeConfig.current
    val frames = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    val frame = frames[spinnerFrame.mod(frames.size)]
    val displayLabel = label.ifBlank { "Thinking" }

    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.accent)) { append(frame) }
            append(" ")
            withStyle(SpanStyle(color = theme.colors.muted)) { append(displayLabel) }
            withStyle(SpanStyle(color = theme.colors.muted)) { append("...") }
            val used = 1 + 1 + displayLabel.length + 3
            val padding = (width - used).coerceAtLeast(0)
            if (padding > 0) append(" ".repeat(padding))
        },
    )
}
