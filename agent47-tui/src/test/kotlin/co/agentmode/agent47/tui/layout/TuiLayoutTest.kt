package co.agentmode.agent47.tui.layout

import kotlin.test.Test
import kotlin.test.assertEquals

class TuiLayoutTest {
    private fun inputs(
        visualLineCount: Int = 1,
        popupItemCount: Int = 0,
        taskBarVisible: Boolean = false,
        taskBarLineCount: Int = 0,
        isStreaming: Boolean = false,
        chatPinnedToBottom: Boolean = true,
        hasBackgroundAgents: Boolean = false,
        runningAgentCount: Int = 0,
        queuedAgentCount: Int = 0,
    ) = LayoutInputs(
        visualLineCount = visualLineCount,
        popupItemCount = popupItemCount,
        taskBarVisible = taskBarVisible,
        taskBarLineCount = taskBarLineCount,
        isStreaming = isStreaming,
        chatPinnedToBottom = chatPinnedToBottom,
        hasBackgroundAgents = hasBackgroundAgents,
        runningAgentCount = runningAgentCount,
        queuedAgentCount = queuedAgentCount,
    )

    @Test
    fun `a plain frame gives the history the terminal minus fixed chrome`() {
        val layout = computeTuiLayout(width = 80, height = 40, inputs = inputs())
        assertEquals(1, layout.horizontalPadding)
        assertEquals(78, layout.contentWidth)
        assertEquals(76, layout.editorContentWidth)
        assertEquals(1, layout.baseInputHeight)
        assertEquals(0, layout.popupHeight)
        assertEquals(0, layout.backgroundPanelHeight)
        assertEquals(1, layout.editorTopMarginHeight)
        // 40 - status(2) - border(2) - popup(0) - input(1) - activity(0) - taskbar(0) - margin(1) - panel(0)
        assertEquals(34, layout.historyHeight)
    }

    @Test
    fun `editor content width and input height clamp to their minimums and maximum`() {
        assertEquals(1, computeTuiLayout(1, 40, inputs()).editorContentWidth)
        assertEquals(2, computeTuiLayout(2, 40, inputs()).editorContentWidth)
        assertEquals(1, computeTuiLayout(80, 40, inputs(visualLineCount = 0)).baseInputHeight)
        assertEquals(8, computeTuiLayout(80, 40, inputs(visualLineCount = 20)).baseInputHeight)
    }

    @Test
    fun `popup height reserves the visible rows, an overflow row, and a border`() {
        assertEquals(0, computeTuiLayout(80, 40, inputs(popupItemCount = 0)).popupHeight)
        assertEquals(4, computeTuiLayout(80, 40, inputs(popupItemCount = 3)).popupHeight)
        assertEquals(9, computeTuiLayout(80, 40, inputs(popupItemCount = 8)).popupHeight)
        assertEquals(10, computeTuiLayout(80, 40, inputs(popupItemCount = 9)).popupHeight)
        assertEquals(10, computeTuiLayout(80, 40, inputs(popupItemCount = 50)).popupHeight)
    }

    @Test
    fun `the task bar moves the editor margin above its title`() {
        val layout = computeTuiLayout(80, 40, inputs(taskBarVisible = true, taskBarLineCount = 6))
        assertEquals(29, layout.historyHeight)
    }

    @Test
    fun `the activity line is adjacent to the editor while streaming without a task bar`() {
        val layout = computeTuiLayout(80, 40, inputs(isStreaming = true))
        assertEquals(0, layout.editorTopMarginHeight)
        assertEquals(33, layout.historyHeight)
        assertEquals(
            29,
            computeTuiLayout(80, 40, inputs(isStreaming = true, taskBarVisible = true, taskBarLineCount = 6)).historyHeight,
        )
    }

    @Test
    fun `the lower scroll marker is adjacent to the editor`() {
        val layout = computeTuiLayout(80, 40, inputs(chatPinnedToBottom = false))

        assertEquals(0, layout.editorTopMarginHeight)
        assertEquals(35, layout.historyHeight)
    }

    @Test
    fun `the background panel reserves a blank line, header, running rows, and an optional queued line`() {
        assertEquals(0, computeTuiLayout(80, 40, inputs(hasBackgroundAgents = false, runningAgentCount = 3)).backgroundPanelHeight)
        assertEquals(
            4,
            computeTuiLayout(80, 40, inputs(hasBackgroundAgents = true, runningAgentCount = 2)).backgroundPanelHeight,
        )
        assertEquals(
            5,
            computeTuiLayout(
                80,
                40,
                inputs(hasBackgroundAgents = true, runningAgentCount = 2, queuedAgentCount = 1),
            ).backgroundPanelHeight,
        )
    }

    @Test
    fun `the history height never drops below one row`() {
        val layout = computeTuiLayout(80, 3, inputs(visualLineCount = 4, popupItemCount = 5, taskBarVisible = true, taskBarLineCount = 6))
        assertEquals(1, layout.historyHeight)
    }
}
