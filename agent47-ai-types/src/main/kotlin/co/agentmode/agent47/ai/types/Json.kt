package co.agentmode.agent47.ai.types

import kotlinx.serialization.json.Json

public val Agent47Json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    classDiscriminator = "type"
}
