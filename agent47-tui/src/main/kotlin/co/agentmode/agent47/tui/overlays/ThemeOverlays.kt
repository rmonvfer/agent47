package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.state.SelectItem

internal fun OverlayNavigator.openThemeOverlay(
    activeTheme: ThemeConfig,
    themeAppearance: ThemeAppearance,
    setActiveTheme: (ThemeConfig) -> Unit,
) {
    val options = availableThemes.map { named ->
        SelectItem(label = named.name, value = named)
    }
    val currentIndex = availableThemes.indexOfFirst {
        it.forAppearance(themeAppearance) == activeTheme
    }.coerceAtLeast(0)

    overlays.push(
        title = "Theme",
        items = options,
        selectedIndex = currentIndex,
        onSubmit = { namedTheme ->
            setActiveTheme(namedTheme.forAppearance(themeAppearance))
            onSettingsChanged { it.copy(theme = namedTheme.name) }
        },
        onClose = { setActiveTheme(activeTheme) },
        onSelectionChanged = { namedTheme ->
            setActiveTheme(namedTheme.forAppearance(themeAppearance))
        },
    )
}

internal fun OverlayNavigator.openAppearanceOverlay(
    activeTheme: ThemeConfig,
    themeAppearance: ThemeAppearance,
    setActiveTheme: (ThemeConfig) -> Unit,
    setThemeAppearance: (ThemeAppearance) -> Unit,
) {
    val appearances = listOf(ThemeAppearance.AUTO, ThemeAppearance.DARK, ThemeAppearance.LIGHT)
    val options = appearances.map { appearance ->
        SelectItem(label = appearance.name.lowercase(), value = appearance)
    }
    overlays.push(
        title = "Appearance",
        items = options,
        selectedIndex = appearances.indexOf(themeAppearance).coerceAtLeast(0),
        onSubmit = { appearance ->
            val resolved = if (appearance == ThemeAppearance.AUTO) themeAppearance else appearance
            setThemeAppearance(resolved)
            availableThemes.firstOrNull { it.config == activeTheme || it.lightConfig == activeTheme }
                ?.let { setActiveTheme(it.forAppearance(resolved)) }
            onSettingsChanged { it.copy(themeAppearance = appearance.name.lowercase()) }
        },
    )
}
