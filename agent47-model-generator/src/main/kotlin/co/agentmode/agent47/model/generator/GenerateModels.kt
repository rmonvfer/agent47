package co.agentmode.agent47.model.generator

import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

private val httpClient: HttpClient = HttpClient.newBuilder().build()

private fun fetchJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? {
    return try {
        val builder = HttpRequest.newBuilder().uri(URI.create(url)).GET()
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            System.err.println("HTTP ${response.statusCode()} from $url")
            return null
        }
        json.parseToJsonElement(response.body())
    } catch (e: Exception) {
        System.err.println("Failed to fetch $url: ${e.message}")
        null
    }
}

private fun JsonElement?.str(key: String): String? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

private fun JsonElement?.int(key: String): Int? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.intOrNull

private fun JsonElement?.long(key: String): Long? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.longOrNull

private fun JsonElement?.double(key: String): Double? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.doubleOrNull

private fun JsonElement?.bool(key: String): Boolean? =
    (this as? JsonObject)?.get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonElement?.obj(key: String): JsonObject? =
    (this as? JsonObject)?.get(key)?.jsonObject

private fun JsonElement?.arr(key: String): JsonArray? =
    (this as? JsonObject)?.get(key)?.jsonArray

private fun JsonArray?.strList(): List<String> =
    this?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

private fun modelsDevInput(m: JsonObject): List<ModelInputKind> {
    val modalities = m.obj("modalities")?.arr("input")?.strList() ?: emptyList()
    return if (modalities.contains("image")) listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)
    else listOf(ModelInputKind.TEXT)
}

private fun modelsDevCost(m: JsonObject): ModelCost {
    val cost = m.obj("cost")
    return ModelCost(
        input = cost.double("input") ?: 0.0,
        output = cost.double("output") ?: 0.0,
        cacheRead = cost.double("cache_read") ?: 0.0,
        cacheWrite = cost.double("cache_write") ?: 0.0,
    )
}

private fun modelsDevContext(m: JsonObject): Int =
    m.obj("limit").int("context") ?: 4096

private fun modelsDevMaxTokens(m: JsonObject): Int =
    m.obj("limit").int("output") ?: 4096

private fun modelsDevReasoning(m: JsonObject): Boolean =
    m.bool("reasoning") == true

private fun modelsDevName(m: JsonObject, id: String): String =
    m.str("name") ?: id

private fun isToolCapable(m: JsonObject): Boolean =
    m.bool("tool_call") == true

private fun processModelsDevProvider(
    data: JsonObject,
    providerKey: String,
    providerId: String,
    apiId: String,
    baseUrl: String,
    defaultContext: Int = 4096,
    defaultMaxTokens: Int = 4096,
    skipFilter: ((String, JsonObject) -> Boolean)? = null,
    transformId: ((String) -> String)? = null,
    extraCompat: JsonObject? = null,
    extraHeaders: Map<String, String>? = null,
): List<Model> {
    val models = mutableListOf<Model>()
    val providerData = data.obj(providerKey) ?: return models
    val modelsObj = providerData.obj("models") ?: return models

    for ((modelId, element) in modelsObj) {
        val m = element as? JsonObject ?: continue
        if (!isToolCapable(m)) continue
        if (skipFilter?.invoke(modelId, m) == true) continue

        val id = transformId?.invoke(modelId) ?: modelId

        models += Model(
            id = id,
            name = modelsDevName(m, id),
            api = ApiId(apiId),
            provider = ProviderId(providerId),
            baseUrl = baseUrl,
            reasoning = modelsDevReasoning(m),
            input = modelsDevInput(m),
            cost = modelsDevCost(m),
            contextWindow = m.obj("limit").int("context") ?: defaultContext,
            maxTokens = m.obj("limit").int("output") ?: defaultMaxTokens,
            compat = extraCompat,
            headers = extraHeaders,
        )
    }
    return models
}

private val COPILOT_STATIC_HEADERS = mapOf(
    "User-Agent" to "GitHubCopilotChat/0.35.0",
    "Editor-Version" to "vscode/1.107.0",
    "Editor-Plugin-Version" to "copilot-chat/0.35.0",
    "Copilot-Integration-Id" to "vscode-chat",
    "Openai-Intent" to "conversation-edits",
)

private val KIMI_CODE_HEADERS = mapOf(
    "User-Agent" to "KimiCLI/1.0",
    "X-Msh-Platform" to "kimi_cli",
)

private val KIMI_COMPAT = buildJsonObject {
    put("thinkingFormat", "zai")
    put("reasoningContentField", "reasoning_content")
    put("supportsDeveloperRole", false)
}

private val ZAI_COMPAT = buildJsonObject {
    put("supportsDeveloperRole", false)
    put("thinkingFormat", "zai")
}

