package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentToolResult
import co.agentmode.agent47.agent.core.AgentToolUpdateCallback
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.coding.core.compaction.CompactionResult
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.file.Path

public data class BeforeAgentContext(
    val messages: List<Message>,
)

public data class AfterAgentContext(
    val messages: List<Message>,
)

public data class ToolWrapContext(
    val toolName: String,
)

public data class ExtensionExecResult(
    val stdout: String,
    val stderr: String,
    val code: Int,
    val killed: Boolean,
)

public fun interface BeforeAgentHook {
    public suspend fun run(context: BeforeAgentContext): List<Message>
}

public fun interface AfterAgentHook {
    public suspend fun run(context: AfterAgentContext)
}

public fun interface ContextTransformHook {
    public suspend fun run(context: Context): Context
}

public interface ToolWrapper {
    public fun <T> wrap(tool: AgentTool<T>): AgentTool<T>
}

public interface CommandRegistry {
    public fun registerCommand(
        name: String,
        description: String,
        handler: ExtensionCommandHandler,
    )

    public fun listCommands(): List<RegisteredCommand>
}

public interface ExtensionCommandContext {
    public val cwd: Path
    public val hasUi: Boolean

    public fun notify(message: String)
    public suspend fun sendUserMessage(message: String)
    public suspend fun reload()
}

public enum class ExtensionMode {
    TUI,
    PRINT,
}

public enum class ExtensionNotificationLevel {
    INFO,
    WARNING,
    ERROR,
}

public interface ExtensionUi {
    public fun notify(message: String, level: ExtensionNotificationLevel = ExtensionNotificationLevel.INFO)
    public suspend fun select(title: String, options: List<String>): String?
    public suspend fun confirm(title: String, message: String): Boolean
    public suspend fun input(title: String, placeholder: String = ""): String?
    public suspend fun editor(title: String, initialText: String = ""): String?
    public fun setStatus(key: String, text: String?)
    public fun setWidget(key: String, lines: List<String>?)
    public fun setTitle(title: String?)
    public fun setEditorText(text: String)
    public suspend fun getEditorText(): String
}

@Suppress("TooManyFunctions")
public class MutableExtensionUi(
    private val fallback: ExtensionUi,
) : ExtensionUi {
    @Volatile
    private var delegate: ExtensionUi = fallback

    public fun bind(ui: ExtensionUi) {
        delegate = ui
    }

    public fun reset() {
        delegate = fallback
    }

    override fun notify(message: String, level: ExtensionNotificationLevel): Unit = delegate.notify(message, level)
    override suspend fun select(title: String, options: List<String>): String? = delegate.select(title, options)
    override suspend fun confirm(title: String, message: String): Boolean = delegate.confirm(title, message)
    override suspend fun input(title: String, placeholder: String): String? = delegate.input(title, placeholder)
    override suspend fun editor(title: String, initialText: String): String? = delegate.editor(title, initialText)
    override fun setStatus(key: String, text: String?): Unit = delegate.setStatus(key, text)
    override fun setWidget(key: String, lines: List<String>?): Unit = delegate.setWidget(key, lines)
    override fun setTitle(title: String?): Unit = delegate.setTitle(title)
    override fun setEditorText(text: String): Unit = delegate.setEditorText(text)
    override suspend fun getEditorText(): String = delegate.getEditorText()
}

public interface ExtensionSessionControl {
    public suspend fun newSession(): String?
    public suspend fun switchSession(path: Path): Boolean
    public suspend fun forkSession(entryId: String? = null): String?
}

public class MutableExtensionSessionControl(
    private val fallback: ExtensionSessionControl,
) : ExtensionSessionControl {
    @Volatile
    private var delegate: ExtensionSessionControl = fallback

    public fun bind(control: ExtensionSessionControl) {
        delegate = control
    }

    public fun reset() {
        delegate = fallback
    }

    override suspend fun newSession(): String? = delegate.newSession()
    override suspend fun switchSession(path: Path): Boolean = delegate.switchSession(path)
    override suspend fun forkSession(entryId: String?): String? = delegate.forkSession(entryId)
}

public enum class ExtensionMessageDelivery {
    STEER,
    FOLLOW_UP,
}

public enum class CompactionReason {
    MANUAL,
    THRESHOLD,
    OVERFLOW,
}

