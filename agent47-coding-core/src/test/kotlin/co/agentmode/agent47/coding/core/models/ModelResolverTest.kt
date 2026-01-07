package co.agentmode.agent47.coding.core.models

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.coding.core.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelResolverTest {

    private fun model(
        id: String,
        provider: String = "openai",
        name: String = id,
    ): Model = Model(
        id = id,
        name = name,
        api = ApiId("openai-completions"),
        provider = ProviderId(provider),
        baseUrl = "https://api.example.com",
        reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

    private val available = listOf(
        model("claude-opus-4-6", "anthropic", "Claude Opus 4.6"),
        model("claude-sonnet-4-5", "anthropic", "Claude Sonnet 4.5"),
        model("claude-haiku-4-5", "anthropic", "Claude Haiku 4.5"),
        model("gpt-5.1-codex", "openai", "GPT 5.1 Codex"),
        model("gpt-4.1-mini", "openai", "GPT 4.1 Mini"),
        model("gemini-2.5-pro", "google", "Gemini 2.5 Pro"),
        model("gemini-2.5-flash", "google", "Gemini 2.5 Flash"),
    )

    @Test
    fun `tryMatchModel matches exact provider and id`() {
        val result = ModelResolver.tryMatchModel("anthropic/claude-opus-4-6", available)
        assertNotNull(result)
        assertEquals("claude-opus-4-6", result.id)
        assertEquals("anthropic", result.provider.value)
    }

    @Test
    fun `tryMatchModel matches exact id without provider`() {
        val result = ModelResolver.tryMatchModel("gpt-5.1-codex", available)
        assertNotNull(result)
        assertEquals("gpt-5.1-codex", result.id)
    }

    @Test
    fun `tryMatchModel matches case-insensitively`() {
        val result = ModelResolver.tryMatchModel("Claude-Opus-4-6", available)
        assertNotNull(result)
        assertEquals("claude-opus-4-6", result.id)
    }

    @Test
    fun `tryMatchModel matches substring on id`() {
        val result = ModelResolver.tryMatchModel("opus", available)
        assertNotNull(result)
        assertEquals("claude-opus-4-6", result.id)
    }

    @Test
    fun `tryMatchModel matches substring on name`() {
        val result = ModelResolver.tryMatchModel("Mini", available)
        assertNotNull(result)
        assertEquals("gpt-4.1-mini", result.id)
    }

    @Test
    fun `tryMatchModel returns null for no match`() {
        val result = ModelResolver.tryMatchModel("nonexistent-model", available)
        assertNull(result)
    }

    @Test
    fun `tryMatchModel matches substring within provider`() {
        val result = ModelResolver.tryMatchModel("anthropic/haiku", available)
        assertNotNull(result)
        assertEquals("claude-haiku-4-5", result.id)
    }

    @Test
    fun `tryMatchModel prefers alias over dated version`() {
        val models = listOf(
            model("gpt-4o", "openai"),
            model("gpt-4o-2025-03-15", "openai"),
        )
        val result = ModelResolver.tryMatchModel("gpt-4o", models)
        assertNotNull(result)
        assertEquals("gpt-4o", result.id)
    }

    @Test
    fun `resolveInitialModel uses CLI provider and model first`() {
        val result = ModelResolver.resolveInitialModel(
            cliProvider = "anthropic",
            cliModel = "claude-sonnet-4-5",
            settings = Settings(),
            available = available,
        )
        assertNotNull(result)
        assertEquals("claude-sonnet-4-5", result.id)
        assertEquals("anthropic", result.provider.value)
    }

    @Test
    fun `resolveInitialModel uses CLI model pattern when no provider`() {
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = "flash",
            settings = Settings(),
            available = available,
        )
        assertNotNull(result)
        assertEquals("gemini-2.5-flash", result.id)
    }

    @Test
    fun `resolveInitialModel uses settings when no CLI args`() {
        val settings = Settings(
            defaultProvider = "google",
            defaultModel = "gemini-2.5-pro",
        )
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = null,
            settings = settings,
            available = available,
        )
        assertNotNull(result)
        assertEquals("gemini-2.5-pro", result.id)
        assertEquals("google", result.provider.value)
    }

    @Test
    fun `resolveInitialModel uses settings defaultModel as pattern`() {
        val settings = Settings(defaultModel = "haiku")
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = null,
            settings = settings,
            available = available,
        )
        assertNotNull(result)
        assertEquals("claude-haiku-4-5", result.id)
    }

    @Test
    fun `resolveInitialModel falls back to first available`() {
        val models = listOf(model("custom-model", "custom"))
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = null,
            settings = Settings(),
            available = models,
        )
        assertNotNull(result)
        assertEquals("custom-model", result.id)
    }

    @Test
    fun `resolveInitialModel returns null for empty list`() {
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = null,
            settings = Settings(),
            available = emptyList(),
        )
        assertNull(result)
    }

    @Test
    fun `findSmolModel uses priority chain`() {
        val result = ModelResolver.findSmolModel(available)
        assertNotNull(result)
        assertEquals("claude-haiku-4-5", result.id)
    }

    @Test
    fun `findSmolModel respects saved model override`() {
        val result = ModelResolver.findSmolModel(available, savedModel = "google/gemini-2.5-flash")
        assertNotNull(result)
        assertEquals("gemini-2.5-flash", result.id)
    }

    @Test
    fun `findSlowModel uses priority chain`() {
        val models = listOf(
            model("gpt-5.2-codex", "openai"),
            model("claude-opus-4-6", "anthropic"),
            model("gemini-2.5-pro", "google"),
        )
        val result = ModelResolver.findSlowModel(models)
        assertNotNull(result)
        assertEquals("gpt-5.2-codex", result.id)
    }

    @Test
    fun `findSlowModel falls back through priority chain`() {
        val models = listOf(
            model("claude-opus-4-6", "anthropic"),
            model("gemini-2.5-pro", "google"),
        )
        val result = ModelResolver.findSlowModel(models)
        assertNotNull(result)
        assertEquals("claude-opus-4-6", result.id)
    }

    @Test
    fun `parseModelString splits provider and id`() {
        val result = ModelResolver.parseModelString("anthropic/claude-opus-4-6")
        assertNotNull(result)
        assertEquals("anthropic", result.first)
        assertEquals("claude-opus-4-6", result.second)
    }

    @Test
    fun `parseModelString returns null for no slash`() {
        val result = ModelResolver.parseModelString("claude-opus-4-6")
        assertNull(result)
    }

    @Test
    fun `resolveInitialModel uses model role default`() {
        val settings = Settings(modelRoles = mapOf("default" to "anthropic/claude-opus-4-6"))
        val result = ModelResolver.resolveInitialModel(
            cliProvider = null,
            cliModel = null,
            settings = settings,
            available = available,
        )
        assertNotNull(result)
        assertEquals("claude-opus-4-6", result.id)
    }
}
