package co.agentmode.agent47.app.cli

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ProviderId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopedModelsTest {
    @Test
    fun `star matches any sequence`() {
        val regex = globToRegex("gpt*")
        assertTrue(regex.matches("gpt-4o"))
        assertTrue(regex.matches("gpt"))
        assertFalse(regex.matches("claude"))
    }

    @Test
    fun `question mark matches exactly one character`() {
        val regex = globToRegex("gpt-?")
        assertTrue(regex.matches("gpt-4"))
        assertFalse(regex.matches("gpt-4o"))
    }

    @Test
    fun `escapes regex special characters literally`() {
        val regex = globToRegex("a.b+c")
        assertTrue(regex.matches("a.b+c"))
        assertFalse(regex.matches("axbxc"))
    }

    @Test
    fun `matches case insensitively`() {
        assertTrue(globToRegex("gpt*").matches("GPT-4o"))
    }

    @Test
    fun `falls back to all models when no pattern matches`() {
        val models = listOf(model("openai", "gpt-4o"), model("anthropic", "claude"))
        assertEquals(models, scopedModels(models, "nonexistent*"))
    }

    @Test
    fun `returns all models when the pattern is absent`() {
        val models = listOf(model("openai", "gpt-4o"))
        assertEquals(models, scopedModels(models, null))
    }

    @Test
    fun `restricts to models matching a provider glob`() {
        val gpt = model("openai", "gpt-4o")
        val claude = model("anthropic", "claude")
        assertEquals(listOf(gpt), scopedModels(listOf(gpt, claude), "openai/*"))
    }

    private fun model(provider: String, id: String) = Model(
        id = id,
        name = id,
        api = ApiId("test-api"),
        provider = ProviderId(provider),
        baseUrl = "",
        reasoning = false,
        input = emptyList(),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 0,
        maxTokens = 0,
    )
}
