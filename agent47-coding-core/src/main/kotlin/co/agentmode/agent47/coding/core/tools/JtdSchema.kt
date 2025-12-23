package co.agentmode.agent47.coding.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts a JTD (JSON Type Definition) schema to JSON Schema.
 *
 * JTD is a simpler schema format. This converter supports:
 * - type (primitives): boolean, string, float32/64, int8/16/32, uint8/16/32, timestamp
 * - enum: enumeration of string values
 * - elements: typed arrays
 * - properties / optionalProperties: object shapes
 * - values: typed maps
 * - discriminator: tagged unions
 * - ref: schema references via definitions
 */
public object JtdSchema {

    private val JTD_TYPE_MAP: Map<String, JsonObject> = mapOf(
        "boolean" to typeObj("boolean"),
        "string" to typeObj("string"),
        "timestamp" to typeObj("string", "date-time"),
        "float32" to typeObj("number"),
        "float64" to typeObj("number"),
        "int8" to typeObj("integer"),
        "int16" to typeObj("integer"),
        "int32" to typeObj("integer"),
        "uint8" to typeObj("integer"),
        "uint16" to typeObj("integer"),
        "uint32" to typeObj("integer"),
    )

    public fun convert(jtd: JsonObject): JsonObject {
        val definitions = jtd["definitions"]?.jsonObject
        val root = convertNode(jtd, definitions)

        if (definitions.isNullOrEmpty()) {
            return root
        }

        val defs = buildJsonObject {
            for ((key, value) in definitions) {
                put(key, convertNode(value.jsonObject, definitions))
            }
        }

        return buildJsonObject {
            for ((key, value) in root) {
                put(key, value)
            }
            put($$"$defs", defs)
        }
    }

    private fun convertNode(node: JsonObject, definitions: JsonObject?): JsonObject {
        val nullable = node["nullable"]?.jsonPrimitive?.content == "true"

        val base = when {
            node.containsKey("ref") -> {
                val ref = node["ref"]!!.jsonPrimitive.content
                buildJsonObject { put($$"$ref", JsonPrimitive($$"#/$defs/$$ref")) }
            }

            node.containsKey("type") -> {
                val typeName = node["type"]!!.jsonPrimitive.content
                JTD_TYPE_MAP[typeName] ?: typeObj("string")
            }

            node.containsKey("enum") -> {
                val values = node["enum"]!!.jsonArray
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", values)
                }
            }

            node.containsKey("elements") -> {
                val items = convertNode(node["elements"]!!.jsonObject, definitions)
                buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("items", items)
                }
            }

            node.containsKey("values") -> {
                val additional = convertNode(node["values"]!!.jsonObject, definitions)
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", additional)
                }
            }

            node.containsKey("properties") || node.containsKey("optionalProperties") -> {
                convertProperties(node, definitions)
            }

            node.containsKey("discriminator") -> {
                convertDiscriminator(node, definitions)
            }

            else -> buildJsonObject {}
        }

        if (!nullable) return base

        return buildJsonObject {
            put("oneOf", buildJsonArray {
                add(base)
                add(buildJsonObject { put("type", JsonPrimitive("null")) })
            })
        }
    }

    private fun convertProperties(node: JsonObject, definitions: JsonObject?): JsonObject {
        val props = node["properties"]?.jsonObject.orEmpty()
        val optionalProps = node["optionalProperties"]?.jsonObject.orEmpty()

        val properties = buildJsonObject {
            for ((key, value) in props) {
                put(key, convertNode(value.jsonObject, definitions))
            }
            for ((key, value) in optionalProps) {
                put(key, convertNode(value.jsonObject, definitions))
            }
        }

        val required = props.keys.toList()

        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
            put("additionalProperties", JsonPrimitive(false))
        }
    }

    private fun convertDiscriminator(node: JsonObject, definitions: JsonObject?): JsonObject {
        val tag = node["discriminator"]!!.jsonPrimitive.content
        val mapping = node["mapping"]?.jsonObject.orEmpty()

        val oneOf = buildJsonArray {
            for ((key, schema) in mapping) {
                val converted = convertNode(schema.jsonObject, definitions)
                val merged = buildJsonObject {
                    for ((k, v) in converted) {
                        put(k, v)
                    }
                    val existingProperties = converted["properties"]?.jsonObject.orEmpty()
                    put("properties", buildJsonObject {
                        for ((k, v) in existingProperties) {
                            put(k, v)
                        }
                        put(tag, buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("const", JsonPrimitive(key))
                        })
                    })
                    val existingRequired = converted["required"]?.jsonArray
                        ?.map { it.jsonPrimitive.content }
                        ?: emptyList()
                    put("required", JsonArray((existingRequired + tag).distinct().map { JsonPrimitive(it) }))
                }
                add(merged)
            }
        }

        return buildJsonObject { put("oneOf", oneOf) }
    }

    private fun typeObj(type: String, format: String? = null): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive(type))
            if (format != null) {
                put("format", JsonPrimitive(format))
            }
        }
    }
}