private val MINIMAX_CODE_COMPAT = buildJsonObject {
    put("supportsDeveloperRole", false)
    put("thinkingFormat", "zai")
    put("reasoningContentField", "reasoning_content")
}

fun loadModelsDevData(): List<Model> {
    println("Fetching models from models.dev API...")
    val root = fetchJson("https://models.dev/api.json")?.jsonObject ?: return emptyList()
    val models = mutableListOf<Model>()

    // Amazon Bedrock
    val bedrockModels = root.obj("amazon-bedrock")?.obj("models")
    if (bedrockModels != null) {
        for ((modelId, element) in bedrockModels) {
            val m = element as? JsonObject ?: continue
            if (!isToolCapable(m)) continue

            if (modelId.startsWith("ai21.jamba")) continue
            if (modelId.startsWith("amazon.titan-text-express") || modelId.startsWith("mistral.mistral-7b-instruct-v0")) continue

            var id = modelId
            // Global cross-region inference profiles
            if (modelId.startsWith("anthropic.claude-haiku-4-5") ||
                modelId.startsWith("anthropic.claude-sonnet-4") ||
                modelId.startsWith("anthropic.claude-opus-4-5") ||
                modelId.startsWith("amazon.nova-2-lite") ||
                modelId.startsWith("cohere.embed-v4") ||
                modelId.startsWith("twelvelabs.pegasus-1-2")
            ) {
                id = "global.$modelId"
            }

            // US cross-region inference profiles
            if (modelId.startsWith("amazon.nova-lite") ||
                modelId.startsWith("amazon.nova-micro") ||
                modelId.startsWith("amazon.nova-premier") ||
                modelId.startsWith("amazon.nova-pro") ||
                modelId.startsWith("anthropic.claude-3-7-sonnet") ||
                modelId.startsWith("anthropic.claude-opus-4-1") ||
                modelId.startsWith("anthropic.claude-opus-4-20250514") ||
                modelId.startsWith("deepseek.r1") ||
                modelId.startsWith("meta.llama3-2") ||
                modelId.startsWith("meta.llama3-3") ||
                modelId.startsWith("meta.llama4")
            ) {
                id = "us.$modelId"
            }

            val bedrockModel = Model(
                id = id,
                name = modelsDevName(m, id),
                api = ApiId("bedrock-converse-stream"),
                provider = ProviderId("amazon-bedrock"),
                baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                reasoning = modelsDevReasoning(m),
                input = modelsDevInput(m),
                cost = modelsDevCost(m),
                contextWindow = modelsDevContext(m),
                maxTokens = modelsDevMaxTokens(m),
            )
            models += bedrockModel

            // EU cross-region inference variants for Claude models
            if (modelId.startsWith("anthropic.claude-haiku-4-5") ||
                modelId.startsWith("anthropic.claude-sonnet-4-5") ||
                modelId.startsWith("anthropic.claude-opus-4-5")
            ) {
                models += bedrockModel.copy(
                    id = "eu.$modelId",
                    name = (m.str("name") ?: modelId) + " (EU)",
                )
            }
        }
    }

    // Anthropic
    models += processModelsDevProvider(
        data = root,
        providerKey = "anthropic",
        providerId = "anthropic",
        apiId = "anthropic-messages",
        baseUrl = "https://api.anthropic.com",
        skipFilter = { id, _ ->
            id.startsWith("claude-3-5-haiku") ||
                id.startsWith("claude-3-7-sonnet") ||
                id == "claude-3-opus-20240229" ||
                id == "claude-3-sonnet-20240229"
        },
    )

    // Google
    models += processModelsDevProvider(
        data = root,
        providerKey = "google",
        providerId = "google",
        apiId = "google-generative-ai",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    )

    // OpenAI
    models += processModelsDevProvider(
        data = root,
        providerKey = "openai",
        providerId = "openai",
        apiId = "openai-responses",
        baseUrl = "https://api.openai.com/v1",
    )

    // Groq
    models += processModelsDevProvider(
        data = root,
        providerKey = "groq",
        providerId = "groq",
        apiId = "openai-completions",
        baseUrl = "https://api.groq.com/openai/v1",
    )

    // Cerebras
    models += processModelsDevProvider(
        data = root,
        providerKey = "cerebras",
        providerId = "cerebras",
        apiId = "openai-completions",
        baseUrl = "https://api.cerebras.ai/v1",
    )

    // xAi
    models += processModelsDevProvider(
        data = root,
        providerKey = "xai",
        providerId = "xai",
        apiId = "openai-completions",
        baseUrl = "https://api.x.ai/v1",
    )

    // zAi
    models += processModelsDevProvider(
        data = root,
        providerKey = "zai-coding-plan",
        providerId = "zai",
        apiId = "openai-completions",
        baseUrl = "https://api.z.ai/api/coding/paas/v4",
        extraCompat = ZAI_COMPAT,
    )

    // MiniMax Coding Plan variants
    data class MiniMaxCodeVariant(val key: String, val provider: String, val baseUrl: String)

    val minimaxCodeVariants = listOf(
        MiniMaxCodeVariant("minimax-coding-plan", "minimax-code", "https://api.minimax.io/v1"),
        MiniMaxCodeVariant("minimax-cn-coding-plan", "minimax-code-cn", "https://api.minimaxi.com/v1"),
    )
    for ((key, provider, baseUrl) in minimaxCodeVariants) {
        models += processModelsDevProvider(
            data = root,
            providerKey = key,
            providerId = provider,
            apiId = "openai-completions",
            baseUrl = baseUrl,
            extraCompat = MINIMAX_CODE_COMPAT,
        )
    }

    // Mistral
    models += processModelsDevProvider(
        data = root,
        providerKey = "mistral",
        providerId = "mistral",
        apiId = "openai-completions",
        baseUrl = "https://api.mistral.ai/v1",
    )

    // OpenCode Zen
    val opencodeModels = root.obj("opencode")?.obj("models")
    if (opencodeModels != null) {
        for ((modelId, element) in opencodeModels) {
            val m = element as? JsonObject ?: continue
            if (!isToolCapable(m)) continue
            if (m.str("status") == "deprecated") continue

            val npm = m.obj("provider")?.str("npm")
            val (api, baseUrl) = when (npm) {
                "@ai-sdk/openai" -> "openai-responses" to "https://opencode.ai/zen/v1"
                "@ai-sdk/anthropic" -> "anthropic-messages" to "https://opencode.ai/zen"
                "@ai-sdk/google" -> "google-generative-ai" to "https://opencode.ai/zen/v1"
                else -> "openai-completions" to "https://opencode.ai/zen/v1"
            }

            models += Model(
                id = modelId,
                name = modelsDevName(m, modelId),
                api = ApiId(api),
                provider = ProviderId("opencode"),
                baseUrl = baseUrl,
                reasoning = modelsDevReasoning(m),
                input = modelsDevInput(m),
                cost = modelsDevCost(m),
                contextWindow = modelsDevContext(m),
                maxTokens = modelsDevMaxTokens(m),
            )
        }
    }

    // GitHub Copilot
    val copilotModels = root.obj("github-copilot")?.obj("models")
    if (copilotModels != null) {
        for ((modelId, element) in copilotModels) {
            val m = element as? JsonObject ?: continue
            if (!isToolCapable(m)) continue
            if (m.str("status") == "deprecated") continue

            val needsResponsesApi = modelId.startsWith("gpt-5") || modelId.startsWith("oswe")
            val api = if (needsResponsesApi) "openai-responses" else "openai-completions"
            val compat = if (needsResponsesApi) null else buildJsonObject {
                put("supportsStore", false)
                put("supportsDeveloperRole", false)
                put("supportsReasoningEffort", false)
            }

            models += Model(
                id = modelId,
                name = modelsDevName(m, modelId),
                api = ApiId(api),
                provider = ProviderId("github-copilot"),
                baseUrl = "https://api.individual.githubcopilot.com",
                reasoning = modelsDevReasoning(m),
                input = modelsDevInput(m),
                cost = modelsDevCost(m),
                contextWindow = m.obj("limit").int("context") ?: 128000,
                maxTokens = m.obj("limit").int("output") ?: 8192,
                headers = COPILOT_STATIC_HEADERS,
                compat = compat,
            )
        }
    }

    // MiniMax (Anthropic API)
    data class MiniMaxVariant(val key: String, val provider: String, val baseUrl: String)

    val minimaxVariants = listOf(
        MiniMaxVariant("minimax", "minimax", "https://api.minimax.io/anthropic"),
        MiniMaxVariant("minimax-cn", "minimax-cn", "https://api.minimaxi.com/anthropic"),
    )
    for ((key, provider, baseUrl) in minimaxVariants) {
        models += processModelsDevProvider(
            data = root,
            providerKey = key,
            providerId = provider,
            apiId = "anthropic-messages",
            baseUrl = baseUrl,
        )
    }

    println("Loaded ${models.size} tool-capable models from models.dev")
    return models
}

