package co.agentmode.agent47.app.cli

import co.agentmode.agent47.tui.theme.AVAILABLE_THEMES
import co.agentmode.agent47.tui.theme.NamedTheme

internal fun mergeThemes(packageThemes: List<NamedTheme>): List<NamedTheme> {
    val duplicateNames = packageThemes.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
    require(duplicateNames.isEmpty()) {
        "Duplicate package theme names: ${duplicateNames.sorted().joinToString()}"
    }
    return (AVAILABLE_THEMES + packageThemes)
        .associateBy { it.name }
        .values
        .sortedBy { it.name }
}
