package co.agentmode.agent47.tui.components

import co.agentmode.agent47.tui.editor.EditorAutocompleteRenderModel
import co.agentmode.agent47.tui.theme.MosaicThemeProvider
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.ui.core.editor.CompletionItem
import co.agentmode.agent47.ui.core.editor.CompletionItemKind
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.terminal.AnsiLevel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AutocompletePopupTest {
    @Test
    fun `file completions longer than the terminal are truncated to the popup width`() = runTest {
        val deepPath = "@" + (1..12).joinToString("/") { "directory-level-$it" } + "/Component.kt"
        val rendered = renderPopup(
            items = listOf(fileItem(deepPath), fileItem("@build.gradle.kts")),
            width = POPUP_WIDTH,
        )

        assertTrue(rendered.isNotEmpty(), "popup rendered no rows")
        rendered.forEach { line ->
            assertTrue(line.length <= POPUP_WIDTH, "row exceeds the popup width: '$line'")
        }
    }

    @Test
    fun `the overflow count stays inside the popup width`() = runTest {
        val items = (1..40).map { fileItem("@src/file-$it.kt") }

        val rendered = renderPopup(items = items, width = POPUP_WIDTH)

        val overflowRow = rendered.firstOrNull { "more" in it }
        assertTrue(overflowRow != null, "expected an overflow row for ${items.size} items")
        assertTrue(overflowRow.length <= POPUP_WIDTH, "overflow row exceeds the popup width: '$overflowRow'")
    }

    @Test
    fun `a terminal too narrow for any row renders nothing`() = runTest {
        val rendered = renderPopup(items = listOf(fileItem("@src/main.kt")), width = 3)

        assertTrue(rendered.all { it.isBlank() }, "expected no popup rows, got $rendered")
    }

    private suspend fun renderPopup(items: List<CompletionItem>, width: Int): List<String> {
        val theme = ThemeConfig()
        var output = emptyList<String>()
        runMosaicTest(MosaicSnapshots) {
            val mosaic = setContentAndSnapshot {
                MosaicThemeProvider(theme) {
                    AutocompletePopup(
                        model = EditorAutocompleteRenderModel(
                            row = 0,
                            column = 0,
                            trigger = '@',
                            items = items,
                            selectedIndex = 0,
                        ),
                        maxWidth = width,
                        theme = theme,
                    )
                }
            }
            output = mosaic.draw().render(AnsiLevel.NONE, supportsKittyUnderlines = false)
                .lines()
                .map { it.trimEnd() }
        }
        return output
    }

    private fun fileItem(label: String) = CompletionItem(
        label = label,
        insertText = label,
        detail = "file",
        kind = CompletionItemKind.File,
    )
}

private const val POPUP_WIDTH = 40
