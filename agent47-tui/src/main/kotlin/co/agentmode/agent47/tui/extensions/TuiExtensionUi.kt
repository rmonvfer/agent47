package co.agentmode.agent47.tui.extensions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionNotificationLevel
import co.agentmode.agent47.ext.core.ExtensionUi
import co.agentmode.agent47.ext.core.MutableExtensionUi
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.ui.core.state.SelectItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Bridges the extension [ExtensionUi] surface to the TUI: notifications become command results,
 * prompts and selects push overlays, and status/widget/title/editor calls update app state.
 */
internal class TuiExtensionUi(
    private val state: TuiAppState,
    private val editor: Editor,
    private val feed: TranscriptFeed,
    private val scope: CoroutineScope,
) : ExtensionUi {
    override fun notify(message: String, level: ExtensionNotificationLevel) {
        scope.launch {
            val prefix = when (level) {
                ExtensionNotificationLevel.INFO -> ""
                ExtensionNotificationLevel.WARNING -> "Warning: "
                ExtensionNotificationLevel.ERROR -> "Error: "
            }
            feed.appendCommandResult(prefix + message)
        }
    }

    override suspend fun select(title: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        val result = CompletableDeferred<String?>()
        scope.launch {
            state.overlays.push(
                title = title,
                items = options.map { SelectItem(label = it, value = it) },
                onSubmit = result::complete,
                onClose = { result.complete(null) },
            )
        }
        return result.await()
    }

    override suspend fun confirm(title: String, message: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        scope.launch {
            state.overlays.push(
                title = "$title — $message",
                items = listOf(
                    SelectItem(label = "Yes", value = true),
                    SelectItem(label = "No", value = false),
                ),
                onSubmit = result::complete,
                onClose = { result.complete(false) },
            )
        }
        return result.await()
    }

    override suspend fun input(title: String, placeholder: String): String? =
        requestText(title, placeholder, "")

    override suspend fun editor(title: String, initialText: String): String? =
        requestText(title, "", initialText)

    private suspend fun requestText(
        title: String,
        placeholder: String,
        initialValue: String,
    ): String? {
        val result = CompletableDeferred<String?>()
        scope.launch {
            state.overlays.pushPrompt(
                title = title,
                placeholder = placeholder,
                initialValue = initialValue,
                onSubmit = result::complete,
                onClose = { result.complete(null) },
            )
        }
        return result.await()
    }

    override fun setStatus(key: String, text: String?) {
        scope.launch {
            state.extensionStatuses = if (text == null) {
                state.extensionStatuses - key
            } else {
                state.extensionStatuses + (key to text)
            }
        }
    }

    override fun setWidget(key: String, lines: List<String>?) {
        scope.launch {
            state.extensionWidgets = if (lines == null) {
                state.extensionWidgets - key
            } else {
                state.extensionWidgets + (key to lines)
            }
        }
    }

    override fun setTitle(title: String?) {
        scope.launch { state.extensionTitle = title }
    }

    override fun setEditorText(text: String) {
        scope.launch {
            editor.setText(text)
            state.editorVersion++
        }
    }

    override suspend fun getEditorText(): String {
        val result = CompletableDeferred<String>()
        scope.launch { result.complete(editor.text()) }
        return result.await()
    }
}

/** Binds [ui] to the extension context's mutable UI surface for the lifetime of the composition. */
@Composable
internal fun BindExtensionUi(extensionContext: ExtensionContext?, ui: TuiExtensionUi) {
    DisposableEffect(extensionContext) {
        val controller = extensionContext?.ui as? MutableExtensionUi
        controller?.bind(ui)
        onDispose { controller?.reset() }
    }
}
