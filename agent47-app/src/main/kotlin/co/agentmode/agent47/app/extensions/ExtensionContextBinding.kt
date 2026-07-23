package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.ext.core.ExtensionContext

/**
 * Late binding for the extension context. Collaborators constructed before the context
 * exists (the session control, the agent client event handler, and the reloader) read it
 * through [get], and the context is supplied once via [bind]. This mirrors the extension
 * runner's own bindContext late-binding and breaks the construction cycle without lateinit.
 */
internal class ExtensionContextBinding {
    private var context: ExtensionContext? = null

    fun bind(context: ExtensionContext) {
        this.context = context
    }

    fun get(): ExtensionContext = checkNotNull(context) { "Extension context has not been bound" }
}
