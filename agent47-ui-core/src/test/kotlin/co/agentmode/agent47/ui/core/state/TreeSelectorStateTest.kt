package co.agentmode.agent47.ui.core.state

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import co.agentmode.agent47.coding.core.session.CompactionEntry
import co.agentmode.agent47.coding.core.session.ModelChangeEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeSelectorStateTest {
    @Test
    fun `a linear chain of single-child entries stays at indent zero`() {
        val manager = newSession()
        manager.appendMessage(userMessage("A"))
        manager.appendMessage(userMessage("B"))
        manager.appendMessage(userMessage("C"))

        val state = TreeSelectorState(manager)

        assertEquals(listOf(0, 0, 0), state.rows.map { it.indent })
        assertFalse(state.multipleRoots)
    }

    @Test
    fun `a branch point indents its children and gutters the sibling connector`() {
        val manager = newSession()
        val root = manager.appendMessage(userMessage("root"))
        manager.branch(root.id)
        manager.appendMessage(userMessage("left"))
        manager.branch(root.id)
        manager.appendMessage(userMessage("right"))

        val state = TreeSelectorState(manager)

        assertEquals(listOf(0, 1, 1), state.rows.map { it.indent })
        val (left, right) = state.rows.drop(1)
        assertTrue(left.showConnector && !left.isLast)
        assertTrue(right.showConnector && right.isLast)
    }

    @Test
    fun `the first generation after a branch point is grouped one level in, then flattens again`() {
        val manager = newSession()
        val root = manager.appendMessage(userMessage("root"))
        manager.branch(root.id)
        val a = manager.appendMessage(userMessage("A"))
        manager.appendMessage(userMessage("A1"))
        manager.appendMessage(userMessage("A2"))
        manager.branch(root.id)
        manager.appendMessage(userMessage("B"))

        val state = TreeSelectorState(manager)

        // root=0, {A,B}=1 (the branch itself), A's single-child chain groups to 2 and then stays flat.
        assertEquals(listOf(0, 1, 2, 2, 1), state.rows.map { it.indent })
        assertTrue(state.rows.single { it.node.entry.id == a.id }.showConnector)
    }

    @Test
    fun `default filter hides bookkeeping entries but keeps them reachable in all mode`() {
        val manager = newSession()
        val first = manager.appendMessage(userMessage("first"))
        manager.append(
            ModelChangeEntry(
                id = "model-1",
                parentId = first.id,
                timestamp = Instant.now().toString(),
                provider = "anthropic",
                modelId = "claude",
            ),
        )
        manager.appendMessage(userMessage("second"))

        val defaultState = TreeSelectorState(manager)
        assertEquals(listOf("user: first", "user: second"), defaultState.rows.map { textOf(defaultState, it) })

        val allState = TreeSelectorState(manager).apply { cycleFilter(); cycleFilter(); cycleFilter() }
        assertEquals(TreeFilterMode.ALL, allState.filterMode)
        assertEquals(listOf("user: first", "[model: claude]", "user: second"), allState.rows.map { textOf(allState, it) })
    }

    @Test
    fun `no-tools filter additionally hides tool result messages`() {
        val manager = newSession()
        manager.appendMessage(assistantWithToolCall("call-1", "read", """{"path":"foo.txt"}"""))
        manager.appendMessage(toolResult("call-1", "read"))
        manager.appendMessage(userMessage("done"))

        val state = TreeSelectorState(manager)
        // The tool-call-only assistant turn renders nothing on its own, so the default view
        // starts at the tool result.
        assertEquals(listOf("[read: foo.txt]", "user: done"), state.rows.map { textOf(state, it) })

        state.cycleFilter()
        assertEquals(TreeFilterMode.NO_TOOLS, state.filterMode)
        assertEquals(listOf("user: done"), state.rows.map { textOf(state, it) })
    }

    @Test
    fun `user-only filter shows just user messages`() {
        val manager = newSession()
        manager.appendMessage(userMessage("hi"))
        manager.appendMessage(assistantWithText("hello back"))

        val state = TreeSelectorState(manager)
        state.cycleFilter()
        state.cycleFilter()
        assertEquals(TreeFilterMode.USER_ONLY, state.filterMode)
        assertEquals(1, state.rows.size)
        assertEquals("user: hi", textOf(state, state.rows.single()))
    }

    @Test
    fun `search filters by substring across tokens`() {
        val manager = newSession()
        manager.appendMessage(userMessage("please fix the login bug"))
        manager.appendMessage(userMessage("unrelated message"))

        val state = TreeSelectorState(manager)
        "login bug".forEach(state::appendSearchChar)
        assertEquals(1, state.rows.size)
        assertEquals("user: please fix the login bug", textOf(state, state.rows.single()))

        assertTrue(state.clearSearch())
        assertEquals(2, state.rows.size)
    }

    @Test
    fun `folding a node hides its descendants but keeps the node itself visible`() {
        val manager = newSession()
        val root = manager.appendMessage(userMessage("root"))
        manager.appendMessage(userMessage("child"))
        manager.branch(root.id)
        manager.appendMessage(userMessage("other child"))

        val state = TreeSelectorState(manager)
        while (state.selectedRow()?.node?.entry?.id != root.id) {
            state.moveUp()
        }
        assertTrue(state.selectedRow()!!.hasVisibleChildren)

        state.foldOrMoveToParent()

        assertTrue(state.isFolded(root.id))
        assertEquals(listOf("user: root"), state.rows.map { textOf(state, it) })

        state.unfoldOrMoveToChild()
        assertFalse(state.isFolded(root.id))
        assertEquals(3, state.rows.size)
    }

    @Test
    fun `selecting the current leaf keeps it as the initial selection`() {
        val manager = newSession()
        manager.appendMessage(userMessage("first"))
        val leaf = manager.appendMessage(userMessage("second"))

        val state = TreeSelectorState(manager)

        assertEquals(leaf.id, state.selectedRow()!!.node.entry.id)
        assertTrue(state.activePathIds.contains(leaf.id))
    }

    @Test
    fun `an assistant turn with only a tool call is hidden unless it is the current leaf`() {
        val manager = newSession()
        manager.appendMessage(userMessage("go"))
        val toolTurn = manager.appendMessage(assistantWithToolCall("call-1", "bash", """{"command":"ls"}"""))

        val state = TreeSelectorState(manager)

        assertTrue(state.rows.any { it.node.entry.id == toolTurn.id }, "current leaf stays visible even with no text")
    }

    @Test
    fun `cycling the filter mode wraps back to default`() {
        val mode = TreeFilterMode.ALL
        assertEquals(TreeFilterMode.DEFAULT, mode.next())
    }

    @Test
    fun `tool result display text reuses the matching tool call's argument summary`() {
        val manager = newSession()
        manager.appendMessage(assistantWithToolCall("call-1", "bash", """{"command":"echo hi"}"""))
        manager.appendMessage(toolResult("call-1", "bash"))

        val state = TreeSelectorState(manager)
        val resultRow = state.rows.last()
        assertEquals("[bash: echo hi]", textOf(state, resultRow))
    }

    @Test
    fun `compaction and branch summary entries render their token count and summary`() {
        val manager = newSession()
        val first = manager.appendMessage(userMessage("first"))
        manager.append(
            CompactionEntry(
                id = "c1",
                parentId = first.id,
                timestamp = Instant.now().toString(),
                summary = "recap",
                firstKeptEntryId = first.id,
                tokensBefore = 4200,
            ),
        )
        manager.branchWithSummary(first.id, "what happened before")

        val state = TreeSelectorState(manager).apply { cycleFilter(); cycleFilter(); cycleFilter() }

        assertEquals(
            listOf("user: first", "[compaction: 4k tokens]", "[branch summary]: what happened before"),
            state.rows.map { textOf(state, it) },
        )
    }

    private fun textOf(state: TreeSelectorState, row: TreeRow): String =
        buildEntryDisplaySegments(row.node, state.toolCalls).joinToString("") { it.text }

    private fun newSession(): SessionManager =
        SessionManager(createTempDirectory("agent47-tree-selector").resolve("session.jsonl"))

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = listOf(TextContent(text = text)), timestamp = System.currentTimeMillis())

    private fun assistantWithText(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text = text)),
        api = KnownApis.OpenAiResponses,
        provider = ProviderId("openai"),
        model = "mock",
        usage = emptyUsage(),
        stopReason = StopReason.STOP,
        timestamp = System.currentTimeMillis(),
    )

    private fun assistantWithToolCall(callId: String, toolName: String, argumentsJson: String): AssistantMessage = AssistantMessage(
        content = listOf(
            ToolCall(
                id = callId,
                name = toolName,
                arguments = Json.parseToJsonElement(argumentsJson) as JsonObject,
            ),
        ),
        api = KnownApis.OpenAiResponses,
        provider = ProviderId("openai"),
        model = "mock",
        usage = emptyUsage(),
        stopReason = StopReason.TOOL_USE,
        timestamp = System.currentTimeMillis(),
    )

    private fun toolResult(toolCallId: String, toolName: String): ToolResultMessage = ToolResultMessage(
        toolCallId = toolCallId,
        toolName = toolName,
        content = listOf(TextContent(text = "ok")),
        isError = false,
        timestamp = System.currentTimeMillis(),
    )
}
