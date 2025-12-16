package co.agentmode.agent47.ai.types

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
public value class ApiId(public val value: String) {
    override fun toString(): String = value
}

@Serializable
@JvmInline
public value class ProviderId(public val value: String) {
    override fun toString(): String = value
}

public object KnownApis {
    public val OpenAiCompletions: ApiId = ApiId("openai-completions")
    public val OpenAiResponses: ApiId = ApiId("openai-responses")
    public val OpenAiCodexResponses: ApiId = ApiId("openai-codex-responses")
    public val AnthropicMessages: ApiId = ApiId("anthropic-messages")
    public val GoogleGenerativeAi: ApiId = ApiId("google-generative-ai")
    public val GoogleGeminiCli: ApiId = ApiId("google-gemini-cli")
    public val GoogleAntigravity: ApiId = ApiId("google-antigravity")
    public val GoogleVertex: ApiId = ApiId("google-vertex")
    public val BedrockConverseStream: ApiId = ApiId("bedrock-converse-stream")
    public val CursorAgent: ApiId = ApiId("cursor-agent")
    public val AzureOpenAiResponses: ApiId = ApiId("azure-openai-responses")
}

public object KnownProviders {
    public val OpenAi: ProviderId = ProviderId("openai")
    public val OpenAiCodex: ProviderId = ProviderId("openai-codex")
    public val Anthropic: ProviderId = ProviderId("anthropic")
    public val Google: ProviderId = ProviderId("google")
    public val GoogleGeminiCli: ProviderId = ProviderId("google-gemini-cli")
    public val GoogleAntigravity: ProviderId = ProviderId("google-antigravity")
    public val GoogleVertex: ProviderId = ProviderId("google-vertex")
    public val AmazonBedrock: ProviderId = ProviderId("amazon-bedrock")
    public val OpenRouter: ProviderId = ProviderId("openrouter")
    public val VercelAiGateway: ProviderId = ProviderId("vercel-ai-gateway")
    public val GitHubCopilot: ProviderId = ProviderId("github-copilot")
    public val Cursor: ProviderId = ProviderId("cursor")
    public val XAi: ProviderId = ProviderId("xai")
    public val Groq: ProviderId = ProviderId("groq")
    public val Cerebras: ProviderId = ProviderId("cerebras")
    public val Zai: ProviderId = ProviderId("zai")
    public val Mistral: ProviderId = ProviderId("mistral")
    public val MiniMax: ProviderId = ProviderId("minimax")
    public val MiniMaxCode: ProviderId = ProviderId("minimax-code")
    public val MiniMaxCodeCn: ProviderId = ProviderId("minimax-code-cn")
    public val OpenCode: ProviderId = ProviderId("opencode")
    public val KimiCode: ProviderId = ProviderId("kimi-code")
}
