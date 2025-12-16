package co.agentmode.agent47.coding.core.models

import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelRole
import co.agentmode.agent47.coding.core.settings.Settings

/**
 * Resolves models from user-provided patterns, CLI arguments, and settings.
 * Handles fuzzy matching, role-based resolution, and provider-specific defaults.
 */
public object ModelResolver {

    public val DEFAULT_MODEL_PER_PROVIDER: Map<String, String> = mapOf(
        "amazon-bedrock" to "us.anthropic.claude-opus-4-6-v1",
        "anthropic" to "claude-opus-4-6",
        "openai" to "gpt-5.1-codex",
        "openai-codex" to "gpt-5.3-codex",
        "google" to "gemini-2.5-pro",
        "google-gemini-cli" to "gemini-2.5-pro",
        "google-antigravity" to "gemini-3-pro-high",
        "google-vertex" to "gemini-3-pro-preview",
        "github-copilot" to "gpt-4o",
        "cursor" to "claude-opus-4-6",
        "openrouter" to "openai/gpt-5.1-codex",
        "vercel-ai-gateway" to "anthropic/claude-opus-4-6",
        "xai" to "grok-4-fast-non-reasoning",
        "groq" to "openai/gpt-oss-120b",
        "cerebras" to "zai-glm-4.6",
        "zai" to "glm-4.6",
        "mistral" to "devstral-medium-latest",
        "minimax" to "MiniMax-M2.5",
        "minimax-code" to "MiniMax-M2.5",
        "minimax-code-cn" to "MiniMax-M2.5",
        "opencode" to "claude-opus-4-6",
        "kimi-code" to "kimi-k2.5",
    )

    public val SMOL_PRIORITY: List<String> = listOf(
        "cerebras/zai-glm-4.6", "claude-haiku-4-5", "haiku", "flash", "mini",
    )

    public val SLOW_PRIORITY: List<String> = listOf(
        "gpt-5.2-codex", "gpt-5.2", "codex", "gpt", "opus", "pro",
    )

    /**
     * Parse a "provider/modelId" string into its parts.
     */
    public fun parseModelString(str: String): Pair<String, String>? {
        val slashIdx = str.indexOf('/')
        if (slashIdx <= 0) return null
        return str.substring(0, slashIdx) to str.substring(slashIdx + 1)
    }

    /**
     * Format a model as "provider/modelId".
     */
    public fun formatModelString(model: Model): String =
        "${model.provider.value}/${model.id}"

    /**
     * Try to match a pattern to an available model.
     * Matching priority: exact provider/id > exact id > case-insensitive id > substring match.
     * Prefers alias models (no date suffix) over dated versions.
     */
    public fun tryMatchModel(pattern: String, available: List<Model>): Model? {
        // Check for provider/modelId format
        val slashIndex = pattern.indexOf('/')
        if (slashIndex > 0) {
            val provider = pattern.substring(0, slashIndex)
            val modelId = pattern.substring(slashIndex + 1)

            // Exact provider/id match
            available.firstOrNull {
                it.provider.value.equals(provider, ignoreCase = true) &&
                    it.id.equals(modelId, ignoreCase = true)
            }?.let { return it }

            // Substring match within provider
            val providerModels = available.filter { it.provider.value.equals(provider, ignoreCase = true) }
            providerModels.firstOrNull { it.id.contains(modelId, ignoreCase = true) }?.let { return it }
        }

        // Exact ID match (case-insensitive)
        available.firstOrNull { it.id.equals(pattern, ignoreCase = true) }?.let { return it }

        // Substring match on id or name, preferring aliases over dated versions
        val matches = available.filter {
            it.id.contains(pattern, ignoreCase = true) ||
                it.name.contains(pattern, ignoreCase = true)
        }

        if (matches.isEmpty()) return null

        val aliases = matches.filter { isAlias(it.id) }
        if (aliases.isNotEmpty()) return aliases.first()

        // Return latest dated version
        return matches.sortedByDescending { it.id }.first()
    }

