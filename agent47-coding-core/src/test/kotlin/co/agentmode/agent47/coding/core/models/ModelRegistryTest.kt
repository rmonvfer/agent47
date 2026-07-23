package co.agentmode.agent47.coding.core.models

import co.agentmode.agent47.coding.core.auth.ApiKeyCredential
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelRegistryTest {
    @Test
    fun `async refresh discovers configured Ollama models`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            val body = """{"models":[{"name":"qwen3:8b"},{"name":"gemma3:4b"}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val dir = createTempDirectory("agent47-ollama")
            val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
            val modelsYml = dir.resolve("models.yml")
            modelsYml.writeText(
                """
                providers:
                  ollama:
                    baseUrl: "http://127.0.0.1:${server.address.port}"
                    api: "openai-completions"
                    discovery:
                      type: "ollama"
                """.trimIndent(),
            )
            val registry = ModelRegistry(authStorage = auth, modelsConfigPath = modelsYml)

            registry.refreshAsync()

            assertNotNull(registry.find("ollama", "qwen3:8b"))
            assertNotNull(registry.find("ollama", "gemma3:4b"))
        } finally {
            server.stop(0)
        }
    }

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
    fun `extension models can opt out of authentication and unregister by source`() {
        val dir = createTempDirectory("agent47-extension-models")
        val auth = AuthStorage(dir.resolve("auth.json"), envResolver = { _ -> null })
        val registry = ModelRegistry(authStorage = auth)
        val model = Model(
            id = "local",
            name = "Local",
            api = ApiId("local-api"),
            provider = ProviderId("local-provider"),
            baseUrl = "http://127.0.0.1:8080",
            reasoning = false,
            input = listOf(ModelInputKind.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 2_048,
        )

        registry.registerExtensionModels(
            sourceId = "test-extension",
            models = listOf(model),
            noAuthProviders = setOf("local-provider"),
        )

        assertEquals(model, registry.find("local-provider", "local"))
        assertTrue(model in registry.getAvailable())
        registry.unregisterExtensionModels("test-extension")
        assertEquals(null, registry.find("local-provider", "local"))
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
