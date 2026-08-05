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
    fun `branching moves the leaf and preserves the abandoned subtree`() {
        val manager = SessionManager(createTempDirectory("agent47-session").resolve("session.jsonl"))
        val first = manager.appendMessage(userMessage("first"))
        manager.appendMessage(userMessage("abandoned"))

        manager.branch(first.id)
        val branched = manager.appendMessage(userMessage("branched"))

        assertEquals(first.id, branched.parentId)
        assertEquals(3, manager.getEntries().size)
        assertEquals(listOf("first", "branched"), manager.getBranch().map(::messageText))
        val roots = manager.getTree()
        assertEquals(1, roots.size)
        assertEquals(2, roots.single().children.size)
    }

    @Test
    fun `reset leaf starts a new root with an empty branch`() {
        val manager = SessionManager(createTempDirectory("agent47-session").resolve("session.jsonl"))
        manager.appendMessage(userMessage("old root"))

        manager.resetLeaf()
        assertEquals(0, manager.buildContext().messages.size)

        val newRoot = manager.appendMessage(userMessage("new root"))
        assertEquals(null, newRoot.parentId)
        assertEquals(2, manager.getTree().size)
    }

    @Test
    fun `branch with summary records the abandoned leaf at the new position`() {
        val manager = SessionManager(createTempDirectory("agent47-session").resolve("session.jsonl"))
        val keep = manager.appendMessage(userMessage("keep"))
        val abandoned = manager.appendMessage(userMessage("abandoned"))

        val summary = manager.branchWithSummary(keep.id, "what happened over there")

        assertEquals(abandoned.id, summary.fromId)
        assertEquals(keep.id, summary.parentId)
        assertEquals(summary.id, manager.getLeafId())
        val context = manager.buildContext()
        assertEquals(listOf("user", "branchSummary"), context.messages.map { it.role })
    }

    @Test
    fun `branched session file re-chains the kept path and records its parent`() {
        val dir = createTempDirectory("agent47-session")
        val manager = SessionManager(dir.resolve("session.jsonl"))
        val first = manager.appendMessage(userMessage("first"))
        manager.appendLabelChange(first.id, "bookmark")
        manager.branch(first.id)
        val kept = manager.appendMessage(userMessage("kept"))

        val forked = SessionManager(manager.createBranchedSession(kept.id, dir.resolve("fork.jsonl")))

        assertEquals(manager.getSessionFile().toString(), forked.getHeader().parentSession)
        assertEquals(listOf("first", "kept"), forked.getEntries().map(::messageText))
        assertEquals(listOf(null, first.id), forked.getEntries().map { it.parentId })
    }

    private fun userMessage(text: String): UserMessage = UserMessage(
        content = listOf(TextContent(text = text)),
        timestamp = System.currentTimeMillis(),
    )

    private fun messageText(entry: SessionEntry): String =
        (((entry as SessionMessageEntry).message as UserMessage).content.single() as TextContent).text

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
