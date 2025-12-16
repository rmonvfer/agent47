package co.agentmode.agent47.ai.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
public enum class ModelInputKind {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,
}

@Serializable
public data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

@Serializable
public data class Model(
    val id: String,
    val name: String,
    val api: ApiId,
    val provider: ProviderId,
    val baseUrl: String,
    val reasoning: Boolean,
    val input: List<ModelInputKind>,
    val cost: ModelCost,
    val contextWindow: Int,
    val maxTokens: Int,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)
