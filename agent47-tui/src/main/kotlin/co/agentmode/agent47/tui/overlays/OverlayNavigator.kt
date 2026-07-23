package co.agentmode.agent47.tui.overlays

import androidx.compose.runtime.Stable
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.instructions.InstructionFile
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.tui.commands.SlashCommandSpec
import co.agentmode.agent47.tui.commands.builtinSlashCommands
import co.agentmode.agent47.tui.controller.AgentPanelController
import co.agentmode.agent47.tui.controller.ModelController
import co.agentmode.agent47.tui.controller.ProviderAuthController
import co.agentmode.agent47.tui.controller.SessionController
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.tui.theme.NamedTheme
import co.agentmode.agent47.ui.core.state.OverlayHostState
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Thin wrapper over [OverlayHostState] that carries the dependencies the overlay flows need.
 * Feature overlays are extension functions on this navigator, so opening one is a single call
 * from the composable instead of a captured closure.
 */
@Stable
internal class OverlayNavigator(
    val state: TuiAppState,
    val feed: TranscriptFeed,
    val client: AgentClient,
    val models: ModelController,
    val providerAuth: ProviderAuthController,
    val agentPanel: AgentPanelController,
    val session: SessionController,
    val editor: Editor,
    val scope: CoroutineScope,
    val cwd: Path,
    val sessionsDir: Path?,
    val instructionFiles: List<InstructionFile>,
    val backgroundAgents: BackgroundAgents?,
    val agentRegistry: AgentRegistry?,
    val scheduler: SubagentScheduler?,
    val availableThemes: List<NamedTheme>,
    val fileSlashCommands: List<SlashCommand>,
    val getAllProviders: () -> List<ProviderInfo>,
    val onSettingsChanged: (transform: (Settings) -> Settings) -> Unit,
) {
    val overlays: OverlayHostState get() = state.overlays

    fun slashCommands(): List<SlashCommandSpec> =
        builtinSlashCommands +
            state.extensionCommands.map { SlashCommandSpec("/${it.name}", it.description) } +
            fileSlashCommands.map { SlashCommandSpec("/${it.name}", it.description) }
}
