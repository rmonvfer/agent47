package co.agentmode.agent47.ext.core

@Suppress("unused")
public val kotlinExtensionRuntimeBootstrap: Unit =
    KotlinExtensionRuntimeRegistry.install(KotlinExtensionScriptLoader())
