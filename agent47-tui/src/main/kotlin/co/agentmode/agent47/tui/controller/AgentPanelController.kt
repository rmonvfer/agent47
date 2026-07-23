package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState

/**
 * Owns background sub-agent control: persisting subagent settings and steering or stopping a
 * running agent through the [BackgroundAgents] orchestrator.
 */
@Stable
internal class AgentPanelController(
    private val state: TuiAppState,
    private val feed: TranscriptFeed,
    private val backgroundAgents: BackgroundAgents?,
    private val persistSubagentsSettings: (SubagentsSettings) -> Unit,
) {
    fun applySubagentsSettings(updated: SubagentsSettings) {
        state.subagentsSettings = updated
        persistSubagentsSettings(updated)
        feed.appendCommandResult("Updated subagent settings.")
    }

    fun steer(id: String, message: String) {
        val bg = backgroundAgents ?: return
        val ok = bg.post(BackgroundAgents.ORCHESTRATOR, id, message)
        feed.appendCommandResult(if (ok) "Steered $id." else "Agent $id is no longer running.")
    }

    fun stop(id: String) {
        val bg = backgroundAgents ?: return
        val ok = bg.abort(id)
        feed.appendCommandResult(if (ok) "Stopped $id." else "Agent $id could not be stopped.")
    }
}
