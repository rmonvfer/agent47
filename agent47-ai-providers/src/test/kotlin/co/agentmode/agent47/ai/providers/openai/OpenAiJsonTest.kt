package co.agentmode.agent47.ai.providers.openai

import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiJsonTest {

    private val defaultCompat = OpenAiCompat(
        supportsDeveloperRole = true,
        maxTokensField = "max_completion_tokens",
        requiresMistralToolIds = false,
        requiresThinkingAsText = false,
        supportsStreamOptions = true,
    )

    private val mistralCompat = OpenAiCompat(
        supportsDeveloperRole = false,
        maxTokensField = "max_tokens",
        requiresMistralToolIds = true,
        requiresThinkingAsText = true,
        supportsStreamOptions = false,
    )

    // --- buildOpenAiMessages ---

    @Test
    fun `buildOpenAiMessages uses system role by default`() {
        val context = Context(systemPrompt = "You are helpful", messages = emptyList())
        val model = model(reasoning = false)
        val result = buildOpenAiMessages(context, model, defaultCompat)
        val first = result[0].jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", first["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildOpenAiMessages uses developer role for reasoning model with compat support`() {
        val context = Context(systemPrompt = "You are helpful", messages = emptyList())
        val model = model(reasoning = true)
        val result = buildOpenAiMessages(context, model, defaultCompat)
        val first = result[0].jsonObject
        assertEquals("developer", first["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildOpenAiMessages omits system prompt when blank`() {
        val context = Context(systemPrompt = "", messages = listOf(user("hi")))
        val result = buildOpenAiMessages(context, model(), defaultCompat)
        assertEquals(1, result.size)
        assertEquals("user", result[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildOpenAiMessages omits system prompt when null`() {
        val context = Context(systemPrompt = null, messages = listOf(user("hi")))
        val result = buildOpenAiMessages(context, model(), defaultCompat)
        assertEquals(1, result.size)
    }

    @Test
    fun `buildOpenAiMessages serializes UserMessage correctly`() {
        val context = Context(messages = listOf(user("hello")))
        val result = buildOpenAiMessages(context, model(), defaultCompat)
        val msg = result[0].jsonObject
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildOpenAiMessages serializes assistant with tool calls`() {
        val toolCall = ToolCall(id = "call_abc", name = "bash", arguments = buildJsonObject { put("cmd", JsonPrimitive("ls")) })
        val assistantMsg = AssistantMessage(
            content = listOf(TextContent(text = "running"), toolCall),
            api = KnownApis.OpenAiCompletions,
            provider = ProviderId("openai"),
            model = "gpt-4",
            usage = emptyUsage(),
            stopReason = StopReason.TOOL_USE,
            timestamp = 1L,
        )
        val context = Context(messages = listOf(assistantMsg))
        val result = buildOpenAiMessages(context, model(), defaultCompat)
        val msg = result[0].jsonObject
        assertEquals("assistant", msg["role"]!!.jsonPrimitive.content)
        val toolCalls = msg["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].jsonObject
        assertEquals("call_abc", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        assertEquals("bash", tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildOpenAiMessages serializes ToolResultMessage correctly`() {
        val toolResultMsg = ToolResultMessage(
            toolCallId = "call_abc",
            toolName = "bash",
            content = listOf(TextContent(text = "output")),
            details = null,
            isError = false,
            timestamp = 1L,
        )
        val context = Context(messages = listOf(toolResultMsg))
        val result = buildOpenAiMessages(context, model(), defaultCompat)
        val msg = result[0].jsonObject
        assertEquals("tool", msg["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", msg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("bash", msg["name"]!!.jsonPrimitive.content)
    }

    // --- normalizeToolCallId ---

    @Test
    fun `normalizeToolCallId returns id unchanged for non-Mistral`() {
        assertEquals("call_abc-123", normalizeToolCallId("call_abc-123", defaultCompat))
    }

    @Test
    fun `normalizeToolCallId strips non-alphanumeric and truncates for Mistral`() {
        assertEquals("callxyz12", normalizeToolCallId("call_xyz-123", mistralCompat))
    }

    @Test
    fun `normalizeToolCallId pads short ids for Mistral`() {
        assertEquals("abc000000", normalizeToolCallId("abc", mistralCompat))
    }

    @Test
    fun `normalizeToolCallId truncates long ids for Mistral`() {
        assertEquals("abcdefghi", normalizeToolCallId("abcdefghijklmnop", mistralCompat))
    }

    // --- contentToText ---

    @Test
    fun `contentToText converts TextContent to plain text`() {
        val result = contentToText(listOf(TextContent(text = "hello world")))
        assertEquals("hello world", result)
    }

    @Test
    fun `contentToText wraps ThinkingContent in thinking tags`() {
        val result = contentToText(listOf(ThinkingContent(thinking = "let me think")))
        assertTrue(result.contains("<thinking>"))
        assertTrue(result.contains("let me think"))
        assertTrue(result.contains("</thinking>"))
    }

    @Test
    fun `contentToText wraps ToolCall in tool_call tags`() {
        val call = ToolCall(id = "c1", name = "bash", arguments = buildJsonObject { put("cmd", JsonPrimitive("ls")) })
        val result = contentToText(listOf(call))
        assertTrue(result.contains("<tool_call"))
        assertTrue(result.contains("bash"))
    }

    @Test
    fun `contentToText joins multiple blocks with newline`() {
        val result = contentToText(listOf(TextContent(text = "one"), TextContent(text = "two")))
        assertEquals("one\ntwo", result)
    }

    // --- parseToolCallsFromCompletions ---

    @Test
    fun `parseToolCallsFromCompletions parses valid tool calls`() {
        val json = Json.parseToJsonElement("""
            {
                "message": {
                    "tool_calls": [
                        {
                            "id": "call_1",
                            "function": {
                                "name": "bash",
                                "arguments": "{\"cmd\":\"ls\"}"
                            }
                        }
                    ]
                }
            }
        """).jsonObject
        val result = parseToolCallsFromCompletions(json)
        assertEquals(1, result.size)
        assertEquals("call_1", result[0].id)
        assertEquals("bash", result[0].name)
        assertEquals(JsonPrimitive("ls"), result[0].arguments["cmd"])
    }

    @Test
    fun `parseToolCallsFromCompletions returns empty when no tool_calls`() {
        val json = Json.parseToJsonElement("""
            {
                "message": {
                    "content": "just text"
                }
            }
        """).jsonObject
        val result = parseToolCallsFromCompletions(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseToolCallsFromCompletions handles malformed arguments gracefully`() {
        val json = Json.parseToJsonElement("""
            {
                "message": {
                    "tool_calls": [
                        {
                            "id": "call_1",
                            "function": {
                                "name": "bash",
                                "arguments": "not valid json {{"
                            }
                        }
                    ]
                }
            }
        """).jsonObject
        val result = parseToolCallsFromCompletions(json)
        assertEquals(1, result.size)
        assertEquals("bash", result[0].name)
        assertTrue(result[0].arguments.isEmpty())
    }

    // --- resolveOpenAiCompat ---

    @Test
    fun `resolveOpenAiCompat default provider supports developer role`() {
        val m = model(provider = KnownProviders.OpenAi)
        val compat = resolveOpenAiCompat(m)
        assertTrue(compat.supportsDeveloperRole)
        assertFalse(compat.requiresMistralToolIds)
    }

    @Test
    fun `resolveOpenAiCompat Mistral gets requiresMistralToolIds and max_tokens`() {
        val m = model(provider = KnownProviders.Mistral)
        val compat = resolveOpenAiCompat(m)
        assertTrue(compat.requiresMistralToolIds)
        assertEquals("max_tokens", compat.maxTokensField)
        assertTrue(compat.requiresThinkingAsText)
        assertFalse(compat.supportsStreamOptions)
    }

    @Test
    fun `resolveOpenAiCompat XAi does not support developer role`() {
        val m = model(provider = KnownProviders.XAi)
        val compat = resolveOpenAiCompat(m)
        assertFalse(compat.supportsDeveloperRole)
    }

    @Test
    fun `resolveOpenAiCompat Cerebras does not support developer role`() {
        val m = model(provider = KnownProviders.Cerebras)
        val compat = resolveOpenAiCompat(m)
        assertFalse(compat.supportsDeveloperRole)
    }

    @Test
    fun `resolveOpenAiCompat compat flags overridable from model compat JSON`() {
        val m = model(
            provider = KnownProviders.OpenAi,
            compat = buildJsonObject {
                put("supportsDeveloperRole", JsonPrimitive(false))
            },
        )
        val result = resolveOpenAiCompat(m)
        assertFalse(result.supportsDeveloperRole)
    }

    // --- helpers ---

    private fun model(
        reasoning: Boolean = false,
        provider: ProviderId = KnownProviders.OpenAi,
        compat: JsonObject? = null,
    ): Model {
        return Model(
            id = "test-model",
            name = "Test",
            api = KnownApis.OpenAiCompletions,
            provider = provider,
            baseUrl = "https://api.example.com",
            reasoning = reasoning,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
            compat = compat,
        )
    }

    private fun user(text: String): UserMessage {
        return UserMessage(content = listOf(TextContent(text = text)), timestamp = 1L)
    }
}
