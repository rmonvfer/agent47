package co.agentmode.agent47.coding.core.session

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import co.agentmode.agent47.coding.core.compaction.estimateTokens
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BranchSummarizationTest {
    @Test
    fun `collects entries between the old leaf and the deepest shared ancestor`() {
        val manager = SessionManager(createTempDirectory("agent47-branch-summary").resolve("session.jsonl"))
        manager.appendMessage(userMessage("root"))
        val a = manager.appendMessage(userMessage("A"))
        val b = manager.appendMessage(userMessage("B"))
        manager.branch(a.id)
        val c = manager.appendMessage(userMessage("C"))
        val d = manager.appendMessage(userMessage("D"))

        val result = collectEntriesForBranchSummary(manager, oldLeafId = d.id, targetId = b.id)

        assertEquals(a.id, result.commonAncestorId)
        assertEquals(listOf(c.id, d.id), result.entries.map { it.id })
    }

    @Test
    fun `no old leaf collects nothing`() {
        val manager = SessionManager(createTempDirectory("agent47-branch-summary").resolve("session.jsonl"))
        val root = manager.appendMessage(userMessage("root"))

        val result = collectEntriesForBranchSummary(manager, oldLeafId = null, targetId = root.id)

        assertEquals(emptyList(), result.entries)
        assertEquals(null, result.commonAncestorId)
    }

    @Test
    fun `prepareBranchEntries drops tool results and keeps only the newest messages under budget`() {
        val userA = messageEntry("1", null, userMessage("A".repeat(40)))
        val assistantB = messageEntry("2", "1", assistantMessage("B".repeat(40)))
        val toolResultC = messageEntry("3", "2", toolResultMessage("C".repeat(400)))
        val userD = messageEntry("4", "3", userMessage("D".repeat(40)))

        val budgetTokens = estimateTokens(userD.message)
        val prepared = prepareBranchEntries(listOf(userA, assistantB, toolResultC, userD), tokenBudget = budgetTokens)

        assertEquals(listOf(userD.message), prepared.messages)
        assertTrue(prepared.messages.none { it is ToolResultMessage })
    }

    @Test
    fun `prepareBranchEntries keeps every non-tool-result message when unlimited`() {
        val userA = messageEntry("1", null, userMessage("A"))
        val assistantB = messageEntry("2", "1", assistantMessage("B"))
        val toolResultC = messageEntry("3", "2", toolResultMessage("C"))
        val userD = messageEntry("4", "3", userMessage("D"))

        val prepared = prepareBranchEntries(listOf(userA, assistantB, toolResultC, userD), tokenBudget = 0)

        assertEquals(listOf(userA.message, assistantB.message, userD.message), prepared.messages)
    }

    @Test
    fun `prepareBranchEntries fits a branch summary past budget when there is headroom`() {
        val summaryEntry = BranchSummaryEntry(
            id = "s1",
            parentId = null,
            timestamp = Instant.now().toString(),
            fromId = "old-leaf",
            summary = "S".repeat(100),
        )

        val prepared = prepareBranchEntries(listOf(summaryEntry), tokenBudget = 10)

        assertEquals(1, prepared.messages.size)
        assertTrue(prepared.messages.single() is BranchSummaryMessage)
    }

    private fun messageEntry(id: String, parentId: String?, message: Message): SessionMessageEntry =
        SessionMessageEntry(id = id, parentId = parentId, timestamp = Instant.now().toString(), message = message)

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = listOf(TextContent(text = text)), timestamp = System.currentTimeMillis())

    private fun assistantMessage(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text = text)),
        api = KnownApis.OpenAiResponses,
        provider = ProviderId("openai"),
        model = "mock",
        usage = emptyUsage(),
        stopReason = StopReason.STOP,
        timestamp = System.currentTimeMillis(),
    )

    private fun toolResultMessage(text: String): ToolResultMessage = ToolResultMessage(
        toolCallId = "call-1",
        toolName = "read",
        content = listOf(TextContent(text = text)),
        isError = false,
        timestamp = System.currentTimeMillis(),
    )
}
