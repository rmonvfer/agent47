package co.agentmode.agent47.ai.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleSerializationTest {

    // --- sanitizeSchemaForGoogle ---

    @Test
    fun `sanitizeSchemaForGoogle strips unsupported top-level fields`() {
        val schema = buildJsonObject {
            put("\$schema", JsonPrimitive("http://json-schema.org/draft-07/schema#"))
            put("type", JsonPrimitive("object"))
            put("title", JsonPrimitive("MySchema"))
            put("format", JsonPrimitive("date-time"))
            put("examples", buildJsonArray { add(JsonPrimitive("example")) })
            put("default", JsonPrimitive("none"))
            put("pattern", JsonPrimitive("^[a-z]+$"))
            put("additionalProperties", JsonPrimitive(false))
            put("description", JsonPrimitive("A test schema"))
        }
        val result = sanitizeSchemaForGoogle(schema)
        val obj = result as JsonObject
        assertTrue(obj.containsKey("type"))
        assertTrue(obj.containsKey("description"))
        assertFalse(obj.containsKey("\$schema"))
        assertFalse(obj.containsKey("title"))
        assertFalse(obj.containsKey("format"))
        assertFalse(obj.containsKey("examples"))
        assertFalse(obj.containsKey("default"))
        assertFalse(obj.containsKey("pattern"))
        assertFalse(obj.containsKey("additionalProperties"))
    }

    @Test
    fun `sanitizeSchemaForGoogle preserves type, properties, required, description, enum, items`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("description", JsonPrimitive("desc"))
            put("required", buildJsonArray { add(JsonPrimitive("name")) })
            put("properties", buildJsonObject {
                put("name", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The name"))
                })
            })
            put("enum", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
            put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        assertTrue(result.containsKey("type"))
        assertTrue(result.containsKey("description"))
        assertTrue(result.containsKey("required"))
        assertTrue(result.containsKey("properties"))
        assertTrue(result.containsKey("enum"))
        assertTrue(result.containsKey("items"))
    }

    @Test
    fun `sanitizeSchemaForGoogle recurses into nested objects`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("address", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("title", JsonPrimitive("Address"))
                    put("format", JsonPrimitive("postal"))
                    put("properties", buildJsonObject {
                        put("street", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("pattern", JsonPrimitive("^[0-9].*"))
                        })
                    })
                })
            })
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        val address = result["properties"]!!.jsonObject["address"]!!.jsonObject
        assertFalse(address.containsKey("title"))
        assertFalse(address.containsKey("format"))
        val street = address["properties"]!!.jsonObject["street"]!!.jsonObject
        assertFalse(street.containsKey("pattern"))
        assertTrue(street.containsKey("type"))
    }

    @Test
    fun `sanitizeSchemaForGoogle recurses into arrays`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("array"))
            put("items", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("format", JsonPrimitive("email"))
                put("title", JsonPrimitive("Email"))
            })
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        val items = result["items"]!!.jsonObject
        assertTrue(items.containsKey("type"))
        assertFalse(items.containsKey("format"))
        assertFalse(items.containsKey("title"))
    }

    @Test
    fun `sanitizeSchemaForGoogle passes through primitives unchanged`() {
        val primitive = JsonPrimitive("hello")
        val result = sanitizeSchemaForGoogle(primitive)
        assertEquals(primitive, result)
    }

    @Test
    fun `sanitizeSchemaForGoogle handles empty object`() {
        val schema = buildJsonObject {}
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        assertTrue(result.isEmpty())
    }

    @Test
    fun `sanitizeSchemaForGoogle strips ref and defs`() {
        val schema = buildJsonObject {
            put("\$ref", JsonPrimitive("#/definitions/Foo"))
            put("\$defs", buildJsonObject {
                put("Foo", buildJsonObject { put("type", JsonPrimitive("string")) })
            })
            put("type", JsonPrimitive("object"))
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        assertFalse(result.containsKey("\$ref"))
        assertFalse(result.containsKey("\$defs"))
        assertTrue(result.containsKey("type"))
    }

    @Test
    fun `sanitizeSchemaForGoogle strips numeric constraint fields`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("minimum", JsonPrimitive(0))
            put("maximum", JsonPrimitive(100))
            put("exclusiveMinimum", JsonPrimitive(0))
            put("exclusiveMaximum", JsonPrimitive(100))
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        assertTrue(result.containsKey("type"))
        assertFalse(result.containsKey("minimum"))
        assertFalse(result.containsKey("maximum"))
        assertFalse(result.containsKey("exclusiveMinimum"))
        assertFalse(result.containsKey("exclusiveMaximum"))
    }

    @Test
    fun `sanitizeSchemaForGoogle strips string and array constraint fields`() {
        val schema = buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("minLength", JsonPrimitive(1))
            put("maxLength", JsonPrimitive(255))
            put("minItems", JsonPrimitive(0))
            put("maxItems", JsonPrimitive(10))
            put("patternProperties", buildJsonObject {})
        }
        val result = sanitizeSchemaForGoogle(schema) as JsonObject
        assertTrue(result.containsKey("type"))
        assertFalse(result.containsKey("minLength"))
        assertFalse(result.containsKey("maxLength"))
        assertFalse(result.containsKey("minItems"))
        assertFalse(result.containsKey("maxItems"))
        assertFalse(result.containsKey("patternProperties"))
    }
}
