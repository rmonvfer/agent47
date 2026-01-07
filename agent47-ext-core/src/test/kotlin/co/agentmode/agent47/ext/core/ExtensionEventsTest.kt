package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolDefinition
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExtensionEventsTest {

    private fun dummyTool(name: String): AgentTool<JsonObject> = object : AgentTool<JsonObject> {
        override val label = name
        override val definition = ToolDefinition(
            name = name,
            description = "Dummy",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )

        override suspend fun execute(
            toolCallId: String,
            parameters: JsonObject,
            onUpdate: AgentToolUpdateCallback<JsonObject>?,
        ): AgentToolResult<JsonObject> {
            return AgentToolResult(content = listOf(TextContent(text = "ok")), details = parameters)
        }
    }

    @Test
    fun `emits ExtensionLoadedEvent on load`() = runTest {
        val eventBus = ExtensionEventBus()
        val runner = ExtensionRunner(eventBus = eventBus)

        val events = mutableListOf<ExtensionEvent>()
        val collectJob = launch {
            eventBus.events.collect { events.add(it) }
        }

        // Ensure collector is subscribed before emitting
        testScheduler.advanceUntilIdle()

        runner.load(ExtensionDefinition(id = "test-ext"))

        testScheduler.advanceUntilIdle()

        assertTrue(events.any { it is ExtensionLoadedEvent && it.extensionId == "test-ext" })
        collectJob.cancel()
    }

    @Test
    fun `emits ToolWrappedEvent when wrapping a tool`() = runTest {
        val eventBus = ExtensionEventBus()
        val runner = ExtensionRunner(eventBus = eventBus)

        val events = mutableListOf<ExtensionEvent>()
        val collectJob = launch {
            eventBus.events.collect { events.add(it) }
        }

        testScheduler.advanceUntilIdle()

        runner.load(
            ExtensionDefinition(
                id = "wrap-ext",
                toolWrapper = object : ToolWrapper {
                    override fun <T> wrap(tool: AgentTool<T>): AgentTool<T> = tool
                },
            ),
        )

        runner.wrapTool(dummyTool("my-tool"))

        testScheduler.advanceUntilIdle()

        val wrapEvents = events.filterIsInstance<ToolWrappedEvent>()
        assertTrue(wrapEvents.any { it.extensionId == "wrap-ext" && it.toolName == "my-tool" })
        collectJob.cancel()
    }

    @Test
    fun `EventBus events flow is accessible from runner`() = runTest {
        val runner = ExtensionRunner()

        val events = mutableListOf<ExtensionEvent>()
        val collectJob = launch {
            runner.events.collect { events.add(it) }
        }

        testScheduler.advanceUntilIdle()

        runner.load(ExtensionDefinition(id = "accessible-ext"))

        testScheduler.advanceUntilIdle()

        assertTrue(events.isNotEmpty())
        assertTrue(events.first() is ExtensionLoadedEvent)
        collectJob.cancel()
    }

    @Test
    fun `ExtensionLoadedEvent has correct type field`() {
        val event = ExtensionLoadedEvent(extensionId = "x")
        assertEquals("extension_loaded", event.type)
    }

    @Test
    fun `ToolWrappedEvent has correct type field`() {
        val event = ToolWrappedEvent(extensionId = "x", toolName = "y")
        assertEquals("tool_wrapped", event.type)
    }
}
