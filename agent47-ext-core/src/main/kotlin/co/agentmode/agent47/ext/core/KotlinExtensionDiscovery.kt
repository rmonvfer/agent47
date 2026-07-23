package co.agentmode.agent47.ext.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

public object KotlinExtensionDiscovery {
    public fun discover(
        explicitPaths: List<Path>,
        projectDirectory: Path,
        globalDirectory: Path,
        packagePaths: List<Path> = emptyList(),
        autoDiscover: Boolean = true,
    ): List<Path> {
        val candidates = buildList {
            explicitPaths.forEach { path -> addAll(resolveExplicitPath(path)) }
            packagePaths.forEach { path -> addAll(resolveExplicitPath(path)) }
            if (autoDiscover) {
                addAll(discoverDirectory(projectDirectory))
                addAll(discoverDirectory(globalDirectory))
            }
        }

        val seen = mutableSetOf<Path>()
        return candidates.filter { path ->
            seen.add(path.toAbsolutePath().normalize())
        }
    }

    private fun resolveExplicitPath(path: Path): List<Path> {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.exists(normalized)) { "Extension path does not exist: $path" }
        return when {
            normalized.isRegularFile() -> {
                require(normalized.extension == "kts") { "Extension must be a .kts file: $path" }
                listOf(normalized)
            }
            normalized.isDirectory() -> discoverDirectory(normalized)
            else -> error("Extension path is not a file or directory: $path")
        }
    }

    private fun discoverDirectory(directory: Path): List<Path> {
        if (!directory.isDirectory()) return emptyList()

        return Files.list(directory).use { entries ->
            entries
                .filter { path -> !path.fileName.toString().startsWith(".") }
                .flatMap { path ->
                    when {
                        path.isRegularFile() && path.extension == "kts" -> java.util.stream.Stream.of(path)
                        path.isDirectory() -> {
                            val entrypoint = path.resolve("index.kts")
                            if (entrypoint.isRegularFile()) java.util.stream.Stream.of(entrypoint) else java.util.stream.Stream.empty()
                        }
                        else -> java.util.stream.Stream.empty()
                    }
                }
                .sorted()
                .map { it.toAbsolutePath().normalize() }
                .toList()
        }
    }
}

public data class KotlinExtensionLoadReport(
    val runner: ExtensionRunner,
    val failures: List<ScriptLoadFailure>,
)

public class KotlinExtensionRuntime(
    private val paths: List<Path>,
    private val loader: ExtensionScriptLoader? = null,
) {
    @Volatile
    private var activeRunner: ExtensionRunner = ExtensionRunner()

    public val runner: ExtensionRunner
        get() = activeRunner

    public fun reload(keepPreviousOnFailure: Boolean = false): KotlinExtensionLoadReport {
        val next = ExtensionRunner()
        val failures = mutableListOf<ScriptLoadFailure>()
        for (path in paths) {
            when (val result = checkNotNull(loader) { "A Kotlin extension loader is required" }.load(path)) {
                is ScriptLoadResult.Loaded -> next.load(result.extension)
                is ScriptLoadResult.Failed -> failures += result.failure
            }
        }
        if (!keepPreviousOnFailure || failures.isEmpty()) {
            activeRunner = next
        }
        return KotlinExtensionLoadReport(activeRunner, failures)
    }
}
