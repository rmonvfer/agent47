package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

public data class BeforeAgentContext(
    val messages: List<Message>,
)

public data class AfterAgentContext(
    val messages: List<Message>,
)

public data class ToolWrapContext(
    val toolName: String,
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
    public fun registerCommand(name: String, description: String)
    public fun listCommands(): List<RegisteredCommand>
}

public data class RegisteredCommand(
    val name: String,
    val description: String,
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

public class ExtensionEventBus {
    private val mutableEvents: MutableSharedFlow<ExtensionEvent> = MutableSharedFlow(extraBufferCapacity = 1_024)

    public val events: SharedFlow<ExtensionEvent> = mutableEvents.asSharedFlow()

    public fun emit(event: ExtensionEvent) {
        mutableEvents.tryEmit(event)
    }
}

public data class ExtensionDefinition(
    val id: String,
    val beforeAgent: BeforeAgentHook? = null,
    val afterAgent: AfterAgentHook? = null,
    val transformContext: ContextTransformHook? = null,
    val toolWrapper: ToolWrapper? = null,
    val registerCommands: ((CommandRegistry) -> Unit)? = null,
)

public class InMemoryCommandRegistry : CommandRegistry {
    private val commands: MutableMap<String, RegisteredCommand> = linkedMapOf()

    override fun registerCommand(name: String, description: String) {
        commands[name] = RegisteredCommand(name, description)
    }

    override fun listCommands(): List<RegisteredCommand> = commands.values.toList()
}

public class ExtensionRunner(
    private val eventBus: ExtensionEventBus = ExtensionEventBus(),
    private val commandRegistry: CommandRegistry = InMemoryCommandRegistry(),
) {
    private val extensions: MutableList<ExtensionDefinition> = mutableListOf()

    public val events: SharedFlow<ExtensionEvent> = eventBus.events

    public fun load(extension: ExtensionDefinition) {
        extensions += extension
        extension.registerCommands?.invoke(commandRegistry)
        eventBus.emit(ExtensionLoadedEvent(extensionId = extension.id))
    }

    public fun commands(): List<RegisteredCommand> = commandRegistry.listCommands()

    public suspend fun runBeforeAgent(messages: List<Message>): List<Message> {
        var current = messages
        for (extension in extensions) {
            val hook = extension.beforeAgent ?: continue
            current = hook.run(BeforeAgentContext(current))
        }
        return current
    }

    public suspend fun runAfterAgent(messages: List<Message>) {
        for (extension in extensions) {
            extension.afterAgent?.run(AfterAgentContext(messages))
        }
    }

    public suspend fun transformContext(context: Context): Context {
        var current = context
        for (extension in extensions) {
            val transform = extension.transformContext ?: continue
            current = transform.run(current)
        }
        return current
    }

    public fun <T> wrapTool(tool: AgentTool<T>): AgentTool<T> {
        var wrapped = tool
        for (extension in extensions) {
            val wrapper = extension.toolWrapper ?: continue
            wrapped = wrapper.wrap(wrapped)
            eventBus.emit(ToolWrappedEvent(extensionId = extension.id, toolName = tool.definition.name))
        }
        return wrapped
    }

    public fun <T> wrapTools(tools: List<AgentTool<T>>): List<AgentTool<T>> {
        return tools.map { tool -> wrapTool(tool) }
    }
}
