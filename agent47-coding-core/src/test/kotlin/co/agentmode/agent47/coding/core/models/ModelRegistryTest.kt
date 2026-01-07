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

    @Test
    fun `loads models from YAML config`() {
        val dir = createTempDirectory("agent47-models-yaml")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
        val modelsYml = dir.resolve("models.yml")
        modelsYml.writeText(
            """
            providers:
              my-provider:
                baseUrl: "https://api.example.com/v1"
                apiKey: "sk-yaml-test"
                api: "openai-completions"
                models:
                  - id: "yaml-model-1"
                    name: "YAML Model One"
                    reasoning: false
                    input:
                      - text
                    contextWindow: 64000
                    maxTokens: 2048
            """.trimIndent(),
        )

        val registry = ModelRegistry(authStorage = auth, modelsConfigPath = modelsYml)
        val model = registry.find("my-provider", "yaml-model-1")

        assertNotNull(model)
        assertEquals("YAML Model One", model.name)
        assertEquals(64000, model.contextWindow)
        assertEquals(2048, model.maxTokens)
    }

    @Test
    fun `YAML config takes precedence over legacy JSON`() {
        val dir = createTempDirectory("agent47-models-precedence")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })

        val modelsYml = dir.resolve("models.yml")
        modelsYml.writeText(
            """
            providers:
              test-prov:
                baseUrl: "https://yaml.example.com/v1"
                apiKey: "sk-yaml"
                api: "openai-completions"
                models:
                  - id: "shared-model"
                    name: "From YAML"
            """.trimIndent(),
        )

        val modelsJson = dir.resolve("models.json")
        modelsJson.writeText(
            """
            {
              "providers": {
                "test-prov": {
                  "baseUrl": "https://json.example.com/v1",
                  "apiKey": "sk-json",
                  "api": "openai-completions",
                  "models": [
                    {
                      "id": "shared-model",
                      "name": "From JSON"
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val registry = ModelRegistry(
            authStorage = auth,
            modelsConfigPath = modelsYml,
            modelsJsonLegacyPath = modelsJson,
        )
        val model = registry.find("test-prov", "shared-model")

        assertNotNull(model)
        assertEquals("From YAML", model.name)
    }

    @Test
    fun `provider overrides modify built-in model properties`() {
        val dir = createTempDirectory("agent47-overrides")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })

        val modelsYml = dir.resolve("models.yml")
        modelsYml.writeText(
            """
            providers:
              openai:
                baseUrl: "https://custom-proxy.example.com/v1"
                modelOverrides:
                  gpt-4.1-mini:
                    id: "gpt-4.1-mini"
                    maxTokens: 32000
                    name: "GPT 4.1 Mini (Proxy)"
            """.trimIndent(),
        )

        val registry = ModelRegistry(authStorage = auth, modelsConfigPath = modelsYml)
        val model = registry.find("openai", "gpt-4.1-mini")

        assertNotNull(model)
        assertEquals("GPT 4.1 Mini (Proxy)", model.name)
        assertEquals(32000, model.maxTokens)
        assertEquals("https://custom-proxy.example.com/v1", model.baseUrl)
    }

    @Test
    fun `reports error for malformed YAML config`() {
        val dir = createTempDirectory("agent47-bad-yaml")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })

        val modelsYml = dir.resolve("models.yml")
        modelsYml.writeText("this is not valid yaml: [[[")

        val registry = ModelRegistry(authStorage = auth, modelsConfigPath = modelsYml)

        assertNotNull(registry.getError())
        assertTrue(registry.getError()!!.contains("Failed to parse"))
    }
}
