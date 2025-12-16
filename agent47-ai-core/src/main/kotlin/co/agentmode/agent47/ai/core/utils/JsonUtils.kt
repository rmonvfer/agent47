package co.agentmode.agent47.ai.core.utils

import co.agentmode.agent47.ai.types.Agent47Json
import kotlinx.serialization.json.JsonElement

public object JsonUtils {
    public fun parsePartialJson(raw: String): JsonElement? {
        if (raw.isBlank()) {
            return null
        }

        val trimmed = raw.trim()
        val direct = runCatching { Agent47Json.parseToJsonElement(trimmed) }.getOrNull()
        if (direct != null) {
            return direct
        }

        for (end in trimmed.length downTo 2) {
            val candidate = trimmed.substring(0, end)
            val parsed = runCatching { Agent47Json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed != null) {
                return parsed
            }
        }

        return null
    }
}
