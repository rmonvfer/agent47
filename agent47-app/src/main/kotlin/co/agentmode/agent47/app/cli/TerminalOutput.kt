package co.agentmode.agent47.app.cli

import com.github.ajalt.mordant.rendering.Theme
import com.github.ajalt.mordant.terminal.Terminal

internal fun Terminal.printError(message: String) {
    println(Theme.Default.danger(message))
}

internal fun Terminal.printWarning(message: String) {
    println(Theme.Default.warning(message))
}

internal fun Terminal.printInfo(message: String) {
    println(Theme.Default.info(message))
}
