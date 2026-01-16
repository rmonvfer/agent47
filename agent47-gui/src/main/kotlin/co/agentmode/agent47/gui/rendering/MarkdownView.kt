package co.agentmode.agent47.gui.rendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.jewel.foundation.code.highlighting.NoOpCodeHighlighter
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.intui.markdown.standalone.ProvideMarkdownStyling
import org.jetbrains.jewel.intui.markdown.standalone.dark
import org.jetbrains.jewel.intui.markdown.standalone.light
import org.jetbrains.jewel.intui.markdown.standalone.styling.dark
import org.jetbrains.jewel.intui.markdown.standalone.styling.light
import org.jetbrains.jewel.markdown.MarkdownBlock
import org.jetbrains.jewel.markdown.MarkdownMode
import org.jetbrains.jewel.markdown.processing.MarkdownProcessor
import org.jetbrains.jewel.markdown.rendering.MarkdownBlockRenderer
import org.jetbrains.jewel.markdown.rendering.MarkdownStyling

@Composable
fun MarkdownView(
    rawMarkdown: String,
    modifier: Modifier = Modifier,
) {
    val isDark = JewelTheme.isDark

    val markdownStyling = remember(isDark) {
        if (isDark) MarkdownStyling.dark() else MarkdownStyling.light()
    }

    var markdownBlocks by remember { mutableStateOf(emptyList<MarkdownBlock>()) }
    val processor = remember { MarkdownProcessor(emptyList(), MarkdownMode.Standalone) }

    LaunchedEffect(rawMarkdown) {
        markdownBlocks = withContext(Dispatchers.Default) {
            processor.processMarkdownDocument(rawMarkdown)
        }
    }

    val blockRenderer = remember(markdownStyling, isDark) {
        if (isDark) {
            MarkdownBlockRenderer.dark(styling = markdownStyling)
        } else {
            MarkdownBlockRenderer.light(styling = markdownStyling)
        }
    }

    ProvideMarkdownStyling(markdownStyling, blockRenderer, NoOpCodeHighlighter) {
        blockRenderer.RenderBlocks(
            blocks = markdownBlocks,
            enabled = true,
            onUrlClick = { url ->
                runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI.create(url)) }
            },
            modifier = modifier,
        )
    }
}