fun fetchOpenRouterModels(): List<Model> {
    println("Fetching models from OpenRouter API...")
    val root = fetchJson("https://openrouter.ai/api/v1/models")?.jsonObject ?: return emptyList()
    val data = root.arr("data") ?: return emptyList()
    val models = mutableListOf<Model>()

    for (element in data) {
        val m = element as? JsonObject ?: continue
        val supportedParams = m.arr("supported_parameters")?.strList() ?: emptyList()
        if ("tools" !in supportedParams) continue

        val modelId = m.str("id") ?: continue
        val name = m.str("name") ?: modelId

        val input = mutableListOf(ModelInputKind.TEXT)
        val modality = m.obj("architecture")?.str("modality") ?: ""
        if (modality.contains("image")) input += ModelInputKind.IMAGE

        val pricing = m.obj("pricing")
        val inputCost = (pricing?.str("prompt")?.toDoubleOrNull() ?: 0.0) * 1_000_000
        val outputCost = (pricing?.str("completion")?.toDoubleOrNull() ?: 0.0) * 1_000_000
        val cacheReadCost = (pricing?.str("input_cache_read")?.toDoubleOrNull() ?: 0.0) * 1_000_000
        val cacheWriteCost = (pricing?.str("input_cache_write")?.toDoubleOrNull() ?: 0.0) * 1_000_000

        val supportsToolChoice = "tool_choice" in supportedParams
        val compat = if (!supportsToolChoice) buildJsonObject {
            put("supportsToolChoice", false)
        } else null

        models += Model(
            id = modelId,
            name = name,
            api = ApiId("openai-completions"),
            provider = ProviderId("openrouter"),
            baseUrl = "https://openrouter.ai/api/v1",
            reasoning = "reasoning" in supportedParams,
            input = input,
            cost = ModelCost(
                input = inputCost,
                output = outputCost,
                cacheRead = cacheReadCost,
                cacheWrite = cacheWriteCost,
            ),
            contextWindow = m["context_length"]?.jsonPrimitive?.intOrNull ?: 4096,
            maxTokens = m.obj("top_provider")?.int("max_completion_tokens") ?: 4096,
            compat = compat,
        )
    }

    println("Fetched ${models.size} tool-capable models from OpenRouter")
    return models
}

