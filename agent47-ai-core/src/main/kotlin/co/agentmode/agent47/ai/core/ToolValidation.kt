package co.agentmode.agent47.ai.core

import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolDefinition
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.ValidationMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

public object ToolValidation {
    private val mapper: com.fasterxml.jackson.databind.ObjectMapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val schemaFactory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    public fun validateToolArguments(
        tool: ToolDefinition,
        toolCall: ToolCall,
    ): JsonObject {
        val schemaNode = mapper.readTree(Json.encodeToString(JsonObject.serializer(), tool.parameters))
        val argsNode = mapper.readTree(Json.encodeToString(JsonObject.serializer(), toolCall.arguments))
        val schema = schemaFactory.getSchema(schemaNode)
        val errors: Set<ValidationMessage> = schema.validate(argsNode)
        if (errors.isNotEmpty()) {
            val errorText = errors.joinToString("; ") { it.message }
            error("Invalid arguments for tool '${tool.name}': $errorText")
        }
        return toolCall.arguments
    }
}
