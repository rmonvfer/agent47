package co.agentmode.agent47.ai.core.utils

public object OverflowUtils {
    // Provider-specific context-overflow error messages. A too-narrow set means recovery via
    // compaction never triggers for the messages providers actually return.
    private val overflowPatterns: List<Regex> = listOf(
        Regex("prompt is too long", RegexOption.IGNORE_CASE), // Anthropic token overflow
        Regex("request_too_large", RegexOption.IGNORE_CASE), // Anthropic request byte-size overflow (413)
        Regex("input is too long for requested model", RegexOption.IGNORE_CASE), // Amazon Bedrock
        Regex("exceeds the context window", RegexOption.IGNORE_CASE), // OpenAI (Completions & Responses)
        Regex(
            "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
            RegexOption.IGNORE_CASE,
        ), // OpenAI-compatible proxies (LiteLLM)
        Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE), // Google (Gemini)
        Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE), // xAI (Grok)
        Regex("reduce the length of the messages", RegexOption.IGNORE_CASE), // Groq
        Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE), // OpenRouter
        Regex("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?", RegexOption.IGNORE_CASE), // Poolside
        Regex(
            "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)",
            RegexOption.IGNORE_CASE,
        ), // Together AI
        Regex("exceeds the limit of \\d+", RegexOption.IGNORE_CASE), // GitHub Copilot
        Regex("exceeds the available context size", RegexOption.IGNORE_CASE), // llama.cpp server
        Regex("greater than the context length", RegexOption.IGNORE_CASE), // LM Studio
        Regex("context window exceeds limit", RegexOption.IGNORE_CASE), // MiniMax
        Regex("exceeded model token limit", RegexOption.IGNORE_CASE), // Kimi For Coding
        Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE), // Mistral
        Regex(
            "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
            RegexOption.IGNORE_CASE,
        ), // DS4 server
        Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE), // z.ai
        Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE), // Ollama
        Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE), // generic fallback
        Regex("too many tokens", RegexOption.IGNORE_CASE), // generic fallback
        Regex("token limit exceeded", RegexOption.IGNORE_CASE), // generic fallback
        Regex("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", RegexOption.IGNORE_CASE), // Cerebras
    )

    // Non-overflow errors excluded even when they also match an overflow pattern (e.g. a Bedrock
    // throttling error that contains "too many tokens").
    private val nonOverflowPatterns: List<Regex> = listOf(
        Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
        Regex("rate limit", RegexOption.IGNORE_CASE),
        Regex("too many requests", RegexOption.IGNORE_CASE),
    )

    public fun isContextOverflow(message: String?): Boolean {
        if (message == null) return false
        if (nonOverflowPatterns.any { it.containsMatchIn(message) }) return false
        return overflowPatterns.any { it.containsMatchIn(message) }
    }
}
