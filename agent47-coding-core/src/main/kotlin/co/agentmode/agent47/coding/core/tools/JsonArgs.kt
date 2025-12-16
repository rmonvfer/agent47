package co.agentmode.agent47.coding.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun JsonObject.string(name: String, required: Boolean = true): String? {
    val value = this[name] as? JsonPrimitive
    val content = value?.contentOrNull
    if (required && content == null) {
        error("Missing required argument: $name")
    }
    return content
}

internal fun JsonObject.int(name: String, required: Boolean = false): Int? {
    val value = this[name] as? JsonPrimitive
    val parsed = value?.intOrNull
    if (required && parsed == null) {
        error("Missing required integer argument: $name")
    }
    return parsed
}

internal fun JsonObject.boolean(name: String, required: Boolean = false): Boolean? {
    val value = this[name] as? JsonPrimitive
    val parsed = value?.booleanOrNull
    if (required && parsed == null) {
        error("Missing required boolean argument: $name")
    }
    return parsed
}

private val JsonPrimitive.contentOrNull: String?
    get() = runCatching { content }.getOrNull()

private val JsonPrimitive.booleanOrNull: Boolean?
    get() = contentOrNull?.toBooleanStrictOrNull()
