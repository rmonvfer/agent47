package co.agentmode.agent47.app.bootstrap

import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.providers.anthropic.registerAnthropicProviders
import co.agentmode.agent47.ai.providers.google.registerGoogleProviders
import co.agentmode.agent47.ai.providers.openai.registerOpenAiProviders
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.ImageContent
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.KotlinExtensionSupport
import co.agentmode.agent47.app.buildSystemPrompt
import co.agentmode.agent47.app.cli.CliOptions
import co.agentmode.agent47.app.cli.listModels
import co.agentmode.agent47.app.cli.mergeThemes
import co.agentmode.agent47.app.cli.parseModelSpec
import co.agentmode.agent47.app.cli.printError
import co.agentmode.agent47.app.cli.processPrompt
import co.agentmode.agent47.app.cli.resolveModel
import co.agentmode.agent47.app.cli.resolveSession
import co.agentmode.agent47.app.cli.resolveThinkingLevel
import co.agentmode.agent47.app.cli.sessionsBaseDir
import co.agentmode.agent47.app.compaction.CompactionService
import co.agentmode.agent47.app.extensions.CliExtensionContext
import co.agentmode.agent47.app.extensions.CliExtensionSessionControl
import co.agentmode.agent47.app.extensions.ExtensionContextBinding
import co.agentmode.agent47.app.extensions.ExtensionReloader
import co.agentmode.agent47.app.extensions.HeadlessExtensionUi
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.Worktree
import co.agentmode.agent47.coding.core.agents.schedule.ScheduleStore
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.auth.CopilotAuthPlugin
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandDiscovery
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.extensions.ExtensionRepositoryManager
import co.agentmode.agent47.coding.core.extensions.ExtensionRepositoryResources
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsState
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.coding.core.tools.ApplicableSkill
import co.agentmode.agent47.coding.core.tools.CheckInboxTool
import co.agentmode.agent47.coding.core.tools.DEFAULT_TOOLS
import co.agentmode.agent47.coding.core.tools.SendMessageTool
import co.agentmode.agent47.coding.core.tools.SkillReader
import co.agentmode.agent47.coding.core.tools.TaskTool
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.coding.core.tools.createCoreTools
import co.agentmode.agent47.ext.core.KotlinExtensionDiscovery
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.MutableExtensionSessionControl
import co.agentmode.agent47.ext.core.MutableExtensionUi
import co.agentmode.agent47.tui.theme.NamedTheme
import co.agentmode.agent47.tui.theme.loadNamedTheme
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path

private const val VALID_ENV_HINT = "ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY"

private fun createRepositoryManagers(
    config: AgentConfig,
    workingDirectory: Path,
): Pair<ExtensionRepositoryManager, ExtensionRepositoryManager> =
    ExtensionRepositoryManager(
        config.globalExtensionRepositoriesPath,
        config.globalExtensionGitDir,
        workingDirectory,
    ) to ExtensionRepositoryManager(
        config.projectExtensionRepositoriesPath,
        config.projectExtensionGitDir,
        workingDirectory,
        relativeLocalSources = true,
    )

/**
 * Assembles the [AgentRuntime] object graph from parsed options in the same order the CLI has
 * always built it. Fallible steps funnel their failures through [abort], which prints the error
 * and raises Abort in one place. Returns null when an informational flag already handled output.
 */