public data class BeforeCompactionEvent(
    val messages: List<Message>,
    val model: Model,
    val reason: CompactionReason,
)

public data class AfterCompactionEvent(
    val compaction: CompactionResult,
    val reason: CompactionReason,
    val fromExtension: Boolean,
)

public data class CompactionHookResult(
    val cancel: Boolean = false,
    val compaction: CompactionResult? = null,
)

public data class PreparedCompaction(
    val compaction: CompactionResult,
    val fromExtension: Boolean,
)

public fun interface BeforeCompactionHook {
    public suspend fun run(event: BeforeCompactionEvent, context: ExtensionContext): CompactionHookResult?
}

public fun interface AfterCompactionHook {
    public suspend fun run(event: AfterCompactionEvent, context: ExtensionContext)
}

public enum class SessionStartReason {
    STARTUP,
    RELOAD,
    NEW,
    RESUME,
    FORK,
}

public enum class SessionShutdownReason {
    QUIT,
    RELOAD,
    NEW,
    RESUME,
    FORK,
}

public data class SessionStartEvent(
    val reason: SessionStartReason,
    val previousSessionFile: Path? = null,
)

public data class SessionShutdownEvent(
    val reason: SessionShutdownReason,
    val targetSessionFile: Path? = null,
)

public fun interface SessionStartHook {
    public suspend fun run(event: SessionStartEvent, context: ExtensionContext)
}

public fun interface SessionShutdownHook {
    public suspend fun run(event: SessionShutdownEvent, context: ExtensionContext)
}

/**
 * Provides an extension with a live view of the running agent and safe controls
 * for interacting with it. Values are snapshots taken when the context is read.
 */
@Suppress("TooManyFunctions")
public interface ExtensionContext : ExtensionCommandContext {
    public val ui: ExtensionUi
    public val session: ExtensionSessionControl
    public val mode: ExtensionMode
    public val model: Model
    public val availableModels: List<Model>
    public val thinkingLevel: AgentThinkingLevel
    public val messages: List<Message>
    public val isIdle: Boolean
    public val systemPrompt: String
    public val availableTools: List<AgentTool<*>>
    public val activeToolNames: List<String>
    public val sessionId: String?
    public val sessionEntries: List<SessionEntry>
    public val sessionName: String?
    public val flags: Map<String, String>

    public fun notify(message: String, level: ExtensionNotificationLevel)
    public suspend fun sendUserMessage(message: String, delivery: ExtensionMessageDelivery)
    public fun registerTool(tool: AgentTool<*>)
    public fun unregisterTool(name: String)
    public fun setActiveTools(names: List<String>)
    public fun setModel(provider: String, modelId: String)
    public fun setThinkingLevel(level: AgentThinkingLevel)
    public fun appendEntry(customType: String, data: JsonObject? = null)
    public fun appendMessage(
        customType: String,
        content: String,
        display: Boolean = true,
        details: JsonObject? = null,
    )
    public fun sendMessage(
        customType: String,
        content: String,
        display: Boolean = true,
        details: JsonObject? = null,
    )
    public fun setSessionName(name: String?)
    public fun setLabel(entryId: String, label: String?)
    public suspend fun waitForIdle()
    public suspend fun exec(
        command: String,
        args: List<String> = emptyList(),
        timeoutMs: Long = 30_000,
    ): ExtensionExecResult
    public fun abort()
}

public fun interface AgentEventHandler {
    public suspend fun run(event: AgentEvent, context: ExtensionContext)
}

public data class RegisteredAgentEventHandler(
    val eventType: String,
    val handler: AgentEventHandler,
)

public data class ExtensionProvider(
    val provider: ApiProvider,
    val models: List<Model>,
    val apiKey: String? = null,
    val requiresAuth: Boolean = true,
)

public data class ToolCallEvent(
    val toolCallId: String,
    val toolName: String,
    val input: JsonObject,
)

public data class ToolCallHookResult(
    val input: JsonObject? = null,
    val block: Boolean = false,
    val reason: String? = null,
)

public data class ToolResultEvent(
    val toolCallId: String,
    val toolName: String,
    val input: JsonObject,
    val content: List<ContentBlock>,
    val details: Any?,
)

public data class ToolResultHookResult(
    val content: List<ContentBlock>? = null,
    val details: Any? = null,
    val replaceDetails: Boolean = false,
)

