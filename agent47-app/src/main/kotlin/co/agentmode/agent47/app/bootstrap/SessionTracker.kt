package co.agentmode.agent47.app.bootstrap

import co.agentmode.agent47.coding.core.session.SessionManager

/**
 * Sole owner of the session that extension hooks act on. The session control, extension
 * context, and reloader all read and update the active session through this holder rather
 * than each capturing their own mutable reference.
 */
internal class SessionTracker(initial: SessionManager?) {
    var current: SessionManager? = initial
}
