package co.agentmode.agent47.tui.components

import co.agentmode.agent47.tui.theme.MosaicThemeProvider
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.state.OverlayHostState
import co.agentmode.agent47.ui.core.state.PromptDialogState
import co.agentmode.agent47.ui.core.state.SelectDialogState
import co.agentmode.agent47.ui.core.state.SelectItem
import co.agentmode.agent47.ui.core.state.UserMessageItem
import co.agentmode.agent47.ui.core.state.UserMessageListState
import com.jakewharton.mosaic.layout.height
import com.jakewharton.mosaic.layout.width
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.ui.Box
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Text
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dialogs must paint every cell of their surface. A surface fill colors cells without drawing
 * characters, so an unpainted row shows the transcript composed beneath it, and an over-long row
 * draws past the terminal. These tests pin both edges of that fit.
 */
class OverlayDialogTest {
    private val theme = ThemeConfig(
        background = Color(20, 20, 20),
        markdownText = Color(200, 210, 220),
        overlayBg = Color(60, 60, 70),
    )

    @Test
    fun `select dialog paints one row per declared row`() {
        val state = SelectDialogState(listOf("Yes", "No").map { SelectItem(it, it) })
        val height = selectDialogHeight(2)

        assertEquals(10, height)
        assertRowsFillSurface(selectDialogRows("Confirm", state, WIDTH, height, theme), height)
    }

    @Test
    fun `select dialog keeps its height when a filter narrows the list`() {
        val state = SelectDialogState((1..6).map { SelectItem("item$it", it) })
        val height = selectDialogHeight(6)
        state.appendChar('1')

        assertRowsFillSurface(selectDialogRows("Pick", state, WIDTH, height, theme), height)
    }

    @Test
    fun `prompt dialog hugs a bare input`() {
        val height = promptDialogHeight(description = null)

        assertEquals(6, height)
        assertRowsFillSurface(
            promptDialogRows("Provider — API Key", PromptDialogState(), WIDTH, height, "Paste your API key", null, theme),
            height,
        )
    }

    @Test
    fun `prompt dialog grows by exactly its description`() {
        val height = promptDialogHeight("first line\nsecond line")

        assertEquals(9, height)
        assertRowsFillSurface(
            promptDialogRows("Steer", PromptDialogState(), WIDTH, height, "", "first line\nsecond line", theme),
            height,
        )
    }

    @Test
    fun `prompt dialog clips a description too tall for its surface`() {
        val description = (1..40).joinToString("\n") { "line $it" }
        val rows = promptDialogRows("Steer", PromptDialogState(), WIDTH, 12, "", description, theme)

        assertRowsFillSurface(rows, 12)
        assertTrue(rows.last { it.text.isNotBlank() }.text.contains("enter submit"))
    }

    @Test
    fun `info dialog hugs its lines`() {
        val lines = listOf("Open this URL in your browser:", "https://example.test/device")
        val height = infoDialogHeight(lines.size)

        assertEquals(7, height)
        assertRowsFillSurface(infoDialogRows("Authorizing", lines, WIDTH, height, theme), height)
    }

    @Test
    fun `info dialog keeps its footer when lines outrun the surface`() {
        val lines = (1..40).map { "line $it" }
        val rows = infoDialogRows("Details", lines, WIDTH, 12, theme)

        assertRowsFillSurface(rows, 12)
        assertTrue(rows.any { it.text.contains("esc cancel") })
    }

    @Test
    fun `fork dialog hugs its message list`() {
        val items = (1..3).map { UserMessageItem(id = "m$it", text = "message $it", timestamp = 0L) }
        val height = userMessageDialogHeight(items.size)

        assertEquals(15, height)
        assertRowsFillSurface(userMessageDialogRows(UserMessageListState(items), WIDTH, height, theme), height)
    }

    @Test
    fun `fork dialog hugs its empty state`() {
        val height = userMessageDialogHeight(0)

        assertEquals(7, height)
        assertRowsFillSurface(userMessageDialogRows(UserMessageListState(emptyList()), WIDTH, height, theme), height)
    }

    @Test
    fun `prompt overlay covers the transcript beneath every row it claims`() = runTest {
        val rendered = renderOverlayHost(terminalWidth = 60, terminalHeight = 20) {
            pushPrompt(title = "Provider — API Key", placeholder = "Paste your API key")
        }

        assertCoversTranscript(rendered, expectedHeight = promptDialogHeight(null))
    }

    @Test
    fun `select overlay covers the transcript beneath every row it claims`() = runTest {
        val rendered = renderOverlayHost(terminalWidth = 60, terminalHeight = 20) {
            push(title = "Confirm", items = listOf(SelectItem("Yes", true), SelectItem("No", false)))
        }

        assertCoversTranscript(rendered, expectedHeight = selectDialogHeight(2))
    }

    @Test
    fun `info overlay covers the transcript beneath every row it claims`() = runTest {
        val rendered = renderOverlayHost(terminalWidth = 60, terminalHeight = 20) {
            pushInfo(title = "Authorizing", lines = listOf("Starting authorization...", "Waiting for the browser"))
        }

        assertCoversTranscript(rendered, expectedHeight = infoDialogHeight(2))
    }

