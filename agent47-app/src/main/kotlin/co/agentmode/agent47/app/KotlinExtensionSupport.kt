package co.agentmode.agent47.app

import co.agentmode.agent47.ext.core.ExtensionScriptLoader

internal object KotlinExtensionSupport {
    private const val LOADER_CLASS = "co.agentmode.agent47.ext.core.KotlinExtensionScriptLoader"

    fun createLoader(): ExtensionScriptLoader =
        Class.forName(LOADER_CLASS, true, Thread.currentThread().contextClassLoader)
            .getDeclaredConstructor()
            .newInstance() as ExtensionScriptLoader
}
