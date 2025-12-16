package co.agentmode.agent47.ai.types

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class ModelRole(public val id: String) {
    @SerialName("default")
    DEFAULT("default"),

    @SerialName("smol")
    SMOL("smol"),

    @SerialName("slow")
    SLOW("slow"),

    @SerialName("plan")
    PLAN("plan"),

    @SerialName("commit")
    COMMIT("commit"),
}