fun fetchAiGatewayModels(): List<Model> {
    println("Fetching models from Vercel AI Gateway API...")
    val root = fetchJson("https://ai-gateway.vercel.sh/v1/models")?.jsonObject ?: return emptyList()
    val data = root.arr("data") ?: return emptyList()
    val models = mutableListOf<Model>()

    fun toNumber(value: JsonPrimitive?): Double {
        if (value == null) return 0.0
        val parsed = value.contentOrNull?.toDoubleOrNull() ?: 0.0
        return if (parsed.isFinite()) parsed else 0.0
    }

    for (element in data) {
        val m = element as? JsonObject ?: continue
        val tags = m.arr("tags")?.strList() ?: emptyList()
        if ("tool-use" !in tags) continue

        val id = m.str("id") ?: continue

        val input = mutableListOf(ModelInputKind.TEXT)
        if ("vision" in tags) input += ModelInputKind.IMAGE

        val pricing = m.obj("pricing")
        val inputCost = toNumber(pricing?.get("input")?.jsonPrimitive) * 1_000_000
        val outputCost = toNumber(pricing?.get("output")?.jsonPrimitive) * 1_000_000
        val cacheReadCost = toNumber(pricing?.get("input_cache_read")?.jsonPrimitive) * 1_000_000
        val cacheWriteCost = toNumber(pricing?.get("input_cache_write")?.jsonPrimitive) * 1_000_000

        models += Model(
            id = id,
            name = m.str("name") ?: id,
            api = ApiId("anthropic-messages"),
            provider = ProviderId("vercel-ai-gateway"),
            baseUrl = "https://ai-gateway.vercel.sh",
            reasoning = "reasoning" in tags,
            input = input,
            cost = ModelCost(
                input = inputCost,
                output = outputCost,
                cacheRead = cacheReadCost,
                cacheWrite = cacheWriteCost,
            ),
            contextWindow = m.int("context_window") ?: 4096,
            maxTokens = m.int("max_tokens") ?: 4096,
        )
    }

    println("Fetched ${models.size} tool-capable models from Vercel AI Gateway")
    return models
}