public enum class InputSource {
    INTERACTIVE,
    EXTENSION,
}

public enum class InputStreamingBehavior {
    STEER,
    FOLLOW_UP,
}

public data class InputEvent(
    val text: String,
    val source: InputSource,
    val streamingBehavior: InputStreamingBehavior? = null,
)

public sealed interface InputHookResult {
    public data object Continue : InputHookResult
    public data class Transform(val text: String) : InputHookResult
    public data object Handled : InputHookResult
}

public fun interface InputHook {
    public suspend fun run(event: InputEvent, context: ExtensionContext): InputHookResult?
}

public fun interface ToolCallHook {
    public suspend fun run(event: ToolCallEvent, context: ExtensionContext): ToolCallHookResult?
}

public fun interface ToolResultHook {
    public suspend fun run(event: ToolResultEvent, context: ExtensionContext): ToolResultHookResult?
}

public class ExtensionToolBlockedException(message: String) : IllegalStateException(message)

public fun interface ExtensionCommandHandler {
    public suspend fun run(args: String, context: ExtensionCommandContext)
}

public data class RegisteredCommand(
    val name: String,
    val description: String,
    val handler: ExtensionCommandHandler,
)

public fun interface ExtensionShortcutHandler {
    public suspend fun run(context: ExtensionContext)
}

public data class RegisteredShortcut(
    val key: String,
    val description: String,
    val handler: ExtensionShortcutHandler,
)

public enum class ExtensionFlagType {
    BOOLEAN,
    STRING,
}

public data class RegisteredExtensionFlag(
    val name: String,
    val description: String,
    val type: ExtensionFlagType,
    val defaultValue: String?,
    val value: String?,
)

public data class ExtensionResources(
    val commands: List<RegisteredCommand>,
    val shortcuts: List<RegisteredShortcut>,
    val toolRenderers: List<RegisteredToolRenderer>,
    val messageRenderers: List<RegisteredMessageRenderer>,
    val flags: List<RegisteredExtensionFlag>,
)

public data class ToolRenderData(
    val toolCallId: String,
    val toolName: String,
    val arguments: String,
    val output: String,
    val details: Any?,
    val isError: Boolean,
    val pending: Boolean,
    val collapsed: Boolean,
)

public fun interface ToolRenderer {
    public fun render(data: ToolRenderData, width: Int): List<String>?
}

public data class RegisteredToolRenderer(
    val toolName: String,
    val renderer: ToolRenderer,
)

public fun interface MessageRenderer {
    public fun render(message: CustomMessage, width: Int): List<String>?
}

public data class RegisteredMessageRenderer(
    val customType: String,
    val renderer: MessageRenderer,
)

public sealed interface ExtensionEvent {
    public val type: String
}

public data class ExtensionLoadedEvent(
    val extensionId: String,
    override val type: String = "extension_loaded",
) : ExtensionEvent

public data class ToolWrappedEvent(
    val extensionId: String,
    val toolName: String,
    override val type: String = "tool_wrapped",
) : ExtensionEvent

public data class ExtensionErrorEvent(
    val extensionId: String,
    val phase: String,
    val message: String,
    override val type: String = "extension_error",
) : ExtensionEvent

public class ExtensionEventBus {
    // replay keeps recent lifecycle events (e.g. extension_loaded, emitted at load time before any
    // collector attaches) available to subscribers that connect later.
    private val mutableEvents: MutableSharedFlow<ExtensionEvent> =
        MutableSharedFlow(replay = 64, extraBufferCapacity = 1_024)

    public val events: SharedFlow<ExtensionEvent> = mutableEvents.asSharedFlow()

    public fun emit(event: ExtensionEvent) {
        mutableEvents.tryEmit(event)
    }
}

/**
 * Declares a set of hooks that plug into the agent execution pipeline. Extensions
 * are loaded into an [ExtensionRunner] and compose in load order: before-agent hooks
 * chain their message transformations, context transforms layer on top of each other,
 * and tool wrappers nest.
 *
 * All hooks are optional. A minimal extension might only wrap tools or only register
 * custom slash commands.
 *
 * @property id unique identifier for this extension, used in events and diagnostics
 * @property beforeAgent runs before the agent processes input; can modify messages
 * @property afterAgent runs after the agent completes; useful for logging or cleanup
 * @property transformContext transforms the AI context before it is sent to the LLM
 * @property toolWrapper wraps individual tools to intercept or modify their behavior
 * @property agentEventHandlers observes agent lifecycle events in registration order
 * @property registerCommands registers custom slash commands at load time
 */
