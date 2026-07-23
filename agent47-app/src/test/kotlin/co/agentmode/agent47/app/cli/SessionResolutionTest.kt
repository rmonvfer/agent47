package co.agentmode.agent47.app.cli

import co.agentmode.agent47.coding.core.session.SessionManager
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SessionResolutionTest {
    @Test
    fun `finds a session by its exact id`() {
        val dir = Files.createTempDirectory("agent47-session-exact")
        val file = dir.resolve("session-1.jsonl")
        val id = SessionManager(file).getHeader().id
        assertEquals(file, findSessionById(dir, id))
    }

    @Test
    fun `finds a session by a unique id prefix`() {
        val dir = Files.createTempDirectory("agent47-session-prefix")
        val file = dir.resolve("session-1.jsonl")
        val id = SessionManager(file).getHeader().id
        assertEquals(file, findSessionById(dir, id.substring(0, 8)))
    }

    @Test
    fun `returns null when an id prefix is ambiguous`() {
        val dir = Files.createTempDirectory("agent47-session-ambiguous")
        val first = dir.resolve("session-1.jsonl")
        val id = SessionManager(first).getHeader().id
        val second = dir.resolve("session-2.jsonl")
        Files.copy(first, second, StandardCopyOption.REPLACE_EXISTING)
        assertNull(findSessionById(dir, id.substring(0, 8)))
    }

    @Test
    fun `returns null when the sessions directory is missing`() {
        val dir = Files.createTempDirectory("agent47-session-missing")
        assertNull(findSessionById(dir.resolve("does-not-exist"), "anything"))
    }
}