fun fetchKimiCodeModels(): List<Model> {
    val apiKey = System.getenv("KIMI_API_KEY")
    if (apiKey != null) {
        println("Fetching models from Kimi Code API...")
        val root = fetchJson(
            "https://api.kimi.com/coding/v1/models",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )?.jsonObject

        if (root != null) {
            val items = root.arr("data") ?: JsonArray(emptyList())
            val models = mutableListOf<Model>()
            val fetchedIds = mutableSetOf<String>()

            for (element in items) {
                val m = element as? JsonObject ?: continue
                val id = m.str("id") ?: continue
                fetchedIds += id

                val hasThinking = m.bool("supports_reasoning") == true || id.lowercase().contains("thinking")
                val hasImage = m.bool("supports_image_in") == true || id.lowercase().contains("k2.5")
                val input = mutableListOf(ModelInputKind.TEXT)
                if (hasImage) input += ModelInputKind.IMAGE

                val name = m.str("display_name")
                    ?: id.split("-").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

                models += Model(
                    id = id,
                    name = name,
                    api = ApiId("openai-completions"),
                    provider = ProviderId("kimi-code"),
                    baseUrl = "https://api.kimi.com/coding/v1",
                    headers = KIMI_CODE_HEADERS,
                    reasoning = hasThinking,
                    input = input,
                    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                    contextWindow = m.int("context_length") ?: 262144,
                    maxTokens = 32000,
                    compat = KIMI_COMPAT,
                )
            }

            // Merge in fallback models not returned by the API
            for (fb in kimiCodeFallbackModels()) {
                if (fb.id !in fetchedIds) models += fb
            }

            println("Fetched ${fetchedIds.size} models from Kimi Code API, ${models.size} total with fallbacks")
            return models
        }
    }

    println("KIMI_API_KEY not set, using fallback Kimi Code models")
    return kimiCodeFallbackModels()
}

private fun kimiCodeFallbackModels(): List<Model> {
    val context = 262144
    val maxTokens = 32000
    return listOf(
        kimiModel("kimi-for-coding", "Kimi For Coding", context, maxTokens, true, listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)),
        kimiModel("kimi-k2.5", "Kimi K2.5", context, maxTokens, true, listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)),
        kimiModel("kimi-k2-turbo-preview", "Kimi K2 Turbo Preview", context, maxTokens, true, listOf(ModelInputKind.TEXT)),
        kimiModel("kimi-k2", "Kimi K2", context, maxTokens, true, listOf(ModelInputKind.TEXT)),
    )
}

private fun kimiModel(
    id: String,
    name: String,
    contextWindow: Int,
    maxTokens: Int,
    reasoning: Boolean,
    input: List<ModelInputKind>,
): Model = Model(
    id = id,
    name = name,
    api = ApiId("openai-completions"),
    provider = ProviderId("kimi-code"),
    baseUrl = "https://api.kimi.com/coding/v1",
    headers = KIMI_CODE_HEADERS,
    reasoning = reasoning,
    input = input,
    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
    contextWindow = contextWindow,
    maxTokens = maxTokens,
    compat = KIMI_COMPAT,
)

private fun hardcodedCodexModels(): List<Model> {
    val baseUrl = "https://chatgpt.com/backend-api"
    val context = 272000
    val maxTokens = 128000

    fun codex(id: String, name: String, inputCost: Double, outputCost: Double, cacheRead: Double, ctx: Int = context, input: List<ModelInputKind> = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)) = Model(
        id = id, name = name,
        api = ApiId("openai-codex-responses"),
        provider = ProviderId("openai-codex"),
        baseUrl = baseUrl, reasoning = true, input = input,
        cost = ModelCost(inputCost, outputCost, cacheRead, 0.0),
        contextWindow = ctx, maxTokens = maxTokens,
    )

    return listOf(
        codex("gpt-5.1", "GPT-5.1", 1.25, 10.0, 0.125),
        codex("gpt-5.1-codex-max", "GPT-5.1 Codex Max", 1.25, 10.0, 0.125),
        codex("gpt-5.1-codex-mini", "GPT-5.1 Codex Mini", 0.25, 2.0, 0.025),
        codex("gpt-5.2", "GPT-5.2", 1.75, 14.0, 0.175),
        codex("gpt-5.2-codex", "GPT-5.2 Codex", 1.75, 14.0, 0.175),
        codex("gpt-5.3-codex", "GPT-5.3 Codex", 1.75, 14.0, 0.175, ctx = 400000),
        codex("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", 1.75, 14.0, 0.175, ctx = 128000, input = listOf(ModelInputKind.TEXT)),
    )
}

