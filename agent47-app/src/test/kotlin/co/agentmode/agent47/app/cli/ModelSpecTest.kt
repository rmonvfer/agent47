package co.agentmode.agent47.app.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelSpecTest {
    @Test
    fun `parses a bare model id`() {
        val spec = parseModelSpec("sonnet")
        assertNull(spec.provider)
        assertEquals("sonnet", spec.modelId)
        assertNull(spec.thinking)
    }

    @Test
    fun `parses a thinking suffix on a bare model`() {
        val spec = parseModelSpec("sonnet:high")
        assertNull(spec.provider)
        assertEquals("sonnet", spec.modelId)
        assertEquals("high", spec.thinking)
    }

    @Test
    fun `parses a provider prefixed model`() {
        val spec = parseModelSpec("anthropic/claude-x")
        assertEquals("anthropic", spec.provider)
        assertEquals("claude-x", spec.modelId)
        assertNull(spec.thinking)
    }

    @Test
    fun `parses a provider prefix and thinking suffix together`() {
        val spec = parseModelSpec("anthropic/claude-x:low")
        assertEquals("anthropic", spec.provider)
        assertEquals("claude-x", spec.modelId)
        assertEquals("low", spec.thinking)
    }

    @Test
    fun `returns an empty spec for a null model`() {
        val spec = parseModelSpec(null)
        assertNull(spec.provider)
        assertNull(spec.modelId)
        assertNull(spec.thinking)
    }

    @Test
    fun `ignores a suffix that is not a valid thinking level`() {
        val spec = parseModelSpec("sonnet:turbo")
        assertNull(spec.provider)
        assertEquals("sonnet:turbo", spec.modelId)
        assertNull(spec.thinking)
    }
}
