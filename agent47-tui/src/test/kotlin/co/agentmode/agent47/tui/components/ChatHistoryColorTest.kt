package co.agentmode.agent47.tui.components

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import co.agentmode.agent47.tui.rendering.DiffRenderer
import co.agentmode.agent47.tui.rendering.MarkdownRenderer
import co.agentmode.agent47.tui.rendering.MarkdownTheme
import co.agentmode.agent47.tui.theme.MosaicThemeProvider
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.dimmed
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import com.jakewharton.mosaic.testing.MosaicSnapshots
import com.jakewharton.mosaic.testing.runMosaicTest
import com.jakewharton.mosaic.terminal.AnsiLevel
import com.jakewharton.mosaic.ui.Color
import kotlinx.coroutines.test.runTest
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

class ChatHistoryColorTest {
    @Test
    fun `dark dialog scrim colors plain chat messages`() = runTest {
        assertChatColors(ThemeAppearance.DARK)
    }

    @Test
    fun `light dialog scrim colors plain chat messages`() = runTest {
        assertChatColors(ThemeAppearance.LIGHT)
    }

    private suspend fun assertChatColors(appearance: ThemeAppearance) {
        val dimmedTheme = activeTheme(appearance).dimmed(0.5f, appearance)
        val state = chatState()

        runMosaicTest(MosaicSnapshots) {
            val mosaic = setContentAndSnapshot {
                MosaicThemeProvider(dimmedTheme) {
                    ChatHistory(
                        state = state,
                        width = 12,
                        height = 8,
                        markdownRenderer = MarkdownRenderer(MarkdownTheme.fromTheme(dimmedTheme)),
                        diffRenderer = DiffRenderer(dimmedTheme),
                    )
                }
            }

            val rendered = mosaic.draw().render(AnsiLevel.TRUECOLOR, supportsKittyUnderlines = false)
            val userLine = rendered.lineSequence().first { 'U' in it }
            val assistantLine = rendered.lineSequence().first { 'A' in it }

            assertTrue(
                userLine.startsWith(
                    trueColorPrefix(
                        foreground = dimmedTheme.userMessageText,
                        background = dimmedTheme.userMessageBg,
                    ),
                ),
            )
            assertTrue(
                userLine.contains(
                    trueColorPrefix(foreground = dimmedTheme.markdownCode) + "C",
                ),
            )
            assertTrue(assistantLine.startsWith(trueColorPrefix(foreground = dimmedTheme.markdownText)))
        }
    }
}

private fun activeTheme(appearance: ThemeAppearance): ThemeConfig =
    if (appearance.isLight) {
        ThemeConfig(
            background = Color(240, 240, 240),
            userMessageBg = Color(210, 200, 190),
            userMessageText = Color(30, 40, 50),
            markdownText = Color(50, 40, 30),
        )
    } else {
        ThemeConfig(
            background = Color(20, 20, 20),
            userMessageBg = Color(80, 90, 100),
            userMessageText = Color(220, 210, 200),
            markdownText = Color(200, 210, 220),
        )
    }

private fun chatState(): ChatHistoryState = ChatHistoryState().apply {
    appendMessage(
        UserMessage(
            content = listOf(TextContent(text = "U `C`")),
            timestamp = 1L,
        ),
    )
    appendMessage(
        AssistantMessage(
            content = listOf(TextContent(text = "A")),
            api = KnownApis.OpenAiResponses,
            provider = KnownProviders.OpenAi,
            model = "test",
            usage = emptyUsage(),
            stopReason = StopReason.STOP,
            timestamp = 2L,
        ),
    )
}

private fun trueColorPrefix(
    foreground: Color,
    background: Color? = null,
): String {
    val attributes = buildList {
        addAll(foreground.trueColorAttributes(selector = 38))
        background?.let { addAll(it.trueColorAttributes(selector = 48)) }
    }
    return "\u001B[${attributes.joinToString(";")}m"
}

private fun Color.trueColorAttributes(selector: Int): List<String> {
    val (red, green, blue) = this
    return listOf(
        selector.toString(),
        "2",
        (red * 255).roundToInt().toString(),
        (green * 255).roundToInt().toString(),
        (blue * 255).roundToInt().toString(),
    )
}