internal class AgentRuntimeBuilder(
    private val options: CliOptions,
    private val config: AgentConfig,
    private val terminal: Terminal,
) {
    private val workingDir: Path = Path.of(System.getProperty("user.dir"))

    suspend fun build(): AgentRuntime? {
        val extensions = setUpExtensions()
        if (options.listExtensions) {
            extensions.runtime.runner.loadedExtensionIds().forEach(terminal::println)
            if (extensions.loadFailed) throw Abort()
            return null
        }
        return assemble(extensions)
    }

    private fun abort(message: String): Nothing {
        terminal.printError(message)
        throw Abort()
    }

    private fun setUpExtensions(): ExtensionSetup {
        val flags = options.extensionFlags.fold(linkedMapOf<String, String>()) { values, raw ->
            val name = raw.substringBefore('=')
            val value = raw.substringAfter('=', "true")
            require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid extension flag name: $name" }
            require(name !in values) { "Duplicate extension flag value: $name" }
            values.apply { put(name, value) }
        }
        val (globalRepositories, projectRepositories) = createRepositoryManagers(config, workingDir)
        val repositoryResources = runCatching {
            val project = projectRepositories.resources()
            val global = globalRepositories.resources(excluding = projectRepositories.identities())
            project + global
        }.getOrElse { abort(it.message ?: it.toString()) }
        val availableThemes = runCatching {
            mergeThemes(repositoryResources.themeFiles.map(::loadNamedTheme))
        }.getOrElse { abort(it.message ?: it.toString()) }
        val discoveredExtensionPaths = runCatching {
            KotlinExtensionDiscovery.discover(
                explicitPaths = options.extensionPaths,
                projectDirectory = config.projectExtensionsDir,
                globalDirectory = config.globalExtensionsDir,
                repositoryPaths = repositoryResources.extensions,
                autoDiscover = !options.noExtensions,
            )
        }.getOrElse { abort(it.message ?: it.toString()) }
        val extensionRuntime = KotlinExtensionRuntime(
            discoveredExtensionPaths,
            discoveredExtensionPaths.takeIf { it.isNotEmpty() }?.let {
                KotlinExtensionSupport.createLoader().also { loader -> loader.configureFlags(flags) }
            },
        )
        var loadFailed = false
        if (discoveredExtensionPaths.isNotEmpty()) {
            val report = extensionRuntime.reload()
            loadFailed = report.failures.isNotEmpty()
            report.failures.forEach { failure ->
                terminal.printError(
                    buildString {
                        append("Failed to load extension ${failure.path}")
                        failure.diagnostics.forEach { diagnostic -> append("\n  $diagnostic") }
                    },
                )
            }
        }
        val unknownFlags = flags.keys - extensionRuntime.runner.flags().map { it.name }.toSet()
        if (unknownFlags.isNotEmpty()) {
            terminal.printError("Unknown extension flags: ${unknownFlags.joinToString()}")
            loadFailed = true
        }
        return ExtensionSetup(extensionRuntime, availableThemes, repositoryResources, loadFailed)
    }

    private suspend fun buildRegistries(extensionRuntime: KotlinExtensionRuntime): Registries {
        val authStorage = AuthStorage(config.authPath)
        val modelRegistry = ModelRegistry(authStorage, config.modelsPath, config.modelsJsonLegacyPath)
        val settings = SettingsManager.create(config.globalSettingsPath, config.projectSettingsPath)
        val subagentsSettings = SubagentsSettingsState(
            SubagentsSettingsManager.load(
                config.globalSubagentsSettingsPath,
                config.projectSubagentsSettingsPath,
            ),
        )
        val apiRegistry = ApiRegistry()
        registerOpenAiProviders(apiRegistry)
        registerAnthropicProviders(apiRegistry)
        registerGoogleProviders(apiRegistry)
        extensionRuntime.runner.registerProviders(apiRegistry, modelRegistry)
        modelRegistry.refreshAsync()
        return Registries(modelRegistry, settings, subagentsSettings, apiRegistry)
    }

    private suspend fun assemble(extensions: ExtensionSetup): AgentRuntime? {
        val registries = buildRegistries(extensions.runtime)
        if (options.listModels) {
            listModels(registries.modelRegistry, options.listModelsSearch, terminal)
            return null
        }
        val aiRuntime = AiRuntime(registries.apiRegistry)
        registries.modelRegistry.registerAuthPlugin(CopilotAuthPlugin())
        // An explicit --api-key must reach the registry before model resolution so first-time setup
        // (with no stored credentials) can find and use the requested provider's models.
        options.apiKey?.let { key ->
            val targetProvider = parseModelSpec(options.model).provider
                ?: options.provider
                ?: registries.settings.get().defaultProvider
            if (targetProvider != null) {
                registries.modelRegistry.storeApiKey(targetProvider, key)
            }
        }
        val resolvedModel = resolveModel(options, registries.modelRegistry, registries.settings)
            ?: abort("No API key found. Set one of: $VALID_ENV_HINT")
        val thinkingLevel = resolveThinkingLevel(options, registries.settings)
        val sessionManager = resolveSession(options, config, terminal)
        val sessionTracker = SessionTracker(sessionManager)
        val (promptContent, images) = processPrompt(options.promptArgs, terminal)
        val tools = buildTools(extensions, registries, aiRuntime, sessionManager)
        val core = buildAgentClient(extensions, registries, aiRuntime, resolvedModel, thinkingLevel, tools)
        val surface = buildExtensionSurface(extensions, registries, sessionTracker, tools, core)
        val initial = buildInitialMessage(promptContent, images)
        val compactionService = CompactionService(
            extensionRuntime = extensions.runtime,
            extensionContext = surface.extensionContext,
            settings = registries.settings,
            aiRuntime = aiRuntime,
            modelRegistry = registries.modelRegistry,
        )
        return AgentRuntime(
            terminal = terminal, client = surface.client, config = config,
            settings = registries.settings, modelRegistry = registries.modelRegistry,
            agentRegistry = tools.agentRegistry, skillRegistry = tools.skillRegistry,
            backgroundAgents = tools.backgroundAgents, scheduler = tools.scheduler,
            subagentsSettings = registries.subagentsSettings, sessionTracker = sessionTracker,
            extensionRuntime = extensions.runtime, extensionContext = surface.extensionContext,
            reloader = surface.reloader, compactionService = compactionService,
            fileCommands = tools.fileCommands, instructionLoader = tools.instructionLoader,
            availableThemes = extensions.availableThemes, todoState = tools.todoState,
            resolvedModel = resolvedModel, thinkingLevel = thinkingLevel,
            sessionsDir = sessionsBaseDir(options, config),
            initialUserMessage = initial.userMessage, runAsPrintMode = initial.runAsPrintMode,
        )
    }

    private fun buildScheduler(
        registries: Registries,
        aiRuntime: AiRuntime,
        agentRegistry: AgentRegistry,
        skillRegistry: SkillRegistry,
        backgroundAgents: BackgroundAgents,
        sessionManager: SessionManager?,
    ): SubagentScheduler {
        // Session-scoped scheduler: fires scheduled subagents while this session runs.
        val sessionId = sessionManager?.getHeader()?.id ?: "default"
        val store = ScheduleStore(config.projectDir.resolve("subagent-schedules").resolve("$sessionId.json"))
        val scheduledJobRunner = ScheduledJobRunner(
            aiRuntime = aiRuntime,
            agentRegistry = agentRegistry,
            modelRegistry = registries.modelRegistry,
            backgroundAgents = backgroundAgents,
            settings = registries.settings,
            subagentsSettings = registries.subagentsSettings,
            skillRegistry = skillRegistry,
            workingDir = workingDir,
            sessionsDir = sessionsBaseDir(options, config),
            memoryProjectDir = config.projectDir,
            memoryGlobalDir = config.globalDir,
            sessionId = sessionId,
        )
        return SubagentScheduler(store, scheduledJobRunner::run)
    }

    private fun buildTools(
        extensions: ExtensionSetup,
        registries: Registries,
        aiRuntime: AiRuntime,
        sessionManager: SessionManager?,
    ): ToolSetup {
        val agentRegistry = AgentRegistry(
            config.projectAgentsDir,
            config.globalAgentsDir,
            disableDefaultAgents = registries.subagentsSettings.get().disableDefaultAgents,
        )
        val skillRegistry = SkillRegistry(
            config.projectSkillsDir,
            config.globalSkillsDir,
            extensions.repositoryResources.skillDirectories,
        )
        val fileCommands = SlashCommandDiscovery.discover(
            config.projectCommandsDir,
            config.globalCommandsDir,
            extensions.repositoryResources.promptDirectories,
        )
        val skillReader = object : SkillReader {
            override fun readSkillFile(name: String, relativePath: String?): String? =
                skillRegistry.readSkillFile(name, relativePath)

            override fun readApplicableSkills(path: String): List<ApplicableSkill> =
                skillRegistry.getApplicable(listOf(path))
                    .filterNot { it.alwaysApply }
                    .map { ApplicableSkill(name = it.name, content = it.content) }
        }
        val toolsEnabled = if (options.noTools) {
            emptyList()
        } else {
            options.tools?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: DEFAULT_TOOLS
        }
        val instructionLoader = InstructionLoader(
            cwd = workingDir,
            globalDir = config.globalDir,
            claudeDir = config.claudeDir,
            settings = registries.settings.get(),
        )
        val todoState = TodoState()
        val toolRegistry = createCoreTools(workingDir, toolsEnabled, skillReader, todoState)
        val allTools = toolRegistry.all().toMutableList<AgentTool<*>>()
        val backgroundAgents = BackgroundAgents(maxConcurrent = registries.subagentsSettings.get().maxConcurrent)
        // Clean up any worktrees orphaned by a previous crashed run.
        Worktree.pruneWorktrees(workingDir)
        val scheduler = buildScheduler(registries, aiRuntime, agentRegistry, skillRegistry, backgroundAgents, sessionManager)
        if (registries.subagentsSettings.get().schedulingEnabled) scheduler.start()
        wireSubagentTools(allTools, registries, aiRuntime, agentRegistry, skillRegistry, backgroundAgents, scheduler, sessionManager)
        return ToolSetup(
            agentRegistry, skillRegistry, fileCommands, instructionLoader,
            todoState, allTools, backgroundAgents, scheduler,
        )
    }

    private fun wireSubagentTools(
        allTools: MutableList<AgentTool<*>>,
        registries: Registries,
        aiRuntime: AiRuntime,
        agentRegistry: AgentRegistry,
        skillRegistry: SkillRegistry,
        backgroundAgents: BackgroundAgents,
        scheduler: SubagentScheduler,
        sessionManager: SessionManager?,
    ) {
        if (options.noTools) return
        allTools += TaskTool(
            streamFunction = aiRuntime::streamSimple,
            agentRegistry = agentRegistry,
            modelRegistry = registries.modelRegistry,
            settings = registries.settings.get(),
            cwd = workingDir,
            backgroundAgents = backgroundAgents,
            subagentsSettings = registries.subagentsSettings,
            currentDepth = 0,
            maxDepth = registries.settings.get().taskMaxRecursionDepth,
            getApiKey = { provider -> registries.modelRegistry.getApiKeyForProvider(provider) },
            sessionsDir = sessionsBaseDir(options, config),
            parentSessionId = sessionManager?.getHeader()?.id,
            memoryProjectDir = config.projectDir,
            memoryGlobalDir = config.globalDir,
            scheduler = scheduler,
            skillContentProvider = { name -> skillRegistry.readSkillFile(name) },
        )
        allTools += CheckInboxTool(backgroundAgents)
        allTools += SendMessageTool(backgroundAgents, BackgroundAgents.ORCHESTRATOR)
    }

    private fun buildAgentClient(
        extensions: ExtensionSetup,
        registries: Registries,
        aiRuntime: AiRuntime,
        resolvedModel: Model,
        thinkingLevel: AgentThinkingLevel,
        tools: ToolSetup,
    ): ClientCore {
        val extensionRuntime = extensions.runtime
        val modelRegistry = registries.modelRegistry
        val configuredTools = extensionRuntime.runner.wrapAllTools(
            (extensionRuntime.runner.tools() + tools.allTools).distinctBy { it.definition.name },
        )
        val toolCatalog = linkedMapOf<String, AgentTool<*>>()
        configuredTools.forEach { tool -> toolCatalog[tool.definition.name] = tool }
        val builtSystemPrompt = buildSystemPrompt(
            cwd = workingDir,
            toolNames = configuredTools.map { it.definition.name },
            customPrompt = options.systemPrompt,
            appendPrompt = options.appendSystemPrompt,
            skills = tools.skillRegistry.getAll(),
            instructions = tools.instructionLoader.format(),
        )
        val binding = ExtensionContextBinding()
        val extensionUi = MutableExtensionUi(HeadlessExtensionUi(terminal))
        val client = AgentClient(
            AgentOptions(
                streamFunction = aiRuntime::streamSimple,
                initialState = PartialAgentState(
                    model = resolvedModel,
                    systemPrompt = builtSystemPrompt,
                    tools = configuredTools,
                    thinkingLevel = thinkingLevel,
                ),
                beforeAgent = { messages -> extensionRuntime.runner.runBeforeAgent(messages) },
                afterAgent = { messages -> extensionRuntime.runner.runAfterAgent(messages) },
                transformContext = { context -> extensionRuntime.runner.transformContext(context) },
                onEvent = { event -> extensionRuntime.runner.dispatchAgentEvent(event, binding.get()) },
                getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
            ),
        )
        return ClientCore(client, toolCatalog, binding, extensionUi)
    }

    private fun buildExtensionSurface(
        extensions: ExtensionSetup,
        registries: Registries,
        sessionTracker: SessionTracker,
        tools: ToolSetup,
        core: ClientCore,
    ): ExtensionSurface {
        val extensionRuntime = extensions.runtime
        val client = core.client
        val extensionSession = MutableExtensionSessionControl(
            CliExtensionSessionControl(
                sessionTracker = sessionTracker,
                extensionRuntime = extensionRuntime,
                client = client,
                sessionsDir = sessionsBaseDir(options, config),
                workingDir = workingDir,
                noSession = options.noSession,
                contextProvider = core.binding::get,
            ),
        )
        val reloader = ExtensionReloader(
            extensionRuntime = extensionRuntime,
            apiRegistry = registries.apiRegistry,
            modelRegistry = registries.modelRegistry,
            allTools = tools.allTools,
            toolCatalog = core.toolCatalog,
            client = client,
            workingDir = workingDir,
            customPrompt = options.systemPrompt,
            appendPrompt = options.appendSystemPrompt,
            skillRegistry = tools.skillRegistry,
            instructionLoader = tools.instructionLoader,
            sessionTracker = sessionTracker,
            contextProvider = core.binding::get,
        )
        val extensionContext = CliExtensionContext(
            ui = core.ui,
            session = extensionSession,
            cwd = workingDir,
            printMode = options.printMode,
            client = client,
            modelRegistry = registries.modelRegistry,
            toolCatalog = core.toolCatalog,
            sessionTracker = sessionTracker,
            extensionRuntime = extensionRuntime,
            customPrompt = options.systemPrompt,
            appendPrompt = options.appendSystemPrompt,
            skillRegistry = tools.skillRegistry,
            instructionLoader = tools.instructionLoader,
            reloader = reloader,
        )
        core.binding.bind(extensionContext)
        extensionRuntime.runner.bindContext { extensionContext }
        // Let background sub-agents inherit the orchestrator's prompt/conversation and (later)
        // receive push notifications.
        tools.backgroundAgents.setOrchestrator(
            systemPrompt = { client.state.systemPrompt },
            messages = { client.state.messages },
        )
        return ExtensionSurface(client, extensionContext, reloader)
    }

    private fun buildInitialMessage(promptContent: String, images: List<ImageContent>): InitialMessage {
        val userContent = mutableListOf<ContentBlock>()
        if (promptContent.isNotBlank()) {
            userContent.add(TextContent(text = promptContent))
        }
        userContent.addAll(images)

        val runAsPrintMode = options.printMode || System.console() == null
        if (userContent.isEmpty() && runAsPrintMode) {
            abort("No prompt provided. Use --help for usage information.")
        }
        val userMessage = if (userContent.isEmpty()) {
            null
        } else {
            UserMessage(
                content = userContent.toList(),
                timestamp = System.currentTimeMillis(),
            )
        }
        return InitialMessage(userMessage, runAsPrintMode)
    }

    private class ExtensionSetup(
        val runtime: KotlinExtensionRuntime,
        val availableThemes: List<NamedTheme>,
        val repositoryResources: ExtensionRepositoryResources,
        val loadFailed: Boolean,
    )

    private class Registries(
        val modelRegistry: ModelRegistry,
        val settings: SettingsManager,
        val subagentsSettings: SubagentsSettingsState,
        val apiRegistry: ApiRegistry,
    )

    private class ToolSetup(
        val agentRegistry: AgentRegistry,
        val skillRegistry: SkillRegistry,
        val fileCommands: List<SlashCommand>,
        val instructionLoader: InstructionLoader,
        val todoState: TodoState,
        val allTools: MutableList<AgentTool<*>>,
        val backgroundAgents: BackgroundAgents,
        val scheduler: SubagentScheduler,
    )

    private class ClientCore(
        val client: AgentClient,
        val toolCatalog: LinkedHashMap<String, AgentTool<*>>,
        val binding: ExtensionContextBinding,
        val ui: MutableExtensionUi,
    )

    private class ExtensionSurface(
        val client: AgentClient,
        val extensionContext: CliExtensionContext,
        val reloader: ExtensionReloader,
    )

    private class InitialMessage(
        val userMessage: UserMessage?,
        val runAsPrintMode: Boolean,
    )
}
