package co.agentmode.agent47.app

import co.agentmode.agent47.coding.core.config.AgentConfig
import co.agentmode.agent47.coding.core.extensions.ExtensionPackageManager
import java.nio.file.Path

internal fun runExtensionPackageCommand(args: List<String>) {
    val config = AgentConfig(cwd = Path.of(System.getProperty("user.dir")))
    val local = args.any { it == "-l" || it == "--local" }
    val positional = args.filterNot { it == "-l" || it == "--local" }
    val global = ExtensionPackageManager(config.globalPackagesRegistryPath, config.globalPackagesDir)
    val project = ExtensionPackageManager(config.projectPackagesRegistryPath, config.projectPackagesDir)
    val manager = if (local) project else global
    val action = positional.firstOrNull() ?: "list"
    val source = positional.getOrNull(1)

    runCatching {
        when (action) {
            "install" -> installExtensionPackage(manager, source)
            "remove" -> removeExtensionPackage(manager, source)
            "update" -> updateExtensionPackages(manager, source)
            "list" -> listExtensionPackages(global, project, local)
            else -> error("Unknown extension package command: $action")
        }
    }.onFailure { error ->
        System.err.println(error.message ?: error.toString())
        kotlin.system.exitProcess(1)
    }
}

private fun installExtensionPackage(manager: ExtensionPackageManager, source: String?) {
    val installed = manager.install(requireNotNull(source) {
        "Usage: agent47 extension install [-l|--local] <source>"
    })
    println("Installed ${installed.source} from ${installed.path}")
}

private fun removeExtensionPackage(manager: ExtensionPackageManager, source: String?) {
    val removed = manager.remove(requireNotNull(source) {
        "Usage: agent47 extension remove [-l|--local] <source>"
    })
    println("Removed ${removed.source}")
}

private fun updateExtensionPackages(manager: ExtensionPackageManager, source: String?) {
    val updates = manager.update(source)
    if (updates.isEmpty()) {
        println("No extension packages installed.")
    } else {
        updates.forEach { update -> println("${update.source}: ${update.message}") }
    }
}

private fun listExtensionPackages(
    global: ExtensionPackageManager,
    project: ExtensionPackageManager,
    local: Boolean,
) {
    val records = if (local) {
        project.list().map { "project" to it }
    } else {
        global.list().map { "global" to it } + project.list().map { "project" to it }
    }
    if (records.isEmpty()) {
        println("No extension packages installed.")
    } else {
        records.forEach { (scope, record) -> println("$scope\t${record.source}\t${record.path}") }
    }
}
