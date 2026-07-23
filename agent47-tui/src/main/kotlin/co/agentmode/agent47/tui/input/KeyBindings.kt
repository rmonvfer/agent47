package co.agentmode.agent47.tui.input

import co.agentmode.agent47.ext.core.RegisteredShortcut

/**
 * Pure inputs the key resolver needs. Everything the resolver decides is a function of the event
 * and this context, so the whole keymap is testable without a running composition.
 */
internal data class KeyContext(
    val isStreaming: Boolean,
    val isViewingAgent: Boolean,
    val ctrlCArmed: Boolean,
    val editorBlank: Boolean,
    val hasAutocompletePopup: Boolean,
    val slashPopupItemCount: Int,
    val extensionShortcuts: List<RegisteredShortcut>,
    val hasExtensionContext: Boolean,
)

internal object KeyBindings {
    /**
     * Resolves [event] against [ctx] to a [TuiIntent], or null when the key is not handled.
     * The order mirrors the interactive TUI: the Enter submit path is checked before the rest of
     * the keymap, so a submit shadows any extension shortcut bound to Enter.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun resolve(event: KeyboardEvent, ctx: KeyContext): TuiIntent? {
        val enterNoModifiers = event.key == Key.Enter && !event.shift && !event.ctrl && !event.alt
        if (enterNoModifiers && ctx.slashPopupItemCount > 0) return TuiIntent.SubmitAfterPopup
        if (enterNoModifiers && !ctx.hasAutocompletePopup) return TuiIntent.Submit

        if (event.ctrl && event.key is Key.Character && event.key.value == 'c') {
            return when {
                ctx.isStreaming -> TuiIntent.InterruptStreaming
                ctx.ctrlCArmed -> TuiIntent.Quit
                else -> TuiIntent.ArmCtrlC
            }
        }

        if (event.key is Key.Escape) {
            return when {
                ctx.isViewingAgent -> TuiIntent.ExitFocusMode
                ctx.isStreaming -> TuiIntent.InterruptStreaming
                else -> null
            }
        }

        resolveCtrlShortcut(event, ctx)?.let { return it }
        resolveExtensionShortcut(event, ctx)?.let { return it }
        resolveScroll(event, ctx)?.let { return it }

        // Everything else, including Enter while a file-completion popup is open, goes to the editor.
        return TuiIntent.PassToEditor
    }

    @Suppress("CyclomaticComplexMethod")
    private fun resolveCtrlShortcut(event: KeyboardEvent, ctx: KeyContext): TuiIntent? {
        if (!(event.ctrl && event.key is Key.Character)) return null
        return when (event.key.value.lowercaseChar()) {
            'l' -> TuiIntent.ClearChat
            't' -> TuiIntent.ToggleThinking
            'p' -> TuiIntent.CycleModel(-1)
            'n' -> TuiIntent.CycleModel(1)
            'o' -> TuiIntent.OpenSettings
            'g' -> TuiIntent.ToggleThinkingBlock
            // With text in the editor, Ctrl+E is move-to-end-of-line; only act globally
            // when the editor is empty so the line-editing shortcut isn't shadowed.
            'e' -> if (ctx.editorBlank) TuiIntent.ToggleToolBlock else null
            // With text in the editor, Ctrl+U is kill-to-start-of-line; only scroll the
            // chat globally when the editor is empty.
            'u' -> if (ctx.editorBlank) TuiIntent.ScrollUp(12) else null
            'd' -> TuiIntent.ScrollDown(12)
            else -> null
        }
    }

    private fun resolveExtensionShortcut(event: KeyboardEvent, ctx: KeyContext): TuiIntent? {
        if (!ctx.hasExtensionContext) return null
        val pressed = keyboardShortcutName(event)
        val shortcut: RegisteredShortcut? = pressed?.let { key -> ctx.extensionShortcuts.firstOrNull { it.key == key } }
        return shortcut?.let { TuiIntent.RunExtensionShortcut(it) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun resolveScroll(event: KeyboardEvent, ctx: KeyContext): TuiIntent? {
        val scrollAmount = 3
        val pageAmount = 12
        val altPageAmount = 10
        return when {
            (event.ctrl || event.shift) && event.key == Key.ArrowUp -> TuiIntent.ScrollUp(scrollAmount)
            (event.ctrl || event.shift) && event.key == Key.ArrowDown -> TuiIntent.ScrollDown(scrollAmount)
            event.key == Key.PageUp && !event.alt && !event.ctrl -> TuiIntent.ScrollUp(pageAmount)
            event.key == Key.PageDown && !event.alt && !event.ctrl -> TuiIntent.ScrollDown(pageAmount)
            event.alt && event.key == Key.PageUp -> TuiIntent.ScrollUp(altPageAmount)
            event.alt && event.key == Key.PageDown -> TuiIntent.ScrollDown(altPageAmount)
            ctx.editorBlank && event.key == Key.ArrowUp && !event.ctrl && !event.alt -> TuiIntent.ScrollUp(scrollAmount)
            ctx.editorBlank && event.key == Key.ArrowDown && !event.ctrl && !event.alt -> TuiIntent.ScrollDown(scrollAmount)
            else -> null
        }
    }
}
