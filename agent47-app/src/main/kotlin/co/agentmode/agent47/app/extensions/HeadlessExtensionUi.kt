package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.ext.core.ExtensionNotificationLevel
import co.agentmode.agent47.ext.core.ExtensionUi
import com.github.ajalt.mordant.terminal.Terminal

/**
 * Non-interactive extension UI used by the CLI. Notifications are printed to the terminal
 * and every interactive request resolves to a no-op default.
 */
internal class HeadlessExtensionUi(private val terminal: Terminal) : ExtensionUi {
    override fun notify(message: String, level: ExtensionNotificationLevel) {
        when (level) {
            ExtensionNotificationLevel.INFO -> terminal.println(message)
            ExtensionNotificationLevel.WARNING -> terminal.println("Warning: $message")
            ExtensionNotificationLevel.ERROR -> terminal.println("Error: $message")
        }
    }

    override suspend fun select(title: String, options: List<String>): String? = null

    override suspend fun confirm(title: String, message: String): Boolean = false

    override suspend fun input(title: String, placeholder: String): String? = null

    override suspend fun editor(title: String, initialText: String): String? = null

    override fun setStatus(key: String, text: String?) = Unit

    override fun setWidget(key: String, lines: List<String>?) = Unit

    override fun setTitle(title: String?) = Unit

    override fun setEditorText(text: String) = Unit

    override suspend fun getEditorText(): String = ""
}
