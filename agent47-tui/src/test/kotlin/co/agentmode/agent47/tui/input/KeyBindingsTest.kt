package co.agentmode.agent47.tui.input

import co.agentmode.agent47.ext.core.RegisteredShortcut
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyBindingsTest {
    private val altX = RegisteredShortcut(key = "alt+x", description = "", handler = { })

    private fun ctx(
        isStreaming: Boolean = false,
        isViewingAgent: Boolean = false,
        ctrlCArmed: Boolean = false,
        editorBlank: Boolean = true,
        hasAutocompletePopup: Boolean = false,
        slashPopupItemCount: Int = 0,
        extensionShortcuts: List<RegisteredShortcut> = emptyList(),
        hasExtensionContext: Boolean = false,
    ) = KeyContext(
        isStreaming = isStreaming,
        isViewingAgent = isViewingAgent,
        ctrlCArmed = ctrlCArmed,
        editorBlank = editorBlank,
        hasAutocompletePopup = hasAutocompletePopup,
        slashPopupItemCount = slashPopupItemCount,
        extensionShortcuts = extensionShortcuts,
        hasExtensionContext = hasExtensionContext,
    )

    private fun char(value: Char, ctrl: Boolean = false, alt: Boolean = false, shift: Boolean = false) =
        KeyboardEvent(Key.Character(value), ctrl = ctrl, alt = alt, shift = shift)

    @Test
    fun `ctrl+c interrupts while streaming, then arms, then quits`() {
        assertEquals(TuiIntent.InterruptStreaming, KeyBindings.resolve(char('c', ctrl = true), ctx(isStreaming = true)))
        assertEquals(TuiIntent.ArmCtrlC, KeyBindings.resolve(char('c', ctrl = true), ctx(ctrlCArmed = false)))
        assertEquals(TuiIntent.Quit, KeyBindings.resolve(char('c', ctrl = true), ctx(ctrlCArmed = true)))
    }

    @Test
    fun `escape leaves focus, interrupts while streaming, otherwise is unhandled`() {
        assertEquals(TuiIntent.ExitFocusMode, KeyBindings.resolve(KeyboardEvent(Key.Escape), ctx(isViewingAgent = true)))
        assertEquals(TuiIntent.InterruptStreaming, KeyBindings.resolve(KeyboardEvent(Key.Escape), ctx(isStreaming = true)))
        assertEquals(null, KeyBindings.resolve(KeyboardEvent(Key.Escape), ctx()))
    }

    @Test
    fun `escape leaving focus takes priority over interrupting`() {
        assertEquals(
            TuiIntent.ExitFocusMode,
            KeyBindings.resolve(KeyboardEvent(Key.Escape), ctx(isViewingAgent = true, isStreaming = true)),
        )
    }

    @Test
    fun `ctrl shortcuts map to their global actions`() {
        assertEquals(TuiIntent.ClearChat, KeyBindings.resolve(char('l', ctrl = true), ctx()))
        assertEquals(TuiIntent.ToggleThinking, KeyBindings.resolve(char('t', ctrl = true), ctx()))
        assertEquals(TuiIntent.CycleModel(-1), KeyBindings.resolve(char('p', ctrl = true), ctx()))
        assertEquals(TuiIntent.CycleModel(1), KeyBindings.resolve(char('n', ctrl = true), ctx()))
        assertEquals(TuiIntent.OpenSettings, KeyBindings.resolve(char('o', ctrl = true), ctx()))
        assertEquals(TuiIntent.ToggleThinkingBlock, KeyBindings.resolve(char('g', ctrl = true), ctx()))
        assertEquals(TuiIntent.ScrollDown(12), KeyBindings.resolve(char('d', ctrl = true), ctx()))
    }

    @Test
    fun `ctrl+e and ctrl+u act globally only when the editor is empty`() {
        assertEquals(TuiIntent.ToggleToolBlock, KeyBindings.resolve(char('e', ctrl = true), ctx(editorBlank = true)))
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(char('e', ctrl = true), ctx(editorBlank = false)))
        assertEquals(TuiIntent.ScrollUp(12), KeyBindings.resolve(char('u', ctrl = true), ctx(editorBlank = true)))
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(char('u', ctrl = true), ctx(editorBlank = false)))
    }

    @Test
    fun `ctrl+d scrolls even when the editor has text`() {
        assertEquals(TuiIntent.ScrollDown(12), KeyBindings.resolve(char('d', ctrl = true), ctx(editorBlank = false)))
    }

    @Test
    fun `scroll keys map to scroll intents with their amounts`() {
        assertEquals(TuiIntent.ScrollUp(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowUp, ctrl = true), ctx()))
        assertEquals(TuiIntent.ScrollUp(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowUp, shift = true), ctx()))
        assertEquals(TuiIntent.ScrollDown(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowDown, ctrl = true), ctx()))
        assertEquals(TuiIntent.ScrollDown(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowDown, shift = true), ctx()))
        assertEquals(TuiIntent.ScrollUp(12), KeyBindings.resolve(KeyboardEvent(Key.PageUp), ctx()))
        assertEquals(TuiIntent.ScrollDown(12), KeyBindings.resolve(KeyboardEvent(Key.PageDown), ctx()))
        assertEquals(TuiIntent.ScrollUp(10), KeyBindings.resolve(KeyboardEvent(Key.PageUp, alt = true), ctx()))
        assertEquals(TuiIntent.ScrollDown(10), KeyBindings.resolve(KeyboardEvent(Key.PageDown, alt = true), ctx()))
    }

    @Test
    fun `bare arrows scroll only when the editor is empty`() {
        assertEquals(TuiIntent.ScrollUp(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowUp), ctx(editorBlank = true)))
        assertEquals(TuiIntent.ScrollDown(3), KeyBindings.resolve(KeyboardEvent(Key.ArrowDown), ctx(editorBlank = true)))
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(KeyboardEvent(Key.ArrowUp), ctx(editorBlank = false)))
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(KeyboardEvent(Key.ArrowDown), ctx(editorBlank = false)))
    }

    @Test
    fun `enter submits after popup when a slash popup is visible`() {
        assertEquals(TuiIntent.SubmitAfterPopup, KeyBindings.resolve(KeyboardEvent(Key.Enter), ctx(slashPopupItemCount = 3)))
    }

    @Test
    fun `enter submits when no autocomplete popup is visible`() {
        assertEquals(TuiIntent.Submit, KeyBindings.resolve(KeyboardEvent(Key.Enter), ctx(hasAutocompletePopup = false)))
    }

    @Test
    fun `enter passes to the editor when a file-completion popup is open`() {
        assertEquals(
            TuiIntent.PassToEditor,
            KeyBindings.resolve(KeyboardEvent(Key.Enter), ctx(hasAutocompletePopup = true, slashPopupItemCount = 0)),
        )
    }

    @Test
    fun `enter with a modifier is not a submit`() {
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(KeyboardEvent(Key.Enter, shift = true), ctx()))
    }

    @Test
    fun `extension shortcut resolves only with an extension context`() {
        assertEquals(
            TuiIntent.RunExtensionShortcut(altX),
            KeyBindings.resolve(char('x', alt = true), ctx(extensionShortcuts = listOf(altX), hasExtensionContext = true)),
        )
        assertEquals(
            TuiIntent.PassToEditor,
            KeyBindings.resolve(char('x', alt = true), ctx(extensionShortcuts = listOf(altX), hasExtensionContext = false)),
        )
    }

    @Test
    fun `an enter binding is shadowed by submit but wins over a file-completion popup`() {
        val enterShortcut = RegisteredShortcut(key = "enter", description = "", handler = { })
        // No autocomplete popup: submit wins, the extension binding never fires.
        assertEquals(
            TuiIntent.Submit,
            KeyBindings.resolve(
                KeyboardEvent(Key.Enter),
                ctx(extensionShortcuts = listOf(enterShortcut), hasExtensionContext = true),
            ),
        )
        // File-completion popup open: not a submit, so the extension binding resolves.
        assertEquals(
            TuiIntent.RunExtensionShortcut(enterShortcut),
            KeyBindings.resolve(
                KeyboardEvent(Key.Enter),
                ctx(hasAutocompletePopup = true, extensionShortcuts = listOf(enterShortcut), hasExtensionContext = true),
            ),
        )
    }

    @Test
    fun `unmapped characters pass to the editor`() {
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(char('a'), ctx()))
        assertEquals(TuiIntent.PassToEditor, KeyBindings.resolve(char('z', ctrl = true), ctx()))
    }
}
