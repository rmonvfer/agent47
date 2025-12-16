package co.agentmode.agent47.coding.core.auth

import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthStorageTest {
    @Test
    fun `api key resolution order honors runtime overrides`() = runTest {
        val dir = createTempDirectory("agent47-auth")
        val storage = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> "env-key" })
        storage.set("openai", ApiKeyCredential(key = "disk-key"))

        storage.setRuntimeApiKey("openai", "runtime-key")
        val apiKey = storage.getApiKey("openai")

        assertEquals("runtime-key", apiKey)
    }

    @Test
    fun `hasAuth returns true when fallback resolver provides value`() {
        val dir = createTempDirectory("agent47-auth-fallback")
        val storage = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
        storage.setFallbackResolver { provider -> if (provider == "custom") "custom-key" else null }

        assertTrue(storage.hasAuth("custom"))
    }
}
