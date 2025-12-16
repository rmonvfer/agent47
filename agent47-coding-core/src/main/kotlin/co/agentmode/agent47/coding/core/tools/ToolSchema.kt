package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * DSL for building [ToolDefinition] instances with JSON Schema parameters.
 *
 * ```kotlin
 * toolDefinition("read", "Read a file.") {
 *     string("path") { required = true }
 *     int("offset")
 *     int("limit")
 * }
 * ```
 */
public fun toolDefinition(
    name: String,
    description: String,
    block: ParametersBuilder.() -> Unit,
): ToolDefinition {
    val builder = ParametersBuilder().apply(block)
    return ToolDefinition(
        name = name,
        description = description,
        parameters = builder.build(),
    )
}

public class ParametersBuilder {
    private val properties = mutableListOf<PropertyEntry>()
    private val requiredNames = mutableListOf<String>()

    public fun string(name: String, block: PropertyBuilder.() -> Unit = {}) {
        addProperty(name, "string", block)
    }

    public fun int(name: String, block: PropertyBuilder.() -> Unit = {}) {
        addProperty(name, "integer", block)
    }

    public fun boolean(name: String, block: PropertyBuilder.() -> Unit = {}) {
        addProperty(name, "boolean", block)
    }

    public fun enum(name: String, values: List<String>, block: PropertyBuilder.() -> Unit = {}) {
        val prop = PropertyBuilder().apply(block)
        val schema = buildJsonObject {
            put("type", "string")
            prop.description?.let { put("description", it) }
            put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
        }
        properties += PropertyEntry(name, schema)
        if (prop.required) requiredNames += name
    }

    public fun obj(name: String, block: PropertyBuilder.() -> Unit = {}) {
        addProperty(name, "object", block)
    }

    public fun array(name: String, block: ArrayPropertyBuilder.() -> Unit = {}) {
        val arrayProp = ArrayPropertyBuilder().apply(block)
        val schema = buildJsonObject {
            put("type", "array")
            arrayProp.description?.let { put("description", it) }
            arrayProp.items?.let { put("items", it) }
        }
        properties += PropertyEntry(name, schema)
        if (arrayProp.required) requiredNames += name
    }

    /**
     * Add a property with a custom schema (for properties that don't fit the
     * standard type helpers, e.g. a schema-less "result" field).
     */
    public fun custom(name: String, schema: JsonElement, required: Boolean = false) {
        properties += PropertyEntry(name, schema)
        if (required) requiredNames += name
    }

    private fun addProperty(name: String, type: String, block: PropertyBuilder.() -> Unit) {
        val prop = PropertyBuilder().apply(block)
        val schema = buildJsonObject {
            put("type", type)
            prop.description?.let { put("description", it) }
        }
        properties += PropertyEntry(name, schema)
        if (prop.required) requiredNames += name
    }

    internal fun build(): kotlinx.serialization.json.JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                properties.forEach { entry -> put(entry.name, entry.schema) }
            })
            if (requiredNames.isNotEmpty()) {
                put("required", buildJsonArray { requiredNames.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    private data class PropertyEntry(val name: String, val schema: JsonElement)
}

public class PropertyBuilder {
    public var required: Boolean = false
    public var description: String? = null
}

public class ArrayPropertyBuilder {
    public var required: Boolean = false
    public var description: String? = null
    public var items: JsonElement? = null

    /**
     * Define the schema for array items using the same DSL.
     */
    public fun items(block: ParametersBuilder.() -> Unit) {
        items = ParametersBuilder().apply(block).build()
    }
}
