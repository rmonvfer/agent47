package co.agentmode.agent47.coding.core.compaction

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactionTest {
    @Test
    fun `estimate context uses latest assistant usage plus trailing messages`() {
        val messages = listOf(
            UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L),
            AssistantMessage(
                content = listOf(TextContent(text = "response")),
                api = KnownApis.OpenAiResponses,
                provider = ProviderId("openai"),
                model = "mock",
                usage = emptyUsage().copy(totalTokens = 50),
                stopReason = StopReason.STOP,
                timestamp = 2L,
            ),
            UserMessage(content = listOf(TextContent(text = "follow up")), timestamp = 3L),
        )

        val estimate = estimateContextTokens(messages)
        assertTrue(estimate.tokens >= 50)
        assertEquals(50, estimate.usageTokens)
        assertEquals(1, estimate.lastUsageIndex)
    }

    @Test
    fun `should compact when reserve threshold exceeded`() {
        val settings = CompactionSettings(enabled = true, reserveTokens = 100, keepRecentTokens = 100)
        assertTrue(shouldCompact(contextTokens = 950, contextWindow = 1000, settings = settings))
    }
}
