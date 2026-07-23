package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ui.core.state.SelectItem

internal fun OverlayNavigator.openModelOverlay() {
    if (state.currentModels.isEmpty()) {
        feed.appendCommandResult("No models available — use /provider to connect one")
        return
    }
    val options = state.currentModels.map { model ->
        SelectItem(label = "${model.provider.value}/${model.id}", value = model)
    }
    val current = state.currentModels.getOrNull(state.selectedModelIndex)
    val selIndex = current?.let { model ->
        state.currentModels.indexOfFirst { it.id == model.id && it.provider == model.provider }
    } ?: 0
    overlays.push(
        title = "Model",
        items = options,
        selectedIndex = selIndex,
        onSubmit = { model -> models.applyModel(model) },
    )
}

internal fun OverlayNavigator.openProviderOverlay() {
    val providers = getAllProviders()
    if (providers.isEmpty()) {
        feed.appendSystemMessage("No providers found in model catalog")
        return
    }
    val options = providers.map { info ->
        val status = if (info.connected) "✓" else "○"
        val modelLabel = if (info.modelCount == 1) "1 model" else "${info.modelCount} models"
        SelectItem(
            label = "$status ${info.name} ($modelLabel)",
            value = info,
        )
    }
    overlays.push(
        title = "Connect Provider",
        items = options,
        selectedIndex = 0,
        onSubmit = { info ->
            if (info.connected) {
                feed.appendSystemMessage("${info.name} is already connected")
            } else {
                providerAuth.startProviderAuth(info)
            }
        },
    )
}

internal fun OverlayNavigator.openThinkingOverlay() {
    val options = AgentThinkingLevel.entries.map {
        SelectItem(label = it.name.lowercase(), value = it)
    }
    val selIndex = AgentThinkingLevel.entries.indexOf(state.thinkingLevel).coerceAtLeast(0)
    overlays.push(
        title = "Thinking",
        items = options,
        selectedIndex = selIndex,
        onSubmit = { level -> models.setThinkingLevel(level) },
    )
}
