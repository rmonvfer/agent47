package co.agentmode.agent47.app

import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.ai.providers.anthropic.registerAnthropicProviders
import co.agentmode.agent47.ai.providers.google.registerGoogleProviders
import co.agentmode.agent47.ai.providers.openai.registerOpenAiProviders
import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.cli.CliOptions
import co.agentmode.agent47.app.cli.escapePromptFileArguments
import co.agentmode.agent47.app.cli.listModels
import co.agentmode.agent47.app.cli.mergeThemes
import co.agentmode.agent47.app.cli.parseModelSpec
import co.agentmode.agent47.app.cli.printError
import co.agentmode.agent47.app.cli.printWarning
import co.agentmode.agent47.app.cli.processPrompt
import co.agentmode.agent47.app.cli.resolveModel
import co.agentmode.agent47.app.cli.resolveSession
import co.agentmode.agent47.app.cli.resolveThinkingLevel
import co.agentmode.agent47.app.cli.runSmokeTool
import co.agentmode.agent47.app.cli.scopedModels
import co.agentmode.agent47.app.cli.sessionsBaseDir
import co.agentmode.agent47.app.bootstrap.ScheduledJobRunner
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.app.compaction.CompactionService
import co.agentmode.agent47.app.extensions.CliExtensionContext
import co.agentmode.agent47.app.extensions.CliExtensionSessionControl
import co.agentmode.agent47.app.extensions.ExtensionContextBinding
import co.agentmode.agent47.app.extensions.ExtensionReloader
import co.agentmode.agent47.app.extensions.HeadlessExtensionUi
import co.agentmode.agent47.app.update.UpdateCommand
import co.agentmode.agent47.app.update.installAutomaticUpdate
import co.agentmode.agent47.app.update.shouldCheckForUpdates
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.auth.CopilotAuthPlugin
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.compaction.applyCompaction
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.extensions.ExtensionPackageManager
import co.agentmode.agent47.coding.core.instructions.InstructionFile
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsState
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.Worktree
import co.agentmode.agent47.coding.core.agents.schedule.ScheduleStore
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandDiscovery
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.coding.core.tools.CheckInboxTool
import co.agentmode.agent47.coding.core.tools.ApplicableSkill
import co.agentmode.agent47.coding.core.tools.DEFAULT_TOOLS
import co.agentmode.agent47.coding.core.tools.SendMessageTool
import co.agentmode.agent47.coding.core.tools.SkillReader
import co.agentmode.agent47.coding.core.tools.TaskTool
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.coding.core.tools.createCoreTools
import co.agentmode.agent47.ext.core.KotlinExtensionDiscovery
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.AfterCompactionEvent
import co.agentmode.agent47.ext.core.CompactionReason
import co.agentmode.agent47.ext.core.PreparedCompaction
import co.agentmode.agent47.ext.core.ExtensionResources
import co.agentmode.agent47.ext.core.MutableExtensionUi
import co.agentmode.agent47.ext.core.MutableExtensionSessionControl
import co.agentmode.agent47.ext.core.RegisteredCommand
import co.agentmode.agent47.ext.core.SessionShutdownEvent
import co.agentmode.agent47.ext.core.SessionShutdownReason
import co.agentmode.agent47.ext.core.SessionStartEvent
import co.agentmode.agent47.ext.core.SessionStartReason
import co.agentmode.agent47.tui.runTui
import co.agentmode.agent47.tui.TuiConversationServices
import co.agentmode.agent47.tui.TuiLaunchConfiguration
import co.agentmode.agent47.tui.TuiProviderServices
import co.agentmode.agent47.tui.TuiSubagentServices
import co.agentmode.agent47.tui.theme.AVAILABLE_THEMES
import co.agentmode.agent47.tui.theme.TerminalAppearance
import co.agentmode.agent47.tui.theme.TerminalAppearanceDetector
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.NamedTheme
import co.agentmode.agent47.tui.theme.loadNamedTheme
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple as multipleArguments
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple as multipleOptions
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*

