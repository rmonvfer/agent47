package co.agentmode.agent47.ai.core

import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolDefinition
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

public object ToolValidation {
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val schemaFactory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    public fun validateToolArguments(
        tool: ToolDefinition,
        toolCall: ToolCall,
    ): JsonObject {
        // Models frequently send scalar arguments as strings ({"count":"5"}); coerce them to the
        // schema's declared type before validating and hand the coerced object back to the tool.
        val coerced = coerceArguments(toolCall.arguments, tool.parameters)

        val schemaNode = mapper.readTree(Json.encodeToString(JsonObject.serializer(), tool.parameters))
        val argsNode = mapper.readTree(Json.encodeToString(JsonObject.serializer(), coerced))
        val schema = schemaFactory.getSchema(schemaNode)
        val errors: Set<ValidationMessage> = schema.validate(argsNode)
        if (errors.isNotEmpty()) {
            val errorText = errors.joinToString("; ") { it.message }
            error("Invalid arguments for tool '${tool.name}': $errorText")
        }
        return coerced
    }

    private fun coerceArguments(args: JsonObject, schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject ?: return args
        return buildJsonObject {
            args.forEach { (key, value) ->
                val propSchema = properties[key] as? JsonObject
                put(key, coerceValue(value, propSchema))
            }
        }
    }

    private fun coerceValue(value: JsonElement, schema: JsonObject?): JsonElement {
        if (schema == null || value !is JsonPrimitive || !value.isString) return value
        val type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
        val text = value.content
        return when (type) {
            "integer" -> text.toLongOrNull()?.let { JsonPrimitive(it) } ?: value
            "number" -> text.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: value
            "boolean" -> text.toBooleanStrictOrNull()?.let { JsonPrimitive(it) } ?: value
            else -> value
        }
    }
}