private fun hardcodedCursorModels(): List<Model> {
    val baseUrl = "https://api2.cursor.sh"
    val zero = ModelCost(0.0, 0.0, 0.0, 0.0)

    fun cursor(id: String, name: String, reasoning: Boolean, input: List<ModelInputKind>, ctx: Int, maxTokens: Int) = Model(
        id = id, name = name,
        api = ApiId("cursor-agent"),
        provider = ProviderId("cursor"),
        baseUrl = baseUrl, reasoning = reasoning, input = input,
        cost = zero, contextWindow = ctx, maxTokens = maxTokens,
    )

    val ti = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)
    val t = listOf(ModelInputKind.TEXT)

    return listOf(
        cursor("default", "Auto (Cursor)", false, ti, 200000, 64000),
        cursor("claude-4.5-sonnet", "Claude 4.5 Sonnet (Cursor)", false, ti, 200000, 64000),
        cursor("claude-4.5-sonnet-thinking", "Claude 4.5 Sonnet Thinking (Cursor)", true, ti, 200000, 64000),
        cursor("claude-4.5-opus-high", "Claude 4.5 Opus (Cursor)", false, ti, 200000, 64000),
        cursor("claude-4.5-opus-high-thinking", "Claude 4.5 Opus Thinking (Cursor)", true, ti, 200000, 64000),
        cursor("gpt-5.1-codex-max", "GPT-5.1 Codex Max (Cursor)", true, ti, 400000, 128000),
        cursor("gpt-5.1-codex-max-high", "GPT-5.1 Codex Max High (Cursor)", true, ti, 400000, 128000),
        cursor("gpt-5.2", "GPT-5.2 (Cursor)", true, ti, 400000, 128000),
        cursor("gpt-5.2-high", "GPT-5.2 High (Cursor)", true, ti, 400000, 128000),
        cursor("gemini-3-pro", "Gemini 3 Pro (Cursor)", true, ti, 1048576, 65535),
        cursor("gemini-3-flash", "Gemini 3 Flash (Cursor)", true, ti, 1048576, 65535),
        cursor("grok-code-fast-1", "Grok (Cursor)", false, t, 32768, 8192),
        cursor("composer-1", "Composer 1 (Cursor)", false, t, 200000, 64000),
    )
}

private fun hardcodedAntigravityModels(): List<Model> {
    val endpoint = "https://daily-cloudcode-pa.sandbox.googleapis.com"
    val zero = ModelCost(0.0, 0.0, 0.0, 0.0)
    val ti = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)
    val t = listOf(ModelInputKind.TEXT)

    fun ag(id: String, name: String, reasoning: Boolean, input: List<ModelInputKind>, ctx: Int, maxTokens: Int) = Model(
        id = id, name = "$name (Antigravity)",
        api = ApiId("google-gemini-cli"),
        provider = ProviderId("google-antigravity"),
        baseUrl = endpoint, reasoning = reasoning, input = input,
        cost = zero, contextWindow = ctx, maxTokens = maxTokens,
    )

    return listOf(
        ag("gemini-3-pro-high", "Gemini 3 Pro High", true, ti, 1048576, 65535),
        ag("gemini-3-pro-low", "Gemini 3 Pro Low", true, ti, 1048576, 65535),
        ag("gemini-3-flash", "Gemini 3 Flash", true, ti, 1048576, 65536),
        ag("claude-sonnet-4-5", "Claude Sonnet 4.5", false, ti, 200000, 64000),
        ag("claude-sonnet-4-5-thinking", "Claude Sonnet 4.5 Thinking", true, ti, 200000, 64000),
        ag("claude-opus-4-5-thinking", "Claude Opus 4.5 Thinking", true, ti, 200000, 64000),
        ag("claude-opus-4-6-thinking", "Claude Opus 4.6 Thinking", true, ti, 200000, 64000),
        ag("gpt-oss-120b-medium", "GPT-OSS 120B Medium", true, t, 131072, 32768),
        ag("gemini-2.5-pro", "Gemini 2.5 Pro", true, ti, 1048576, 65535),
        ag("gemini-2.5-flash", "Gemini 2.5 Flash", true, ti, 1048576, 65535),
        ag("gemini-2.5-flash-thinking", "Gemini 2.5 Flash Thinking", true, ti, 1048576, 65535),
    )
}

private fun hardcodedCloudCodeAssistModels(): List<Model> {
    val endpoint = "https://cloudcode-pa.googleapis.com"
    val zero = ModelCost(0.0, 0.0, 0.0, 0.0)
    val ti = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)

    fun cca(id: String, name: String, reasoning: Boolean, ctx: Int, maxTokens: Int) = Model(
        id = id, name = "$name (Cloud Code Assist)",
        api = ApiId("google-gemini-cli"),
        provider = ProviderId("google-gemini-cli"),
        baseUrl = endpoint, reasoning = reasoning, input = ti,
        cost = zero, contextWindow = ctx, maxTokens = maxTokens,
    )

    return listOf(
        cca("gemini-2.5-pro", "Gemini 2.5 Pro", true, 1048576, 65535),
        cca("gemini-2.5-flash", "Gemini 2.5 Flash", true, 1048576, 65535),
        cca("gemini-2.0-flash", "Gemini 2.0 Flash", false, 1048576, 8192),
        cca("gemini-3-pro-preview", "Gemini 3 Pro Preview", true, 1048576, 65535),
        cca("gemini-3-flash-preview", "Gemini 3 Flash Preview", true, 1048576, 65535),
    )
}

