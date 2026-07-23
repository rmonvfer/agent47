package co.agentmode.agent47.app

import co.agentmode.agent47.app.cli.escapePromptFileArguments
import co.agentmode.agent47.app.update.UpdateCommand
import co.agentmode.agent47.app.update.installAutomaticUpdate
import co.agentmode.agent47.app.update.shouldCheckForUpdates
import com.github.ajalt.clikt.command.main

suspend fun main(args: Array<String>) {
    if (args.firstOrNull() == "extension") {
        runExtensionPackageCommand(args.drop(1))
        return
    }
    if (args.firstOrNull() == "update") {
        UpdateCommand().main(args.drop(1).toTypedArray())
        return
    }
    if (args.contentEquals(arrayOf("--version"))) {
        println("agent47 ${BuildInfo.version}")
        return
    }

    if (shouldCheckForUpdates(args) && installAutomaticUpdate(args)) return
    Agent47Command().main(escapePromptFileArguments(args))
}
