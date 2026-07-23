package co.agentmode.agent47.app

import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.extensions.ExtensionRepositoryManager
import co.agentmode.agent47.coding.core.extensions.ExtensionRepositoryUpdate
import java.nio.file.Path

internal enum class ExtensionRepositoryAction {
    INSTALL,
    REMOVE,
    LIST,
}

internal data class ExtensionRepositoryCommandOptions(
    val action: ExtensionRepositoryAction,
    val local: Boolean,
    val source: String? = null,
    val help: Boolean = false,
)

internal data class ExtensionRepositoryManagers(
    val global: ExtensionRepositoryManager,
    val project: ExtensionRepositoryManager,
)

internal fun isExtensionRepositoryCommand(command: String?): Boolean =
    command in setOf("install", "remove", "uninstall", "list")

internal fun runExtensionRepositoryCommand(args: List<String>) {
    runCatching {
        val options = parseExtensionRepositoryCommand(args)
        if (options.help) {
            println(extensionRepositoryHelp(options.action))
            return
        }
        val managers = createExtensionRepositoryManagers(
            AgentConfig(cwd = Path.of(System.getProperty("user.dir"))),
        )
        when (options.action) {
            ExtensionRepositoryAction.INSTALL -> {
                val manager = if (options.local) managers.project else managers.global
                val installed = manager.install(checkNotNull(options.source))
                println("Installed ${installed.source} at ${installed.path}")
            }
            ExtensionRepositoryAction.REMOVE -> {
                val manager = if (options.local) managers.project else managers.global
                val removed = manager.remove(checkNotNull(options.source))
                println("Removed ${removed.source}")
            }
            ExtensionRepositoryAction.LIST -> listExtensionRepositories(managers)
        }
    }.onFailure { error ->
        System.err.println(error.message ?: error.toString())
        kotlin.system.exitProcess(1)
    }
}

internal fun createExtensionRepositoryManagers(config: AgentConfig): ExtensionRepositoryManagers =
    ExtensionRepositoryManagers(
        global = ExtensionRepositoryManager(
            config.globalExtensionRepositoriesPath,
            config.globalExtensionGitDir,
            Path.of(System.getProperty("user.dir")),
        ),
        project = ExtensionRepositoryManager(
            config.projectExtensionRepositoriesPath,
            config.projectExtensionGitDir,
            Path.of(System.getProperty("user.dir")),
            relativeLocalSources = true,
        ),
    )

internal fun updateExtensionRepositories(
    managers: ExtensionRepositoryManagers,
    source: String? = null,
): List<ExtensionRepositoryUpdate> {
    if (source == null) {
        return managers.project.update() + managers.global.update(excluding = managers.project.identities())
    }
    return when {
        managers.project.contains(source) -> managers.project.update(source)
        managers.global.contains(source) -> managers.global.update(source)
        else -> error("Extension repository is not installed: $source")
    }
}

internal fun parseExtensionRepositoryCommand(args: List<String>): ExtensionRepositoryCommandOptions {
    val command = args.firstOrNull() ?: error("Missing extension repository command")
    val action = when (command) {
        "install" -> ExtensionRepositoryAction.INSTALL
        "remove", "uninstall" -> ExtensionRepositoryAction.REMOVE
        "list" -> ExtensionRepositoryAction.LIST
        else -> error("Unknown extension repository command: $command")
    }
    var local = false
    var help = false
    var source: String? = null
    args.drop(1).forEach { argument ->
        when (argument) {
            "-l", "--local" -> {
                require(action != ExtensionRepositoryAction.LIST) {
                    "$argument is only valid for install, remove, and uninstall"
                }
                local = true
            }
            "-h", "--help" -> help = true
            else -> {
                require(!argument.startsWith("-") || argument == "-") { "Unknown option: $argument" }
                require(source == null) { "Unexpected argument: $argument" }
                source = argument
            }
        }
    }
    if (!help && action != ExtensionRepositoryAction.LIST) {
        require(source != null) { extensionRepositoryUsage(action) }
    }
    require(action != ExtensionRepositoryAction.LIST || source == null) {
        "The list command does not accept a source"
    }
    return ExtensionRepositoryCommandOptions(action, local, source, help)
}

private fun listExtensionRepositories(managers: ExtensionRepositoryManagers) {
    val project = managers.project.list().map { "project" to it }
    val global = managers.global.list().map { "global" to it }
    if (project.isEmpty() && global.isEmpty()) {
        println("No extension repositories installed.")
        return
    }
    (project + global).forEach { (scope, repository) ->
        val pin = repository.ref?.let { " (pinned to $it)" }.orEmpty()
        println("$scope\t${repository.source}\t${repository.path}$pin")
    }
}

private fun extensionRepositoryHelp(action: ExtensionRepositoryAction): String = when (action) {
    ExtensionRepositoryAction.INSTALL ->
        """
        Usage: agent47 install SOURCE [-l|--local]

        Install an extension repository globally, or into the current project with --local.
        Sources may be Git URLs, git:host/owner/repository shorthand, or local paths.
        """.trimIndent()
    ExtensionRepositoryAction.REMOVE ->
        """
        Usage: agent47 remove SOURCE [-l|--local]

        Remove an installed extension repository. uninstall is an alias for remove.
        """.trimIndent()
    ExtensionRepositoryAction.LIST ->
        """
        Usage: agent47 list

        List globally and project-installed extension repositories.
        """.trimIndent()
}

private fun extensionRepositoryUsage(action: ExtensionRepositoryAction): String = when (action) {
    ExtensionRepositoryAction.INSTALL -> "Usage: agent47 install SOURCE [-l|--local]"
    ExtensionRepositoryAction.REMOVE -> "Usage: agent47 remove SOURCE [-l|--local]"
    ExtensionRepositoryAction.LIST -> "Usage: agent47 list"
}
