package co.agentmode.agent47.app.update

import co.agentmode.agent47.app.BuildInfo
import co.agentmode.agent47.app.UpdateResult
import co.agentmode.agent47.app.UpdateService
import co.agentmode.agent47.app.createExtensionRepositoryManagers
import co.agentmode.agent47.app.updateExtensionRepositories
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.settings.SettingsManager
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Path

internal fun shouldCheckForUpdates(args: Array<String>): Boolean = when {
    System.console() == null -> false
    System.getenv("AGENT47_NO_AUTO_UPDATE")?.lowercase() in setOf("1", "true", "yes") -> false
    else -> args.none { argument ->
        argument in setOf("-p", "--print", "-h", "--help", "--version", "--list-models")
    }
}

internal fun installAutomaticUpdate(args: Array<String>): Boolean {
    val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
    val settings = SettingsManager.create(config.globalSettingsPath, config.projectSettingsPath).getGlobal()
    if (!settings.updates.automatic) return false

    val result = createUpdateService(config, settings.updates.checkIntervalHours).checkAndInstall(force = false)
    return when (result) {
        is UpdateResult.Installed -> restartAfterUpdate(result, args)
        is UpdateResult.Failed -> {
            System.err.println("Warning: agent47 could not check for updates: ${result.message}")
            false
        }
        else -> false
    }
}

private fun restartAfterUpdate(result: UpdateResult.Installed, args: Array<String>): Boolean {
    println("Updated agent47 ${result.previousVersion} to ${result.version}. Restarting...")
    val process = runCatching {
        ProcessBuilder(listOf(result.executable.toString()) + args)
            .inheritIO()
            .start()
    }.getOrNull()
    if (process == null) {
        System.err.println("agent47 was updated, but could not restart automatically. Run it again to use ${result.version}.")
        return false
    }
    process.waitFor()
    return true
}

private fun createUpdateService(config: AgentConfig, checkIntervalHours: Int = 24): UpdateService = UpdateService(
    currentVersion = BuildInfo.version,
    statePath = config.updateStatePath,
    repository = System.getenv("AGENT47_REPOSITORY") ?: "rmonvfer/agent47",
    checkIntervalMillis = checkIntervalHours * 60L * 60L * 1_000L,
    progress = ::println,
)

internal class UpdateCommand : SuspendingCliktCommand(name = "update") {
    private val selfOnly by option(
        "--self",
        help = "Update the agent47 executable only",
    ).flag()
    private val extensionsOnly by option(
        "--extensions",
        help = "Update all unpinned extension repositories only",
    ).flag()
    private val all by option(
        "--all",
        help = "Update agent47 and all unpinned extension repositories",
    ).flag()
    private val extensionSource by option(
        "--extension",
        help = "Update one installed extension repository",
    )
    private val target by argument(
        name = "SOURCE",
        help = "Installed extension source, or 'agent47' for the executable",
    ).optional()

    override suspend fun run() {
        val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
        val targets = runCatching {
            resolveUpdateTargets(selfOnly, extensionsOnly, all, extensionSource, target)
        }.getOrElse { error ->
            abortUpdate(error.message ?: error.toString())
        }
        if (targets.extensions) {
            val updates = runCatching {
                updateExtensionRepositories(
                    createExtensionRepositoryManagers(config),
                    targets.extensionSource,
                )
            }.getOrElse { error ->
                abortUpdate("Failed to update extensions: ${error.message ?: error}")
            }
            if (updates.isEmpty()) {
                echo("No extension repositories installed.")
            } else {
                updates.forEach { update -> echo("${update.source}: ${update.message}") }
            }
        }
        if (!targets.executable) return

        handleExecutableUpdate(createUpdateService(config).checkAndInstall(force = true))
    }

    private fun handleExecutableUpdate(result: UpdateResult) {
        when (result) {
            is UpdateResult.Current -> echo("agent47 ${result.version} is already the latest version.")
            is UpdateResult.Installed -> echo("Updated agent47 ${result.previousVersion} to ${result.version}.")
            is UpdateResult.Skipped -> {
                echo("Cannot update agent47: ${result.reason}", err = true)
                throw Abort()
            }
            is UpdateResult.Failed -> {
                echo("Failed to update agent47: ${result.message}", err = true)
                throw Abort()
            }
        }
    }

    private fun abortUpdate(message: String): Nothing {
        echo(message, err = true)
        throw Abort()
    }
}

internal data class UpdateTargets(
    val executable: Boolean,
    val extensions: Boolean,
    val extensionSource: String? = null,
)

internal fun resolveUpdateTargets(
    selfOnly: Boolean,
    extensionsOnly: Boolean,
    all: Boolean,
    extensionOption: String?,
    positionalTarget: String?,
): UpdateTargets {
    require(listOf(selfOnly, extensionsOnly, all).count { it } <= 1) {
        "Choose exactly one of --self, --extensions, or --all."
    }
    require(extensionOption == null || positionalTarget == null) {
        "Choose either --extension SOURCE or a positional SOURCE."
    }
    val selectedSource = extensionOption ?: positionalTarget
    val executableTarget = selectedSource in setOf("agent47", "self")
    require(selectedSource == null || executableTarget || !selfOnly && !extensionsOnly && !all) {
        "An extension source cannot be combined with --self, --extensions, or --all."
    }
    return when {
        all -> UpdateTargets(executable = true, extensions = true)
        extensionsOnly -> UpdateTargets(executable = false, extensions = true)
        selfOnly || selectedSource == null || executableTarget ->
            UpdateTargets(executable = true, extensions = false)
        else -> UpdateTargets(executable = false, extensions = true, extensionSource = selectedSource)
    }
}