private fun hardcodedVertexModels(): List<Model> {
    val baseUrl = "https://{location}-aiplatform.googleapis.com"
    val ti = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE)

    fun vertex(id: String, name: String, reasoning: Boolean, inputCost: Double, outputCost: Double, cacheRead: Double, ctx: Int, maxTokens: Int) = Model(
        id = id, name = "$name (Vertex)",
        api = ApiId("google-vertex"),
        provider = ProviderId("google-vertex"),
        baseUrl = baseUrl, reasoning = reasoning, input = ti,
        cost = ModelCost(inputCost, outputCost, cacheRead, 0.0),
        contextWindow = ctx, maxTokens = maxTokens,
    )

    return listOf(
        vertex("gemini-3-pro-preview", "Gemini 3 Pro Preview", true, 2.0, 12.0, 0.2, 1000000, 64000),
        vertex("gemini-3-flash-preview", "Gemini 3 Flash Preview", true, 0.5, 3.0, 0.05, 1048576, 65536),
        vertex("gemini-2.0-flash", "Gemini 2.0 Flash", false, 0.15, 0.6, 0.0375, 1048576, 8192),
        vertex("gemini-2.0-flash-lite", "Gemini 2.0 Flash Lite", true, 0.075, 0.3, 0.01875, 1048576, 65536),
        vertex("gemini-2.5-pro", "Gemini 2.5 Pro", true, 1.25, 10.0, 0.125, 1048576, 65536),
        vertex("gemini-2.5-flash", "Gemini 2.5 Flash", true, 0.3, 2.5, 0.03, 1048576, 65536),
        vertex("gemini-2.5-flash-lite-preview-09-2025", "Gemini 2.5 Flash Lite Preview 09-25", true, 0.1, 0.4, 0.01, 1048576, 65536),
        vertex("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", true, 0.1, 0.4, 0.01, 1048576, 65536),
        vertex("gemini-1.5-pro", "Gemini 1.5 Pro", false, 1.25, 5.0, 0.3125, 1000000, 8192),
        vertex("gemini-1.5-flash", "Gemini 1.5 Flash", false, 0.075, 0.3, 0.01875, 1000000, 8192),
        vertex("gemini-1.5-flash-8b", "Gemini 1.5 Flash-8B", false, 0.0375, 0.15, 0.01, 1000000, 8192),
    )
}

private fun addIfMissing(models: MutableList<Model>, model: Model) {
    if (models.none { it.provider == model.provider && it.id == model.id }) {
        models += model
    }
}

