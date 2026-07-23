package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.app.buildSystemPrompt
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.CustomEntry
import co.agentmode.agent47.coding.core.session.CustomMessageEntry
import co.agentmode.agent47.coding.core.session.LabelEntry
import co.agentmode.agent47.coding.core.session.SessionEntry
import co.agentmode.agent47.coding.core.session.SessionInfoEntry
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionExecResult
import co.agentmode.agent47.ext.core.ExtensionMessageDelivery
import co.agentmode.agent47.ext.core.ExtensionMode
import co.agentmode.agent47.ext.core.ExtensionNotificationLevel
import co.agentmode.agent47.ext.core.ExtensionSessionControl
import co.agentmode.agent47.ext.core.ExtensionUi
import co.agentmode.agent47.ext.core.InputEvent
import co.agentmode.agent47.ext.core.InputHookResult
import co.agentmode.agent47.ext.core.InputSource
import co.agentmode.agent47.ext.core.InputStreamingBehavior
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class CliExtensionContext(
    override val ui: ExtensionUi,
    override val session: ExtensionSessionControl,
    override val cwd: Path,
    private val printMode: Boolean,
    private val client: AgentClient,
    private val modelRegistry: ModelRegistry,
    private val toolCatalog: MutableMap<String, AgentTool<*>>,
    private val sessionTracker: SessionTracker,
    private val extensionRuntime: KotlinExtensionRuntime,
    private val customPrompt: String?,
    private val appendPrompt: String?,
    private val skillRegistry: SkillRegistry,
    private val instructionLoader: InstructionLoader,
    private val reloader: ExtensionReloader,
) : ExtensionContext {
    override val hasUi: Boolean
        get() = mode == ExtensionMode.TUI
    override val mode: ExtensionMode
        get() = if (printMode || System.console() == null) ExtensionMode.PRINT else ExtensionMode.TUI
    override val model: Model
        get() = client.state.model
    override val availableModels: List<Model>
        get() = modelRegistry.getAvailable()
    override val thinkingLevel: AgentThinkingLevel
        get() = client.state.thinkingLevel
    override val messages: List<Message>
        get() = client.state.messages
    override val isIdle: Boolean
        get() = !client.state.isStreaming
    override val systemPrompt: String
        get() = client.state.systemPrompt
    override val availableTools: List<AgentTool<*>>
        get() = toolCatalog.values.toList()
    override val activeToolNames: List<String>
        get() = client.state.tools.map { it.definition.name }
    override val sessionId: String?
        get() = sessionTracker.current?.getHeader()?.id
    override val sessionEntries: List<SessionEntry>
        get() = sessionTracker.current?.getEntries().orEmpty()
    override val sessionName: String?
        get() = sessionEntries.filterIsInstance<SessionInfoEntry>().lastOrNull()?.name
    override val flags: Map<String, String>
        get() = extensionRuntime.runner.flags().mapNotNull { flag ->
            flag.value?.let { flag.name to it }
        }.toMap()

    override fun notify(message: String) {
        notify(message, ExtensionNotificationLevel.INFO)
    }

    override fun notify(message: String, level: ExtensionNotificationLevel) {
        ui.notify(message, level)
    }

    override suspend fun sendUserMessage(message: String) {
        sendUserMessage(message, ExtensionMessageDelivery.FOLLOW_UP)
    }

    override suspend fun sendUserMessage(message: String, delivery: ExtensionMessageDelivery) {
        val inputResult = extensionRuntime.runner.processInput(
            InputEvent(
                text = message,
                source = InputSource.EXTENSION,
                streamingBehavior = when (delivery) {
                    ExtensionMessageDelivery.STEER -> InputStreamingBehavior.STEER
                    ExtensionMessageDelivery.FOLLOW_UP -> InputStreamingBehavior.FOLLOW_UP
                },
            ),
            this,
        )
        if (inputResult == InputHookResult.Handled) return
        val processedText = when (inputResult) {
            is InputHookResult.Transform -> inputResult.text
            else -> message
        }
        val userMessage = UserMessage(
            content = listOf(TextContent(text = processedText)),
            timestamp = System.currentTimeMillis(),
        )
        if (client.state.isStreaming) {
            when (delivery) {
                ExtensionMessageDelivery.STEER -> client.steer(userMessage)
                ExtensionMessageDelivery.FOLLOW_UP -> client.followUp(userMessage)
            }
        } else {
            client.prompt(listOf(userMessage))
        }
    }

    override fun registerTool(tool: AgentTool<*>) {
        val name = tool.definition.name
        require(name !in toolCatalog) { "Tool is already registered: $name" }
        val wrapped = extensionRuntime.runner.wrapTool(tool)
        toolCatalog[name] = wrapped
        client.setTools(client.state.tools + wrapped)
        updateSystemPromptForTools(client.state.tools)
    }

    override fun unregisterTool(name: String) {
        require(toolCatalog.remove(name) != null) { "Tool is not registered: $name" }
        val active = client.state.tools.filterNot { it.definition.name == name }
        client.setTools(active)
        updateSystemPromptForTools(active)
    }

    override fun setActiveTools(names: List<String>) {
        require(names.distinct().size == names.size) { "Active tool names must be unique" }
        val unknown = names.filterNot(toolCatalog::containsKey)
        require(unknown.isEmpty()) { "Unknown tools: ${unknown.joinToString()}" }
        val active = names.map(toolCatalog::getValue)
        client.setTools(active)
        updateSystemPromptForTools(active)
    }

    override fun setModel(provider: String, modelId: String) {
        val selected = modelRegistry.find(provider, modelId)
            ?: error("Model not found: $provider/$modelId")
        client.setModel(selected)
    }

    override fun setThinkingLevel(level: AgentThinkingLevel) {
        client.setThinkingLevel(level)
    }

    override fun appendEntry(customType: String, data: JsonObject?) {
        val manager = requireNotNull(sessionTracker.current) {
            "Session persistence is disabled"
        }
        manager.append(
            CustomEntry(
                id = UUID.randomUUID().toString().substring(0, 8),
                parentId = manager.getLeafId(),
                timestamp = Instant.now().toString(),
                customType = customType,
                data = data,
            ),
        )
    }

    override fun appendMessage(
        customType: String,
        content: String,
        display: Boolean,
        details: JsonObject?,
    ) {
        val manager = requireNotNull(sessionTracker.current) {
            "Session persistence is disabled"
        }
        manager.append(
            CustomMessageEntry(
                id = UUID.randomUUID().toString().substring(0, 8),
                parentId = manager.getLeafId(),
                timestamp = Instant.now().toString(),
                customType = customType,
                content = listOf(TextContent(text = content)),
                display = display,
                details = details,
            ),
        )
    }

    override fun sendMessage(
        customType: String,
        content: String,
        display: Boolean,
        details: JsonObject?,
    ) {
        val message = CustomMessage(
            customType = customType,
            content = listOf(TextContent(text = content)),
            display = display,
            details = details,
            timestamp = System.currentTimeMillis(),
        )
        client.appendMessage(message)
        sessionTracker.current?.let { manager ->
            manager.append(
                CustomMessageEntry(
                    id = UUID.randomUUID().toString().substring(0, 8),
                    parentId = manager.getLeafId(),
                    timestamp = Instant.now().toString(),
                    customType = customType,
                    content = listOf(TextContent(text = content)),
                    display = display,
                    details = details,
                ),
            )
        }
    }

    override fun setSessionName(name: String?) {
        val manager = requireNotNull(sessionTracker.current) {
            "Session persistence is disabled"
        }
        manager.append(
            SessionInfoEntry(
                id = UUID.randomUUID().toString().substring(0, 8),
                parentId = manager.getLeafId(),
                timestamp = Instant.now().toString(),
                name = name?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
    }

    override fun setLabel(entryId: String, label: String?) {
        val manager = requireNotNull(sessionTracker.current) {
            "Session persistence is disabled"
        }
        require(manager.getEntry(entryId) != null) { "Session entry not found: $entryId" }
        manager.append(
            LabelEntry(
                id = UUID.randomUUID().toString().substring(0, 8),
                parentId = manager.getLeafId(),
                timestamp = Instant.now().toString(),
                targetId = entryId,
                label = label,
            ),
        )
    }

    override suspend fun waitForIdle() {
        client.waitForIdle()
    }

    override suspend fun exec(
        command: String,
        args: List<String>,
        timeoutMs: Long,
    ): ExtensionExecResult = withContext(Dispatchers.IO) {
        require(command.isNotBlank()) { "Command cannot be blank" }
        require(timeoutMs > 0) { "Command timeout must be positive" }
        val process = ProcessBuilder(listOf(command) + args)
            .directory(cwd.toFile())
            .start()
        coroutineScope {
            val stdout = async { process.inputStream.bufferedReader().use { it.readText() } }
            val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            ExtensionExecResult(
                stdout = stdout.await(),
                stderr = stderr.await(),
                code = if (finished) process.exitValue() else 124,
                killed = !finished,
            )
        }
    }

    override fun abort() {
        client.abort()
    }

    override suspend fun reload() {
        reloader.reload()
    }

    private fun updateSystemPromptForTools(tools: List<AgentTool<*>>) {
        client.setSystemPrompt(
            buildSystemPrompt(
                cwd = cwd,
                toolNames = tools.map { it.definition.name },
                customPrompt = customPrompt,
                appendPrompt = appendPrompt,
                skills = skillRegistry.getAll(),
                instructions = instructionLoader.format(),
            ),
        )
    }
}
