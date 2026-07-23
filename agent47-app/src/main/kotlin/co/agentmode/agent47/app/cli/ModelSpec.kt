package co.agentmode.agent47.app.cli

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.models.ModelResolver
import co.agentmode.agent47.coding.core.settings.SettingsManager

internal val VALID_THINKING_LEVELS = listOf(
    "off",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
)

internal data class ModelSpec(
    val provider: String?,
    val modelId: String?,
    val thinking: String?,
)

internal fun parseModelSpec(modelSpec: String?): ModelSpec {
    if (modelSpec == null) return ModelSpec(null, null, null)

    val thinkingSuffix = VALID_THINKING_LEVELS.find { level ->
        modelSpec.endsWith(":$level")
    }
    val modelPart = thinkingSuffix?.let { modelSpec.removeSuffix(":$it") } ?: modelSpec
    val providerPrefix = modelPart.substringBefore("/", "").ifEmpty { null }
    val modelId = if (providerPrefix != null) modelPart.substringAfter("/") else modelPart
    return ModelSpec(providerPrefix, modelId.ifBlank { null }, thinkingSuffix)
}

internal fun resolveThinkingLevel(options: CliOptions, settings: SettingsManager): AgentThinkingLevel {
    // A `--model sonnet:high` suffix sets the thinking level when --thinking is not given.
    val suffix = parseModelSpec(options.model).thinking
    val level = options.thinking ?: suffix ?: settings.get().defaultThinkingLevel ?: "off"
    return when (level.lowercase()) {
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> AgentThinkingLevel.OFF
    }
}

internal fun resolveModel(
    options: CliOptions,
    registry: ModelRegistry,
    settings: SettingsManager,
): Model? {
    val available = registry.getAvailable()
    if (available.isEmpty()) return null

    val (requestedProvider, requestedModelId, _) = parseModelSpec(options.model)

    return ModelResolver.resolveInitialModel(
        cliProvider = requestedProvider ?: options.provider,
        cliModel = requestedModelId ?: options.model,
        settings = settings.get(),
        available = available,
    )
}
