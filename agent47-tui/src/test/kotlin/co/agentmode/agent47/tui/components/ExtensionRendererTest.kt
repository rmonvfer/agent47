package co.agentmode.agent47.tui.components

import co.agentmode.agent47.ext.core.RegisteredToolRenderer
import co.agentmode.agent47.ext.core.ToolRenderData
import co.agentmode.agent47.ext.core.ToolRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionRendererTest {
    private val data = ToolRenderData(
        toolCallId = "call",
        toolName = "example",
        arguments = "{}",
        output = "done",
        details = null,
        isError = false,
        pending = false,
        collapsed = false,
    )

    @Test
    fun `extension tool renderer output is returned`() {
        val renderer = RegisteredToolRenderer("example", ToolRenderer { _, width -> listOf("width=$width") })

        assertEquals(listOf("width=80"), renderWithExtension(renderer, data, 80))
    }

    @Test
    fun `extension tool renderer failure falls back`() {
        val renderer = RegisteredToolRenderer("example", ToolRenderer { _, _ -> error("broken") })

        assertNull(renderWithExtension(renderer, data, 80))
    }
}
