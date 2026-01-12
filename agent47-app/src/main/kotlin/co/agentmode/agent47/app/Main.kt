package co.agentmode.agent47.app

import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.agent.core.PartialAgentState
import co.agentmode.agent47.ai.providers.anthropic.registerAnthropicProviders
import co.agentmode.agent47.ai.providers.google.registerGoogleProviders
import co.agentmode.agent47.ai.providers.openai.registerOpenAiProviders
import co.agentmode.agent47.ai.types.*
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.auth.CopilotAuthPlugin
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.models.ModelResolver
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandDiscovery
import co.agentmode.agent47.coding.core.skills.Skill
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.coding.core.tools.SkillReader
import co.agentmode.agent47.coding.core.tools.TaskTool
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.coding.core.tools.createCoreTools
import co.agentmode.agent47.tui.runTui
import co.agentmode.agent47.tui.theme.AVAILABLE_THEMES
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import javax.imageio.ImageIO
import kotlin.io.path.*

private val VALID_ENV_HINT = "ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY"

private val VALID_THINKING_LEVELS = listOf(
    "off",
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh"
)

suspend fun main(args: Array<String>): Unit = Agent47Command().main(args)

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
        help = "Select session to resume"
    ).flag()
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
        help = "Comma-separated tools (read,bash,edit,write,grep,find,ls)"
    )
    private val noTools by option(
        "--no-tools",
        help = "Disable all built-in tools"
    ).flag()
    private val listModels by option(
        "--list-models",
        help = "List available models"
    ).flag()
    private val listModelsSearch by option(
        "--list-models-search",
        help = "Search pattern for --list-models"
    )
    private val export by option(
        "--export",
        help = "Export session to HTML"
    ).path()
    private val verbose by option(
        "--verbose",
        help = "Verbose output"
    ).flag()
    private val promptArgs by argument(
        help = "Prompt messages or @file paths"
    ).multiple()

    private val terminal = Terminal()

    override suspend fun run() {
        if (handleSpecialCommands()) return

        val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
        val authStorage = AuthStorage(config.authPath)
        val modelRegistry = ModelRegistry(authStorage, config.modelsPath, config.modelsJsonLegacyPath)
        val settings = SettingsManager.create(
            config.globalSettingsPath,
            config.projectSettingsPath
        )

        if (listModels) {
            listModels(modelRegistry, listModelsSearch)
            return
        }

        registerProviders()
        modelRegistry.registerAuthPlugin(CopilotAuthPlugin())

        val resolvedModel = resolveModel(modelRegistry, settings)
        if (resolvedModel == null) {
            terminal.printError("No API key found. Set one of: $VALID_ENV_HINT")
            throw Abort()
        }

        val thinkingLevel = resolveThinkingLevel(settings)
        val sessionManager = resolveSession(config)
        val (promptContent, images) = processPrompt(promptArgs)

        val agentRegistry = AgentRegistry(config.projectAgentsDir, config.globalAgentsDir)
        val skillRegistry = SkillRegistry(config.projectSkillsDir, config.globalSkillsDir)
        val fileCommands = SlashCommandDiscovery.discover(config.projectCommandsDir, config.globalCommandsDir)

        val skillReader = SkillReader { name, relativePath -> skillRegistry.readSkillFile(name, relativePath) }

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

        if (!noTools) {
            val taskTool = TaskTool(
                agentRegistry = agentRegistry,
                modelRegistry = modelRegistry,
                settings = settings.get(),
                cwd = workingDir,
                currentDepth = 0,
                maxDepth = settings.get().taskMaxRecursionDepth,
                getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
                sessionsDir = config.sessionsDir,
                parentSessionId = sessionManager?.getHeader()?.id,
            )
            allTools += taskTool
        }

        val builtSystemPrompt = buildSystemPrompt(
            cwd = workingDir,
            toolNames = allTools.map { it.definition.name },
            customPrompt = systemPrompt,
            appendPrompt = appendSystemPrompt,
            skills = skillRegistry.getAll(),
            instructions = instructionLoader.format(),
        )

        val client = AgentClient(
            AgentOptions(
                initialState = PartialAgentState(
                    model = resolvedModel,
                    systemPrompt = builtSystemPrompt,
                    tools = allTools,
                    thinkingLevel = thinkingLevel,
                ),
                getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
            ),
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
                availableModels = modelRegistry.getAvailable(),
                sessionManager = sessionManager,
                sessionsDir = config.sessionsDir,
                fileCommands = fileCommands,
                modelRegistry = modelRegistry,
                settingsManager = settings,
                todoState = todoState,
            )
        }
    }

    private fun handleSpecialCommands(): Boolean {
        if (promptArgs.firstOrNull() == "--smoke-tool") {
            runSmokeTool(promptArgs.drop(1))
            return true
        }
        return false
    }

    private fun registerProviders() {
        registerOpenAiProviders()
        registerAnthropicProviders()
        registerGoogleProviders()
    }

    private fun listModels(registry: ModelRegistry, search: String?) {
        val available = registry.getAvailable()

        val filtered = if (search != null) {
            available.filter {
                it.id.contains(search, ignoreCase = true) ||
                        it.name.contains(search, ignoreCase = true) ||
                        it.provider.value.contains(search, ignoreCase = true)
            }
        } else {
            available
        }

        if (filtered.isEmpty()) {
            terminal.printWarning("No models found${search?.let { " matching '$it'" } ?: ""}")
            return
        }

        val table = table {
            header { row("Provider", "Model ID", "Name", "Context", "Max Tokens") }
            body {
                filtered.sortedWith(compareBy({ it.provider.value }, { it.id })).forEach { model ->
                    row(
                        model.provider.value,
                        model.id,
                        model.name,
                        formatNumber(model.contextWindow),
                        formatNumber(model.maxTokens),
                    )
                }
            }
        }
        terminal.println(table)
    }

    private fun resolveModel(
        registry: ModelRegistry,
        settings: SettingsManager,
    ): Model? {
        val available = registry.getAvailable()
        if (available.isEmpty()) return null

        val (requestedProvider, requestedModelId, _) = parseModelSpec()

        return ModelResolver.resolveInitialModel(
            cliProvider = requestedProvider ?: provider,
            cliModel = requestedModelId ?: model,
            settings = settings.get(),
            available = available,
        )
    }

    private fun parseModelSpec(): Triple<String?, String?, String?> {
        val modelSpec = model ?: return Triple(null, null, null)

        val thinkingSuffix = VALID_THINKING_LEVELS.find { level ->
            modelSpec.endsWith(":$level")
        }

        val modelPart = thinkingSuffix?.let {
            modelSpec.removeSuffix(":$it")
        } ?: modelSpec

        val providerPrefix = modelPart.substringBefore("/", "").ifEmpty { null }
        val modelId = if (providerPrefix != null) {
            modelPart.substringAfter("/")
        } else {
            modelPart
        }

        return Triple(providerPrefix, modelId.ifBlank { null }, thinkingSuffix)
    }

    private fun resolveThinkingLevel(settings: SettingsManager): AgentThinkingLevel {
        val level = thinking ?: settings.get().defaultThinkingLevel ?: "off"
        return when (level.lowercase()) {
            "minimal" -> AgentThinkingLevel.MINIMAL
            "low" -> AgentThinkingLevel.LOW
            "medium" -> AgentThinkingLevel.MEDIUM
            "high" -> AgentThinkingLevel.HIGH
            "xhigh" -> AgentThinkingLevel.XHIGH
            else -> AgentThinkingLevel.OFF
        }
    }

    private fun resolveSession(config: AgentConfig): SessionManager? {
        if (noSession) return null

        val sessionPath = when {
            session != null -> session!!
            continueSession -> findLatestSession(config) ?: createNewSession(config)
            resume -> {
                terminal.printWarning("--resume is not yet implemented, starting new session")
                createNewSession(config)
            }

            else -> createNewSession(config)
        }

        return SessionManager(sessionPath)
    }

    private fun createNewSession(config: AgentConfig): Path {
        val dir = config.sessionsDir
        Files.createDirectories(dir)
        return dir.resolve("session-${System.currentTimeMillis()}.jsonl")
    }

    private fun findLatestSession(config: AgentConfig): Path? {
        val dir = config.sessionsDir
        if (!dir.exists()) return null

        return Files.list(dir)
            .filter { it.toString().endsWith(".jsonl") }
            .sorted { a, b -> b.fileName.compareTo(a.fileName) }
            .findFirst()
            .orElse(null)
    }

    private fun resolveTools(): List<String> {
        val defaultTools = listOf("read", "bash", "edit", "write")
        return tools?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: defaultTools
    }

    private fun processPrompt(args: List<String>): Pair<String, List<ImageContent>> {
        val textParts = mutableListOf<String>()
        val images = mutableListOf<ImageContent>()

        for (arg in args) {
            if (arg.startsWith("@")) {
                val filePath = Path.of(arg.substring(1))
                if (!filePath.exists()) {
                    terminal.printError("File not found: ${filePath.toAbsolutePath()}")
                    throw Abort()
                }

                val mimeType = detectImageMimeType(filePath)
                if (mimeType != null) {
                    val bytes = filePath.readBytes()
                    val resized = resizeImage(bytes, mimeType)
                    images.add(
                        ImageContent(
                            data = Base64.getEncoder().encodeToString(resized.data),
                            mimeType = resized.mimeType,
                        ),
                    )
                    textParts.add("<file name=\"${filePath.name}\"></file>")
                } else {
                    val content = filePath.readText()
                    textParts.add("<file name=\"${filePath.name}\">\n$content\n</file>")
                }
            } else {
                textParts.add(arg)
            }
        }

        return Pair(textParts.joinToString("\n\n"), images)
    }

    private fun detectImageMimeType(path: Path): String? {
        return when (path.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
        }
    }

    private fun resizeImage(bytes: ByteArray, mimeType: String): ImageResizeResult {
        val image = ImageIO.read(bytes.inputStream()) ?: return ImageResizeResult(bytes, mimeType)
        val (width, height) = image.width to image.height
        val maxDim = 2000

        if (width <= maxDim && height <= maxDim) {
            return ImageResizeResult(bytes, mimeType)
        }

        val scale = maxDim.toDouble() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val resized = java.awt.image.BufferedImage(newWidth, newHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = resized.createGraphics()
        graphics.drawImage(image.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
        graphics.dispose()

        val outputStream = java.io.ByteArrayOutputStream()
        val format = when (mimeType) {
            "image/png" -> "PNG"
            "image/jpeg" -> "JPEG"
            "image/gif" -> "GIF"
            "image/webp" -> "PNG"
            else -> "PNG"
        }
        ImageIO.write(resized, format, outputStream)

        return ImageResizeResult(outputStream.toByteArray(), if (format == "JPEG") "image/jpeg" else mimeType)
    }

    private data class ImageResizeResult(val data: ByteArray, val mimeType: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ImageResizeResult

            if (!data.contentEquals(other.data)) return false
            if (mimeType != other.mimeType) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + mimeType.hashCode()
            return result
        }
    }

    private suspend fun runPrintMode(
        client: AgentClient,
        userMessage: UserMessage,
        model: Model,
        sessionManager: SessionManager?,
    ) {
        client.prompt(listOf(userMessage))
        client.waitForIdle()

        val messages = client.rawAgent().state.messages
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
        modelRegistry: ModelRegistry,
        settingsManager: SettingsManager,
        todoState: TodoState? = null,
    ) {
        val resolvedTheme = settingsManager.get().theme?.let { name ->
            AVAILABLE_THEMES.firstOrNull { it.name == name }?.config
        } ?: ThemeConfig.DEFAULT

        val initialShowUsageFooter = settingsManager.get().showUsageFooter ?: true

        runTui(
            client = client,
            initialUserMessage = userMessage,
            availableModels = availableModels,
            sessionManager = sessionManager,
            sessionsDir = sessionsDir,
            cwd = Path.of(System.getProperty("user.dir")),
            initialThinkingLevel = thinkingLevel,
            initialModel = model,
            theme = resolvedTheme,
            fileCommands = fileCommands,
            getAllProviders = { modelRegistry.getAllProviders() },
            storeApiKey = { provider, apiKey -> modelRegistry.storeApiKey(provider, apiKey) },
            storeOAuthCredential = { provider, credential -> modelRegistry.storeOAuthCredential(provider, credential) },
            refreshModels = { modelRegistry.getAvailable() },
            authorizeOAuth = { provider ->
                modelRegistry.getAuthPlugin(provider)?.authorize()
            },
            pollOAuthToken = { provider ->
                modelRegistry.getAuthPlugin(provider)?.pollForToken()
            },
            onSettingsChanged = { transform -> settingsManager.update(transform) },
            initialShowUsageFooter = initialShowUsageFooter,
            todoState = todoState,
        )
    }

    private fun runSmokeTool(args: List<String>) {
        val command = args.joinToString(" ").ifBlank { "echo native-tool-smoke" }
        val tool = co.agentmode.agent47.coding.core.tools.BashTool(Path.of("."))
        val result = runBlocking {
            tool.execute(
                toolCallId = "smoke-tool",
                parameters = buildJsonObject {
                    put("command", command)
                    put("timeout", 10)
                },
            )
        }
        val output = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        terminal.println(output.ifBlank { "(no output)" })
    }

    private fun buildSystemPrompt(
        cwd: Path,
        toolNames: List<String>,
        customPrompt: String?,
        appendPrompt: String?,
        skills: List<Skill> = emptyList(),
        instructions: String = "",
    ): String {
        val toolList = toolNames.joinToString("\n") { "- $it" }

        val hasFileExplorationTools = toolNames.containsAll(listOf("grep", "find", "ls"))
        val fileExplorationGuideline = if (hasFileExplorationTools) {
            "- Prefer grep/find/ls tools over bash for file exploration (faster, respects .gitignore)"
        } else {
            "- Use bash for file operations (ls, grep, find, etc.)"
        }

        val now = ZonedDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, hh:mm:ss a z"))

        return buildString {
            if (customPrompt != null) {
                appendLine(customPrompt)
            } else {
                appendLine("You are an expert coding assistant operating inside agent47, a coding agent harness. You help users by reading files, executing commands, editing code, and writing new files.")
            }

            appendLine()
            appendLine("Available tools:")
            appendLine(toolList)
            appendLine()
            appendLine("Guidelines:")
            appendLine("- Use read to examine files before editing. You must use this tool instead of cat or sed.")
            appendLine("- Use edit for precise changes (old text must match exactly)")
            appendLine("- Use write only for new files or complete rewrites")
            appendLine(fileExplorationGuideline)
            appendLine("- When summarizing your actions, output plain text directly - do NOT use cat or bash to display what you did")
            appendLine("- Be concise in your responses")
            appendLine("- Show file paths clearly when working with files")

            if (instructions.isNotBlank()) {
                appendLine()
                appendLine(instructions)
            }

            if (skills.isNotEmpty()) {
                appendLine()
                appendLine("Skills are domain-specific knowledge files you can read lazily with the read tool using skill:// URLs.")
                appendLine()
                appendLine("<skills>")
                for (skill in skills) {
                    appendLine("<skill name=\"${skill.name}\">${skill.description}</skill>")
                }
                appendLine("</skills>")
            }

            if (appendPrompt != null) {
                appendLine()
                appendLine(appendPrompt)
            }

            appendLine()
            appendLine("Current date and time: $dateTime")
            append("Current working directory: $cwd")
        }
    }

    private fun formatNumber(n: Int): String {
        return if (n >= 1_000_000) {
            String.format("%.1fM", n / 1_000_000.0)
        } else if (n >= 1_000) {
            String.format("%.0fK", n / 1_000.0)
        } else {
            n.toString()
        }
    }
}

private fun Terminal.printError(message: String) {
    println(Theme.Default.danger(message))
}

private fun Terminal.printWarning(message: String) {
    println(Theme.Default.warning(message))
}

private fun Terminal.printInfo(message: String) {
    println(Theme.Default.info(message))
}
