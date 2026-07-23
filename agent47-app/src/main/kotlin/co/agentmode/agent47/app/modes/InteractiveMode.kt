package co.agentmode.agent47.app.modes

import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.app.BuildInfo
import co.agentmode.agent47.app.bootstrap.AgentRuntime
import co.agentmode.agent47.app.cli.CliOptions
import co.agentmode.agent47.app.cli.scopedModels
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsManager
import co.agentmode.agent47.ext.core.SessionShutdownEvent
import co.agentmode.agent47.ext.core.SessionShutdownReason
import co.agentmode.agent47.ext.core.SessionStartEvent
import co.agentmode.agent47.tui.TuiConversationServices
import co.agentmode.agent47.tui.TuiLaunchConfiguration
import co.agentmode.agent47.tui.TuiProviderServices
import co.agentmode.agent47.tui.TuiSubagentServices
import co.agentmode.agent47.tui.runTui
import co.agentmode.agent47.tui.theme.NamedTheme
import co.agentmode.agent47.tui.theme.TerminalAppearance
import co.agentmode.agent47.tui.theme.TerminalAppearanceDetector
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import java.nio.file.Path

internal suspend fun runInteractiveMode(runtime: AgentRuntime, options: CliOptions, initialMessage: UserMessage?) {
    val (resolvedAppearance, resolvedTheme) = resolveTheme(runtime.settings, runtime.availableThemes)
    runtime.use {
        runTui(
            client = runtime.client,
            configuration = buildLaunchConfiguration(runtime, options, initialMessage, resolvedAppearance, resolvedTheme),
            providerServices = buildProviderServices(runtime),
            conversationServices = buildConversationServices(runtime),
            subagentServices = buildSubagentServices(runtime),
        )
    }
}

private fun buildLaunchConfiguration(
    runtime: AgentRuntime,
    options: CliOptions,
    initialMessage: UserMessage?,
    appearance: ThemeAppearance,
    theme: ThemeConfig,
): TuiLaunchConfiguration = TuiLaunchConfiguration(
    version = BuildInfo.version,
    initialUserMessage = initialMessage,
    availableModels = scopedModels(runtime.modelRegistry.getAvailable(), options.models),
    sessionManager = runtime.sessionTracker.current,
    sessionsDir = runtime.sessionsDir,
    cwd = Path.of(System.getProperty("user.dir")),
    initialThinkingLevel = runtime.thinkingLevel,
    initialModel = runtime.resolvedModel,
    theme = theme,
    themeAppearance = appearance,
    availableThemes = runtime.availableThemes,
    fileCommands = runtime.fileCommands,
    extensionCommands = runtime.extensionRuntime.runner.commands(),
    extensionShortcuts = runtime.extensionRuntime.runner.shortcuts(),
    extensionContext = runtime.extensionContext,
    extensionToolRenderers = runtime.extensionRuntime.runner.toolRenderers(),
    extensionMessageRenderers = runtime.extensionRuntime.runner.messageRenderers(),
    initialShowUsageFooter = runtime.settings.get().showUsageFooter ?: true,
    todoState = runtime.todoState,
    instructionFiles = runtime.instructionLoader.load(),
    skills = runtime.skillRegistry.getAll(),
    extensionIds = runtime.extensionRuntime.runner.loadedExtensionIds(),
    compactionSettings = runtime.settings.get().compaction,
)

private fun buildProviderServices(runtime: AgentRuntime): TuiProviderServices {
    val modelRegistry = runtime.modelRegistry
    return TuiProviderServices(
        getAllProviders = { modelRegistry.getAllProviders() },
        storeApiKey = { provider, apiKey -> modelRegistry.storeApiKey(provider, apiKey) },
        storeOAuthCredential = { provider, credential -> modelRegistry.storeOAuthCredential(provider, credential) },
        refreshModels = { modelRegistry.getAvailable() },
        authorizeOAuth = { provider -> modelRegistry.getAuthPlugin(provider)?.authorize() },
        pollOAuthToken = { provider -> modelRegistry.getAuthPlugin(provider)?.pollForToken() },
        isUsingOAuth = modelRegistry::isUsingOAuth,
    )
}

private fun buildConversationServices(runtime: AgentRuntime): TuiConversationServices = TuiConversationServices(
    onSettingsChanged = { transform -> runtime.settings.update(transform) },
    compactContext = runtime.compactionService::compact,
    onCompacted = { event ->
        runtime.extensionRuntime.runner.completeCompaction(event, runtime.extensionContext)
    },
    reloadExtensions = runtime.reloader::reload,
    onSessionChanged = { manager ->
        runtime.sessionTracker.current = manager
    },
    onSessionTransition = { previous, next, reason ->
        runtime.sessionTracker.current = previous
        runtime.extensionRuntime.runner.shutdownSession(
            SessionShutdownEvent(
                reason = SessionShutdownReason.valueOf(reason.name),
                targetSessionFile = next?.getSessionFile(),
            ),
            runtime.extensionContext,
        )
        runtime.sessionTracker.current = next
        runtime.extensionRuntime.runner.startSession(
            SessionStartEvent(reason, previous?.getSessionFile()),
            runtime.extensionContext,
        )
    },
    processInput = { event ->
        runtime.extensionRuntime.runner.processInput(event, runtime.extensionContext)
    },
)

private fun buildSubagentServices(runtime: AgentRuntime): TuiSubagentServices = TuiSubagentServices(
    backgroundAgents = runtime.backgroundAgents,
    settings = runtime.subagentsSettings.get(),
    agentRegistry = runtime.agentRegistry,
    scheduler = runtime.scheduler,
    persistSettings = { updated ->
        SubagentsSettingsManager.save(runtime.config.projectSubagentsSettingsPath, updated)
        val previous = runtime.subagentsSettings.get()
        runtime.subagentsSettings.set(updated)
        runtime.backgroundAgents.setMaxConcurrent(updated.maxConcurrent)
        runtime.agentRegistry.setDisableDefaultAgents(updated.disableDefaultAgents)
        if (updated.schedulingEnabled && !previous.schedulingEnabled) runtime.scheduler.start()
        if (!updated.schedulingEnabled && previous.schedulingEnabled) runtime.scheduler.stop()
    },
)

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
