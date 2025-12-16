package co.agentmode.agent47.coding.core.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TruncateTest {
    @Test
    fun `truncateHead limits by line count`() {
        val content = (1..3000).joinToString("\n") { "line-$it" }
        val result = truncateHead(content)

        assertTrue(result.truncated)
        assertEquals(TruncatedBy.LINES, result.truncatedBy)
        assertEquals(DEFAULT_MAX_LINES, result.outputLines)
    }

    @Test
    fun `truncateTail keeps trailing lines`() {
        val content = (1..20).joinToString("\n") { "line-$it" }
        val result = truncateTail(
            content,
            TruncationOptions(maxLines = 5, maxBytes = 1024 * 1024)
        )

        assertTrue(result.truncated)
        assertEquals(5, result.outputLines)
        assertTrue(result.content.contains("line-20"))
        assertTrue(result.content.lineSequence().none { it == "line-1" })
    }
}
