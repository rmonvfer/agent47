package co.agentmode.agent47.coding.core.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolSchemaDslTest {

    @Test
    fun `toolDefinition creates definition with name and description`() {
        val def = toolDefinition("my_tool", "Does something.") {}
        assertEquals("my_tool", def.name)
        assertEquals("Does something.", def.description)
    }

    @Test
    fun `string property is added to schema`() {
        val def = toolDefinition("t", "d") {
            string("query") { description = "Search query" }
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals("string", props["query"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Search query", props["query"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `int property is added to schema`() {
        val def = toolDefinition("t", "d") {
            int("limit") { description = "Max results" }
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals("integer", props["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `boolean property is added to schema`() {
        val def = toolDefinition("t", "d") {
            boolean("verbose") { description = "Enable verbose output" }
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals("boolean", props["verbose"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enum property includes values`() {
        val def = toolDefinition("t", "d") {
            enum("format", listOf("json", "text", "csv"))
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        val enumValues = props["format"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(3, enumValues.size)
        assertEquals("json", (enumValues[0] as JsonPrimitive).content)
        assertEquals("text", (enumValues[1] as JsonPrimitive).content)
        assertEquals("csv", (enumValues[2] as JsonPrimitive).content)
    }

    @Test
    fun `required properties appear in required array`() {
        val def = toolDefinition("t", "d") {
            string("query") { required = true }
            int("limit")
            string("format") { required = true }
        }
        val required = def.parameters.jsonObject["required"]!!.jsonArray
        val names = required.map { (it as JsonPrimitive).content }
        assertTrue("query" in names)
        assertTrue("format" in names)
        assertTrue("limit" !in names)
    }

    @Test
    fun `no required array when nothing is required`() {
        val def = toolDefinition("t", "d") {
            string("query")
            int("limit")
        }
        assertEquals(def.parameters.jsonObject["required"], null)
    }

    @Test
    fun `obj property has type object`() {
        val def = toolDefinition("t", "d") {
            obj("metadata") { description = "Additional metadata" }
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals("object", props["metadata"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `array property with items`() {
        val def = toolDefinition("t", "d") {
            array("tags") {
                required = true
                description = "List of tags"
                items {
                    string("name") { required = true }
                    string("value")
                }
            }
        }
        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        val arrayProp = props["tags"]!!.jsonObject
        assertEquals("array", arrayProp["type"]!!.jsonPrimitive.content)
        assertEquals("List of tags", arrayProp["description"]!!.jsonPrimitive.content)

        val items = arrayProp["items"]!!.jsonObject
        assertEquals("object", items["type"]!!.jsonPrimitive.content)
        val itemProps = items["properties"]!!.jsonObject
        assertTrue("name" in itemProps)
        assertTrue("value" in itemProps)
    }

    @Test
    fun `multiple properties compose correctly`() {
        val def = toolDefinition("search", "Search files.") {
            string("query") { required = true; description = "Search pattern" }
            int("limit") { description = "Max results" }
            boolean("case_sensitive")
            enum("output", listOf("files", "lines", "count"))
        }

        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals(4, props.size)
        assertTrue("query" in props)
        assertTrue("limit" in props)
        assertTrue("case_sensitive" in props)
        assertTrue("output" in props)
    }
}
