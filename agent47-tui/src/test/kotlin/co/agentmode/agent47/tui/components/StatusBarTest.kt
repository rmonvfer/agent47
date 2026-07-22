package co.agentmode.agent47.tui.components

import co.agentmode.agent47.tui.theme.ThemeConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusBarTest {
    private val theme = ThemeConfig()

    @Test
    fun `initial footer always shows context model and thinking state`() {
        val lines = renderStatusBar(state(), 100, theme.colors.dim, theme.colors.warning, theme.colors.error)

        assertEquals("~/code/agent47 (main)", lines.first.text)
        assertTrue(lines.second.text.contains("?/200k (auto)"))
        assertTrue(lines.second.text.endsWith("(anthropic) claude-sonnet • thinking off"))
    }

    @Test
    fun `footer shows cumulative cache cost and context usage`() {
        val lines = renderStatusBar(
            state().copy(
                inputTokens = 1_500,
                outputTokens = 250,
                cacheReadTokens = 2_000,
                cacheWriteTokens = 500,
                latestCacheHitRate = 50.0,
                cost = 0.125,
                contextTokens = 100_000,
                thinkingLabel = "high",
            ),
            120,
            theme.colors.dim,
            theme.colors.warning,
            theme.colors.error,
        )

        assertTrue(lines.second.text.contains("↑1.5k ↓250 R2.0k W500 CH50.0% $0.125 50.0%/200k (auto)"))
        assertTrue(lines.second.text.endsWith("claude-sonnet • high"))
    }

    @Test
    fun `subscription displays zero cost marker`() {
        val lines = renderStatusBar(
            state().copy(usingSubscription = true),
            100,
            theme.colors.dim,
            theme.colors.warning,
            theme.colors.error,
        )

        assertTrue(lines.second.text.contains("$0.000 (sub)"))
    }

    @Test
    fun `usage toggle hides stats but preserves model information`() {
        val lines = renderStatusBar(
            state().copy(showUsage = false),
            80,
            theme.colors.dim,
            theme.colors.warning,
            theme.colors.error,
        )

        assertFalse(lines.second.text.contains("200k"))
        assertTrue(lines.second.text.endsWith("(anthropic) claude-sonnet • thinking off"))
    }

    @Test
    fun `narrow footer drops provider before model details`() {
        val lines = renderStatusBar(state(), 35, theme.colors.dim, theme.colors.warning, theme.colors.error)

        assertFalse(lines.second.text.contains("anthropic"))
        assertTrue(lines.second.text.contains("claude-sonnet"))
        assertTrue(lines.second.text.length <= 35)
    }

    private fun state(): MosaicStatusBarState = MosaicStatusBarState(
        cwdPath = "~/code/agent47",
        branch = "main",
        providerId = "anthropic",
        availableProviderCount = 2,
        modelId = "claude-sonnet",
        modelSupportsReasoning = true,
        thinkingLabel = "off",
        inputTokens = 0,
        outputTokens = 0,
        cacheReadTokens = 0,
        cacheWriteTokens = 0,
        latestCacheHitRate = null,
        cost = 0.0,
        usingSubscription = false,
        contextTokens = null,
        contextWindow = 200_000,
        autoCompactEnabled = true,
        showUsage = true,
    )
}
