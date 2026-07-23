package co.agentmode.agent47.tui.input

import androidx.compose.runtime.Stable
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.ext.core.ExtensionCommandContext
import co.agentmode.agent47.ext.core.ExtensionResources
import co.agentmode.agent47.ext.core.InputEvent
import co.agentmode.agent47.ext.core.InputHookResult
import co.agentmode.agent47.ext.core.InputSource
import co.agentmode.agent47.ext.core.InputStreamingBehavior
import co.agentmode.agent47.tui.commands.helpText
import co.agentmode.agent47.tui.controller.CompactionController
import co.agentmode.agent47.tui.controller.ConversationController
import co.agentmode.agent47.tui.controller.ModelController
import co.agentmode.agent47.tui.controller.SessionController
import co.agentmode.agent47.tui.editor.Editor
import co.agentmode.agent47.tui.overlays.OverlayNavigator
import co.agentmode.agent47.tui.overlays.openAgentsOverlay
import co.agentmode.agent47.tui.overlays.openCommandsOverlay
import co.agentmode.agent47.tui.overlays.openMemoryOverlay
import co.agentmode.agent47.tui.overlays.openModelOverlay
import co.agentmode.agent47.tui.overlays.openProviderOverlay
import co.agentmode.agent47.tui.overlays.openSessionOverlay
import co.agentmode.agent47.tui.overlays.openSettingsOverlay
import co.agentmode.agent47.tui.overlays.openThemeOverlay
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.util.executeShell
import co.agentmode.agent47.ui.core.state.ToolExecutionView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Applies a parsed [Submission] through the controllers and overlays. Replaces the many-argument
 * submit handler: dependencies are injected once, and the dynamic theme values travel with the call.
 */
