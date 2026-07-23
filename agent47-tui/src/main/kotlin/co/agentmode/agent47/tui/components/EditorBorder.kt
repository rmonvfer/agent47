package co.agentmode.agent47.tui.components

import androidx.compose.runtime.Composable
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.tui.theme.LocalThemeConfig
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.ui.Filler

@Composable
internal fun EditorBorder(
    width: Int,
    bashMode: Boolean,
    thinkingLevel: AgentThinkingLevel,
) {
    val theme = LocalThemeConfig.current
    // The input rule's color is the primary mode indicator: green in bash mode,
    // otherwise a distinct shade per thinking level (off to xhigh).
    val color = if (bashMode) {
        theme.bashModeBorder
    } else {
        when (thinkingLevel) {
            AgentThinkingLevel.OFF -> theme.thinkingOff
            AgentThinkingLevel.MINIMAL -> theme.thinkingMinimal
            AgentThinkingLevel.LOW -> theme.thinkingLow
            AgentThinkingLevel.MEDIUM -> theme.thinkingMedium
            AgentThinkingLevel.HIGH -> theme.thinkingHigh
            AgentThinkingLevel.XHIGH -> theme.thinkingXhigh
        }
    }
    Filler('─', modifier = Modifier.width(width).height(1), foreground = color)
}
