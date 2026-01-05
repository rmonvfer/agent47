package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagePipelineTest {

    // --- insertSyntheticToolResults ---

    @Test
    fun `insertSyntheticToolResults passes through messages without tool calls`() {
        val messages = listOf(user("hello"), assistant("hi"))
        val result = insertSyntheticToolResults(messages)
        assertEquals(2, result.size)
        assertEquals("hello", textOf(result[0]))
        assertEquals("hi", textOf(result[1]))
    }

    @Test
    fun `insertSyntheticToolResults does not add synthetic when results are matched`() {
        val messages = listOf(
            user("do something"),
            assistantWithToolCalls(ToolCall(id = "call_1", name = "bash", arguments = JsonObject(emptyMap()))),
            toolResult("call_1", "bash", "done"),
            assistant("finished"),
        )
        val result = insertSyntheticToolResults(messages)
        assertEquals(4, result.size)
    }

    @Test
    fun `insertSyntheticToolResults adds synthetic for orphaned tool calls`() {
        val messages = listOf(
            user("do something"),
            assistantWithToolCalls(ToolCall(id = "call_1", name = "bash", arguments = JsonObject(emptyMap()))),
            assistant("continued"),
        )
        val result = insertSyntheticToolResults(messages)
        assertEquals(4, result.size)
        val synthetic = result[2] as ToolResultMessage
        assertEquals("call_1", synthetic.toolCallId)
        assertEquals("bash", synthetic.toolName)
        assertTrue(synthetic.isError)
        assertTrue(textOf(synthetic).contains("aborted"))
    }

    @Test
    fun `insertSyntheticToolResults handles multiple calls with partial matches`() {
        val messages = listOf(
            assistantWithToolCalls(
                ToolCall(id = "call_1", name = "read", arguments = JsonObject(emptyMap())),
                ToolCall(id = "call_2", name = "write", arguments = JsonObject(emptyMap())),
            ),
            toolResult("call_1", "read", "file content"),
        )
        val result = insertSyntheticToolResults(messages)
        assertEquals(3, result.size)
        val synthetic = result.filterIsInstance<ToolResultMessage>().find { it.toolCallId == "call_2" }!!
        assertTrue(synthetic.isError)
        assertTrue(textOf(synthetic).contains("aborted"))
    }

    @Test
    fun `insertSyntheticToolResults stops scanning at next assistant boundary`() {
        val messages = listOf(
            assistantWithToolCalls(ToolCall(id = "call_1", name = "bash", arguments = JsonObject(emptyMap()))),
            assistant("boundary"),
            toolResult("call_1", "bash", "late result"),
        )
        val result = insertSyntheticToolResults(messages)
        val synthetics = result.filterIsInstance<ToolResultMessage>().filter { it.isError }
        assertEquals(1, synthetics.size)
    }

    // --- defaultConvertToLlm ---

    @Test
    fun `defaultConvertToLlm passes through normal messages`() {
        val messages = listOf(user("hello"), assistant("hi"), user("thanks"))
        val result = defaultConvertToLlm(messages)
        assertEquals(3, result.size)
    }

    @Test
    fun `defaultConvertToLlm strips error turn and replaces with summary`() {
        val messages = listOf(
            user("do something"),
            assistantWithToolCalls(ToolCall(id = "call_1", name = "bash", arguments = JsonObject(emptyMap()))),
            toolResult("call_1", "bash", "command output"),
            assistantError("something broke"),
        )
        val result = defaultConvertToLlm(messages)
        val userMessages = result.filterIsInstance<UserMessage>()
        assertEquals(2, userMessages.size)
        val summary = textOf(userMessages.last())
        assertTrue(summary.contains("previous tool exchange failed"))
        assertTrue(summary.contains("bash"))
        assertTrue(summary.contains("something broke"))
    }

    @Test
    fun `defaultConvertToLlm strips non-standard message roles`() {
        val messages = listOf(
            user("hello"),
            CustomMessage(
                customType = "info",
                content = listOf(TextContent(text = "custom data")),
                timestamp = 1L,
            ),
            assistant("hi"),
        )
        val result = defaultConvertToLlm(messages)
        assertEquals(2, result.size)
        assertTrue(result.none { it is CustomMessage })
    }

    @Test
    fun `defaultConvertToLlm strips BashExecutionMessage`() {
        val messages = listOf(
            user("hello"),
            BashExecutionMessage(command = "ls", output = "files", exitCode = 0, timestamp = 1L),
            assistant("hi"),
        )
        val result = defaultConvertToLlm(messages)
        assertEquals(2, result.size)
        assertTrue(result.none { it is BashExecutionMessage })
    }

    @Test
    fun `defaultConvertToLlm handles error stripping and orphan injection together`() {
        val messages = listOf(
            user("step 1"),
            assistantWithToolCalls(ToolCall(id = "c1", name = "read", arguments = JsonObject(emptyMap()))),
            toolResult("c1", "read", "data"),
            assistantError("fail"),
            user("step 2"),
            assistantWithToolCalls(ToolCall(id = "c2", name = "write", arguments = JsonObject(emptyMap()))),
        )
        val result = defaultConvertToLlm(messages)
        val synthetics = result.filterIsInstance<ToolResultMessage>().filter { it.isError }
        assertTrue(synthetics.any { it.toolCallId == "c2" })
        val summaries = result.filterIsInstance<UserMessage>().filter { textOf(it).contains("previous tool exchange failed") }
        assertEquals(1, summaries.size)
    }

    // --- createSkippedToolResult ---

    @Test
    fun `createSkippedToolResult returns ToolResultMessage with correct fields`() {
        val toolCall = ToolCall(id = "call_42", name = "edit", arguments = JsonObject(emptyMap()))
        val result = createSkippedToolResult(toolCall)
        assertEquals("call_42", result.toolCallId)
        assertEquals("edit", result.toolName)
        assertTrue(result.isError)
        assertTrue(textOf(result).contains("Skipped"))
    }

    // --- helpers ---

    private fun user(text: String): UserMessage {
        return UserMessage(content = listOf(TextContent(text = text)), timestamp = 1L)
    }

    private fun assistant(text: String): AssistantMessage {
        return AssistantMessage(
            content = listOf(TextContent(text = text)),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("test"),
            model = "mock",
            usage = emptyUsage(),
            stopReason = StopReason.STOP,
            timestamp = 1L,
        )
    }

    private fun assistantWithToolCalls(vararg calls: ToolCall): AssistantMessage {
        return AssistantMessage(
            content = calls.toList(),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("test"),
            model = "mock",
            usage = emptyUsage(),
            stopReason = StopReason.TOOL_USE,
            timestamp = 1L,
        )
    }

    private fun assistantError(errorMsg: String): AssistantMessage {
        return AssistantMessage(
            content = listOf(TextContent(text = errorMsg)),
            api = KnownApis.OpenAiResponses,
            provider = ProviderId("test"),
            model = "mock",
            usage = emptyUsage(),
            stopReason = StopReason.ERROR,
            errorMessage = errorMsg,
            timestamp = 1L,
        )
    }

    private fun toolResult(callId: String, name: String, text: String): ToolResultMessage {
        return ToolResultMessage(
            toolCallId = callId,
            toolName = name,
            content = listOf(TextContent(text = text)),
            details = null,
            isError = false,
            timestamp = 1L,
        )
    }

    private fun textOf(message: Message): String {
        return when (message) {
            is UserMessage -> message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            is ToolResultMessage -> message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            else -> ""
        }
    }
}
