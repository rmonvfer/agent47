package co.agentmode.agent47.tui.input

import co.agentmode.agent47.ext.core.ExtensionShortcutHandler
import co.agentmode.agent47.ext.core.RegisteredShortcut
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiIntentTest {
    @Test
    fun `arming and submit intents keep the armed ctrl-c state`() {
        val preserving: List<TuiIntent> = listOf(
            TuiIntent.ArmCtrlC,
            TuiIntent.Submit,
            TuiIntent.SubmitAfterPopup,
        )
        preserving.forEach { intent ->
            assertFalse(intent.resetsCtrlCArm(), "expected $intent to keep the armed state")
        }
    }

    @Test
    fun `every other intent clears the armed ctrl-c state`() {
        val shortcut = RegisteredShortcut("ctrl+x", "test shortcut", ExtensionShortcutHandler { })
        val clearing: List<TuiIntent?> = listOf(
            null,
            TuiIntent.InterruptStreaming,
            TuiIntent.Quit,
            TuiIntent.ExitFocusMode,
            TuiIntent.ClearChat,
            TuiIntent.ToggleThinking,
            TuiIntent.CycleModel(1),
            TuiIntent.CycleModel(-1),
            TuiIntent.OpenSettings,
            TuiIntent.ToggleThinkingBlock,
            TuiIntent.ToggleToolBlock,
            TuiIntent.ScrollUp(3),
            TuiIntent.ScrollDown(3),
            TuiIntent.RunExtensionShortcut(shortcut),
            TuiIntent.PassToEditor,
        )
        clearing.forEach { intent ->
            assertTrue(intent.resetsCtrlCArm(), "expected $intent to clear the armed state")
        }
    }
}
