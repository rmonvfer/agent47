package co.agentmode.agent47.ai.core.utils

import co.agentmode.agent47.ai.types.Model

public object ModelUtils {
    public fun supportsXhigh(model: Model): Boolean {
        val id = model.id.lowercase()
        return id.contains("gpt-5.1-codex-max") ||
            id.contains("gpt-5.2") ||
            id.contains("gpt-5.2-codex") ||
            id.contains("gpt-5.3") ||
            id.contains("gpt-5.3-codex")
    }

    public fun modelsAreEqual(a: Model?, b: Model?): Boolean {
        if (a == null || b == null) {
            return a == b
        }
        return a.id == b.id &&
            a.provider == b.provider &&
            a.api == b.api &&
            a.baseUrl == b.baseUrl
    }
}
