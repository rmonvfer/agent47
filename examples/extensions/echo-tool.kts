import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

registerTool(object : AgentTool<JsonObject> {
    override val label: String = "Echo"
    override val definition: ToolDefinition = ToolDefinition(
        name = "echo",
        description = "Return the supplied text",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("text"))))
        },
    )

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<JsonObject>?,
    ): AgentToolResult<JsonObject> = AgentToolResult(
        content = listOf(TextContent(text = parameters.getValue("text").jsonPrimitive.content)),
        details = parameters,
    )
})
