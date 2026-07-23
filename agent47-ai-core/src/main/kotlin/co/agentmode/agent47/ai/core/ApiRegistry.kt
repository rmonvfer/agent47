package co.agentmode.agent47.ai.core

import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.types.ApiId
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry mapping [ApiId] values to [ApiProvider] implementations. A registry belongs
 * to one application runtime, allowing independently composed runtimes to coexist safely.
 *
 * Providers can optionally carry a source identifier for bulk unregistration, which is useful
 * for dynamic provider sources that may be reloaded at runtime.
 */
public class ApiRegistry {
    private val providers: MutableMap<String, RegisteredProvider> = ConcurrentHashMap()

    public fun register(provider: ApiProvider, sourceId: String? = null): Unit {
        providers[provider.api.value] = RegisteredProvider(provider = provider, sourceId = sourceId)
    }

    public fun get(api: ApiId): ApiProvider? = providers[api.value]?.provider

    public fun list(): List<ApiProvider> = providers.values.map { it.provider }

    public fun unregisterBySource(sourceId: String): Unit {
        providers.entries.removeIf { (_, entry) -> entry.sourceId == sourceId }
    }

    public fun clear(): Unit {
        providers.clear()
    }

    private data class RegisteredProvider(
        val provider: ApiProvider,
        val sourceId: String?,
    )
}
