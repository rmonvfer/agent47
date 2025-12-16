package co.agentmode.agent47.ai.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class ThinkingLevel {
    @SerialName("minimal")
    MINIMAL,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("xhigh")
    XHIGH,
}

@Serializable
public data class ThinkingBudgets(
    val minimal: Int? = null,
    val low: Int? = null,
    val medium: Int? = null,
    val high: Int? = null,
)
