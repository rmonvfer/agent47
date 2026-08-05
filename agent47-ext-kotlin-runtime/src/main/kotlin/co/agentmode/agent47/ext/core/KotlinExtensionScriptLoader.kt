package co.agentmode.agent47.ext.core

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.ai.core.providers.ApiProvider
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptCompiler
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.baseClass
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.constructorArgs
import kotlin.script.experimental.host.BasicScriptingHost
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

@KotlinScript(
    fileExtension = "kts",
    compilationConfiguration = Agent47ScriptCompilationConfiguration::class,
)
@Suppress("TooManyFunctions")
public abstract class Agent47Script(
    private val api: Agent47ScriptApi,
) {
    public fun beforeAgent(hook: suspend (List<Message>) -> List<Message>) {
        api.builder.beforeAgent(hook)
    }

    public fun afterAgent(hook: suspend (List<Message>) -> Unit) {
        api.builder.afterAgent(hook)
    }

    public fun transformContext(hook: suspend (Context) -> Context) {
        api.builder.transformContext(hook)
    }

    public fun wrapTools(wrapper: ToolWrapper) {
        api.builder.wrapTools(wrapper)
    }

    public fun registerTool(tool: AgentTool<*>) {
        api.builder.registerTool(tool)
    }

    public fun on(
        eventType: String,
        handler: suspend (AgentEvent, ExtensionContext) -> Unit,
    ) {
        api.builder.on(eventType, handler)
    }

    public fun beforeCompaction(
        hook: suspend (BeforeCompactionEvent, ExtensionContext) -> CompactionHookResult?,
    ) {
        api.builder.beforeCompaction(hook)
    }

    public fun afterCompaction(hook: suspend (AfterCompactionEvent, ExtensionContext) -> Unit) {
        api.builder.afterCompaction(hook)
    }

    public fun beforeTree(
        hook: suspend (SessionBeforeTreeEvent, ExtensionContext) -> TreeHookResult?,
    ) {
        api.builder.beforeTree(hook)
    }

    public fun afterTree(hook: suspend (SessionTreeEvent, ExtensionContext) -> Unit) {
        api.builder.afterTree(hook)
    }

    public fun registerProvider(
        provider: ApiProvider,
        models: List<Model>,
        apiKey: String? = null,
        requiresAuth: Boolean = true,
    ) {
        api.builder.registerProvider(provider, models, apiKey, requiresAuth)
    }

    public fun onToolCall(hook: suspend (ToolCallEvent, ExtensionContext) -> ToolCallHookResult?) {
        api.builder.onToolCall(hook)
    }

    public fun onToolResult(hook: suspend (ToolResultEvent, ExtensionContext) -> ToolResultHookResult?) {
        api.builder.onToolResult(hook)
    }

    public fun registerShortcut(
        key: String,
        description: String,
        handler: suspend (ExtensionContext) -> Unit,
    ) {
        api.builder.registerShortcut(key, description, handler)
    }

    public fun registerToolRenderer(toolName: String, renderer: ToolRenderer) {
        api.builder.registerToolRenderer(toolName, renderer)
    }

    public fun registerMessageRenderer(customType: String, renderer: MessageRenderer) {
        api.builder.registerMessageRenderer(customType, renderer)
    }

    public fun registerFlag(
        name: String,
        description: String,
        type: ExtensionFlagType = ExtensionFlagType.BOOLEAN,
        defaultValue: String? = if (type == ExtensionFlagType.BOOLEAN) "false" else null,
    ) {
        api.builder.registerFlag(name, description, type, defaultValue)
    }

    public fun getFlag(name: String): String? = api.builder.getFlag(name)

    public fun onInput(hook: suspend (InputEvent, ExtensionContext) -> InputHookResult?) {
        api.builder.onInput(hook)
    }

    public fun onSessionStart(hook: suspend (SessionStartEvent, ExtensionContext) -> Unit) {
        api.builder.onSessionStart(hook)
    }

    public fun onSessionShutdown(hook: suspend (SessionShutdownEvent, ExtensionContext) -> Unit) {
        api.builder.onSessionShutdown(hook)
    }

    public fun registerCommand(
        name: String,
        description: String,
        handler: suspend (String, ExtensionCommandContext) -> Unit,
    ) {
        api.builder.registerCommand(name, description, handler)
    }
}