public data class ExtensionDefinition(
    val id: String,
    val beforeAgent: BeforeAgentHook? = null,
    val afterAgent: AfterAgentHook? = null,
    val transformContext: ContextTransformHook? = null,
    val toolWrapper: ToolWrapper? = null,
    val tools: List<AgentTool<*>> = emptyList(),
    val agentEventHandlers: List<RegisteredAgentEventHandler> = emptyList(),
    val beforeCompaction: BeforeCompactionHook? = null,
    val afterCompaction: AfterCompactionHook? = null,
    val providers: List<ExtensionProvider> = emptyList(),
    val toolCallHooks: List<ToolCallHook> = emptyList(),
    val toolResultHooks: List<ToolResultHook> = emptyList(),
    val shortcuts: List<RegisteredShortcut> = emptyList(),
    val toolRenderers: List<RegisteredToolRenderer> = emptyList(),
    val messageRenderers: List<RegisteredMessageRenderer> = emptyList(),
    val flags: List<RegisteredExtensionFlag> = emptyList(),
    val inputHooks: List<InputHook> = emptyList(),
    val sessionStartHooks: List<SessionStartHook> = emptyList(),
    val sessionShutdownHooks: List<SessionShutdownHook> = emptyList(),
    val registerCommands: ((CommandRegistry) -> Unit)? = null,
)

public class InMemoryCommandRegistry : CommandRegistry {
    private val commands: MutableMap<String, RegisteredCommand> = linkedMapOf()

    override fun registerCommand(
        name: String,
        description: String,
        handler: ExtensionCommandHandler,
    ) {
        require(name.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]*"))) { "Invalid extension command name: $name" }
        require(name !in commands) { "Duplicate extension command: $name" }
        commands[name] = RegisteredCommand(name, description, handler)
    }

    override fun listCommands(): List<RegisteredCommand> = commands.values.toList()
}

/**
 * Loads and sequences [ExtensionDefinition] instances. Multiple extensions compose:
 * hooks run in the order extensions were loaded, and tool wrappers nest so that
 * the last-loaded extension's wrapper is the outermost layer.
 *
 * The runner also aggregates custom slash commands from all loaded extensions via
 * its internal [CommandRegistry].
 */
