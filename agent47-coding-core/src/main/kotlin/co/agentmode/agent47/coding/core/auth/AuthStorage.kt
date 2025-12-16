package co.agentmode.agent47.coding.core.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
public sealed interface AuthCredential {
    public val type: String
}

@Serializable
public data class ApiKeyCredential(
    override val type: String = "api_key",
    val key: String,
) : AuthCredential

@Serializable
public data class OAuthCredential(
    override val type: String = "oauth",
    val accessToken: String,
    val refreshToken: String,
    val expires: Long,
    val metadata: JsonObject? = null,
) : AuthCredential

public fun interface OAuthRefreshFunction {
    public suspend fun refresh(provider: String, credential: OAuthCredential): OAuthCredential?
}

public class AuthStorage(
    private val authPath: Path,
    private val envResolver: (String) -> String? = ::defaultEnvResolver,
) {
    public companion object {
        private val PROVIDER_ENV_MAP: Map<String, List<String>> = mapOf(
            "anthropic" to listOf("ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
            "google" to listOf("GEMINI_API_KEY"),
            "openai" to listOf("OPENAI_API_KEY"),
            "openai-codex" to listOf("OPENAI_API_KEY"),
            "github-copilot" to listOf("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"),
            "xai" to listOf("XAI_API_KEY"),
            "groq" to listOf("GROQ_API_KEY"),
            "cerebras" to listOf("CEREBRAS_API_KEY"),
            "openrouter" to listOf("OPENROUTER_API_KEY"),
            "vercel-ai-gateway" to listOf("VERCEL_AI_GATEWAY_API_KEY"),
            "zai" to listOf("ZAI_API_KEY"),
            "mistral" to listOf("MISTRAL_API_KEY"),
            "kimi-code" to listOf("KIMI_API_KEY"),
            "cursor" to listOf("CURSOR_ACCESS_TOKEN"),
            "minimax" to listOf("MINIMAX_API_KEY"),
            "minimax-code" to listOf("MINIMAX_CODE_API_KEY", "MINIMAX_API_KEY"),
            "minimax-code-cn" to listOf("MINIMAX_CODE_CN_API_KEY", "MINIMAX_API_KEY"),
            "opencode" to listOf("OPENCODE_API_KEY"),
        )

        private fun defaultEnvResolver(provider: String): String? {
            val envVars = PROVIDER_ENV_MAP[provider]
            if (envVars != null) {
                for (envVar in envVars) {
                    val value = System.getenv(envVar)
                    if (!value.isNullOrBlank()) return value
                }
                return null
            }
            // Generic fallback for unknown providers
            val envName = provider.uppercase().replace('-', '_') + "_API_KEY"
            return System.getenv(envName)
        }
    }
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val runtimeOverrides: MutableMap<String, String> = ConcurrentHashMap()
    private val data: MutableMap<String, AuthCredential> = ConcurrentHashMap()
    private var fallbackResolver: ((String) -> String?)? = null
    private var oauthRefresher: OAuthRefreshFunction? = null

    init {
        reload()
    }

    public fun setRuntimeApiKey(provider: String, apiKey: String) {
        runtimeOverrides[provider] = apiKey
    }

    public fun removeRuntimeApiKey(provider: String) {
        runtimeOverrides.remove(provider)
    }

    public fun setFallbackResolver(resolver: (String) -> String?) {
        fallbackResolver = resolver
    }

    public fun setOauthRefresher(refresher: OAuthRefreshFunction) {
        oauthRefresher = refresher
    }

    public fun reload() {
        data.clear()
        if (!authPath.exists()) {
            return
        }

        val content = runCatching { authPath.readText() }.getOrNull() ?: return
        val root = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull() ?: return

        root.forEach { (provider, rawCredential) ->
            val credential = decodeCredential(rawCredential.jsonObject)
            if (credential != null) {
                data[provider] = credential
            }
        }
    }

    public fun get(provider: String): AuthCredential? = data[provider]

    public fun set(provider: String, credential: AuthCredential) {
        data[provider] = credential
        save()
    }

    public fun remove(provider: String) {
        data.remove(provider)
        save()
    }

    public fun list(): List<String> = data.keys.sorted()

    public fun has(provider: String): Boolean = data.containsKey(provider)

    public fun hasAuth(provider: String): Boolean {
        return runtimeOverrides.containsKey(provider) ||
                data.containsKey(provider) ||
                envResolver(provider) != null ||
                fallbackResolver?.invoke(provider) != null
    }

    public suspend fun getApiKey(provider: String): String? {
        runtimeOverrides[provider]?.let { return it }

        when (val credential = data[provider]) {
            is ApiKeyCredential -> return resolveConfigValue(credential.key)
            is OAuthCredential -> {
                // expires = 0 means the token never expires (e.g. GitHub OAuth tokens)
                if (credential.expires > 0 && credential.expires <= Instant.now().toEpochMilli()) {
                    val refreshed = refreshWithLock(provider, credential)
                    return refreshed?.accessToken
                }
                return credential.accessToken
            }

            null -> {
                // continue
            }
        }

        envResolver(provider)?.let { return it }
        return fallbackResolver?.invoke(provider)
    }

    public fun getAll(): Map<String, AuthCredential> = data.toMap()

    private fun save() {
        Files.createDirectories(authPath.parent)

        val root = buildJsonObject {
            data.toSortedMap().forEach { (provider, credential) ->
                put(provider, encodeCredential(credential))
            }
        }

        authPath.writeText(json.encodeToString(JsonObject.serializer(), root), StandardCharsets.UTF_8)
        runCatching {
            Files.setPosixFilePermissions(
                authPath,
                setOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private suspend fun refreshWithLock(provider: String, credential: OAuthCredential): OAuthCredential? {
        val refresher = oauthRefresher ?: return null

        withContext(Dispatchers.IO) {
            Files.createDirectories(authPath.parent)
        }
        if (!authPath.exists()) {
            save()
        }

        withContext(Dispatchers.IO) {
            FileChannel.open(authPath, StandardOpenOption.READ, StandardOpenOption.WRITE)
        }.use { channel ->
            channel.lock().use {
                reload()
                val latest = data[provider]
                if (latest is OAuthCredential && (latest.expires == 0L || latest.expires > Instant.now().toEpochMilli())) {
                    return latest
                }

                val refreshed = refresher.refresh(provider, latest as? OAuthCredential ?: credential)
                if (refreshed != null) {
                    data[provider] = refreshed
                    save()
                }
                return refreshed
            }
        }
    }

    private fun resolveConfigValue(raw: String): String {
        if (!raw.startsWith("$") || raw.length < 2) {
            return raw
        }
        val name = raw.substring(1)
        return System.getenv(name) ?: raw
    }

    private fun decodeCredential(raw: JsonObject): AuthCredential? {
        val type = raw["type"]?.jsonPrimitive?.content ?: return null
        return when (type) {
            "api_key" -> {
                val key = raw["key"]?.jsonPrimitive?.content ?: return null
                ApiKeyCredential(key = key)
            }

            "oauth" -> {
                val accessToken = raw["accessToken"]?.jsonPrimitive?.content ?: return null
                val refreshToken = raw["refreshToken"]?.jsonPrimitive?.content ?: return null
                val expires = raw["expires"]?.jsonPrimitive?.longOrNull ?: return null
                val metadata = raw["metadata"]?.jsonObject
                OAuthCredential(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expires = expires,
                    metadata = metadata,
                )
            }

            else -> null
        }
    }

    private fun encodeCredential(credential: AuthCredential): JsonObject {
        return when (credential) {
            is ApiKeyCredential -> buildJsonObject {
                put("type", JsonPrimitive("api_key"))
                put("key", JsonPrimitive(credential.key))
            }

            is OAuthCredential -> buildJsonObject {
                put("type", JsonPrimitive("oauth"))
                put("accessToken", JsonPrimitive(credential.accessToken))
                put("refreshToken", JsonPrimitive(credential.refreshToken))
                put("expires", JsonPrimitive(credential.expires))
                credential.metadata?.let { metadata -> put("metadata", metadata) }
            }
        }
    }

    private val JsonPrimitive.longOrNull: Long?
        get() = content.toLongOrNull()
}
