package co.agentmode.agent47.app.update

import co.agentmode.agent47.app.BuildInfo
import co.agentmode.agent47.app.UpdateResult
import co.agentmode.agent47.app.UpdateService
import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.settings.SettingsManager
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Abort
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
    override suspend fun run() {
        val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
        when (val result = createUpdateService(config).checkAndInstall(force = true)) {
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
}
