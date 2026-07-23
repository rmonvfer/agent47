package co.agentmode.agent47.tui.runtime

import androidx.compose.runtime.Composable
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.session.SessionMessageEntry
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.runMosaicMain
import com.jakewharton.mosaic.ui.isSpecifiedColor
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the terminal lifecycle for the interactive TUI: alternate screen, kitty keyboard protocol,
 * background pre-fill, the terminal-restore shutdown hook, and the resume hint. [runInTerminalSession]
 * brackets the Mosaic render loop with setup and teardown.
 */
internal object TerminalSession {
    /**
     * The session the interactive app is currently in. Read by the terminal-restore shutdown hook,
     * the app exits via exitProcess(), so that hook is the only reliable place to print on the way out.
     */
    private val activeResumeSession = AtomicReference<SessionManager?>(null)

    private const val RESTORE_TERMINAL = "\u001b[<u\u001b[?25h\u001b[?1049l"
    private const val ENTER_TERMINAL = "\u001b[?1049h\u001b[?25l\u001b[>1u"
    private const val RGB_SCALE = 255

    /** Records the active session so the shutdown hook can print a resume hint on exit. */
    fun trackResumeSession(session: SessionManager?) {
        activeResumeSession.set(session)
    }

    fun runInTerminalSession(theme: ThemeConfig, content: @Composable () -> Unit) {
        val out = System.out

        // Install a shutdown hook so that exitProcess() (or any JVM shutdown) restores
        // the terminal from alternate screen / kitty keyboard mode.
        val shutdownHook = Thread({
            out.write(RESTORE_TERMINAL.toByteArray())
            out.flush()
            printResumeHint(out)
        }, "terminal-restore")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Enter alternate screen buffer, hide cursor, and enable kitty keyboard protocol.
        // Kitty keyboard flags=1 (disambiguate) makes the terminal encode modifier keys
        // on Enter, Tab, Escape, and Backspace via CSI u sequences, allowing Shift+Enter
        // to be distinguished from bare Enter.
        // Non-ASCII Unicode codepoints (accented letters, etc.) are handled by our patched CompatKt.java
        // which shadows Mosaic's broken version that throws for codepoints outside ASCII.
        out.write(ENTER_TERMINAL.toByteArray())
        out.flush()

        // Fill the alternate screen with the theme background color. Mosaic renders
        // rows-1 rows of content (to avoid a scroll caused by the trailing \r\n it
        // appends after every row). Pre-filling ensures the unused last terminal row
        // matches the app background instead of showing the terminal's native color.
        if (theme.background.isSpecifiedColor) {
            val (r, g, b) = theme.background
            val red = (r * RGB_SCALE).toInt()
            val green = (g * RGB_SCALE).toInt()
            val blue = (b * RGB_SCALE).toInt()
            out.write("\u001b[48;2;${red};${green};${blue}m\u001b[2J\u001b[H\u001b[0m".toByteArray())
            out.flush()
        }
        try {
            runMosaicMain {
                content()
            }
        } catch (e: UnsupportedOperationException) {
            // Safety net: if our CompatKt shadow somehow isn't loaded and Mosaic's
            // original throws for an unrecognized codepoint, exit gracefully.
            System.err.println("Keyboard input error: ${e.message}")
            System.err.println("This is a Mosaic library bug with non-ASCII characters.")
        } finally {
            // Pop kitty keyboard flags, restore cursor, and leave alternate screen buffer
            out.write(RESTORE_TERMINAL.toByteArray())
            out.flush()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }

    /** Prints a "resume this session" hint to [out] once the terminal has been restored. */
    @Suppress("ReturnCount")
    private fun printResumeHint(out: PrintStream) {
        val session = activeResumeSession.get() ?: return
        val hasContent = runCatching { session.getEntries().any { it is SessionMessageEntry } }.getOrDefault(false)
        if (!hasContent) return
        val id = runCatching { session.getHeader().id }.getOrNull() ?: return
        runCatching {
            out.write("\nTo resume this session:  agent47 --resume $id\n".toByteArray())
            out.flush()
        }
    }
}