@Stable
internal class SubmitDispatcher(
    private val state: TuiAppState,
    private val feed: TranscriptFeed,
    private val navigator: OverlayNavigator,
    private val conversation: ConversationController,
    private val compaction: CompactionController,
    private val session: SessionController,
    private val models: ModelController,
    private val client: AgentClient,
    private val cwd: Path,
    private val scope: CoroutineScope,
    private val editor: Editor,
    private val fileSlashCommands: List<SlashCommand>,
    private val reloadExtensions: suspend () -> ExtensionResources,
    private val processInput: suspend (InputEvent) -> InputHookResult,
) {
    /**
     * Applies the current slash-command popup selection (when submitting after a popup), then
     * drains the editor and submits its contents through the parser.
     */
    fun submitEditor(
        applyPopupFirst: Boolean,
        event: KeyboardEvent,
        activeTheme: ThemeConfig,
        themeAppearance: ThemeAppearance,
        setActiveTheme: (ThemeConfig) -> Unit,
        setThemeAppearance: (ThemeAppearance) -> Unit,
    ) {
        if (applyPopupFirst) {
            editor.handle(event)
            state.editorVersion++
        }
        val text = editor.text().trimEnd()
        if (text.isBlank()) {
            editor.setText("")
            state.editorVersion++
            return
        }
        // Record the submission so Up-arrow / Ctrl+P recall previous prompts.
        state.promptHistory.add(text)
        editor.setHistory(state.promptHistory.toList())
        editor.setText("")
        state.editorVersion++
        dispatch(
            parseSubmission(text, state.extensionCommands, fileSlashCommands),
            activeTheme,
            themeAppearance,
            setActiveTheme,
            setThemeAppearance,
        )
    }

    fun dispatch(
        submission: Submission,
        activeTheme: ThemeConfig,
        themeAppearance: ThemeAppearance,
        setActiveTheme: (ThemeConfig) -> Unit,
        setThemeAppearance: (ThemeAppearance) -> Unit,
    ) {
        when (submission) {
            is Submission.Builtin ->
                dispatchBuiltin(submission, activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
            is Submission.Extension -> dispatchExtension(submission)
            is Submission.FileExpansion -> conversation.submitMessage(userMessage(submission.expanded))
            is Submission.Bash -> dispatchBash(submission)
            is Submission.Prompt -> dispatchPrompt(submission)
            is Submission.UnknownSlash -> {
                feed.showCommandInput(submission.raw)
                feed.appendCommandResult("Unknown command: ${submission.command}")
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun dispatchBuiltin(
        builtin: Submission.Builtin,
        activeTheme: ThemeConfig,
        themeAppearance: ThemeAppearance,
        setActiveTheme: (ThemeConfig) -> Unit,
        setThemeAppearance: (ThemeAppearance) -> Unit,
    ) {
        val raw = builtin.raw
        val args = builtin.args
        when (builtin.command) {
            "/help" -> {
                feed.showCommandInput(raw)
                feed.appendCommandResult(helpText(navigator.slashCommands()))
            }
            "/commands" -> {
                feed.showCommandInput(raw)
                navigator.openCommandsOverlay()
            }
            "/new" -> {
                feed.showCommandInput(raw)
                session.startNewSession()
            }
            "/clear" -> {
                state.chatHistory.entries.clear()
            }
            "/exit" -> state.quit(client)
            "/model" -> {
                feed.showCommandInput(raw)
                if (args.isEmpty()) {
                    navigator.openModelOverlay()
                } else {
                    setModelById(args.joinToString(" "))
                }
            }
            "/theme" -> {
                feed.showCommandInput(raw)
                navigator.openThemeOverlay(activeTheme, themeAppearance, setActiveTheme)
            }
            "/provider" -> {
                feed.showCommandInput(raw)
                navigator.openProviderOverlay()
            }
            "/compact" -> {
                feed.showCommandInput(raw)
                compaction.runCompaction()
            }
            "/reload" -> {
                feed.showCommandInput(raw)
                if (state.isStreaming) {
                    feed.appendCommandResult("Wait for the current response before reloading extensions.")
                } else {
                    scope.launch {
                        runCatching { reloadExtensionsAndSummarize() }
                            .onSuccess(feed::appendCommandResult)
                            .onFailure { error -> feed.appendCommandResult(error.message ?: error.toString()) }
                    }
                }
            }
            "/memory" -> {
                feed.showCommandInput(raw)
                navigator.openMemoryOverlay()
            }
            "/settings" -> {
                feed.showCommandInput(raw)
                navigator.openSettingsOverlay(activeTheme, themeAppearance, setActiveTheme, setThemeAppearance)
            }
            "/agents" -> {
                feed.showCommandInput(raw)
                navigator.openAgentsOverlay()
            }
            "/session" -> {
                feed.showCommandInput(raw)
                if (args.isEmpty()) {
                    navigator.openSessionOverlay()
                } else {
                    feed.appendCommandResult("Use /session without arguments to open the session picker.")
                }
            }
        }
    }

    private fun dispatchExtension(extension: Submission.Extension) {
        feed.showCommandInput(extension.raw)
        if (state.isStreaming) {
            feed.appendCommandResult("Wait for the current response before running extension commands.")
            return
        }
        scope.launch {
            val context = object : ExtensionCommandContext {
                override val cwd: Path = this@SubmitDispatcher.cwd
                override val hasUi: Boolean = true

                override fun notify(message: String) {
                    feed.appendCommandResult(message)
                }

                override suspend fun sendUserMessage(message: String) {
                    val userMessage = userMessage(message)
                    state.chatHistory.appendMessage(userMessage)
                    state.activeSessionManager?.appendMessage(userMessage)
                    client.prompt(listOf(userMessage))
                    client.waitForIdle()
                }

                override suspend fun reload() {
                    notify(reloadExtensionsAndSummarize())
                }
            }
            runCatching { extension.command.handler.run(extension.rawArgs, context) }
                .onFailure { error -> feed.appendCommandResult(error.message ?: error.toString()) }
        }
    }

    private fun dispatchBash(bash: Submission.Bash) {
        val command = bash.command
        if (command.isBlank()) {
            feed.appendSystemMessage("No command provided after !")
            return
        }
        val start = userMessage("!$command")
        state.chatHistory.appendMessage(start)
        state.activeSessionManager?.appendMessage(start)

        val output = executeShell(command, cwd)
        val id = "local-${System.currentTimeMillis()}"
        state.chatHistory.appendToolExecution(
            ToolExecutionView(
                toolCallId = id,
                toolName = "bash",
                arguments = command,
                output = output.first,
                isError = output.second != 0,
                pending = false,
            ),
        )
    }

    private fun dispatchPrompt(prompt: Submission.Prompt) {
        scope.launch {
            val behavior = if (state.isStreaming) InputStreamingBehavior.FOLLOW_UP else null
            when (val result = processInput(InputEvent(prompt.text, InputSource.INTERACTIVE, behavior))) {
                InputHookResult.Handled -> Unit
                InputHookResult.Continue -> conversation.submitMessage(userMessage(prompt.text))
                is InputHookResult.Transform -> conversation.submitMessage(userMessage(result.text))
            }
        }
    }

    private fun setModelById(modelId: String) {
        val match = state.currentModels.firstOrNull { model ->
            model.id.equals(modelId, ignoreCase = true) ||
                "${model.provider.value}/${model.id}".equals(modelId, ignoreCase = true)
        }
        if (match == null) {
            feed.appendCommandResult("Model not found: $modelId")
        } else {
            models.applyModel(match)
        }
    }

    private suspend fun reloadExtensionsAndSummarize(): String {
        val resources = reloadExtensions()
        state.extensionCommands = resources.commands
        state.extensionShortcuts = resources.shortcuts
        state.extensionToolRenderers = resources.toolRenderers
        state.extensionMessageRenderers = resources.messageRenderers
        return "Reloaded ${resources.commands.size} extension command${if (resources.commands.size == 1) "" else "s"} " +
            "and ${resources.shortcuts.size} shortcut${if (resources.shortcuts.size == 1) "" else "s"}."
    }

    private fun userMessage(text: String): UserMessage = UserMessage(
        content = listOf(TextContent(text = text)),
        timestamp = System.currentTimeMillis(),
    )
}
