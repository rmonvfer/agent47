package co.agentmode.agent47.ai.core.utils

public object OverflowUtils {
    private val knownOverflowPatterns: List<Regex> = listOf(
        Regex("context length", RegexOption.IGNORE_CASE),
        Regex("too many tokens", RegexOption.IGNORE_CASE),
        Regex("maximum context", RegexOption.IGNORE_CASE),
        Regex("input is too long", RegexOption.IGNORE_CASE),
    )

    public fun isContextOverflow(message: String?): Boolean {
        if (message == null) {
            return false
        }
        return knownOverflowPatterns.any { pattern -> pattern.containsMatchIn(message) }
    }
}