private val VALID_ENV_HINT = "ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY"

suspend fun main(args: Array<String>) {
    if (args.firstOrNull() == "extension") {
        runExtensionPackageCommand(args.drop(1))
        return
    }
    if (args.firstOrNull() == "update") {
        UpdateCommand().main(args.drop(1).toTypedArray())
        return
    }
    if (args.contentEquals(arrayOf("--version"))) {
        println("agent47 ${BuildInfo.version}")
        return
    }

    if (shouldCheckForUpdates(args) && installAutomaticUpdate(args)) return
    Agent47Command().main(escapePromptFileArguments(args))
}

class Agent47Command :
    SuspendingCliktCommand(name = "agent47") {
    private val provider by option(
        "--provider",
        help = "Provider name (anthropic, openai, google, ...)"
    )
    private val model by option(
        "--model",
        help = "Model ID or pattern (supports provider/id and :thinking)"
    )
    private val apiKey by option(
        "--api-key",
        help = "API key (defaults to env vars)"
    )
    private val systemPrompt by option(
        "--system-prompt",
        help = "Custom system prompt"
    )
    private val appendSystemPrompt by option(
        "--append-system-prompt",
        help = "Append to system prompt"
    )
    private val thinking by option(
        "--thinking",
        help = "Thinking level: off, minimal, low, medium, high, xhigh"
    )
    private val printMode by option(
        "-p", "--print",
        help = "Non-interactive mode: process and exit"
    ).flag()
    private val continueSession by option(
        "-c", "--continue",
        help = "Continue previous session"
    ).flag()
    private val resume by option(
        "-r", "--resume",
        help = "Resume a session by its id (shown in the hint printed on exit)",
    )
    private val session by option(
        "--session",
        help = "Use specific session file"
    ).path()
    private val sessionDir by option(
        "--session-dir",
        help = "Directory for session storage"
    ).path()
    private val noSession by option(
        "--no-session",
        help = "Don't save session (ephemeral)"
    ).flag()
    private val models by option(
        "--models",
        help = "Comma-separated model patterns for cycling"
    )
    private val tools by option(
        "--tools",
        help = "Comma-separated tools (read,bash,edit,write,multiedit,grep,find,ls,todowrite,todoread,todocreate,todoupdate,batch)"
    )
    private val noTools by option(
        "--no-tools",
        help = "Disable all built-in tools"
    ).flag()
    private val extensionPaths by option(
        "-e", "--extension",
        help = "Load a Kotlin extension file or directory",
    ).path().multipleOptions()
    private val noExtensions by option(
        "--no-extensions",
        help = "Disable project and global extension discovery",
    ).flag()
    private val extensionFlagOptions by option(
        "--extension-flag",
        help = "Set a Kotlin extension flag as name or name=value",
    ).multipleOptions()
    private val listExtensions by option(
        "--list-extensions",
        help = "Load and list Kotlin extensions, then exit",
    ).flag()
    private val listModels by option(
        "--list-models",
        help = "List available models"
    ).flag()
    private val listModelsSearch by option(
        "--list-models-search",
        help = "Search pattern for --list-models"
    )
    private val showVersion by option(
        "--version",
        help = "Show the installed agent47 version"
    ).flag()
    private val promptArgs by argument(
        help = "Prompt messages, @file paths, or the update command"
    ).multipleArguments()

    private val terminal = Terminal()

    override suspend fun run() {
        if (showVersion) {
            terminal.println("agent47 ${BuildInfo.version}")
            return
        }
        if (handleSpecialCommands()) return

        val options = CliOptions(
            provider = provider,
            model = model,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            appendSystemPrompt = appendSystemPrompt,
            thinking = thinking,
            printMode = printMode,
            continueSession = continueSession,
            resume = resume,
            session = session,
            sessionDir = sessionDir,
            noSession = noSession,
            models = models,
            tools = tools,
            noTools = noTools,
            extensionPaths = extensionPaths,
            noExtensions = noExtensions,
            extensionFlags = extensionFlagOptions,
            listExtensions = listExtensions,
            listModels = listModels,
            listModelsSearch = listModelsSearch,
            showVersion = showVersion,
            promptArgs = promptArgs,
        )

        val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
        val extensionFlagValues = options.extensionFlags.fold(linkedMapOf<String, String>()) { values, raw ->
            val name = raw.substringBefore('=')
            val value = raw.substringAfter('=', "true")
            require(name.matches(Regex("[a-z][a-z0-9-]*"))) { "Invalid extension flag name: $name" }
            require(name !in values) { "Duplicate extension flag value: $name" }
            values.apply { put(name, value) }
        }
        val globalPackages = ExtensionPackageManager(config.globalPackagesRegistryPath, config.globalPackagesDir)
        val projectPackages = ExtensionPackageManager(config.projectPackagesRegistryPath, config.projectPackagesDir)
        val packageResources = runCatching {
            val global = globalPackages.resources()
            val project = projectPackages.resources()
            co.agentmode.agent47.coding.core.extensions.ExtensionPackageResources(
                extensions = global.extensions + project.extensions,
                skillDirectories = global.skillDirectories + project.skillDirectories,
                promptDirectories = global.promptDirectories + project.promptDirectories,
                themeFiles = global.themeFiles + project.themeFiles,
            )
        }.getOrElse { error ->
            terminal.printError(error.message ?: error.toString())
            throw Abort()
        }
        val availableThemes = runCatching {
            mergeThemes(packageResources.themeFiles.map(::loadNamedTheme))
        }.getOrElse { error ->
            terminal.printError(error.message ?: error.toString())
            throw Abort()
        }
        val discoveredExtensionPaths = runCatching {
            KotlinExtensionDiscovery.discover(
                explicitPaths = extensionPaths,
                projectDirectory = config.projectExtensionsDir,
                globalDirectory = config.globalExtensionsDir,
                packagePaths = packageResources.extensions,
                autoDiscover = !noExtensions,
            )
        }.getOrElse { error ->
            terminal.printError(error.message ?: error.toString())
            throw Abort()
        }
        val extensionRuntime = KotlinExtensionRuntime(
            discoveredExtensionPaths,
            discoveredExtensionPaths.takeIf { it.isNotEmpty() }?.let {
                KotlinExtensionSupport.createLoader().also { loader ->
                    loader.configureFlags(extensionFlagValues)
                }
            },
        )
        var extensionLoadFailed = false
        if (discoveredExtensionPaths.isNotEmpty()) {
            val report = extensionRuntime.reload()
            extensionLoadFailed = report.failures.isNotEmpty()
            report.failures.forEach { failure ->
                terminal.printError(
                    buildString {
                        append("Failed to load extension ${failure.path}")
                        failure.diagnostics.forEach { diagnostic -> append("\n  $diagnostic") }
                    },
                )
            }
        }
        val unknownFlags = extensionFlagValues.keys - extensionRuntime.runner.flags().map { it.name }.toSet()
        if (unknownFlags.isNotEmpty()) {
            terminal.printError("Unknown extension flags: ${unknownFlags.joinToString()}")
            extensionLoadFailed = true
        }
        if (listExtensions) {
            extensionRuntime.runner.loadedExtensionIds().forEach(terminal::println)
            if (extensionLoadFailed) throw Abort()
            return
        }
        val authStorage = AuthStorage(config.authPath)
        val modelRegistry = ModelRegistry(authStorage, config.modelsPath, config.modelsJsonLegacyPath)
        val settings = SettingsManager.create(
            config.globalSettingsPath,
            config.projectSettingsPath
        )
        val subagentsSettings = SubagentsSettingsState(
            SubagentsSettingsManager.load(
                config.globalSubagentsSettingsPath,
                config.projectSubagentsSettingsPath,
            ),
        )

        val apiRegistry = ApiRegistry()
        registerProviders(apiRegistry)
        extensionRuntime.runner.registerProviders(apiRegistry, modelRegistry)
        modelRegistry.refreshAsync()

        if (listModels) {
            listModels(modelRegistry, options.listModelsSearch, terminal)
            return
        }

        val aiRuntime = AiRuntime(apiRegistry)
        modelRegistry.registerAuthPlugin(CopilotAuthPlugin())

        // An explicit --api-key must reach the registry before model resolution so first-time setup
        // (with no stored credentials) can find and use the requested provider's models.
        apiKey?.let { key ->
            val targetProvider = parseModelSpec(options.model).provider ?: options.provider ?: settings.get().defaultProvider
            if (targetProvider != null) {
                modelRegistry.storeApiKey(targetProvider, key)
            }
        }

        val resolvedModel = resolveModel(options, modelRegistry, settings)
        if (resolvedModel == null) {
            terminal.printError("No API key found. Set one of: $VALID_ENV_HINT")
            throw Abort()
        }

        val thinkingLevel = resolveThinkingLevel(options, settings)
        val sessionManager = resolveSession(options, config, terminal)
        val sessionTracker = SessionTracker(sessionManager)

        val (promptContent, images) = processPrompt(options.promptArgs, terminal)

        val agentRegistry = AgentRegistry(
            config.projectAgentsDir,
            config.globalAgentsDir,
            disableDefaultAgents = subagentsSettings.get().disableDefaultAgents,
        )
        val skillRegistry = SkillRegistry(
            config.projectSkillsDir,
            config.globalSkillsDir,
            packageResources.skillDirectories,
        )
        val fileCommands = SlashCommandDiscovery.discover(
            config.projectCommandsDir,
            config.globalCommandsDir,
            packageResources.promptDirectories,
        )

        val skillReader = object : SkillReader {
            override fun readSkillFile(name: String, relativePath: String?): String? =
                skillRegistry.readSkillFile(name, relativePath)

            override fun readApplicableSkills(path: String): List<ApplicableSkill> =
                skillRegistry.getApplicable(listOf(path))
                    .filterNot { it.alwaysApply }
                    .map { ApplicableSkill(name = it.name, content = it.content) }
        }

        val toolsEnabled = if (noTools) emptyList() else resolveTools()
        val workingDir = Path.of(System.getProperty("user.dir"))

        val instructionLoader = InstructionLoader(
            cwd = workingDir,
            globalDir = config.globalDir,
            claudeDir = config.claudeDir,
            settings = settings.get(),
        )
        val todoState = TodoState()
        val toolRegistry = createCoreTools(workingDir, toolsEnabled, skillReader, todoState)
        val allTools = toolRegistry.all().toMutableList<co.agentmode.agent47.agent.core.AgentTool<*>>()

        val backgroundAgents = BackgroundAgents(maxConcurrent = subagentsSettings.get().maxConcurrent)
        // Clean up any worktrees orphaned by a previous crashed run.
        Worktree.pruneWorktrees(workingDir)

        // Session-scoped scheduler: fires scheduled subagents while this session runs.
        val sessionId = sessionManager?.getHeader()?.id ?: "default"
        val store = ScheduleStore(config.projectDir.resolve("subagent-schedules").resolve("$sessionId.json"))
        val scheduledJobRunner = ScheduledJobRunner(
            aiRuntime = aiRuntime,
            agentRegistry = agentRegistry,
            modelRegistry = modelRegistry,
            backgroundAgents = backgroundAgents,
            settings = settings,
            subagentsSettings = subagentsSettings,
            skillRegistry = skillRegistry,
            workingDir = workingDir,
            sessionsDir = sessionsBaseDir(options, config),
            memoryProjectDir = config.projectDir,
            memoryGlobalDir = config.globalDir,
            sessionId = sessionId,
        )
        val scheduler = SubagentScheduler(store, scheduledJobRunner::run)
        if (subagentsSettings.get().schedulingEnabled) scheduler.start()
        if (!noTools) {
            val taskTool = TaskTool(
                streamFunction = aiRuntime::streamSimple,
                agentRegistry = agentRegistry,
                modelRegistry = modelRegistry,
                settings = settings.get(),
                cwd = workingDir,
                backgroundAgents = backgroundAgents,
                subagentsSettings = subagentsSettings,
                currentDepth = 0,
                maxDepth = settings.get().taskMaxRecursionDepth,
                getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
                sessionsDir = sessionsBaseDir(options, config),
                parentSessionId = sessionManager?.getHeader()?.id,
                memoryProjectDir = config.projectDir,
                memoryGlobalDir = config.globalDir,
                scheduler = scheduler,
                skillContentProvider = { name -> skillRegistry.readSkillFile(name) },
            )
            allTools += taskTool
            allTools += CheckInboxTool(backgroundAgents)
            allTools += SendMessageTool(backgroundAgents, BackgroundAgents.ORCHESTRATOR)
        }

        val extensionRunner = extensionRuntime.runner
        val configuredTools = extensionRunner.wrapAllTools(
            (extensionRunner.tools() + allTools).distinctBy { it.definition.name },
        )
        val extensionToolCatalog = linkedMapOf<String, AgentTool<*>>()
        configuredTools.forEach { tool -> extensionToolCatalog[tool.definition.name] = tool }
        val builtSystemPrompt = buildSystemPrompt(
            cwd = workingDir,
            toolNames = configuredTools.map { it.definition.name },
            customPrompt = systemPrompt,
            appendPrompt = appendSystemPrompt,
            skills = skillRegistry.getAll(),
            instructions = instructionLoader.format(),
        )

        val extensionBinding = ExtensionContextBinding()
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
                onEvent = { event -> extensionRuntime.runner.dispatchAgentEvent(event, extensionBinding.get()) },
                getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
            ),
        )
        val extensionSession = MutableExtensionSessionControl(
            CliExtensionSessionControl(
                sessionTracker = sessionTracker,
                extensionRuntime = extensionRuntime,
                client = client,
                sessionsDir = sessionsBaseDir(options, config),
                workingDir = workingDir,
                noSession = options.noSession,
                contextProvider = extensionBinding::get,
            ),
        )
        val reloadExtensions = ExtensionReloader(
            extensionRuntime = extensionRuntime,
            apiRegistry = apiRegistry,
            modelRegistry = modelRegistry,
            allTools = allTools,
            toolCatalog = extensionToolCatalog,
            client = client,
            workingDir = workingDir,
            customPrompt = options.systemPrompt,
            appendPrompt = options.appendSystemPrompt,
            skillRegistry = skillRegistry,
            instructionLoader = instructionLoader,
            sessionTracker = sessionTracker,
            contextProvider = extensionBinding::get,
        )
        val extensionContext = CliExtensionContext(
            ui = extensionUi,
            session = extensionSession,
            cwd = workingDir,
            printMode = options.printMode,
            client = client,
            modelRegistry = modelRegistry,
            toolCatalog = extensionToolCatalog,
            sessionTracker = sessionTracker,
            extensionRuntime = extensionRuntime,
            customPrompt = options.systemPrompt,
            appendPrompt = options.appendSystemPrompt,
            skillRegistry = skillRegistry,
            instructionLoader = instructionLoader,
            reloader = reloadExtensions,
        )
        extensionBinding.bind(extensionContext)
        extensionRunner.bindContext { extensionContext }
        // Let background sub-agents inherit the orchestrator's prompt/conversation and (later)
        // receive push notifications.
        backgroundAgents.setOrchestrator(
            systemPrompt = { client.state.systemPrompt },
            messages = { client.state.messages },
        )

        val userContent = mutableListOf<ContentBlock>()
        if (promptContent.isNotBlank()) {
            userContent.add(TextContent(text = promptContent))
        }
        userContent.addAll(images)

        val runAsPrintMode = printMode || System.console() == null

        if (userContent.isEmpty() && runAsPrintMode) {
            terminal.printError("No prompt provided. Use --help for usage information.")
            throw Abort()
        }

        val userMessage = if (userContent.isEmpty()) {
            null
        } else {
            UserMessage(
                content = userContent.toList(),
                timestamp = System.currentTimeMillis(),
            )
        }

        val compactionService = CompactionService(
            extensionRuntime = extensionRuntime,
            extensionContext = extensionContext,
            settings = settings,
            aiRuntime = aiRuntime,
            modelRegistry = modelRegistry,
        )
        val compactContext: suspend (List<Message>, Model, CompactionReason) -> PreparedCompaction? =
            compactionService::compact

        extensionRuntime.runner.startSession(
            co.agentmode.agent47.ext.core.SessionStartEvent(
                co.agentmode.agent47.ext.core.SessionStartReason.STARTUP,
            ),
            extensionContext,
        )
        try {
            if (runAsPrintMode) {
                val initialMessage = userMessage ?: run {
                    terminal.printError("No prompt provided. Use --help for usage information.")
                    throw Abort()
                }
                sessionManager?.appendMessage(initialMessage)
                runPrintMode(client, initialMessage, resolvedModel, sessionManager)
            } else {
                runInteractiveMode(
                client = client,
                userMessage = userMessage,
                model = resolvedModel,
                thinkingLevel = thinkingLevel,
                availableModels = scopedModels(modelRegistry.getAvailable(), options.models),
                sessionManager = sessionManager,
                sessionsDir = sessionsBaseDir(options, config),
                fileCommands = fileCommands,
                extensionCommands = extensionRuntime.runner.commands(),
                extensionShortcuts = extensionRuntime.runner.shortcuts(),
                extensionContext = extensionContext,
                extensionToolRenderers = extensionRuntime.runner.toolRenderers(),
                extensionMessageRenderers = extensionRuntime.runner.messageRenderers(),
                availableThemes = availableThemes,
                modelRegistry = modelRegistry,
                settingsManager = settings,
                todoState = todoState,
                instructionFiles = instructionLoader.load(),
                compactContext = compactContext,
                onCompacted = { event ->
                    extensionRuntime.runner.completeCompaction(event, extensionContext)
                },
                onSessionChanged = { manager ->
                    sessionTracker.current = manager
                },
                onSessionTransition = { previous, next, reason ->
                    sessionTracker.current = previous
                    extensionRuntime.runner.shutdownSession(
                        SessionShutdownEvent(
                            reason = SessionShutdownReason.valueOf(reason.name),
                            targetSessionFile = next?.getSessionFile(),
                        ),
                        extensionContext,
                    )
                    sessionTracker.current = next
                    extensionRuntime.runner.startSession(
                        SessionStartEvent(reason, previous?.getSessionFile()),
                        extensionContext,
                    )
                },
                processInput = { event ->
                    extensionRuntime.runner.processInput(event, extensionContext)
                },
                compactionSettings = settings.get().compaction,
                backgroundAgents = backgroundAgents,
                subagentsSettings = subagentsSettings.get(),
                agentRegistry = agentRegistry,
                scheduler = scheduler,
                reloadExtensions = reloadExtensions::reload,
                persistSubagentsSettings = { updated ->
                    SubagentsSettingsManager.save(config.projectSubagentsSettingsPath, updated)
                    val previous = subagentsSettings.get()
                    subagentsSettings.set(updated)
                    backgroundAgents.setMaxConcurrent(updated.maxConcurrent)
                    agentRegistry.setDisableDefaultAgents(updated.disableDefaultAgents)
                    if (updated.schedulingEnabled && !previous.schedulingEnabled) scheduler.start()
                    if (!updated.schedulingEnabled && previous.schedulingEnabled) scheduler.stop()
                },
                )
            }
        } finally {
            extensionRuntime.runner.shutdownSession(
                co.agentmode.agent47.ext.core.SessionShutdownEvent(
                    co.agentmode.agent47.ext.core.SessionShutdownReason.QUIT,
                ),
                extensionContext,
            )
        }
    }

    private fun handleSpecialCommands(): Boolean {
        if (promptArgs.firstOrNull() == "--smoke-tool") {
            runSmokeTool(promptArgs.drop(1), terminal)
            return true
        }
        return false
    }

    private fun registerProviders(registry: ApiRegistry) {
        registerOpenAiProviders(registry)
        registerAnthropicProviders(registry)
        registerGoogleProviders(registry)
    }

    private fun resolveTools(): List<String> {
        return tools?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: DEFAULT_TOOLS
    }

    private suspend fun runPrintMode(
        client: AgentClient,
        userMessage: UserMessage,
        model: Model,
        sessionManager: SessionManager?,
    ) {
        client.prompt(listOf(userMessage))
        client.waitForIdle()

        val messages = client.state.messages
        val lastAssistant = messages.lastOrNull { it is AssistantMessage } as? AssistantMessage

        if (lastAssistant != null) {
            val text = lastAssistant.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .ifBlank { "(assistant returned no text)" }

            terminal.println(text)

            sessionManager?.appendMessage(lastAssistant)

            val usage = lastAssistant.usage
            if (usage.cost.total > 0.0) {
                terminal.printError(
                    $$"""[$${model.name}] tokens: $${usage.input}in/$${usage.output}out, cost: \$$${
                        String.format(
                            "%.4f",
                            usage.cost.total
                        )
                    }""",
                )
            }
        } else {
            terminal.println("No assistant response was produced.")
        }
    }

    private suspend fun runInteractiveMode(
        client: AgentClient,
        userMessage: UserMessage?,
        model: Model,
        thinkingLevel: AgentThinkingLevel,
        availableModels: List<Model>,
        sessionManager: SessionManager?,
        sessionsDir: Path,
        fileCommands: List<SlashCommand> = emptyList(),
        extensionCommands: List<RegisteredCommand> = emptyList(),
        extensionShortcuts: List<co.agentmode.agent47.ext.core.RegisteredShortcut> = emptyList(),
        extensionContext: ExtensionContext? = null,
        extensionToolRenderers: List<co.agentmode.agent47.ext.core.RegisteredToolRenderer> = emptyList(),
        extensionMessageRenderers: List<co.agentmode.agent47.ext.core.RegisteredMessageRenderer> = emptyList(),
        availableThemes: List<NamedTheme> = AVAILABLE_THEMES,
        modelRegistry: ModelRegistry,
        settingsManager: SettingsManager,
        todoState: TodoState? = null,
        instructionFiles: List<InstructionFile> = emptyList(),
        compactContext: (suspend (List<Message>, Model, CompactionReason) -> PreparedCompaction?)? = null,
        onCompacted: suspend (AfterCompactionEvent) -> Unit = {},
        onSessionChanged: (SessionManager?) -> Unit = {},
        onSessionTransition: suspend (SessionManager?, SessionManager?, SessionStartReason) -> Unit = { _, _, _ -> },
        processInput: suspend (co.agentmode.agent47.ext.core.InputEvent) -> co.agentmode.agent47.ext.core.InputHookResult = {
            co.agentmode.agent47.ext.core.InputHookResult.Continue
        },
        compactionSettings: co.agentmode.agent47.coding.core.compaction.CompactionSettings = co.agentmode.agent47.coding.core.compaction.CompactionSettings(),
        backgroundAgents: BackgroundAgents,
        subagentsSettings: SubagentsSettings,
        agentRegistry: AgentRegistry,
        scheduler: SubagentScheduler?,
        reloadExtensions: suspend () -> ExtensionResources,
        persistSubagentsSettings: (SubagentsSettings) -> Unit,
    ) {
        val (resolvedAppearance, resolvedTheme) = resolveTheme(settingsManager, availableThemes)
        val initialShowUsageFooter = settingsManager.get().showUsageFooter ?: true

        try {
            runTui(
                client = client,
                configuration = TuiLaunchConfiguration(
                    initialUserMessage = userMessage,
                    availableModels = availableModels,
                    sessionManager = sessionManager,
                    sessionsDir = sessionsDir,
                    cwd = Path.of(System.getProperty("user.dir")),
                    initialThinkingLevel = thinkingLevel,
                    initialModel = model,
                    theme = resolvedTheme,
                    themeAppearance = resolvedAppearance,
                    availableThemes = availableThemes,
                    fileCommands = fileCommands,
                    extensionCommands = extensionCommands,
                    extensionShortcuts = extensionShortcuts,
                    extensionContext = extensionContext,
                    extensionToolRenderers = extensionToolRenderers,
                    extensionMessageRenderers = extensionMessageRenderers,
                    initialShowUsageFooter = initialShowUsageFooter,
                    todoState = todoState,
                    instructionFiles = instructionFiles,
                    compactionSettings = compactionSettings,
                ),
                providerServices = TuiProviderServices(
                    getAllProviders = { modelRegistry.getAllProviders() },
                    storeApiKey = { provider, apiKey -> modelRegistry.storeApiKey(provider, apiKey) },
                    storeOAuthCredential = { provider, credential -> modelRegistry.storeOAuthCredential(provider, credential) },
                    refreshModels = { modelRegistry.getAvailable() },
                    authorizeOAuth = { provider -> modelRegistry.getAuthPlugin(provider)?.authorize() },
                    pollOAuthToken = { provider -> modelRegistry.getAuthPlugin(provider)?.pollForToken() },
                    isUsingOAuth = modelRegistry::isUsingOAuth,
                ),
                conversationServices = TuiConversationServices(
                    onSettingsChanged = { transform -> settingsManager.update(transform) },
                    compactContext = compactContext,
                    onCompacted = onCompacted,
                    reloadExtensions = reloadExtensions,
                    onSessionChanged = onSessionChanged,
                    onSessionTransition = onSessionTransition,
                    processInput = processInput,
                ),
                subagentServices = TuiSubagentServices(
                    backgroundAgents = backgroundAgents,
                    settings = subagentsSettings,
                    agentRegistry = agentRegistry,
                    scheduler = scheduler,
                    persistSettings = persistSubagentsSettings,
                ),
            )
        } finally {
            scheduler?.stop()
            backgroundAgents.cancelAll()
        }
    }

    private fun resolveTheme(
        settingsManager: SettingsManager,
        availableThemes: List<NamedTheme>,
    ): Pair<ThemeAppearance, ThemeConfig> {
        val configuredAppearance = settingsManager.get().themeAppearance
            ?.uppercase()
            ?.let { runCatching { ThemeAppearance.valueOf(it) }.getOrNull() }
            ?: ThemeAppearance.AUTO
        val terminalAppearance = if (configuredAppearance == ThemeAppearance.AUTO) {
            TerminalAppearanceDetector.detect().appearance
        } else {
            null
        }
        val resolvedAppearance = configuredAppearance.resolve(terminalAppearance != TerminalAppearance.LIGHT)
        val resolvedTheme = settingsManager.get().theme?.let { name ->
            availableThemes.firstOrNull { it.name == name }?.forAppearance(resolvedAppearance)
        } ?: availableThemes.firstOrNull { it.name == "default" }?.forAppearance(resolvedAppearance)
            ?: ThemeConfig.DEFAULT
        return resolvedAppearance to resolvedTheme
    }
}
