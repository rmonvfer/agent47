package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.Agent47Json
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public data class SubmitResultData(
    val result: JsonElement?,
    val status: String,
    val error: String?,
)

public class SubmitResultTool(
    private val outputSchema: JsonObject?,
    private val onSubmit: (SubmitResultData) -> Unit,
) : AgentTool<Unit> {

    override val label: String = "submit_result"

    override val definition: ToolDefinition = toolDefinition(
        "submit_result",
        buildString {
            append("Submit the final result of this task. ")
            append("Call this when you have completed the assigned work. ")
            append("The result field should contain your output.")
            if (outputSchema != null) {
                append(" The result must conform to the provided output schema.")
            }
        },
    ) {
        custom("result", buildJsonObject { put("description", "The task result") }, required = true)
        enum("status", listOf("success", "aborted")) { description = "Whether the task completed successfully or was aborted" }
        string("error") { description = "Error message if the task failed" }
    }

    override suspend fun execute(
        toolCallId: String,
        parameters: JsonObject,
        onUpdate: AgentToolUpdateCallback<Unit>?,
    ): AgentToolResult<Unit> {
        val result = parameters["result"]
        val status = parameters.string("status", required = false) ?: "success"
        val error = parameters.string("error", required = false)

        if (outputSchema != null && result != null && status == "success") {
            val validationErrors = validateAgainstSchema(result, outputSchema)
            if (validationErrors.isNotEmpty()) {
                val errorMessage = buildString {
                    appendLine("Result does not match the expected output schema.")
                    appendLine("Validation errors:")
                    validationErrors.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Please fix the result and call submit_result again.")
                }
                return AgentToolResult(
                    content = listOf(TextContent(text = errorMessage)),
                    details = Unit,
                )
            }
        }

        onSubmit(SubmitResultData(result = result, status = status, error = error))

        return AgentToolResult(
            content = listOf(TextContent(text = "Result submitted successfully.")),
            details = Unit,
        )
    }

    private fun validateAgainstSchema(value: JsonElement, jtdSchema: JsonObject): List<String> {
        return runCatching {
            val jsonSchema = JtdSchema.convert(jtdSchema)
            val schemaStr = Agent47Json.encodeToString(JsonObject.serializer(), jsonSchema)
            val valueStr = Agent47Json.encodeToString(JsonElement.serializer(), value)

            val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
            val schema = factory.getSchema(schemaStr)
            val node = com.fasterxml.jackson.databind.ObjectMapper().readTree(valueStr)
            val errors = schema.validate(node)
            errors.map { it.message }
        }.getOrElse { listOf("Schema validation failed: ${it.message}") }
    }
}
