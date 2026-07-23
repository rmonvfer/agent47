package co.agentmode.agent47.tui.input

import co.agentmode.agent47.ext.core.RegisteredShortcut

/**
 * A resolved keyboard action. [KeyBindings.resolve] maps a key event plus [KeyContext] to one of
 * these; the composable applies it. A null result means the key was not handled.
 */
internal sealed interface TuiIntent {
    /** Abort the in-flight response (Ctrl+C or Esc while streaming). */
    data object InterruptStreaming : TuiIntent

    /** Arm the exit-on-second-press behavior after a first Ctrl+C. */
    data object ArmCtrlC : TuiIntent

    /** Abort, cancel the prompt job, and exit (second Ctrl+C). */
    data object Quit : TuiIntent

    /** Leave background-agent transcript focus mode. */
    data object ExitFocusMode : TuiIntent

    /** Clear the visible chat history. */
    data object ClearChat : TuiIntent

    /** Toggle the thinking level between off and low. */
    data object ToggleThinking : TuiIntent

    /** Move the model selection by [direction] (negative previous, positive next). */
    data class CycleModel(val direction: Int) : TuiIntent

    /** Open the settings overlay. */
    data object OpenSettings : TuiIntent

    /** Collapse or expand the latest thinking block. */
    data object ToggleThinkingBlock : TuiIntent

    /** Collapse or expand the latest tool execution. */
    data object ToggleToolBlock : TuiIntent

    /** Scroll the active chat up by [lines]. */
    data class ScrollUp(val lines: Int) : TuiIntent

    /** Scroll the active chat down by [lines]. */
    data class ScrollDown(val lines: Int) : TuiIntent

    /** Run an extension-registered shortcut handler. */
    data class RunExtensionShortcut(val shortcut: RegisteredShortcut) : TuiIntent

    /** Forward the event to the editor. */
    data object PassToEditor : TuiIntent

    /** Submit the current editor contents. */
    data object Submit : TuiIntent

    /** Apply the visible slash-command popup selection, then submit. */
    data object SubmitAfterPopup : TuiIntent
}
