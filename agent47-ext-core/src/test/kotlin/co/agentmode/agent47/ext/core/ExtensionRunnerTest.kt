package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentStartEvent
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.KnownApis
import co.agentmode.agent47.ai.types.KnownProviders
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.ai.types.ToolDefinition
import co.agentmode.agent47.coding.core.compaction.CompactionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionRunnerTest {
    @Test
    fun `runner invokes before and after hooks`() = runTest {
        val runner = ExtensionRunner()
        var afterCount = 0

        runner.load(
            ExtensionDefinition(
                id = "test-ext",
                beforeAgent = { context ->
                    context.messages + UserMessage(content = listOf(TextContent(text = "injected")), timestamp = 2L)
                },
                afterAgent = {
                    afterCount += 1
                },
                registerCommands = { registry ->
                    registry.registerCommand("test", "test command") { _, _ -> }
                },
            ),
        )

        val before =
            runner.runBeforeAgent(listOf(UserMessage(content = listOf(TextContent(text = "hello")), timestamp = 1L)))
        assertEquals(2, before.size)
        runner.runAfterAgent(before)
        assertEquals(1, afterCount)

        val commandNames = runner.commands().map { it.name }
        assertTrue(commandNames.contains("test"))
    }

    @Test
    fun `runner dispatches matching and wildcard lifecycle handlers in load order`() = runTest {
        val runner = ExtensionRunner()
        val calls = mutableListOf<String>()
        runner.load(
            ExtensionDefinition(
                id = "first",
                agentEventHandlers = listOf(
                    RegisteredAgentEventHandler("agent_start") { _, context ->
                        calls += "first:${context.mode}"
                    },
                ),
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "second",
                agentEventHandlers = listOf(
                    RegisteredAgentEventHandler("*") { event, _ ->
                        calls += "second:${event.type}"
                    },
                    RegisteredAgentEventHandler("turn_start") { _, _ ->
                        calls += "unexpected"
                    },
                ),
            ),
        )

        runner.dispatchAgentEvent(AgentStartEvent(), testExtensionContext())

        assertEquals(listOf("first:PRINT", "second:agent_start"), calls)
    }

    @Test
    fun `runner isolates lifecycle handler failures and reports the phase`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "broken",
                agentEventHandlers = listOf(
                    RegisteredAgentEventHandler("agent_start") { _, _ -> error("boom") },
                ),
            ),
        )
        val errorEvent = launch {
            val event = runner.events.first { it is ExtensionErrorEvent } as ExtensionErrorEvent
            assertEquals("broken", event.extensionId)
            assertEquals("event:agent_start", event.phase)
            assertEquals("boom", event.message)
        }

        runner.dispatchAgentEvent(AgentStartEvent(), testExtensionContext())
        errorEvent.join()
    }

    @Test
    fun `compaction hooks can provide a summary and observe completion`() = runTest {
        val runner = ExtensionRunner()
        var completed: AfterCompactionEvent? = null
        val custom = CompactionResult(
            summary = "custom summary",
            firstKeptEntryId = "entry-2",
            tokensBefore = 42,
        )
        runner.load(
            ExtensionDefinition(
                id = "compactor",
                beforeCompaction = { event, _ ->
                    assertEquals(CompactionReason.MANUAL, event.reason)
                    CompactionHookResult(compaction = custom)
                },
                afterCompaction = { event, _ -> completed = event },
            ),
        )
        val context = testExtensionContext()

        val prepared = runner.prepareCompaction(
            BeforeCompactionEvent(emptyList(), context.model, CompactionReason.MANUAL),
            context,
        )
        assertEquals(custom, prepared?.compaction)

        val event = AfterCompactionEvent(custom, CompactionReason.MANUAL, fromExtension = true)
        runner.completeCompaction(event, context)
        assertEquals(event, completed)
    }

    @Test
    fun `tool hooks chain input changes and result patches`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "tools",
                toolCallHooks = listOf(
                    ToolCallHook { event, _ ->
                        ToolCallHookResult(
                            input = buildJsonObject { put("value", event.input.getValue("value").jsonPrimitive.content + "-input") },
                        )
                    },
                ),
                toolResultHooks = listOf(
                    ToolResultHook { event, _ ->
                        ToolResultHookResult(
                            content = event.content + TextContent(text = "patched"),
                        )
                    },
                ),
            ),
        )
        runner.bindContext(::testExtensionContext)
        val tool = runner.wrapTool(object : AgentTool<JsonObject> {
            override val definition = ToolDefinition("echo", "Echo", buildJsonObject { put("type", "object") })
            override val label = "Echo"
            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<JsonObject>?,
            ): AgentToolResult<JsonObject> = AgentToolResult(
                listOf(TextContent(text = parameters.getValue("value").jsonPrimitive.content)),
                parameters,
            )
        })

        val result = tool.execute("call", buildJsonObject { put("value", "original") })

        assertEquals(listOf("original-input", "patched"), result.content.filterIsInstance<TextContent>().map { it.text })
    }

    @Test
    fun `tool call hook can block execution`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "blocker",
                toolCallHooks = listOf(ToolCallHook { _, _ -> ToolCallHookResult(block = true, reason = "nope") }),
            ),
        )
        runner.bindContext(::testExtensionContext)
        var executed = false
        val tool = runner.wrapTool(object : AgentTool<JsonObject> {
            override val definition = ToolDefinition("blocked", "Blocked", buildJsonObject { put("type", "object") })
            override val label = "Blocked"
            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<JsonObject>?,
            ): AgentToolResult<JsonObject> {
                executed = true
                return AgentToolResult(emptyList(), parameters)
            }
        })

        val failure = runCatching { tool.execute("call", buildJsonObject { }) }.exceptionOrNull()

        assertTrue(failure is ExtensionToolBlockedException)
        assertEquals("nope", failure?.message)
        assertEquals(false, executed)
    }

    @Test
    fun `input hooks chain transformations and can handle input`() = runTest {
        val runner = ExtensionRunner()
        runner.load(
            ExtensionDefinition(
                id = "input",
                inputHooks = listOf(
                    InputHook { event, _ -> InputHookResult.Transform(event.text + "-one") },
                    InputHook { event, _ ->
                        if (event.text == "stop-one") InputHookResult.Handled
                        else InputHookResult.Transform(event.text + "-two")
                    },
                ),
            ),
        )
        val context = testExtensionContext()

        assertEquals(
            InputHookResult.Transform("start-one-two"),
            runner.processInput(InputEvent("start", InputSource.INTERACTIVE), context),
        )
        assertEquals(
            InputHookResult.Handled,
            runner.processInput(InputEvent("stop", InputSource.INTERACTIVE), context),
        )
    }

    @Test
    fun `session hooks run in extension load order`() = runTest {
        val runner = ExtensionRunner()
        val calls = mutableListOf<String>()
        runner.load(
            ExtensionDefinition(
                id = "first",
                sessionShutdownHooks = listOf(SessionShutdownHook { event, _ ->
                    calls += "first:shutdown:${event.reason}"
                }),
                sessionStartHooks = listOf(SessionStartHook { event, _ ->
                    calls += "first:start:${event.reason}"
                }),
            ),
        )
        runner.load(
            ExtensionDefinition(
                id = "second",
                sessionShutdownHooks = listOf(SessionShutdownHook { event, _ ->
                    calls += "second:shutdown:${event.reason}"
                }),
                sessionStartHooks = listOf(SessionStartHook { event, _ ->
                    calls += "second:start:${event.reason}"
                }),
            ),
        )
        val context = testExtensionContext()

        runner.shutdownSession(SessionShutdownEvent(SessionShutdownReason.FORK), context)
        runner.startSession(SessionStartEvent(SessionStartReason.FORK), context)

        assertEquals(
            listOf(
                "first:shutdown:FORK",
                "second:shutdown:FORK",
                "first:start:FORK",
                "second:start:FORK",
            ),
            calls,
        )
    }

    @Test
    fun `mutable UI delegates calls and resets to fallback`() = runTest {
        val calls = mutableListOf<String>()
        fun ui(name: String): ExtensionUi = object : ExtensionUi {
            override fun notify(message: String, level: ExtensionNotificationLevel) {
                calls += "$name:$message"
            }
            override suspend fun select(title: String, options: List<String>): String? = options.firstOrNull()
            override suspend fun confirm(title: String, message: String): Boolean = true
            override suspend fun input(title: String, placeholder: String): String? = placeholder
            override suspend fun editor(title: String, initialText: String): String? = initialText
            override fun setStatus(key: String, text: String?) = Unit
            override fun setWidget(key: String, lines: List<String>?) = Unit
            override fun setTitle(title: String?) = Unit
            override fun setEditorText(text: String) = Unit
            override suspend fun getEditorText(): String = name
        }
        val controller = MutableExtensionUi(ui("fallback"))
        controller.notify("one")
        controller.bind(ui("bound"))
        controller.notify("two")
        controller.reset()
        controller.notify("three")

        assertEquals(listOf("fallback:one", "bound:two", "fallback:three"), calls)
    }

    private fun testExtensionContext(): ExtensionContext = object : ExtensionContext {
        override val ui: ExtensionUi = object : ExtensionUi {
            override fun notify(message: String, level: ExtensionNotificationLevel) = Unit
            override suspend fun select(title: String, options: List<String>): String? = null
            override suspend fun confirm(title: String, message: String): Boolean = false
            override suspend fun input(title: String, placeholder: String): String? = null
            override suspend fun editor(title: String, initialText: String): String? = null
            override fun setStatus(key: String, text: String?) = Unit
            override fun setWidget(key: String, lines: List<String>?) = Unit
            override fun setTitle(title: String?) = Unit
            override fun setEditorText(text: String) = Unit
            override suspend fun getEditorText(): String = ""
        }
        override val session: ExtensionSessionControl = object : ExtensionSessionControl {
            override suspend fun newSession(): String? = null
            override suspend fun switchSession(path: Path): Boolean = false
            override suspend fun forkSession(entryId: String?): String? = null
        }
        override val cwd: Path = Path.of(".")
        override val hasUi: Boolean = false
        override val mode: ExtensionMode = ExtensionMode.PRINT
        override val model: Model = Model(
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
        override val availableModels: List<Model> = listOf(model)
        override val thinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF
        override val messages = emptyList<UserMessage>()
        override val isIdle: Boolean = true
        override val systemPrompt: String = ""
        override val availableTools = emptyList<co.agentmode.agent47.agent.core.AgentTool<*>>()
        override val activeToolNames = emptyList<String>()
        override val sessionId: String? = null
        override val sessionEntries = emptyList<co.agentmode.agent47.coding.core.session.SessionEntry>()
        override val sessionName: String? = null
        override val flags: Map<String, String> = emptyMap()
        override fun notify(message: String) = Unit
        override fun notify(message: String, level: ExtensionNotificationLevel) = Unit
        override suspend fun sendUserMessage(message: String) = Unit
        override suspend fun sendUserMessage(message: String, delivery: ExtensionMessageDelivery) = Unit
        override fun registerTool(tool: co.agentmode.agent47.agent.core.AgentTool<*>) = Unit
        override fun unregisterTool(name: String) = Unit
        override fun setActiveTools(names: List<String>) = Unit
        override fun setModel(provider: String, modelId: String) = Unit
        override fun setThinkingLevel(level: AgentThinkingLevel) = Unit
        override fun appendEntry(customType: String, data: kotlinx.serialization.json.JsonObject?) = Unit
        override fun appendMessage(
            customType: String,
            content: String,
            display: Boolean,
            details: kotlinx.serialization.json.JsonObject?,
        ) = Unit
        override fun setSessionName(name: String?) = Unit
        override fun sendMessage(
            customType: String,
            content: String,
            display: Boolean,
            details: kotlinx.serialization.json.JsonObject?,
        ) = Unit
        override fun setLabel(entryId: String, label: String?) = Unit
        override suspend fun waitForIdle() = Unit
        override suspend fun exec(
            command: String,
            args: List<String>,
            timeoutMs: Long,
        ) = ExtensionExecResult("", "", 0, false)
        override fun abort() = Unit
        override suspend fun reload() = Unit
    }
}
