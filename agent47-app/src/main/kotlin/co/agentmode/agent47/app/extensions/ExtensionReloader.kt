package co.agentmode.agent47.app.extensions

import co.agentmode.agent47.agent.core.AgentTool
import co.agentmode.agent47.ai.core.ApiRegistry
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.app.bootstrap.SessionTracker
import co.agentmode.agent47.app.buildSystemPrompt
import co.agentmode.agent47.coding.core.instructions.InstructionLoader
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.skills.SkillRegistry
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.ExtensionResources
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.SessionShutdownEvent
import co.agentmode.agent47.ext.core.SessionShutdownReason
import co.agentmode.agent47.ext.core.SessionStartEvent
import co.agentmode.agent47.ext.core.SessionStartReason
import java.nio.file.Path

internal class ExtensionReloader(
    private val extensionRuntime: KotlinExtensionRuntime,
    private val apiRegistry: ApiRegistry,
    private val modelRegistry: ModelRegistry,
    private val allTools: List<AgentTool<*>>,
    private val toolCatalog: MutableMap<String, AgentTool<*>>,
    private val client: AgentClient,
    private val workingDir: Path,
    private val customPrompt: String?,
    private val appendPrompt: String?,
    private val skillRegistry: SkillRegistry,
    private val instructionLoader: InstructionLoader,
    private val sessionTracker: SessionTracker,
    private val contextProvider: () -> ExtensionContext,
) {
    suspend fun reload(): ExtensionResources {
        val previousRunner = extensionRuntime.runner
        val report = extensionRuntime.reload(keepPreviousOnFailure = true)
        if (report.failures.isNotEmpty()) {
            error(
                report.failures.joinToString("\n") { failure ->
                    "${failure.path}: ${failure.diagnostics.joinToString("; ")}"
                },
            )
        }
        val runner = report.runner
        if (runner !== previousRunner) {
            previousRunner.shutdownSession(
                SessionShutdownEvent(
                    SessionShutdownReason.RELOAD,
                    sessionTracker.current?.getSessionFile(),
                ),
                contextProvider(),
            )
            previousRunner.unregisterProviders(apiRegistry, modelRegistry)
            runner.registerProviders(apiRegistry, modelRegistry)
        }
        runner.bindContext(contextProvider)
        val reloadedTools = runner.wrapAllTools(
            (runner.tools() + allTools).distinctBy { it.definition.name },
        )
        toolCatalog.clear()
        reloadedTools.forEach { tool -> toolCatalog[tool.definition.name] = tool }
        client.setTools(reloadedTools)
        client.setSystemPrompt(
            buildSystemPrompt(
                cwd = workingDir,
                toolNames = reloadedTools.map { it.definition.name },
                customPrompt = customPrompt,
                appendPrompt = appendPrompt,
                skills = skillRegistry.getAll(),
                instructions = instructionLoader.format(),
            ),
        )
        if (runner !== previousRunner) {
            runner.startSession(
                SessionStartEvent(
                    SessionStartReason.RELOAD,
                    sessionTracker.current?.getSessionFile(),
                ),
                contextProvider(),
            )
        }
        return ExtensionResources(
            runner.commands(),
            runner.shortcuts(),
            runner.toolRenderers(),
            runner.messageRenderers(),
            runner.flags(),
        )
    }
}