    /**
     * Find the smol/fast model from available models using the priority chain.
     */
    public fun findSmolModel(
        available: List<Model>,
        savedModel: String? = null,
    ): Model? {
        if (available.isEmpty()) return null

        // Try saved model from settings
        if (savedModel != null) {
            val parsed = parseModelString(savedModel)
            if (parsed != null) {
                available.firstOrNull { it.provider.value == parsed.first && it.id == parsed.second }
                    ?.let { return it }
            }
        }

        // Try priority chain
        for (pattern in SMOL_PRIORITY) {
            // Try exact match with provider prefix
            available.firstOrNull {
                "${it.provider.value}/${it.id}".equals(pattern, ignoreCase = true)
            }?.let { return it }

            // Try exact id match
            available.firstOrNull { it.id.equals(pattern, ignoreCase = true) }?.let { return it }

            // Try substring match
            available.firstOrNull { it.id.contains(pattern, ignoreCase = true) }?.let { return it }
        }

        return available.first()
    }

    /**
     * Find the slow/comprehensive model from available models using the priority chain.
     */
    public fun findSlowModel(
        available: List<Model>,
        savedModel: String? = null,
    ): Model? {
        if (available.isEmpty()) return null

        // Try saved model from settings
        if (savedModel != null) {
            val parsed = parseModelString(savedModel)
            if (parsed != null) {
                available.firstOrNull { it.provider.value == parsed.first && it.id == parsed.second }
                    ?.let { return it }
            }
        }

        // Try priority chain
        for (pattern in SLOW_PRIORITY) {
            available.firstOrNull { it.id.equals(pattern, ignoreCase = true) }?.let { return it }
            available.firstOrNull { it.id.contains(pattern, ignoreCase = true) }?.let { return it }
        }

        return available.first()
    }

    /**
     * Resolve the initial model based on CLI arguments, settings, and available models.
     *
     * Priority:
     * 1. Explicit --provider + --model CLI args
     * 2. --model pattern (fuzzy matched)
     * 3. --provider (use that provider's default)
     * 4. Settings: defaultProvider + defaultModel
     * 5. Model role from settings
     * 6. Default model per provider (iterate known providers)
     * 7. First available model
     */
    public fun resolveInitialModel(
        cliProvider: String?,
        cliModel: String?,
        settings: Settings,
        available: List<Model>,
    ): Model? {
        if (available.isEmpty()) return null

        // 1. Explicit CLI provider + model
        if (cliProvider != null && cliModel != null) {
            val exact = available.firstOrNull {
                it.provider.value == cliProvider && it.id == cliModel
            }
            if (exact != null) return exact
        }

        // 2. CLI model pattern (fuzzy)
        if (cliModel != null) {
            val matched = tryMatchModel(cliModel, available)
            if (matched != null) return matched
        }

        // 3. CLI provider only -> provider's default
        if (cliProvider != null) {
            val defaultId = DEFAULT_MODEL_PER_PROVIDER[cliProvider]
            if (defaultId != null) {
                available.firstOrNull { it.provider.value == cliProvider && it.id == defaultId }
                    ?.let { return it }
            }
            available.firstOrNull { it.provider.value == cliProvider }?.let { return it }
        }

        // 4. Settings: defaultProvider + defaultModel
        val settingsProvider = settings.defaultProvider
        val settingsModel = settings.defaultModel

        if (settingsProvider != null && settingsModel != null) {
            available.firstOrNull {
                it.provider.value == settingsProvider && it.id == settingsModel
            }?.let { return it }
        }

        if (settingsModel != null) {
            tryMatchModel(settingsModel, available)?.let { return it }
        }

        // 5. Model role "default" from settings
        val defaultRole = settings.modelRoles[ModelRole.DEFAULT.id]
        if (defaultRole != null) {
            tryMatchModel(defaultRole, available)?.let { return it }
        }

        // 6. Default model per provider
        for ((provider, defaultId) in DEFAULT_MODEL_PER_PROVIDER) {
            available.firstOrNull { it.provider.value == provider && it.id == defaultId }
                ?.let { return it }
        }

        // 7. First available
        return available.first()
    }

    private fun isAlias(id: String): Boolean {
        if (id.endsWith("-latest")) return true
        val datePattern = Regex("-\\d{8}$")
        return !datePattern.containsMatchIn(id)
    }
}
