package co.agentmode.agent47.ext.core

import java.nio.file.Path

public data class ScriptLoadFailure(
    val path: Path,
    val diagnostics: List<String>,
)

public sealed interface ScriptLoadResult {
    public data class Loaded(
        val path: Path,
        val extension: ExtensionDefinition,
    ) : ScriptLoadResult

    public data class Failed(
        val failure: ScriptLoadFailure,
    ) : ScriptLoadResult
}

public fun interface ExtensionScriptLoader {
    public fun load(path: Path): ScriptLoadResult

    public fun configureFlags(values: Map<String, String>) {
        require(values.isEmpty()) { "This extension loader does not support extension flags" }
    }
}
