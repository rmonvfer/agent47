package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHistoryStateTest {
    @Test
    fun `scrolling to the top keeps entries and disables bottom snapping`() {
        val state = populatedState().apply {
            scrollTopLine = 12
            scrollToBottom = true
        }

        state.scrollToTop()

        assertEquals(0, state.scrollTopLine)
        assertFalse(state.pinnedToBottom)
        assertFalse(state.scrollToBottom)
        assertTrue(state.hasEntries())
    }

    @Test
    fun `clearing resets transcript and scroll state`() {
        val state = populatedState().apply {
            scrollTopLine = 12
            pinnedToBottom = false
            scrollToBottom = true
            toolCollapsedState["tool"] = true
            thinkingCollapsedState["thinking"] = true
        }
        val previousVersion = state.version

        state.clear()

        assertTrue(state.entries.isEmpty())
        assertTrue(state.toolCollapsedState.isEmpty())
        assertTrue(state.thinkingCollapsedState.isEmpty())
        assertEquals(0, state.scrollTopLine)
        assertTrue(state.pinnedToBottom)
        assertFalse(state.scrollToBottom)
        assertEquals(previousVersion + 1, state.version)
    }

    private fun populatedState(): ChatHistoryState = ChatHistoryState().apply {
        appendMessage(
            UserMessage(
                content = listOf(TextContent(text = "hello")),
                timestamp = 1L,
            ),
        )
    }
}
