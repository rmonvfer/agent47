package co.agentmode.agent47.app

import co.agentmode.agent47.app.bootstrap.AgentRuntime
import co.agentmode.agent47.app.bootstrap.AgentRuntimeBuilder
import co.agentmode.agent47.app.cli.CliOptions
import co.agentmode.agent47.app.cli.printError
import co.agentmode.agent47.app.cli.runSmokeTool
import co.agentmode.agent47.app.modes.runInteractiveMode
import co.agentmode.agent47.app.modes.runPrintMode
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.ext.core.SessionShutdownEvent
import co.agentmode.agent47.ext.core.SessionShutdownReason
import co.agentmode.agent47.ext.core.SessionStartEvent
import co.agentmode.agent47.ext.core.SessionStartReason
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple as multipleArguments
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple as multipleOptions
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path

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
        if (handleEarlyExit()) return

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
        val runtime = AgentRuntimeBuilder(options, config, terminal).build() ?: return
        runRuntime(runtime, options)
    }

    private fun handleEarlyExit(): Boolean = when {
        showVersion -> {
            terminal.println("agent47 ${BuildInfo.version}")
            true
        }

        promptArgs.firstOrNull() == "--smoke-tool" -> {
            runSmokeTool(promptArgs.drop(1), terminal)
            true
        }

        else -> false
    }

    private suspend fun runRuntime(runtime: AgentRuntime, options: CliOptions) {
        runtime.extensionRuntime.runner.startSession(
            SessionStartEvent(SessionStartReason.STARTUP),
            runtime.extensionContext,
        )
        try {
            if (runtime.runAsPrintMode) {
                val initialMessage = runtime.initialUserMessage ?: run {
                    terminal.printError("No prompt provided. Use --help for usage information.")
                    throw Abort()
                }
                runtime.sessionTracker.current?.appendMessage(initialMessage)
                runPrintMode(runtime, initialMessage)
            } else {
                runInteractiveMode(runtime, options, runtime.initialUserMessage)
            }
        } finally {
            runtime.extensionRuntime.runner.shutdownSession(
                SessionShutdownEvent(SessionShutdownReason.QUIT),
                runtime.extensionContext,
            )
        }
    }
}
