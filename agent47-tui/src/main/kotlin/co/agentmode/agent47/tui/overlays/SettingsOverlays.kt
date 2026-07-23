@file:Suppress("MatchingDeclarationName")

package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.tui.commands.helpText
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.state.SelectItem

private enum class SettingsAction {
    Model,
    Provider,
    Thinking,
    Theme,
    Appearance,
    Usage,
    Session,
    Commands,
    Help,
    Exit,
}

internal fun OverlayNavigator.openSettingsOverlay(
    activeTheme: ThemeConfig,
    themeAppearance: ThemeAppearance,
    setActiveTheme: (ThemeConfig) -> Unit,
    setThemeAppearance: (ThemeAppearance) -> Unit,
) {
    val options = listOf(
        SelectItem("Model - choose model", SettingsAction.Model),
        SelectItem("Provider - connect provider", SettingsAction.Provider),
        SelectItem("Thinking - choose level", SettingsAction.Thinking),
        SelectItem("Theme - pick color theme", SettingsAction.Theme),
        SelectItem("Appearance - auto, dark, or light", SettingsAction.Appearance),
        SelectItem("Usage footer - toggle", SettingsAction.Usage),
        SelectItem("Session - load session", SettingsAction.Session),
        SelectItem("Commands - list slash cmds", SettingsAction.Commands),
        SelectItem("Help - show shortcuts", SettingsAction.Help),
        SelectItem("Exit", SettingsAction.Exit),
    )
    overlays.push(
        title = "Settings",
        items = options,
        selectedIndex = 0,
        keepOpenOnSubmit = true,
        onSubmit = { action ->
            when (action) {
                SettingsAction.Model -> openModelOverlay()
                SettingsAction.Provider -> openProviderOverlay()
                SettingsAction.Thinking -> openThinkingOverlay()
                SettingsAction.Theme -> openThemeOverlay(activeTheme, themeAppearance, setActiveTheme)
                SettingsAction.Appearance ->
                    openAppearanceOverlay(activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
                SettingsAction.Usage -> {
                    state.showUsageFooter = !state.showUsageFooter
                    onSettingsChanged { it.copy(showUsageFooter = state.showUsageFooter) }
                    feed.appendCommandResult("Usage footer: ${if (state.showUsageFooter) "on" else "off"}")
                }
                SettingsAction.Session -> openSessionOverlay()
                SettingsAction.Commands -> openCommandsOverlay()
                SettingsAction.Help -> feed.appendCommandResult(helpText(slashCommands()))
                SettingsAction.Exit -> {
                    state.quit(client)
                }
            }
        },
    )
}

internal fun OverlayNavigator.openCommandsOverlay() {
    val options = slashCommands().map { spec ->
        SelectItem(
            label = "${spec.command} - ${spec.description}",
            value = spec.command,
        )
    }
    overlays.push(
        title = "Commands",
        items = options,
        selectedIndex = 0,
        onSubmit = { command ->
            if (command == "/help" || command == "/commands" || command == "/settings") {
                // recursive, handled below via slash command dispatch
                editor.setText(command)
            } else {
                editor.setText(if (command == "/exit") command else "$command ")
            }
            state.editorVersion++
        },
    )
}
