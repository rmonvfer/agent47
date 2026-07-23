package co.agentmode.agent47.tui.controller

import androidx.compose.runtime.Stable
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.session.ModelChangeEntry
import co.agentmode.agent47.coding.core.session.ThinkingLevelChangeEntry
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.tui.session.randomEntryId
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import java.time.Instant

/**
 * Owns model selection and thinking-level changes: applying a model, cycling through the list,
 * setting the thinking level, and adopting the first model of a newly connected provider.
 */
@Stable
internal class ModelController(
    private val state: TuiAppState,
    private val client: AgentClient,
    private val feed: TranscriptFeed,
    private val onSettingsChanged: (transform: (Settings) -> Settings) -> Unit,
    private val refreshModels: () -> List<Model>,
) {
    fun applyModel(
        model: Model,
        recordSessionEntry: Boolean = true,
        announce: Boolean = true,
    ) {
        client.setModel(model)
        state.selectedModelIndex = state.currentModels
            .indexOfFirst { it.id == model.id && it.provider == model.provider }
            .takeIf { it >= 0 }
            ?: state.selectedModelIndex

        if (recordSessionEntry) {
            state.activeSessionManager?.append(
                ModelChangeEntry(
                    id = randomEntryId(),
                    parentId = state.activeSessionManager?.getLeafId(),
                    timestamp = Instant.now().toString(),
                    provider = model.provider.value,
                    modelId = model.id,
                ),
            )
        }
        if (announce) {
            feed.appendCommandResult("Model set to ${model.provider.value}/${model.id}")
        }
        onSettingsChanged { it.copy(defaultModel = model.id, defaultProvider = model.provider.value) }
    }

    fun cycleModel(direction: Int) {
        val models = state.currentModels
        if (models.isEmpty()) {
            feed.appendSystemMessage("No models available")
            return
        }
        val size = models.size
        val current = state.selectedModelIndex.coerceIn(0, size - 1)
        val newIndex = ((current + direction) % size + size) % size
        state.selectedModelIndex = newIndex
        applyModel(models[newIndex])
    }

    fun setThinkingLevel(
        level: AgentThinkingLevel,
        recordSessionEntry: Boolean = true,
        announce: Boolean = true,
    ) {
        state.thinkingLevel = level
        client.setThinkingLevel(level)
        if (recordSessionEntry) {
            state.activeSessionManager?.append(
                ThinkingLevelChangeEntry(
                    id = randomEntryId(),
                    parentId = state.activeSessionManager?.getLeafId(),
                    timestamp = Instant.now().toString(),
                    thinkingLevel = level.name.lowercase(),
                ),
            )
        }
        if (announce) {
            feed.appendCommandResult("Thinking set to ${level.name.lowercase()}")
        }
        onSettingsChanged { it.copy(defaultThinkingLevel = level.name.lowercase()) }
    }

    fun onProviderConnected(info: ProviderInfo) {
        val previousModel = state.currentModel
        val previousIndex = state.selectedModelIndex
        state.currentModels = refreshModels()
        val newIndex = state.currentModels.indexOfFirst {
            it.provider.value == info.id
        }.takeIf { it >= 0 }
        if (newIndex != null && (previousIndex < 0 || previousModel == null)) {
            applyModel(state.currentModels[newIndex])
        }
        feed.appendCommandResult(
            "Connected ${info.name} — ${state.currentModels.count { it.provider.value == info.id }} models available",
        )
    }
}
