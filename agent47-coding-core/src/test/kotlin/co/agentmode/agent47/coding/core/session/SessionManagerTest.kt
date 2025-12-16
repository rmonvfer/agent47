package co.agentmode.agent47.coding.core.session

import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SessionManagerTest {
    @Test
    fun `session manager appends messages and builds context`() {
        val dir = createTempDirectory("agent47-session")
        val file = dir.resolve("session.jsonl")
        val manager = SessionManager(file)

        manager.appendMessage(
            UserMessage(
                content = listOf(TextContent(text = "hello")),
                timestamp = System.currentTimeMillis(),
            ),
        )

        val context = manager.buildContext()
        assertEquals(1, context.messages.size)
        assertEquals("user", context.messages.first().role)
    }

    @Test
    fun `migration v1 to v3 backfills ids and version`() {
        val dir = createTempDirectory("agent47-migration")
        val file = dir.resolve("legacy.jsonl")

        val legacy = """
            {"type":"session","id":"abc","timestamp":"${Instant.now()}","cwd":"$dir"}
            {"type":"message","id":"","parentId":null,"timestamp":"${Instant.now()}","message":{"type":"user","role":"user","timestamp":1,"content":[{"type":"text","text":"hi"}]}}
        """.trimIndent() + "\n"

        file.writeText(legacy)

        val manager = SessionManager(file)
        val header = manager.getHeader()
        assertEquals(CURRENT_SESSION_VERSION, header.version)
        assertNotNull(manager.getEntries().first().id)
    }
}
