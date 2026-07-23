package co.agentmode.agent47.tui

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.auth.OAuthAuthorization
import co.agentmode.agent47.coding.core.auth.OAuthCredential
import co.agentmode.agent47.coding.core.auth.OAuthResult
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import co.agentmode.agent47.coding.core.instructions.InstructionFile
import co.agentmode.agent47.coding.core.models.ProviderInfo
import co.agentmode.agent47.coding.core.session.SessionManager
import co.agentmode.agent47.coding.core.settings.Settings
import co.agentmode.agent47.coding.core.settings.SubagentsSettings
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.ext.core.RegisteredCommand
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionResources
import co.agentmode.agent47.ext.core.RegisteredShortcut
import co.agentmode.agent47.ext.core.RegisteredToolRenderer
import co.agentmode.agent47.ext.core.RegisteredMessageRenderer
import co.agentmode.agent47.ext.core.AfterCompactionEvent
import co.agentmode.agent47.ext.core.CompactionReason
import co.agentmode.agent47.ext.core.PreparedCompaction
import co.agentmode.agent47.ext.core.InputEvent
import co.agentmode.agent47.ext.core.InputHookResult
import co.agentmode.agent47.ext.core.SessionStartReason
import co.agentmode.agent47.tui.theme.ThemeAppearance
import co.agentmode.agent47.tui.theme.ThemeConfig
import co.agentmode.agent47.tui.theme.NamedTheme
import java.nio.file.Path

public data class TuiLaunchConfiguration(
    val initialUserMessage: UserMessage? = null,
    val availableModels: List<Model> = emptyList(),
    val sessionManager: SessionManager? = null,
    val sessionsDir: Path? = null,
    val cwd: Path = Path.of(System.getProperty("user.dir")),
    val initialThinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    val initialModel: Model? = null,
    val theme: ThemeConfig = ThemeConfig.DEFAULT,
    val themeAppearance: ThemeAppearance = ThemeAppearance.DARK,
    val availableThemes: List<NamedTheme> = emptyList(),
    val fileCommands: List<SlashCommand> = emptyList(),
    val extensionCommands: List<RegisteredCommand> = emptyList(),
    val extensionShortcuts: List<RegisteredShortcut> = emptyList(),
    val extensionContext: ExtensionContext? = null,
    val extensionToolRenderers: List<RegisteredToolRenderer> = emptyList(),
    val extensionMessageRenderers: List<RegisteredMessageRenderer> = emptyList(),
    val initialShowUsageFooter: Boolean = true,
    val todoState: TodoState? = null,
    val instructionFiles: List<InstructionFile> = emptyList(),
    val compactionSettings: CompactionSettings = CompactionSettings(),
)

public data class TuiProviderServices(
    val getAllProviders: () -> List<ProviderInfo> = { emptyList() },
    val storeApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    val storeOAuthCredential: (provider: String, credential: OAuthCredential) -> Unit = { _, _ -> },
    val refreshModels: () -> List<Model> = { emptyList() },
    val authorizeOAuth: suspend (provider: String) -> OAuthAuthorization? = { null },
    val pollOAuthToken: suspend (provider: String) -> OAuthResult? = { null },
    val isUsingOAuth: (Model) -> Boolean = { false },
)

public data class TuiConversationServices(
    val onSettingsChanged: (transform: (Settings) -> Settings) -> Unit = {},
    val compactContext: (suspend (List<Message>, Model, CompactionReason) -> PreparedCompaction?)? = null,
    val onCompacted: suspend (AfterCompactionEvent) -> Unit = {},
    val reloadExtensions: suspend () -> ExtensionResources = {
        ExtensionResources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    },
    val onSessionChanged: (SessionManager?) -> Unit = {},
    val onSessionTransition: suspend (SessionManager?, SessionManager?, SessionStartReason) -> Unit = { _, _, _ -> },
    val processInput: suspend (InputEvent) -> InputHookResult = { InputHookResult.Continue },
)

public data class TuiSubagentServices(
    val backgroundAgents: BackgroundAgents? = null,
    val settings: SubagentsSettings = SubagentsSettings(),
    val agentRegistry: AgentRegistry? = null,
    val scheduler: SubagentScheduler? = null,
    val persistSettings: (SubagentsSettings) -> Unit = {},
)