    @Test
    fun `overlays stay inside a terminal too small for their content`() = runTest {
        val rendered = renderOverlayHost(terminalWidth = 40, terminalHeight = 8) {
            pushInfo(title = "Details", lines = (1..40).map { "line $it" })
        }

        assertEquals(8, rendered.size)
        assertTrue(rendered.all { it.length <= 40 }, "a row drew past the terminal: $rendered")
    }

    @Test
    fun `a title longer than the surface truncates instead of widening the row`() {
        val title = "Delete everything — " + "are you sure? ".repeat(20)
        val state = SelectDialogState(listOf(SelectItem("Yes", true), SelectItem("No", false)))

        assertRowsFillSurface(selectDialogRows(title, state, WIDTH, selectDialogHeight(2), theme), selectDialogHeight(2))
    }

    @Test
    fun `an input longer than the field scrolls to keep the cursor in view`() {
        val state = PromptDialogState()
        val key = "sk-" + "0123456789".repeat(12)
        key.forEach { state.appendChar(it) }
        val height = promptDialogHeight(null)

        assertRowsFillSurface(promptDialogRows("API Key", state, WIDTH, height, "", null, theme), height)

        val inputRow = promptDialogRows("API Key", state, WIDTH, height, "", null, theme)[3].text
        assertTrue(inputRow.contains(key.takeLast(8)), "the cursor end of the input scrolled out of view: $inputRow")

        state.moveHome()
        val atStart = promptDialogRows("API Key", state, WIDTH, height, "", null, theme)[3].text
        assertRowsFillSurface(promptDialogRows("API Key", state, WIDTH, height, "", null, theme), height)
        assertTrue(atStart.contains("sk-0123"), "the cursor start of the input scrolled out of view: $atStart")
    }

    @Test
    fun `the input row holds its width at every cursor position`() {
        val state = PromptDialogState()
        val key = "sk-" + "0123456789".repeat(12)
        key.forEach { state.appendChar(it) }
        val height = promptDialogHeight(null)

        state.moveHome()
        repeat(key.length + 1) {
            assertRowsFillSurface(promptDialogRows("API Key", state, WIDTH, height, "", null, theme), height)
            state.moveRight()
        }
    }

    @Test
    fun `a masked input hides the secret at every scroll position`() {
        val state = PromptDialogState().apply { masked = true }
        "sk-secret-key-value-that-is-long".forEach { state.appendChar(it) }
        val height = promptDialogHeight(null)

        val inputRow = promptDialogRows("API Key", state, WIDTH, height, "", null, theme)[3].text
        assertTrue("secret" !in inputRow, "the masked input leaked its text: $inputRow")
        assertTrue('•' in inputRow, "the masked input rendered no bullets: $inputRow")
    }

    private fun assertRowsFillSurface(rows: List<AnnotatedString>, height: Int) {
        assertEquals(height, rows.size, "dialog painted ${rows.size} rows into a $height row surface")
        rows.forEachIndexed { index, row ->
            assertEquals(WIDTH, row.text.length, "row $index is ${row.text.length} columns wide, not $WIDTH")
        }
    }

    /**
     * The dialog must show up as one unbroken run of rows in which the transcript filler survives
     * only in the margins either side of the surface. A row the dialog claims but never paints is
     * indistinguishable from the transcript, so it breaks the run.
     */
    private fun assertCoversTranscript(rendered: List<String>, expectedHeight: Int) {
        val painted = rendered.indices.filter { rendered[it].any { char -> char != FILLER } }

        assertTrue(painted.isNotEmpty(), "no dialog rows were painted: $rendered")
        assertEquals(
            (painted.first()..painted.last()).toList(),
            painted,
            "the transcript showed through a row inside the dialog: $rendered",
        )
        assertEquals(expectedHeight, painted.size, "dialog painted ${painted.size} rows, expected $expectedHeight")
        painted.forEach { row ->
            assertTrue(
                FILLER !in rendered[row].trim(FILLER),
                "the transcript showed through the middle of row $row: ${rendered[row]}",
            )
        }
    }

    private suspend fun renderOverlayHost(
        terminalWidth: Int,
        terminalHeight: Int,
        push: OverlayHostState.() -> Unit,
    ): List<String> {
        val hostState = OverlayHostState().apply(push)
        var rendered = emptyList<String>()

        runMosaicTest(MosaicSnapshots) {
            val mosaic = setContentAndSnapshot {
                MosaicThemeProvider(theme) {
                    Box(modifier = Modifier.height(terminalHeight).width(terminalWidth)) {
                        Column {
                            repeat(terminalHeight) { Text(FILLER.toString().repeat(terminalWidth)) }
                        }
                        OverlayHost(hostState, terminalWidth, terminalHeight)
                    }
                }
            }
            rendered = mosaic.draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false).lines()
        }

        return rendered
    }

    private companion object {
        const val WIDTH = 40

        /** Stands in for the transcript: it appears in no dialog's own chrome. */
        const val FILLER = '#'
    }
}