public object Agent47ScriptCompilationConfiguration : ScriptCompilationConfiguration({
    baseClass(Agent47Script::class)
    compilerOptions.append("-Xlambdas=class")
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

public class Agent47ScriptApi internal constructor(id: String, flagValues: Map<String, String>) {
    internal val builder: Agent47ExtensionBuilder = Agent47ExtensionBuilder(id, flagValues)
}

@Suppress("TooManyFunctions")
public class Agent47ExtensionBuilder internal constructor(
    private val id: String,
    private val flagValues: Map<String, String> = emptyMap(),
) {
    private var beforeAgent: BeforeAgentHook? = null
    private var afterAgent: AfterAgentHook? = null
    private var transformContext: ContextTransformHook? = null
    private var toolWrapper: ToolWrapper? = null
    private val tools: MutableList<AgentTool<*>> = mutableListOf()
    private val agentEventHandlers: MutableList<RegisteredAgentEventHandler> = mutableListOf()
    private var beforeCompaction: BeforeCompactionHook? = null
    private var afterCompaction: AfterCompactionHook? = null
    private var beforeTree: BeforeTreeHook? = null
    private var afterTree: AfterTreeHook? = null
    private val providers: MutableList<ExtensionProvider> = mutableListOf()
    private val toolCallHooks: MutableList<ToolCallHook> = mutableListOf()
    private val toolResultHooks: MutableList<ToolResultHook> = mutableListOf()
    private val shortcuts: MutableList<RegisteredShortcut> = mutableListOf()
    private val toolRenderers: MutableList<RegisteredToolRenderer> = mutableListOf()
    private val messageRenderers: MutableList<RegisteredMessageRenderer> = mutableListOf()
    private val flags: MutableList<RegisteredExtensionFlag> = mutableListOf()
    private val inputHooks: MutableList<InputHook> = mutableListOf()
    private val sessionStartHooks: MutableList<SessionStartHook> = mutableListOf()
    private val sessionShutdownHooks: MutableList<SessionShutdownHook> = mutableListOf()
    private var registerCommands: ((CommandRegistry) -> Unit)? = null

    public fun beforeAgent(hook: suspend (List<Message>) -> List<Message>) {
        beforeAgent = BeforeAgentHook { context -> hook(context.messages) }
    }

    public fun afterAgent(hook: suspend (List<Message>) -> Unit) {
        afterAgent = AfterAgentHook { context -> hook(context.messages) }
    }

    public fun transformContext(hook: suspend (Context) -> Context) {
        transformContext = ContextTransformHook(hook)
    }

    public fun wrapTools(wrapper: ToolWrapper) {
        toolWrapper = wrapper
    }

    public fun registerTool(tool: AgentTool<*>) {
        tools += tool
    }

    public fun on(
        eventType: String,
        handler: suspend (AgentEvent, ExtensionContext) -> Unit,
    ) {
        require(eventType == "*" || eventType.matches(Regex("[a-z][a-z0-9_]*"))) {
            "Invalid extension event type: $eventType"
        }
        agentEventHandlers += RegisteredAgentEventHandler(eventType, AgentEventHandler(handler))
    }

    public fun beforeCompaction(
        hook: suspend (BeforeCompactionEvent, ExtensionContext) -> CompactionHookResult?,
    ) {
        beforeCompaction = BeforeCompactionHook(hook)
    }

    public fun afterCompaction(hook: suspend (AfterCompactionEvent, ExtensionContext) -> Unit) {
        afterCompaction = AfterCompactionHook(hook)
    }

    public fun beforeTree(
        hook: suspend (SessionBeforeTreeEvent, ExtensionContext) -> TreeHookResult?,
    ) {
        beforeTree = BeforeTreeHook(hook)
    }

    public fun afterTree(hook: suspend (SessionTreeEvent, ExtensionContext) -> Unit) {
        afterTree = AfterTreeHook(hook)
    }

    public fun registerProvider(
        provider: ApiProvider,
        models: List<Model>,
        apiKey: String? = null,
        requiresAuth: Boolean = true,
    ) {
        require(models.isNotEmpty()) { "An extension provider must register at least one model" }
        require(models.all { it.api == provider.api }) {
            "All extension models must use provider API ${provider.api.value}"
        }
        providers += ExtensionProvider(provider, models.toList(), apiKey, requiresAuth)
    }

    public fun onToolCall(hook: suspend (ToolCallEvent, ExtensionContext) -> ToolCallHookResult?) {
        toolCallHooks += ToolCallHook(hook)
    }

    public fun onToolResult(hook: suspend (ToolResultEvent, ExtensionContext) -> ToolResultHookResult?) {
        toolResultHooks += ToolResultHook(hook)
    }

    public fun registerShortcut(
        key: String,
        description: String,
        handler: suspend (ExtensionContext) -> Unit,
    ) {
        val normalized = key.lowercase().replace(" ", "")
        require(normalized.matches(Regex("(?:(?:ctrl|alt|shift)\\+)*(?:[a-z0-9]|enter|tab|escape|backspace|delete|up|down|left|right|pageup|pagedown)"))) {
            "Invalid extension shortcut: $key"
        }
        require(shortcuts.none { it.key == normalized }) { "Duplicate extension shortcut: $normalized" }
        shortcuts += RegisteredShortcut(normalized, description, ExtensionShortcutHandler(handler))
    }

    public fun registerToolRenderer(toolName: String, renderer: ToolRenderer) {
        require(toolName.isNotBlank()) { "Tool renderer name cannot be blank" }
        require(toolRenderers.none { it.toolName == toolName }) { "Duplicate tool renderer: $toolName" }
        toolRenderers += RegisteredToolRenderer(toolName, renderer)
    }

    public fun registerMessageRenderer(customType: String, renderer: MessageRenderer) {
        require(customType.isNotBlank()) { "Message renderer type cannot be blank" }
        require(messageRenderers.none { it.customType == customType }) {
            "Duplicate message renderer: $customType"
        }
        messageRenderers += RegisteredMessageRenderer(customType, renderer)
    }

    public fun registerFlag(
        name: String,
        description: String,
        type: ExtensionFlagType,
        defaultValue: String?,
    ) {
        require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid extension flag name: $name" }
        require(flags.none { it.name == name }) { "Duplicate extension flag: $name" }
        val value = flagValues[name] ?: defaultValue
        if (type == ExtensionFlagType.BOOLEAN && value != null) {
            require(value.lowercase() in setOf("true", "false")) {
                "Boolean extension flag $name must be true or false"
            }
        }
        flags += RegisteredExtensionFlag(name, description, type, defaultValue, value)
    }

    public fun getFlag(name: String): String? =
        flags.firstOrNull { it.name == name }?.value
            ?: error("Extension flag must be registered before it is read: $name")

    public fun onInput(hook: suspend (InputEvent, ExtensionContext) -> InputHookResult?) {
        inputHooks += InputHook(hook)
    }

    public fun onSessionStart(hook: suspend (SessionStartEvent, ExtensionContext) -> Unit) {
        sessionStartHooks += SessionStartHook(hook)
    }

    public fun onSessionShutdown(hook: suspend (SessionShutdownEvent, ExtensionContext) -> Unit) {
        sessionShutdownHooks += SessionShutdownHook(hook)
    }

    public fun registerCommand(
        name: String,
        description: String,
        handler: suspend (String, ExtensionCommandContext) -> Unit,
    ) {
        val existing = registerCommands
        registerCommands = { registry ->
            existing?.invoke(registry)
            registry.registerCommand(name, description, ExtensionCommandHandler(handler))
        }
    }

    internal fun build(): ExtensionDefinition = ExtensionDefinition(
        id = id,
        beforeAgent = beforeAgent,
        afterAgent = afterAgent,
        transformContext = transformContext,
        toolWrapper = toolWrapper,
        tools = tools.toList(),
        agentEventHandlers = agentEventHandlers.toList(),
        beforeCompaction = beforeCompaction,
        afterCompaction = afterCompaction,
        beforeTree = beforeTree,
        afterTree = afterTree,
        providers = providers.toList(),
        toolCallHooks = toolCallHooks.toList(),
        toolResultHooks = toolResultHooks.toList(),
        shortcuts = shortcuts.toList(),
        toolRenderers = toolRenderers.toList(),
        messageRenderers = messageRenderers.toList(),
        flags = flags.toList(),
        inputHooks = inputHooks.toList(),
        sessionStartHooks = sessionStartHooks.toList(),
        sessionShutdownHooks = sessionShutdownHooks.toList(),
        registerCommands = registerCommands,
    )
}

public class KotlinExtensionScriptLoader : ExtensionScriptLoader {
    private var flagValues: Map<String, String> = emptyMap()
    private var compilationCache: KotlinExtensionCompilationCache? = null
    private val evaluator = BasicJvmScriptEvaluator()
    private val evaluationHost = object : BasicScriptingHost(
        compiler = object : ScriptCompiler {
            override suspend fun invoke(
                script: SourceCode,
                scriptCompilationConfiguration: ScriptCompilationConfiguration,
            ): ResultWithDiagnostics<CompiledScript> = error("The evaluation host cannot compile scripts")
        },
        evaluator = evaluator,
    ) {}
    private val compilerHost = lazy(::BasicJvmScriptingHost)

    override fun configureFlags(values: Map<String, String>) {
        flagValues = values.toMap()
    }

    override fun configureCompilationCache(directory: Path, runtimeId: String) {
        require(runtimeId.isNotBlank()) { "Extension compilation cache runtime ID cannot be blank" }
        compilationCache = KotlinExtensionCompilationCache(directory, runtimeId)
    }

    override fun load(path: Path): ScriptLoadResult {
        val thread = Thread.currentThread()
        val previousClassLoader = thread.contextClassLoader
        thread.contextClassLoader = javaClass.classLoader
        return try {
            loadScript(path)
        } finally {
            thread.contextClassLoader = previousClassLoader
        }
    }

    private fun loadScript(path: Path): ScriptLoadResult {
        val normalizedPath = path.toAbsolutePath().normalize()
        val api = Agent47ScriptApi(normalizedPath.toString(), flagValues)
        val source = FileScriptSource(normalizedPath.toFile())
        val cachedScript = compilationCache?.load(source, Agent47ScriptCompilationConfiguration)
        val compilation = if (cachedScript == null) {
            compilerHost.value.runInCoroutineContext {
                compilerHost.value.compiler(source, Agent47ScriptCompilationConfiguration)
            }
        } else {
            null
        }
        val compiledScript = cachedScript ?: (compilation as? ResultWithDiagnostics.Success)
            ?.value
            ?.also { script ->
                compilationCache?.store(script, source, Agent47ScriptCompilationConfiguration)
            }
        val evaluation = compiledScript?.let { script ->
            evaluationHost.runInCoroutineContext {
                evaluator(
                    script,
                    ScriptEvaluationConfiguration {
                        constructorArgs(api)
                    },
                )
            }
        }
        val reports = compilation?.reports.orEmpty() + evaluation?.reports.orEmpty()
        val errors = reports
            .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .map { diagnostic ->
                val location = diagnostic.location?.start?.let { "${it.line}:${it.col}: " }.orEmpty()
                val cause = diagnostic.exception?.stackTraceToString()?.let { "\n$it" }.orEmpty()
                "$location${diagnostic.message}$cause"
            }
        return if (evaluation is ResultWithDiagnostics.Success && errors.isEmpty()) {
            ScriptLoadResult.Loaded(path = path, extension = api.builder.build())
        } else {
            ScriptLoadResult.Failed(
                ScriptLoadFailure(
                    path = path,
                    diagnostics = errors.ifEmpty { listOf("Failed to evaluate ${path.name}") },
                ),
            )
        }
    }

    internal fun compilerWasInitialized(): Boolean = compilerHost.isInitialized()
}
