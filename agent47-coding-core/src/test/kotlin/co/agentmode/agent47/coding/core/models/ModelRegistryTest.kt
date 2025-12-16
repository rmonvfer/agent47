package co.agentmode.agent47.coding.core.models

import co.agentmode.agent47.coding.core.auth.ApiKeyCredential
import co.agentmode.agent47.coding.core.auth.AuthStorage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `registry loads built-in models and filters available by auth`() = runTest {
        val dir = createTempDirectory("agent47-registry")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
        val registry = ModelRegistry(authStorage = auth)

        assertTrue(registry.getAll().isNotEmpty())

        auth.set("openai", ApiKeyCredential(key = "test"))
        val available = registry.getAvailable()
        assertTrue(available.any { it.provider.value == "openai" })
    }

    @Test
    fun `custom models override built-in by provider and id`() {
        val dir = createTempDirectory("agent47-models-json")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
        val modelsJson = dir.resolve("models.json")
        modelsJson.writeText(
            """
            {
              "providers": {
                "openai": {
                  "baseUrl": "https://api.openai.com/v1",
                  "apiKey": "sk-test",
                  "api": "openai-responses",
                  "models": [
                    {
                      "id": "gpt-4.1-mini",
                      "name": "GPT 4.1 Mini Custom",
                      "reasoning": true,
                      "input": ["text"],
                      "contextWindow": 120000,
                      "maxTokens": 4096
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val registry = ModelRegistry(authStorage = auth, modelsJsonLegacyPath = modelsJson)
        val model = registry.find("openai", "gpt-4.1-mini")

        assertNotNull(model)
        assertEquals("GPT 4.1 Mini Custom", model.name)
    }
}