fun main() {
    val allModels = mutableListOf<Model>()

    // Fetch from APIs
    allModels += loadModelsDevData()
    allModels += fetchOpenRouterModels()
    allModels += fetchAiGatewayModels()
    allModels += fetchKimiCodeModels()

    // Apply corrections
    allModels.find { it.provider.value == "anthropic" && it.id == "claude-opus-4-5" }?.let { opus45 ->
        val idx = allModels.indexOf(opus45)
        allModels[idx] = opus45.copy(cost = opus45.cost.copy(cacheRead = 0.5, cacheWrite = 6.25))
    }

    for (i in allModels.indices) {
        val m = allModels[i]
        // Bedrock Opus 4.6 cache pricing correction
        if (m.provider.value == "amazon-bedrock" && m.id.contains("anthropic.claude-opus-4-6-v1")) {
            allModels[i] = m.copy(cost = m.cost.copy(cacheRead = 0.5, cacheWrite = 6.25))
        }
        // Opus 4.6 context window correction
        if (m.id.contains("opus-4-6") || m.id.contains("opus-4.6")) {
            allModels[i] = allModels[i].copy(contextWindow = 200000)
        }
        // OpenCode Claude Sonnet context correction
        if (m.provider.value == "opencode" && (m.id == "claude-sonnet-4-5" || m.id == "claude-sonnet-4")) {
            allModels[i] = allModels[i].copy(contextWindow = 200000)
        }
    }

    // Hardcoded models (add if not already present from API)
    addIfMissing(allModels, Model(
        id = "gpt-5-chat-latest", name = "GPT-5 Chat Latest",
        api = ApiId("openai-responses"), provider = ProviderId("openai"),
        baseUrl = "https://api.openai.com/v1", reasoning = false,
        input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
        cost = ModelCost(1.25, 10.0, 0.125, 0.0),
        contextWindow = 128000, maxTokens = 16384,
    ))

    addIfMissing(allModels, Model(
        id = "gpt-5.1-codex", name = "GPT-5.1 Codex",
        api = ApiId("openai-responses"), provider = ProviderId("openai"),
        baseUrl = "https://api.openai.com/v1", reasoning = true,
        input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
        cost = ModelCost(1.25, 5.0, 0.125, 1.25),
        contextWindow = 400000, maxTokens = 128000,
    ))

    addIfMissing(allModels, Model(
        id = "gpt-5.1-codex-max", name = "GPT-5.1 Codex Max",
        api = ApiId("openai-responses"), provider = ProviderId("openai"),
        baseUrl = "https://api.openai.com/v1", reasoning = true,
        input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
        cost = ModelCost(1.25, 10.0, 0.125, 0.0),
        contextWindow = 400000, maxTokens = 128000,
    ))

    // OpenAI Codex (ChatGPT OAuth) models
    allModels += hardcodedCodexModels()

    // Missing Grok model
    addIfMissing(allModels, Model(
        id = "grok-code-fast-1", name = "Grok Code Fast 1",
        api = ApiId("openai-completions"), provider = ProviderId("xai"),
        baseUrl = "https://api.x.ai/v1", reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.2, 1.5, 0.02, 0.0),
        contextWindow = 32768, maxTokens = 8192,
    ))

    // OpenRouter auto alias
    addIfMissing(allModels, Model(
        id = "auto", name = "Auto",
        api = ApiId("openai-completions"), provider = ProviderId("openrouter"),
        baseUrl = "https://openrouter.ai/api/v1", reasoning = true,
        input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 2000000, maxTokens = 30000,
    ))

    // MiniMax Coding Plan fallbacks
    fun addMiniMaxCodeFallbacks(provider: String, baseUrl: String, nameSuffix: String) {
        for ((id, name, ctx) in listOf(
            Triple("MiniMax-M2.1", "MiniMax M2.1", 1000000),
            Triple("MiniMax-M2.1-lightning", "MiniMax M2.1 Lightning", 1000000),
            Triple("MiniMax-M2.5", "MiniMax M2.5", 204800),
            Triple("MiniMax-M2.5-lightning", "MiniMax M2.5 Lightning", 204800),
        )) {
            addIfMissing(allModels, Model(
                id = id, name = "$name$nameSuffix",
                api = ApiId("openai-completions"), provider = ProviderId(provider),
                baseUrl = baseUrl, reasoning = true, input = listOf(ModelInputKind.TEXT),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = ctx, maxTokens = 32000,
                compat = MINIMAX_CODE_COMPAT,
            ))
        }
    }
    addMiniMaxCodeFallbacks("minimax-code", "https://api.minimax.io/v1", " (Coding Plan)")
    addMiniMaxCodeFallbacks("minimax-code-cn", "https://api.minimaxi.com/v1", " (Coding Plan CN)")

    // MiniMax Anthropic API fallbacks
    fun addMiniMaxAnthropicFallbacks(provider: String, baseUrl: String, nameSuffix: String) {
        for ((id, name, inputCost, outputCost) in listOf(
            listOf("MiniMax-M2.5", "MiniMax M2.5", 0.15, 1.2),
            listOf("MiniMax-M2.5-lightning", "MiniMax M2.5 Lightning", 0.3, 2.4),
        )) {
            addIfMissing(allModels, Model(
                id = id as String, name = "$name$nameSuffix",
                api = ApiId("anthropic-messages"), provider = ProviderId(provider),
                baseUrl = baseUrl, reasoning = true, input = listOf(ModelInputKind.TEXT),
                cost = ModelCost(inputCost as Double, outputCost as Double, 0.0, 0.0),
                contextWindow = 204800, maxTokens = 32000,
            ))
        }
    }
    addMiniMaxAnthropicFallbacks("minimax", "https://api.minimax.io/anthropic", "")
    addMiniMaxAnthropicFallbacks("minimax-cn", "https://api.minimaxi.com/anthropic", " (CN)")

    // Cloud Code Assist, Antigravity, Vertex, and Cursor models
    allModels += hardcodedCloudCodeAssistModels()
    allModels += hardcodedAntigravityModels()
    allModels += hardcodedVertexModels()
    allModels += hardcodedCursorModels()

    // Deduplicate: first occurrence wins (models.dev > OpenRouter > AI Gateway > Kimi)
    val seen = mutableSetOf<String>()
    val deduplicated = mutableListOf<Model>()
    for (model in allModels) {
        val key = "${model.provider.value}/${model.id}"
        if (seen.add(key)) {
            deduplicated += model
        }
    }

    // Write output
    val outputPath = Path.of("agent47-coding-core/src/main/resources/model-catalog.json")
    val output = json.encodeToString(ListSerializer(Model.serializer()), deduplicated)
    outputPath.toFile().writeText(output)

    // Statistics
    println("\nModel Statistics:")
    println("  Total unique models: ${deduplicated.size}")
    println("  Reasoning-capable: ${deduplicated.count { it.reasoning }}")
    val byProvider = deduplicated.groupBy { it.provider.value }
    for ((provider, models) in byProvider.entries.sortedBy { it.key }) {
        println("  $provider: ${models.size} models")
    }
    println("\nGenerated model-catalog.json")
}
