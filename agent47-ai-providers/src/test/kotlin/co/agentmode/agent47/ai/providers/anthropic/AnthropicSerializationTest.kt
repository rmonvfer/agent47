package co.agentmode.agent47.ai.providers.anthropic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicSerializationTest {

    // --- normalizeAnthropicToolId ---

    @Test
    fun `normalizeAnthropicToolId passes through clean id`() {
        assertEquals("call_abc-123", normalizeAnthropicToolId("call_abc-123"))
    }

    @Test
    fun `normalizeAnthropicToolId replaces non-alphanumeric non-underscore non-hyphen with underscore`() {
        assertEquals("call_abc_123_", normalizeAnthropicToolId("call.abc@123!"))
    }

    @Test
    fun `normalizeAnthropicToolId truncates to 40 chars`() {
        val longId = "a".repeat(50)
        val result = normalizeAnthropicToolId(longId)
        assertEquals(40, result.length)
        assertEquals("a".repeat(40), result)
    }

    @Test
    fun `normalizeAnthropicToolId does not truncate short ids`() {
        assertEquals("short", normalizeAnthropicToolId("short"))
    }

    // --- applyCacheBreakpoints ---

    @Test
    fun `applyCacheBreakpoints adds cache_control to last tool definition`() {
        val payload = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", JsonPrimitive("tool_a")) })
                add(buildJsonObject { put("name", JsonPrimitive("tool_b")) })
            })
            put("messages", buildJsonArray {})
        }
        val result = applyCacheBreakpoints(payload)
        val tools = result["tools"]!!.jsonArray
        val lastTool = tools.last().jsonObject
        assertTrue(lastTool.containsKey("cache_control"))
        assertEquals("ephemeral", lastTool["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `applyCacheBreakpoints converts string system prompt to block array with cache_control`() {
        val payload = buildJsonObject {
            put("system", JsonPrimitive("You are helpful"))
            put("messages", buildJsonArray {})
        }
        val result = applyCacheBreakpoints(payload)
        val system = result["system"]!!.jsonArray
        assertEquals(1, system.size)
        val block = system[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", block["text"]!!.jsonPrimitive.content)
        assertTrue(block.containsKey("cache_control"))
    }

    @Test
    fun `applyCacheBreakpoints adds cache_control to penultimate and final user messages`() {
        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("first")) })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("reply")) })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("second")) })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("third")) })
                    })
                })
            })
        }
        val result = applyCacheBreakpoints(payload)
        val messages = result["messages"]!!.jsonArray

        val secondUser = messages[2].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertTrue(secondUser.containsKey("cache_control"), "penultimate user message should have cache_control")

        val thirdUser = messages[3].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertTrue(thirdUser.containsKey("cache_control"), "final user message should have cache_control")
    }

    @Test
    fun `applyCacheBreakpoints respects 4-breakpoint limit`() {
        val payload = buildJsonObject {
            put("tools", buildJsonArray {
                add(buildJsonObject { put("name", JsonPrimitive("tool_a")) })
            })
            put("system", JsonPrimitive("sys"))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("msg1")) })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("msg2")) })
                    })
                })
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("msg3")) })
                    })
                })
            })
        }
        val result = applyCacheBreakpoints(payload)

        var cacheCount = 0
        val tools = result["tools"]?.jsonArray
        tools?.forEach { tool ->
            if (tool.jsonObject.containsKey("cache_control")) cacheCount++
        }
        val system = result["system"]?.jsonArray
        system?.forEach { block ->
            if (block.jsonObject.containsKey("cache_control")) cacheCount++
        }
        val messages = result["messages"]?.jsonArray
        messages?.forEach { msg ->
            val content = msg.jsonObject["content"]?.jsonArray
            content?.forEach { block ->
                if (block.jsonObject.containsKey("cache_control")) cacheCount++
            }
        }
        assertTrue(cacheCount <= 4, "Should not exceed 4 cache breakpoints, got $cacheCount")
    }

    @Test
    fun `applyCacheBreakpoints handles missing tools gracefully`() {
        val payload = buildJsonObject {
            put("system", JsonPrimitive("sys"))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("hi")) })
                    })
                })
            })
        }
        val result = applyCacheBreakpoints(payload)
        assertTrue(result.containsKey("system"))
        assertTrue(result.containsKey("messages"))
    }

    @Test
    fun `applyCacheBreakpoints handles missing system prompt gracefully`() {
        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive("hi")) })
                    })
                })
            })
        }
        val result = applyCacheBreakpoints(payload)
        val content = result["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray.last().jsonObject
        assertTrue(content.containsKey("cache_control"))
    }

    @Test
    fun `applyCacheBreakpoints handles empty messages gracefully`() {
        val payload = buildJsonObject {
            put("messages", buildJsonArray {})
        }
        val result = applyCacheBreakpoints(payload)
        assertEquals(0, result["messages"]!!.jsonArray.size)
    }
}
