package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.StopReason
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.emptyUsage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.CustomMessageEntry
import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionExecResult
import co.agentmode.agent47.ext.core.ExtensionMessageDelivery
import co.agentmode.agent47.ext.core.ExtensionMode
import co.agentmode.agent47.ext.core.ExtensionNotificationLevel
import co.agentmode.agent47.ext.core.ExtensionSessionControl
import co.agentmode.agent47.ext.core.ExtensionUi
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject

class SessionTreeServiceTest {
    @Test
    fun `navigating to a user message moves the leaf to its parent and returns the message text`() = runTest {
        val manager = SessionManager(createTempDirectory("agent47-session-tree").resolve("session.jsonl"))
        val root = manager.appendMessage(userMessage("root"))
        val target = manager.appendMessage(userMessage("target text"))
        manager.appendMessage(userMessage("leaf"))
        val service = service(manager)

        val result = service.navigateTree(target.id, summarize = false)

        assertEquals(root.id, result.newLeafId)
        assertEquals("target text", result.editorText)
        assertEquals(root.id, manager.getLeafId())
    }

    @Test
    fun `navigating to a root user message resets the leaf`() = runTest {
        val manager = SessionManager(createTempDirectory("agent47-session-tree").resolve("session.jsonl"))
        val root = manager.appendMessage(userMessage("root"))
        manager.appendMessage(userMessage("leaf"))
        val service = service(manager)

        val result = service.navigateTree(root.id, summarize = false)

        assertNull(result.newLeafId)
        assertEquals("root", result.editorText)
        assertNull(manager.getLeafId())
    }

    @Test
    fun `navigating to a non-user entry moves the leaf to that entry itself`() = runTest {
        val manager = SessionManager(createTempDirectory("agent47-session-tree").resolve("session.jsonl"))
        manager.appendMessage(userMessage("root"))
        val assistantEntry = manager.appendMessage(assistantMessage("assistant reply"))
        manager.appendMessage(userMessage("leaf"))
        val service = service(manager)

        val result = service.navigateTree(assistantEntry.id, summarize = false)

        assertEquals(assistantEntry.id, result.newLeafId)
        assertNull(result.editorText)
        assertEquals(assistantEntry.id, manager.getLeafId())
    }

    @Test
    fun `navigating to a custom message moves the leaf to its parent and returns its text`() = runTest {
        val manager = SessionManager(createTempDirectory("agent47-session-tree").resolve("session.jsonl"))
        val root = manager.appendMessage(userMessage("root"))
        manager.append(
            CustomMessageEntry(
                id = "custom-1",
                parentId = root.id,
                timestamp = Instant.now().toString(),
                customType = "note",
                content = listOf(TextContent(text = "custom text")),
                display = true,
            ),
        )
        manager.appendMessage(userMessage("leaf"))
        val service = service(manager)

        val result = service.navigateTree("custom-1", summarize = false)

        assertEquals(root.id, result.newLeafId)
        assertEquals("custom text", result.editorText)
    }

    @Test
    fun `navigating to the current leaf is a no-op`() = runTest {
        val manager = SessionManager(createTempDirectory("agent47-session-tree").resolve("session.jsonl"))
        val leaf = manager.appendMessage(userMessage("root"))
        val service = service(manager)

        val result = service.navigateTree(leaf.id, summarize = false)

        assertEquals(leaf.id, result.newLeafId)
        assertEquals(leaf.id, manager.getLeafId())
    }

    private fun service(manager: SessionManager): SessionTreeService {
        val model = testModel()
        val client = AgentClient(
            AgentOptions(
                streamFunction = { _, _, _ -> error("not expected: no summarization requested") },
                initialState = PartialAgentState(model = model),
            ),
        )
        val auth = AuthStorage(createTempDirectory("agent47-session-tree-auth").resolve("auth.json"), envResolver = { null })
        return SessionTreeService(
            sessionTracker = SessionTracker(manager),
            client = client,
            extensionRuntime = KotlinExtensionRuntime(emptyList()),
            extensionContext = fakeExtensionContext(model),
            settings = SettingsManager.inMemory(),
            aiRuntime = AiRuntime(ApiRegistry()),
            modelRegistry = ModelRegistry(authStorage = auth),
        )
    }

    private fun testModel(): Model = Model(
        id = "test",
        name = "Test",
        api = KnownApis.OpenAiCompletions,
        provider = KnownProviders.OpenAi,
        baseUrl = "https://example.test",
        reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 1_000,
        maxTokens = 100,
    )

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = listOf(TextContent(text = text)), timestamp = System.currentTimeMillis())

    private fun assistantMessage(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text = text)),
        api = KnownApis.OpenAiResponses,
        provider = ProviderId("openai"),
        model = "mock",
        usage = emptyUsage(),
        stopReason = StopReason.STOP,
        timestamp = System.currentTimeMillis(),
    )

    // No extensions are loaded in these tests, so ExtensionRunner.prepareTree/completeTree never
    // invoke any member here; every method is unreachable and only exists to satisfy the interface.
    private fun fakeExtensionContext(testModel: Model): ExtensionContext = object : ExtensionContext {
        override val ui: ExtensionUi get() = error("not expected")
        override val session: ExtensionSessionControl get() = error("not expected")
        override val cwd: Path get() = error("not expected")
        override val hasUi: Boolean get() = error("not expected")
        override val mode: ExtensionMode get() = ExtensionMode.PRINT
        override val model: Model get() = testModel
        override val availableModels: List<Model> get() = error("not expected")
        override val thinkingLevel: AgentThinkingLevel get() = error("not expected")
        override val messages get() = error("not expected")
        override val isIdle: Boolean get() = error("not expected")
        override val systemPrompt: String get() = error("not expected")
        override val availableTools: List<AgentTool<*>> get() = error("not expected")
        override val activeToolNames: List<String> get() = error("not expected")
        override val sessionId: String? get() = error("not expected")
        override val sessionEntries: List<SessionEntry> get() = error("not expected")
        override val sessionName: String? get() = error("not expected")
        override val flags: Map<String, String> get() = error("not expected")
        override fun notify(message: String) = error("not expected")
        override fun notify(message: String, level: ExtensionNotificationLevel) = error("not expected")
        override suspend fun sendUserMessage(message: String) = error("not expected")
        override suspend fun sendUserMessage(message: String, delivery: ExtensionMessageDelivery) = error("not expected")
        override fun registerTool(tool: AgentTool<*>) = error("not expected")
        override fun unregisterTool(name: String) = error("not expected")
        override fun setActiveTools(names: List<String>) = error("not expected")
        override fun setModel(provider: String, modelId: String) = error("not expected")
        override fun setThinkingLevel(level: AgentThinkingLevel) = error("not expected")
        override fun appendEntry(customType: String, data: JsonObject?) = error("not expected")
        override fun appendMessage(
            customType: String,
            content: String,
            display: Boolean,
            details: JsonObject?,
        ) = error("not expected")
        override fun sendMessage(
            customType: String,
            content: String,
            display: Boolean,
            details: JsonObject?,
        ) = error("not expected")
        override fun setSessionName(name: String?) = error("not expected")
        override fun setLabel(entryId: String, label: String?) = error("not expected")
        override suspend fun waitForIdle() = error("not expected")
        override suspend fun exec(command: String, args: List<String>, timeoutMs: Long): ExtensionExecResult =
            error("not expected")
        override fun abort() = error("not expected")
        override suspend fun reload() = error("not expected")
    }
}
