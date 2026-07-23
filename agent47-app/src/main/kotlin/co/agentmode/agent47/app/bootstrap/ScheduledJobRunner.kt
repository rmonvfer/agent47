package co.agentmode.agent47.app.bootstrap

import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.coding.core.agents.AgentInvocationParams
import co.agentmode.agent47.coding.core.agents.AgentRegistry
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.IsolationMode
import co.agentmode.agent47.coding.core.agents.SubAgentOptions
import co.agentmode.agent47.coding.core.agents.runSubAgent
import co.agentmode.agent47.coding.core.agents.schedule.ScheduledSubagent
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.coding.core.settings.SubagentsSettingsState
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

internal class ScheduledJobRunner(
    private val aiRuntime: AiRuntime,
    private val agentRegistry: AgentRegistry,
    private val modelRegistry: ModelRegistry,
    private val backgroundAgents: BackgroundAgents,
    private val settings: SettingsManager,
    private val subagentsSettings: SubagentsSettingsState,
    private val skillRegistry: SkillRegistry,
    private val workingDir: Path,
    private val sessionsDir: Path,
    private val memoryProjectDir: Path,
    private val memoryGlobalDir: Path,
    private val sessionId: String,
) {
    suspend fun run(job: ScheduledSubagent) {
        val def = agentRegistry.get(job.subagentType)
        val parentModel = modelRegistry.getAvailable().firstOrNull()
        if (def != null && parentModel != null) {
            val agentId = backgroundAgents.uniqueId(job.name)
            val running = backgroundAgents.launch(
                id = agentId,
                agentName = def.name,
                description = job.description,
                task = job.prompt,
            ) {
                runSubAgent(
                    SubAgentOptions(
                        streamFunction = aiRuntime::streamSimple,
                        agentDefinition = def,
                        task = job.prompt,
                        taskId = agentId,
                        description = job.description,
                        context = null,
                        cwd = workingDir,
                        parentModel = parentModel,
                        modelRegistry = modelRegistry,
                        settings = settings.get(),
                        currentDepth = 0,
                        maxDepth = settings.get().taskMaxRecursionDepth,
                        agentRegistry = agentRegistry,
                        getApiKey = { provider -> modelRegistry.getApiKeyForProvider(provider) },
                        subagentsSettings = subagentsSettings,
                        invocation = AgentInvocationParams(
                            model = job.model,
                            thinking = job.thinking,
                            maxTurns = job.maxTurns,
                            isolation = if (job.isolation == "worktree") IsolationMode.WORKTREE else null,
                        ),
                        onProgress = { p -> backgroundAgents.updateProgress(agentId, p) },
                        onEvent = null,
                        onAgentReady = { a -> backgroundAgents.setAgentRef(agentId, a) },
                        backgroundAgents = backgroundAgents,
                        backgroundAgentId = agentId,
                        memoryProjectDir = memoryProjectDir,
                        memoryGlobalDir = memoryGlobalDir,
                        skillContentProvider = { name -> skillRegistry.readSkillFile(name) },
                        sessionsDir = sessionsDir,
                        parentSessionId = sessionId,
                    ),
                )
            }
            val result = try {
                running.awaitResult()
            } catch (e: CancellationException) {
                backgroundAgents.abort(agentId)
                throw e
            } ?: error("Scheduled agent '$agentId' did not complete")
            if (result.aborted || result.error != null || result.exitCode != 0) {
                error(result.error ?: "Scheduled agent '$agentId' exited with ${result.exitCode}")
            }
        }
    }
}