@Suppress("TooManyFunctions")
public class ExtensionRunner(
    private val eventBus: ExtensionEventBus = ExtensionEventBus(),
    private val commandRegistry: CommandRegistry = InMemoryCommandRegistry(),
) {
    private val extensions: MutableList<ExtensionDefinition> = mutableListOf()
    private var contextProvider: (() -> ExtensionContext)? = null

    public val events: SharedFlow<ExtensionEvent> = eventBus.events

    public fun load(extension: ExtensionDefinition) {
        require(extensions.none { it.id == extension.id }) { "Duplicate extension: ${extension.id}" }
        extensions += extension
        extension.registerCommands?.invoke(commandRegistry)
        eventBus.emit(ExtensionLoadedEvent(extensionId = extension.id))
    }

    public fun commands(): List<RegisteredCommand> = commandRegistry.listCommands()

    public fun shortcuts(): List<RegisteredShortcut> = extensions.flatMap { it.shortcuts }

    public fun toolRenderers(): List<RegisteredToolRenderer> = extensions.flatMap { it.toolRenderers }

    public fun messageRenderers(): List<RegisteredMessageRenderer> = extensions.flatMap { it.messageRenderers }

    public fun flags(): List<RegisteredExtensionFlag> = extensions.flatMap { it.flags }

    public fun tools(): List<AgentTool<*>> = extensions.flatMap { it.tools }

    public fun loadedExtensionIds(): List<String> = extensions.map { it.id }

    public fun bindContext(provider: () -> ExtensionContext) {
        contextProvider = provider
    }

    public fun registerProviders(apiRegistry: ApiRegistry, modelRegistry: ModelRegistry) {
        for (extension in extensions) {
            for (registration in extension.providers) {
                apiRegistry.register(registration.provider, sourceId = extension.id)
            }
            val models = extension.providers.flatMap { it.models }
            val apiKeys = extension.providers
                .mapNotNull { registration ->
                    registration.apiKey?.let { key ->
                        registration.models.map { it.provider.value }.distinct().associateWith { key }
                    }
                }
                .fold(emptyMap<String, String>()) { current, next -> current + next }
            val noAuthProviders = extension.providers
                .filterNot { it.requiresAuth }
                .flatMap { it.models }
                .map { it.provider.value }
                .toSet()
            modelRegistry.registerExtensionModels(extension.id, models, apiKeys, noAuthProviders)
        }
    }

    public fun unregisterProviders(apiRegistry: ApiRegistry, modelRegistry: ModelRegistry) {
        for (extension in extensions) {
            apiRegistry.unregisterBySource(extension.id)
            modelRegistry.unregisterExtensionModels(extension.id)
        }
    }

    public suspend fun dispatchAgentEvent(event: AgentEvent, context: ExtensionContext) {
        for (extension in extensions) {
            for (registration in extension.agentEventHandlers) {
                if (registration.eventType != "*" && registration.eventType != event.type) continue
                try {
                    registration.handler.run(event, context)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    eventBus.emit(
                        ExtensionErrorEvent(
                            extension.id,
                            "event:${event.type}",
                            error.message ?: error.toString(),
                        ),
                    )
                }
            }
        }
    }

    public suspend fun processInput(event: InputEvent, context: ExtensionContext): InputHookResult {
        var text = event.text
        for (extension in extensions) {
            for (hook in extension.inputHooks) {
                val result = try {
                    hook.run(event.copy(text = text), context)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    eventBus.emit(ExtensionErrorEvent(extension.id, "input", error.message ?: error.toString()))
                    null
                }
                when (result) {
                    is InputHookResult.Transform -> text = result.text
                    InputHookResult.Handled -> return InputHookResult.Handled
                    InputHookResult.Continue, null -> Unit
                }
            }
        }
        return if (text == event.text) InputHookResult.Continue else InputHookResult.Transform(text)
    }

    public suspend fun startSession(event: SessionStartEvent, context: ExtensionContext) {
        for (extension in extensions) {
            for (hook in extension.sessionStartHooks) {
                runSessionHook(extension, "sessionStart") { hook.run(event, context) }
            }
        }
    }

    public suspend fun shutdownSession(event: SessionShutdownEvent, context: ExtensionContext) {
        for (extension in extensions) {
            for (hook in extension.sessionShutdownHooks) {
                runSessionHook(extension, "sessionShutdown") { hook.run(event, context) }
            }
        }
    }

    private suspend fun runSessionHook(
        extension: ExtensionDefinition,
        phase: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            eventBus.emit(ExtensionErrorEvent(extension.id, phase, error.message ?: error.toString()))
        }
    }

    public suspend fun prepareCompaction(
        event: BeforeCompactionEvent,
        context: ExtensionContext,
    ): CompactionHookResult? {
        for (extension in extensions) {
            val hook = extension.beforeCompaction ?: continue
            val result = try {
                hook.run(event, context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                eventBus.emit(
                    ExtensionErrorEvent(
                        extension.id,
                        "beforeCompaction",
                        error.message ?: error.toString(),
                    ),
                )
                null
            }
            if (result?.cancel == true || result?.compaction != null) return result
        }
        return null
    }

    public suspend fun completeCompaction(event: AfterCompactionEvent, context: ExtensionContext) {
        for (extension in extensions) {
            val hook = extension.afterCompaction ?: continue
            try {
                hook.run(event, context)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                eventBus.emit(
                    ExtensionErrorEvent(
                        extension.id,
                        "afterCompaction",
                        error.message ?: error.toString(),
                    ),
                )
            }
        }
    }

    public suspend fun runBeforeAgent(messages: List<Message>): List<Message> {
        var current = messages
        for (extension in extensions) {
            val hook = extension.beforeAgent ?: continue
            current = try {
                hook.run(BeforeAgentContext(current))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Isolate a failing extension: report it and continue the chain with the prior value.
                eventBus.emit(ExtensionErrorEvent(extension.id, "beforeAgent", error.message ?: error.toString()))
                current
            }
        }
        return current
    }

    public suspend fun runAfterAgent(messages: List<Message>) {
        for (extension in extensions) {
            val hook = extension.afterAgent ?: continue
            try {
                hook.run(AfterAgentContext(messages))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                eventBus.emit(ExtensionErrorEvent(extension.id, "afterAgent", error.message ?: error.toString()))
            }
        }
    }

    public suspend fun transformContext(context: Context): Context {
        var current = context
        for (extension in extensions) {
            val transform = extension.transformContext ?: continue
            current = try {
                transform.run(current)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                eventBus.emit(ExtensionErrorEvent(extension.id, "transformContext", error.message ?: error.toString()))
                current
            }
        }
        return current
    }

    public fun <T> wrapTool(tool: AgentTool<T>): AgentTool<T> {
        var wrapped = tool
        for (extension in extensions) {
            val wrapper = extension.toolWrapper ?: continue
            wrapped = try {
                val next = wrapper.wrap(wrapped)
                eventBus.emit(ToolWrappedEvent(extensionId = extension.id, toolName = tool.definition.name))
                next
            } catch (error: Throwable) {
                eventBus.emit(ExtensionErrorEvent(extension.id, "toolWrapper", error.message ?: error.toString()))
                wrapped
            }
        }
        if (extensions.none { it.toolCallHooks.isNotEmpty() || it.toolResultHooks.isNotEmpty() }) {
            return wrapped
        }
        val delegate = wrapped
        return object : AgentTool<T> {
            override val definition = delegate.definition
            override val label = delegate.label

            override suspend fun execute(
                toolCallId: String,
                parameters: JsonObject,
                onUpdate: AgentToolUpdateCallback<T>?,
            ): AgentToolResult<T> {
                val context = checkNotNull(contextProvider) {
                    "Extension context has not been bound"
                }.invoke()
                var input = parameters
                for (extension in extensions) {
                    for (hook in extension.toolCallHooks) {
                        val decision = runToolCallHook(extension, hook, toolCallId, definition.name, input, context)
                        if (decision?.block == true) {
                            throw ExtensionToolBlockedException(
                                decision.reason ?: "Tool call blocked by extension ${extension.id}",
                            )
                        }
                        decision?.input?.let { input = it }
                    }
                }

                var result: AgentToolResult<*> = delegate.execute(toolCallId, input, onUpdate)
                for (extension in extensions) {
                    for (hook in extension.toolResultHooks) {
                        result = runToolResultHook(
                            extension,
                            hook,
                            toolCallId,
                            definition.name,
                            input,
                            result,
                            context,
                        )
                    }
                }
                @Suppress("UNCHECKED_CAST")
                return result as AgentToolResult<T>
            }
        }
    }

    private suspend fun runToolCallHook(
        extension: ExtensionDefinition,
        hook: ToolCallHook,
        toolCallId: String,
        toolName: String,
        input: JsonObject,
        context: ExtensionContext,
    ): ToolCallHookResult? = try {
        hook.run(ToolCallEvent(toolCallId, toolName, input), context)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        eventBus.emit(ExtensionErrorEvent(extension.id, "toolCall", error.message ?: error.toString()))
        null
    }

    private suspend fun runToolResultHook(
        extension: ExtensionDefinition,
        hook: ToolResultHook,
        toolCallId: String,
        toolName: String,
        input: JsonObject,
        result: AgentToolResult<*>,
        context: ExtensionContext,
    ): AgentToolResult<*> {
        val patch = try {
            hook.run(
                ToolResultEvent(toolCallId, toolName, input, result.content, result.details),
                context,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            eventBus.emit(ExtensionErrorEvent(extension.id, "toolResult", error.message ?: error.toString()))
            null
        } ?: return result
        return AgentToolResult(
            content = patch.content ?: result.content,
            details = if (patch.replaceDetails) patch.details else result.details,
        )
    }

    public fun <T> wrapTools(tools: List<AgentTool<T>>): List<AgentTool<T>> {
        return tools.map { tool -> wrapTool(tool) }
    }

    public fun wrapAllTools(tools: List<AgentTool<*>>): List<AgentTool<*>> = tools.map(::wrapUnknownTool)

    @Suppress("UNCHECKED_CAST")
    private fun wrapUnknownTool(tool: AgentTool<*>): AgentTool<*> = wrapTool(tool as AgentTool<Any?>)
}
