package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

public data class OpenAiCompat(
    val supportsDeveloperRole: Boolean,
    val maxTokensField: String,
    val requiresMistralToolIds: Boolean,
    val requiresThinkingAsText: Boolean,
    val supportsStreamOptions: Boolean,
)

private val NO_DEVELOPER_ROLE_PROVIDERS = setOf(
    KnownProviders.XAi,
    KnownProviders.Cerebras,
    KnownProviders.Mistral,
    KnownProviders.Zai,
    KnownProviders.OpenCode,
    KnownProviders.KimiCode,
    KnownProviders.GitHubCopilot,
)

private val MISTRAL_PROVIDERS = setOf(
    KnownProviders.Mistral,
)

public fun resolveOpenAiCompat(model: Model): OpenAiCompat {
    val compat = model.compat
    val provider = model.provider

    fun boolFlag(key: String, default: Boolean): Boolean {
        val value = compat?.get(key)
        if (value is JsonPrimitive) {
            value.booleanOrNull?.let { return it }
        }
        return default
    }

    fun stringFlag(key: String, default: String): String {
        val value = compat?.get(key)
        if (value is JsonPrimitive) {
            value.contentOrNull?.let { return it }
        }
        return default
    }

    val isMistral = provider in MISTRAL_PROVIDERS

    return OpenAiCompat(
        supportsDeveloperRole = boolFlag(
            "supportsDeveloperRole",
            provider !in NO_DEVELOPER_ROLE_PROVIDERS,
        ),
        maxTokensField = stringFlag(
            "maxTokensField",
            if (isMistral) "max_tokens" else "max_completion_tokens",
        ),
        requiresMistralToolIds = boolFlag(
            "requiresMistralToolIds",
            isMistral,
        ),
        requiresThinkingAsText = boolFlag(
            "requiresThinkingAsText",
            isMistral,
        ),
        supportsStreamOptions = boolFlag(
            "supportsStreamOptions",
            !isMistral,
        ),
    )
}
