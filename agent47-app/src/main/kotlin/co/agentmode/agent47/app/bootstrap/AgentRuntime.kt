package co.agentmode.agent47.app.bootstrap

import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.compaction.CompactionService
import co.agentmode.agent47.app.extensions.ExtensionReloader
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.schedule.SubagentScheduler
import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsState
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.coding.core.tools.TodoState
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.tui.theme.NamedTheme
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path

/**
 * The assembled object graph for a single agent47 run. Built by [AgentRuntimeBuilder] and
 * consumed by the print and interactive modes. Closing the runtime stops the session-scoped
 * scheduler and cancels any background sub-agents.
 */
internal class AgentRuntime(
    val terminal: Terminal,
    val client: AgentClient,
    val config: AgentConfig,
    val settings: SettingsManager,
    val modelRegistry: ModelRegistry,
    val agentRegistry: AgentRegistry,
    val skillRegistry: SkillRegistry,
    val backgroundAgents: BackgroundAgents,
    val scheduler: SubagentScheduler,
    val subagentsSettings: SubagentsSettingsState,
    val sessionTracker: SessionTracker,
    val extensionRuntime: KotlinExtensionRuntime,
    val extensionContext: ExtensionContext,
    val reloader: ExtensionReloader,
    val compactionService: CompactionService,
    val fileCommands: List<SlashCommand>,
    val instructionLoader: InstructionLoader,
    val availableThemes: List<NamedTheme>,
    val todoState: TodoState,
    val resolvedModel: Model,
    val thinkingLevel: AgentThinkingLevel,
    val sessionsDir: Path,
    val initialUserMessage: UserMessage?,
    val runAsPrintMode: Boolean,
) : AutoCloseable {
    override fun close() {
        scheduler.stop()
        backgroundAgents.cancelAll()
    }
}
