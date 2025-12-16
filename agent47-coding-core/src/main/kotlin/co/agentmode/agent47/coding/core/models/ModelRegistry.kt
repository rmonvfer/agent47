package co.agentmode.agent47.coding.core.models

import co.agentmode.agent47.ai.types.Agent47Json
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.coding.core.auth.ApiKeyCredential
import co.agentmode.agent47.coding.core.auth.AuthMethod
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.ProviderAuthPlugin
import co.agentmode.agent47.coding.core.config.ConfigValueResolver
import com.charleskorn.kaml.Yaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

@Serializable
public data class ModelDefinition(
    val id: String,
    val name: String? = null,
    val api: String? = null,
    val reasoning: Boolean? = null,
    val input: List<String>? = null,
    val cost: ModelCostDefinition? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

@Serializable
public data class ModelCostDefinition(
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
)

@Serializable
public data class ProviderDiscovery(
    val type: String,
)

@Serializable
public data class ProviderConfig(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val api: String? = null,
    val headers: Map<String, String>? = null,
    val authHeader: Boolean? = null,
    val auth: String? = null,
    val models: List<ModelDefinition>? = null,
    val modelOverrides: Map<String, ModelDefinition>? = null,
    val discovery: ProviderDiscovery? = null,
)

@Serializable
public data class ModelsConfig(
    val providers: Map<String, ProviderConfig>,
)

public data class ProviderInfo(
    val id: String,
    val name: String,
    val connected: Boolean,
    val modelCount: Int,
    val authMethods: List<AuthMethod> = listOf(AuthMethod.ApiKey()),
)

private val PROVIDER_PRIORITY = listOf(
    "anthropic",
    "openai",
    "google",
    "openrouter",
    "github-copilot",
    "xai",
    "groq",
    "mistral",
    "cerebras",
    "amazon-bedrock",
)

private fun providerDisplayName(id: String): String = when (id) {
    "anthropic" -> "Anthropic"
    "openai" -> "OpenAI"
    "openai-codex" -> "OpenAI Codex"
    "google" -> "Google"
    "google-gemini-cli" -> "Google Gemini CLI"
    "google-antigravity" -> "Google Antigravity"
    "google-vertex" -> "Google Vertex"
    "amazon-bedrock" -> "Amazon Bedrock"
    "openrouter" -> "OpenRouter"
    "vercel-ai-gateway" -> "Vercel AI Gateway"
    "github-copilot" -> "GitHub Copilot"
    "cursor" -> "Cursor"
    "xai" -> "xAI"
    "groq" -> "Groq"
    "cerebras" -> "Cerebras"
    "zai" -> "Zai"
    "mistral" -> "Mistral"
    "minimax" -> "MiniMax"
    "minimax-code" -> "MiniMax Code"
    "minimax-code-cn" -> "MiniMax Code CN"
    "opencode" -> "OpenCode"
    "kimi-code" -> "Kimi Code"
    else -> id.replaceFirstChar { it.uppercaseChar() }
}

public interface ProviderHook {
    public val name: String
    public fun apply(models: List<Model>): List<Model>
}

public class ModelRegistry(
    private val authStorage: AuthStorage,
    private val modelsConfigPath: Path? = null,
    private val modelsJsonLegacyPath: Path? = null,
) {
    private val customProviderApiKeys: MutableMap<String, String> = linkedMapOf()
    private val providerHooks: MutableMap<String, ProviderHook> = linkedMapOf()
    private val authPlugins: MutableMap<String, ProviderAuthPlugin> = linkedMapOf()
    private val models: MutableList<Model> = mutableListOf()
    private var loadError: String? = null

    init {
        authStorage.setFallbackResolver { provider ->
            customProviderApiKeys[provider]?.let { ConfigValueResolver.resolveSync(it) }
        }
        refresh()
    }

    public fun refresh(): Unit {
        customProviderApiKeys.clear()
        loadError = null

        val baseModels = loadBuiltInModels().toMutableList()
        val custom = loadCustomModels()

        if (custom.config != null) {
            applyProviderOverrides(baseModels, custom.config)
        }

        models.clear()
        models += mergeCustomModels(baseModels, custom.models)

        if (custom.error != null) {
            loadError = custom.error
        }

        providerHooks.values.forEach { hook ->
            val updated = hook.apply(models.toList())
            models.clear()
            models += updated
        }
    }

    public suspend fun refreshAsync(): Unit {
        customProviderApiKeys.clear()
        loadError = null

        val baseModels = loadBuiltInModels().toMutableList()
        val custom = loadCustomModels()

        if (custom.config != null) {
            applyProviderOverrides(baseModels, custom.config)
        }

        val discoveredModels = mutableListOf<Model>()
        if (custom.config != null) {
            for ((providerName, providerConfig) in custom.config.providers) {
                if (providerConfig.discovery?.type == "ollama") {
                    discoveredModels += discoverOllamaModels(providerName, providerConfig)
                }
            }
        }

        models.clear()
        models += mergeCustomModels(baseModels, custom.models + discoveredModels)

        if (custom.error != null) {
            loadError = custom.error
        }

        providerHooks.values.forEach { hook ->
            val updated = hook.apply(models.toList())
            models.clear()
            models += updated
        }
    }

    public fun getError(): String? = loadError

    public fun getAll(): List<Model> = models.toList()

    public fun getAvailable(): List<Model> {
        return models.filter { authStorage.hasAuth(it.provider.value) }
    }

    public fun getConnectedProviders(): Set<String> {
        return models
            .map { it.provider.value }
            .filter { authStorage.hasAuth(it) }
            .toSet()
    }

    public fun getAllProviders(): List<ProviderInfo> {
        val connected = getConnectedProviders()
        return models
            .groupBy { it.provider.value }
            .map { (providerId, providerModels) ->
                val plugin = authPlugins[providerId]
                ProviderInfo(
                    id = providerId,
                    name = providerDisplayName(providerId),
                    connected = providerId in connected,
                    modelCount = providerModels.size,
                    authMethods = plugin?.methods ?: listOf(AuthMethod.ApiKey()),
                )
            }
            .sortedWith(compareBy({ !it.connected }, { PROVIDER_PRIORITY.indexOf(it.id).let { i -> if (i < 0) 99 else i } }, { it.name }))
    }

    public fun storeApiKey(provider: String, apiKey: String) {
        authStorage.set(provider, ApiKeyCredential(key = apiKey))
        refresh()
    }

    public fun storeOAuthCredential(provider: String, credential: OAuthCredential) {
        authStorage.set(provider, credential)
        refresh()
    }

    public fun registerAuthPlugin(plugin: ProviderAuthPlugin) {
        authPlugins[plugin.providerId] = plugin
    }

    public fun getAuthPlugin(providerId: String): ProviderAuthPlugin? {
        return authPlugins[providerId]
    }

    public fun find(provider: String, modelId: String): Model? {
        return models.firstOrNull { it.provider.value == provider && it.id == modelId }
    }

    public suspend fun getApiKey(model: Model): String? = authStorage.getApiKey(model.provider.value)

    public suspend fun getApiKeyForProvider(provider: String): String? = authStorage.getApiKey(provider)

    public fun isUsingOAuth(model: Model): Boolean {
        return authStorage.get(model.provider.value) is co.agentmode.agent47.coding.core.auth.OAuthCredential
    }

    public fun registerProviderHook(hook: ProviderHook): Unit {
        providerHooks[hook.name] = hook
        refresh()
    }

    private fun loadBuiltInModels(): List<Model> {
        val resource = javaClass.classLoader.getResourceAsStream("model-catalog.json")
            ?: return defaultModelCatalog()

        val text = resource.bufferedReader().readText()
        return runCatching {
            Agent47Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Model.serializer()), text)
        }.getOrElse {
            defaultModelCatalog()
        }
    }

    private fun loadCustomModels(): CustomModelsResult {
        // Try YAML first, then fall back to legacy JSON
        val yamlPath = modelsConfigPath
        val jsonPath = modelsJsonLegacyPath

        val (path, isYaml) = when {
            yamlPath != null && yamlPath.exists() -> yamlPath to true
            jsonPath != null && jsonPath.exists() -> jsonPath to false
            yamlPath != null && !yamlPath.exists() && jsonPath == null -> return CustomModelsResult(emptyList(), null, null)
            else -> return CustomModelsResult(emptyList(), null, null)
        }

        return try {
            val content = path.readText()
            val config = if (isYaml) {
                Yaml.default.decodeFromString(ModelsConfig.serializer(), content)
            } else {
                Agent47Json.decodeFromString(ModelsConfig.serializer(), content)
            }
            val parsed = parseCustomModels(config)
            CustomModelsResult(parsed, null, config)
        } catch (error: Throwable) {
            CustomModelsResult(emptyList(), "Failed to parse models config: ${error.message}", null)
        }
    }

    private fun parseCustomModels(config: ModelsConfig): List<Model> {
        val parsed = mutableListOf<Model>()

        config.providers.forEach { (providerName, providerConfig) ->
            providerConfig.apiKey?.let { key -> customProviderApiKeys[providerName] = key }

            providerConfig.models.orEmpty().forEach { modelDef ->
                val api = modelDef.api ?: providerConfig.api
                    ?: error("Provider $providerName model ${modelDef.id} must declare api")
                val baseUrl = providerConfig.baseUrl
                    ?: error("Provider $providerName requires baseUrl when defining models")

                parsed += Model(
                    id = modelDef.id,
                    name = modelDef.name ?: modelDef.id,
                    api = ApiId(api),
                    provider = ProviderId(providerName),
                    baseUrl = baseUrl,
                    reasoning = modelDef.reasoning ?: false,
                    input = parseInput(modelDef.input),
                    cost = parseCost(modelDef.cost),
                    contextWindow = modelDef.contextWindow ?: 128_000,
                    maxTokens = modelDef.maxTokens ?: 16_384,
                    headers = mergeHeaders(providerConfig, modelDef),
                    compat = modelDef.compat,
                )
            }
        }

        return parsed
    }

    private fun applyProviderOverrides(baseModels: MutableList<Model>, config: ModelsConfig) {
        for ((providerName, providerConfig) in config.providers) {
            // Only apply overrides to providers that don't define their own models
            if (!providerConfig.models.isNullOrEmpty()) continue

            val hasOverrides = providerConfig.baseUrl != null ||
                providerConfig.headers != null ||
                providerConfig.apiKey != null ||
                !providerConfig.modelOverrides.isNullOrEmpty()

            if (!hasOverrides) continue

            providerConfig.apiKey?.let { key -> customProviderApiKeys[providerName] = key }

            for (i in baseModels.indices) {
                val model = baseModels[i]
                if (model.provider.value != providerName) continue

                var updated = model

                providerConfig.baseUrl?.let { url ->
                    updated = updated.copy(baseUrl = url)
                }

                providerConfig.headers?.let { headers ->
                    val merged = (updated.headers.orEmpty()) + headers.mapValues { (_, v) ->
                        ConfigValueResolver.resolveSync(v)
                    }
                    updated = updated.copy(headers = merged)
                }

                // Per-model overrides
                providerConfig.modelOverrides?.get(model.id)?.let { override ->
                    override.maxTokens?.let { updated = updated.copy(maxTokens = it) }
                    override.contextWindow?.let { updated = updated.copy(contextWindow = it) }
                    override.name?.let { updated = updated.copy(name = it) }
                    override.reasoning?.let { updated = updated.copy(reasoning = it) }
                    override.cost?.let { updated = updated.copy(cost = parseCost(it)) }
                }

                baseModels[i] = updated
            }
        }
    }

    private suspend fun discoverOllamaModels(provider: String, config: ProviderConfig): List<Model> {
        val baseUrl = config.baseUrl ?: return emptyList()
        return try {
            val response = withContext(Dispatchers.IO) {
                val client = HttpClient.newBuilder().build()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("${baseUrl.trimEnd('/')}/api/tags"))
                    .GET()
                    .build()
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }

            if (response.statusCode() != 200) return emptyList()

            val root = Agent47Json.parseToJsonElement(response.body())
            val modelsArray = (root as? kotlinx.serialization.json.JsonObject)
                ?.get("models")
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?: return emptyList()

            modelsArray.mapNotNull { element ->
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: return@mapNotNull null

                Model(
                    id = name,
                    name = name,
                    api = ApiId(config.api ?: "openai-completions"),
                    provider = ProviderId(provider),
                    baseUrl = baseUrl,
                    reasoning = false,
                    input = listOf(ModelInputKind.TEXT),
                    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                    contextWindow = 128_000,
                    maxTokens = 16_384,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergeCustomModels(base: List<Model>, custom: List<Model>): List<Model> {
        val merged = base.toMutableList()
        custom.forEach { customModel ->
            val index = merged.indexOfFirst { it.provider == customModel.provider && it.id == customModel.id }
            if (index >= 0) {
                merged[index] = customModel
            } else {
                merged += customModel
            }
        }
        return merged
    }

    private fun mergeHeaders(provider: ProviderConfig, model: ModelDefinition): Map<String, String>? {
        val merged = linkedMapOf<String, String>()
        provider.headers?.forEach { (key, value) -> merged[key] = ConfigValueResolver.resolveSync(value) }
        model.headers?.forEach { (key, value) -> merged[key] = ConfigValueResolver.resolveSync(value) }

        if (provider.authHeader == true && provider.apiKey != null) {
            merged["Authorization"] = "Bearer ${ConfigValueResolver.resolveSync(provider.apiKey)}"
        }

        return if (merged.isEmpty()) null else merged
    }

    private fun parseInput(raw: List<String>?): List<ModelInputKind> {
        val source = raw ?: listOf("text")
        return source.map { kind ->
            when (kind.lowercase()) {
                "image" -> ModelInputKind.IMAGE
                else -> ModelInputKind.TEXT
            }
        }
    }

    private fun parseCost(raw: ModelCostDefinition?): ModelCost {
        return ModelCost(
            input = raw?.input ?: 0.0,
            output = raw?.output ?: 0.0,
            cacheRead = raw?.cacheRead ?: 0.0,
            cacheWrite = raw?.cacheWrite ?: 0.0,
        )
    }

    private fun defaultModelCatalog(): List<Model> {
        return listOf(
            Model(
                id = "gpt-4.1-mini",
                name = "GPT-4.1 mini",
                api = ApiId("openai-responses"),
                provider = KnownProviders.OpenAi,
                baseUrl = "https://api.openai.com/v1",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 128_000,
                maxTokens = 16_384,
            ),
            Model(
                id = "gpt-5-codex",
                name = "GPT-5 Codex",
                api = ApiId("openai-codex-responses"),
                provider = KnownProviders.OpenAiCodex,
                baseUrl = "https://api.openai.com/v1",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 200_000,
                maxTokens = 32_000,
            ),
            Model(
                id = "claude-sonnet-4-5",
                name = "Claude Sonnet 4.5",
                api = ApiId("anthropic-messages"),
                provider = KnownProviders.Anthropic,
                baseUrl = "https://api.anthropic.com/v1",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 200_000,
                maxTokens = 8_192,
            ),
            Model(
                id = "gemini-2.5-pro",
                name = "Gemini 2.5 Pro",
                api = ApiId("google-generative-ai"),
                provider = KnownProviders.Google,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 65_536,
            ),
            Model(
                id = "gemini-2.5-flash",
                name = "Gemini 2.5 Flash",
                api = ApiId("google-gemini-cli"),
                provider = KnownProviders.GoogleGeminiCli,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 32_768,
            ),
            Model(
                id = "gemini-2.5-pro",
                name = "Gemini 2.5 Pro (Antigravity)",
                api = ApiId("google-antigravity"),
                provider = KnownProviders.GoogleAntigravity,
                baseUrl = "https://generativelanguage.googleapis.com/v1beta",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 65_536,
            ),
            Model(
                id = "gemini-2.5-pro",
                name = "Gemini 2.5 Pro (Vertex)",
                api = ApiId("google-vertex"),
                provider = KnownProviders.GoogleVertex,
                baseUrl = "https://us-central1-aiplatform.googleapis.com/v1/projects/PROJECT/locations/us-central1/publishers/google",
                reasoning = true,
                input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                contextWindow = 1_000_000,
                maxTokens = 65_536,
            ),
        )
    }

    private data class CustomModelsResult(
        val models: List<Model>,
        val error: String?,
        val config: ModelsConfig?,
    )
}
