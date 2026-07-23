package co.agentmode.agent47.coding.core.extensions

import co.agentmode.agent47.ai.types.Agent47Json
import kotlinx.serialization.Serializable
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
public data class ExtensionRepositoryRegistry(
    val repositories: List<String> = emptyList(),
)

@Serializable
public data class Agent47RepositoryManifest(
    val extensions: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
)

public data class ExtensionRepository(
    val source: String,
    val path: Path,
    val managed: Boolean,
    val ref: String? = null,
)

public data class ExtensionRepositoryResources(
    val extensions: List<Path> = emptyList(),
    val skillDirectories: List<Path> = emptyList(),
    val promptDirectories: List<Path> = emptyList(),
    val themeFiles: List<Path> = emptyList(),
) {
    public operator fun plus(other: ExtensionRepositoryResources): ExtensionRepositoryResources =
        ExtensionRepositoryResources(
            extensions = extensions + other.extensions,
            skillDirectories = skillDirectories + other.skillDirectories,
            promptDirectories = promptDirectories + other.promptDirectories,
            themeFiles = themeFiles + other.themeFiles,
        )

    public fun isEmpty(): Boolean =
        extensions.isEmpty() &&
            skillDirectories.isEmpty() &&
            promptDirectories.isEmpty() &&
            themeFiles.isEmpty()
}

public data class ExtensionRepositoryUpdate(
    val source: String,
    val updated: Boolean,
    val message: String,
)

@Suppress("TooManyFunctions")
public class ExtensionRepositoryManager(
    registryPath: Path,
    installDirectory: Path,
    workingDirectory: Path,
    private val relativeLocalSources: Boolean = false,
) {
    private val registryPath: Path = registryPath.toAbsolutePath().normalize()
    private val installDirectory: Path = installDirectory.toAbsolutePath().normalize()
    private val workingDirectory: Path = workingDirectory.toAbsolutePath().normalize()

    public fun list(): List<ExtensionRepository> =
        loadRegistry().repositories.map { resolveRepository(it, installMissing = false) }

    public fun install(source: String): ExtensionRepository {
        require(source.isNotBlank()) { "Extension source cannot be blank" }
        val parsed = parseExtensionSource(source, workingDirectory)
        val storedSource = sourceForRegistry(parsed)
        val current = loadRegistry()
        val identity = parsed.identity
        require(current.repositories.none { parseStoredSource(it).identity == identity }) {
            "Extension repository is already installed: $source"
        }

        val repository = when (parsed) {
            is ParsedExtensionSource.Local -> {
                require(parsed.path.exists()) { "Extension path does not exist: ${parsed.path}" }
                ExtensionRepository(storedSource, parsed.path, managed = false)
            }
            is ParsedExtensionSource.Git -> installGitRepository(parsed)
        }

        runCatching { resources(repository) }.getOrElse { error ->
            if (repository.managed) deleteRecursively(repository.path)
            throw error
        }
        saveRegistry(current.copy(repositories = current.repositories + storedSource))
        return repository
    }

    public fun remove(source: String): ExtensionRepository {
        val current = loadRegistry()
        val identity = parseExtensionSource(source, workingDirectory).identity
        val storedSource = current.repositories.firstOrNull { parseStoredSource(it).identity == identity }
            ?: error("Extension repository is not installed: $source")
        val repository = resolveRepository(storedSource, installMissing = false)
        if (repository.managed) deleteRecursively(repository.path)
        saveRegistry(current.copy(repositories = current.repositories - storedSource))
        return repository
    }

    public fun contains(source: String): Boolean {
        val identity = parseExtensionSource(source, workingDirectory).identity
        return loadRegistry().repositories.any { parseStoredSource(it).identity == identity }
    }

    public fun update(
        source: String? = null,
        excluding: Set<String> = emptySet(),
    ): List<ExtensionRepositoryUpdate> {
        val repositories = if (source == null) {
            loadRegistry().repositories
                .filter { parseStoredSource(it).identity !in excluding }
                .map { resolveRepository(it, installMissing = true) }
        } else {
            val identity = parseExtensionSource(source, workingDirectory).identity
            loadRegistry().repositories
                .filter { parseStoredSource(it).identity == identity }
                .also { require(it.isNotEmpty()) { "Extension repository is not installed: $source" } }
                .map { resolveRepository(it, installMissing = true) }
        }
        return repositories.map { repository ->
            when {
                !repository.managed ->
                    ExtensionRepositoryUpdate(repository.source, false, "local source")
                repository.ref != null ->
                    ExtensionRepositoryUpdate(repository.source, false, "pinned to ${repository.ref}")
                else -> updateManagedRepository(repository)
            }
        }
    }

    public fun resources(excluding: Set<String> = emptySet()): ExtensionRepositoryResources =
        loadRegistry().repositories
            .filter { parseStoredSource(it).identity !in excluding }
            .map { source ->
                val parsed = parseStoredSource(source)
                val pathExisted = when (parsed) {
                    is ParsedExtensionSource.Local -> true
                    is ParsedExtensionSource.Git -> installPath(parsed).exists()
                }
                val repository = resolveRepository(source, installMissing = true)
                runCatching { resources(repository) }.getOrElse { error ->
                    if (repository.managed && !pathExisted) deleteRecursively(repository.path)
                    throw error
                }
            }
            .fold(ExtensionRepositoryResources(), ExtensionRepositoryResources::plus)

    public fun identities(): Set<String> =
        loadRegistry().repositories.mapTo(linkedSetOf()) { parseStoredSource(it).identity }

    private fun resolveRepository(source: String, installMissing: Boolean): ExtensionRepository {
        val parsed = parseStoredSource(source)
        return when (parsed) {
            is ParsedExtensionSource.Local ->
                ExtensionRepository(source, parsed.path, managed = false)
            is ParsedExtensionSource.Git -> {
                val target = installPath(parsed)
                if (!target.exists() && installMissing) {
                    installGitRepository(parsed)
                } else {
                    ExtensionRepository(source, target, managed = true, ref = parsed.ref)
                }
            }
        }
    }

    private fun installGitRepository(source: ParsedExtensionSource.Git): ExtensionRepository {
        val target = installPath(source)
        require(!target.exists()) { "Extension install directory already exists: $target" }
        target.parent.createDirectories()
        runCatching { clone(source, target) }.getOrElse { error ->
            deleteRecursively(target)
            throw error
        }
        return ExtensionRepository(source.source, target, managed = true, ref = source.ref)
    }

    private fun clone(source: ParsedExtensionSource.Git, target: Path) {
        val command = buildList {
            add("git")
            add("clone")
            add("--depth")
            add("1")
            source.ref?.let {
                add("--branch")
                add(it)
            }
            add(source.cloneUrl)
            add(target.toString())
        }
        runGit(command, "Failed to clone extension repository ${source.source}")
    }

    private fun resources(repository: ExtensionRepository): ExtensionRepositoryResources {
        val root = repository.path.toAbsolutePath().normalize()
        require(root.exists()) { "Installed extension source is missing: ${repository.source} ($root)" }
        if (root.isRegularFile()) {
            require(root.extension == "kts") { "Extension source file must use .kts: $root" }
            return ExtensionRepositoryResources(extensions = listOf(root))
        }
        require(root.isDirectory()) { "Extension source is not a file or directory: $root" }

        val manifestPath = root.resolve("agent47.json")
        val manifest = if (manifestPath.isRegularFile()) {
            Agent47Json.decodeFromString(Agent47RepositoryManifest.serializer(), manifestPath.readText())
        } else {
            Agent47RepositoryManifest(
                extensions = listOf("extensions"),
                skills = listOf("skills"),
                prompts = listOf("prompts"),
                themes = listOf("themes"),
            )
        }
        val discovered = ExtensionRepositoryResources(
            extensions = resolveExtensionResources(root, manifest.extensions),
            skillDirectories = resolveDirectories(root, manifest.skills),
            promptDirectories = resolveDirectories(root, manifest.prompts),
            themeFiles = resolveThemeResources(root, manifest.themes),
        )
        require(!discovered.isEmpty()) {
            "Extension repository contains no extensions, skills, prompts, or themes: $root"
        }
        return discovered
    }

    private fun resolveExtensionResources(root: Path, entries: List<String>): List<Path> =
        entries.flatMap { entry ->
            val path = resolveInside(root, entry)
            when {
                path.isRegularFile() -> {
                    require(path.extension == "kts") { "Extension resource must use .kts: $path" }
                    listOf(path)
                }
                path.isDirectory() -> discoverKotlinExtensions(path)
                else -> emptyList()
            }
        }.distinct()

    private fun resolveDirectories(root: Path, entries: List<String>): List<Path> =
        entries.map(::normalizeManifestPath)
            .map { resolveInside(root, it) }
            .filter { it.isDirectory() }
            .distinct()

    private fun resolveThemeResources(root: Path, entries: List<String>): List<Path> =
        entries.flatMap { entry ->
            val path = resolveInside(root, entry)
            when {
                path.isRegularFile() && path.extension == "json" -> listOf(path)
                path.isDirectory() -> Files.list(path).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension == "json" }.sorted().toList()
                }
                else -> emptyList()
            }
        }.distinct()

    private fun discoverKotlinExtensions(directory: Path): List<Path> =
        Files.walk(directory).use { stream ->
            stream
                .filter { it.isRegularFile() && it.extension == "kts" }
                .filter { path ->
                    val parentEntrypoint = path.parent.resolve("index.kts")
                    path.name == "index.kts" || !parentEntrypoint.isRegularFile()
                }
                .sorted()
                .toList()
        }

    private fun resolveInside(root: Path, entry: String): Path {
        val path = root.resolve(normalizeManifestPath(entry)).normalize()
        require(path.startsWith(root)) { "Extension resource escapes its repository: $entry" }
        if (path.exists()) {
            require(path.toRealPath().startsWith(root.toRealPath())) {
                "Extension resource escapes its repository through a symbolic link: $entry"
            }
        }
        return path
    }

    private fun normalizeManifestPath(entry: String): String =
        entry.removePrefix("./").also {
            require(it.isNotBlank()) { "Extension resource path cannot be blank" }
            require(!it.contains("*")) { "Extension resource globs are not supported: $entry" }
        }

    private fun updateManagedRepository(repository: ExtensionRepository): ExtensionRepositoryUpdate {
        require(repository.path.isDirectory()) {
            "Installed extension repository is missing: ${repository.source} (${repository.path})"
        }
        val status = gitOutput(listOf("git", "-C", repository.path.toString(), "status", "--porcelain"))
        require(status.isBlank()) {
            "Extension repository has local changes and cannot be updated: ${repository.source}"
        }
        val before = gitOutput(listOf("git", "-C", repository.path.toString(), "rev-parse", "HEAD"))
        val parsed = parseStoredSource(repository.source) as ParsedExtensionSource.Git
        val candidate = repository.path.resolveSibling(
            ".${repository.path.fileName}.update-${UUID.randomUUID()}",
        )
        val backup = repository.path.resolveSibling(
            ".${repository.path.fileName}.backup-${UUID.randomUUID()}",
        )
        try {
            clone(parsed, candidate)
            resources(ExtensionRepository(repository.source, candidate, managed = true))
            val after = gitOutput(listOf("git", "-C", candidate.toString(), "rev-parse", "HEAD"))
            if (before == after) {
                return ExtensionRepositoryUpdate(repository.source, false, "already current")
            }
            moveDirectory(repository.path, backup)
            runCatching {
                moveDirectory(candidate, repository.path)
            }.getOrElse { error ->
                moveDirectory(backup, repository.path)
                throw error
            }
            deleteRecursively(backup)
            return ExtensionRepositoryUpdate(
                source = repository.source,
                updated = true,
                message = "${before.take(8)} -> ${after.take(8)}",
            )
        } finally {
            deleteRecursively(candidate)
            if (repository.path.exists()) deleteRecursively(backup)
        }
    }

    private fun moveDirectory(source: Path, target: Path) {
        runCatching {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(source, target)
        }
    }

    private fun installPath(source: ParsedExtensionSource.Git): Path =
        source.repositoryPath.split('/').fold(installDirectory.resolve(source.host)) { path, segment ->
            path.resolve(segment)
        }.normalize().also { path ->
            require(path.startsWith(installDirectory.normalize())) {
                "Extension repository path escapes its install directory: ${source.source}"
            }
        }

    private fun sourceForRegistry(source: ParsedExtensionSource): String = when (source) {
        is ParsedExtensionSource.Git -> source.source
        is ParsedExtensionSource.Local -> if (relativeLocalSources) {
            runCatching {
                registryPath.parent.toAbsolutePath().normalize().relativize(source.path).toString()
            }.getOrDefault(source.path.toString()).ifBlank { "." }
        } else {
            source.path.toString()
        }
    }

    private fun parseStoredSource(source: String): ParsedExtensionSource =
        parseExtensionSource(source, registryPath.parent)

    private fun loadRegistry(): ExtensionRepositoryRegistry {
        if (!registryPath.exists()) return ExtensionRepositoryRegistry()
        return Agent47Json.decodeFromString(ExtensionRepositoryRegistry.serializer(), registryPath.readText())
    }

    private fun saveRegistry(registry: ExtensionRepositoryRegistry) {
        registryPath.parent.createDirectories()
        val temporary = registryPath.resolveSibling(".${registryPath.fileName}.${UUID.randomUUID()}.tmp")
        temporary.writeText(
            Agent47Json.encodeToString(ExtensionRepositoryRegistry.serializer(), registry) + "\n",
        )
        runCatching {
            Files.move(
                temporary,
                registryPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary, registryPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun runGit(command: List<String>, failureMessage: String) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) { "$failureMessage: ${output.trim()}" }
    }

    private fun gitOutput(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { output }
        return output
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}

internal sealed interface ParsedExtensionSource {
    val source: String
    val identity: String

    data class Local(
        override val source: String,
        val path: Path,
    ) : ParsedExtensionSource {
        override val identity: String = "local:${path.toAbsolutePath().normalize()}"
    }

    data class Git(
        override val source: String,
        val cloneUrl: String,
        val host: String,
        val repositoryPath: String,
        val ref: String?,
    ) : ParsedExtensionSource {
        override val identity: String = "git:$host/$repositoryPath"
    }
}

internal fun parseExtensionSource(source: String, baseDirectory: Path): ParsedExtensionSource {
    require(source.isNotBlank()) { "Extension source cannot be blank" }
    if (isLocalExtensionSource(source)) {
        val expanded = when {
            source == "~" -> Path.of(System.getProperty("user.home"))
            source.startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(source.removePrefix("~/"))
            else -> baseDirectory.resolve(source)
        }
        val path = expanded.toAbsolutePath().normalize()
        return ParsedExtensionSource.Local(source, path)
    }

    val raw = source.removePrefix("git:")
    val (withoutRef, ref) = splitGitRef(raw)
    val parts = parseGitRepository(withoutRef)
        ?: error("Unsupported extension source: $source")
    return ParsedExtensionSource.Git(
        source = source,
        cloneUrl = parts.cloneUrl,
        host = parts.host.lowercase(),
        repositoryPath = parts.repositoryPath.removeSuffix(".git").trim('/'),
        ref = ref,
    )
}

private data class GitRepositoryParts(
    val cloneUrl: String,
    val host: String,
    val repositoryPath: String,
)

private fun parseGitRepository(source: String): GitRepositoryParts? = when {
    source.startsWith("git@") -> {
        val separator = source.indexOf(':')
        if (separator <= 4) {
            null
        } else {
            val host = source.substringAfter('@').substringBefore(':')
            gitParts(source, host, source.substring(separator + 1))
        }
    }
    source.startsWith("file://") -> runCatching {
        val path = Path.of(URI(source)).toAbsolutePath().normalize()
        gitParts(source, "local", path.toString())
    }.getOrNull()
    "://" in source -> {
        runCatching { URI(source) }.getOrNull()?.let { uri ->
            uri.host?.let { host -> gitParts(source, host, uri.path) }
        }
    }
    source.matches(Regex("[A-Za-z0-9.-]+(?:/[A-Za-z0-9_.-]+)+(?:\\.git)?")) -> {
        val host = source.substringBefore('/')
        val path = source.substringAfter('/')
        gitParts("https://$source", host, path)
    }
    else -> null
}

private fun splitGitRef(source: String): Pair<String, String?> {
    val separator = source.lastIndexOf('@')
    val pathStart = when {
        source.startsWith("git@") -> source.indexOf(':')
        "://" in source -> {
            val authorityStart = source.indexOf("://") + 3
            source.indexOf('/', authorityStart)
        }
        else -> 0
    }
    return if (pathStart < 0 || separator <= pathStart || separator == source.lastIndex) {
        source to null
    } else {
        source.substring(0, separator) to source.substring(separator + 1)
    }
}

private fun gitParts(cloneUrl: String, host: String, path: String): GitRepositoryParts? {
    val normalized = path.trim('/').removeSuffix(".git")
    return GitRepositoryParts(cloneUrl, host, normalized).takeIf {
        host.isNotBlank() &&
            normalized.isNotBlank() &&
            normalized.split('/').none { segment -> segment.isBlank() || segment == "." || segment == ".." }
    }
}

private fun isLocalExtensionSource(source: String): Boolean =
    source.startsWith(".") ||
        source.startsWith("/") ||
        source.startsWith("~") ||
        !source.startsWith("git:") &&
        !source.startsWith("git@") &&
        "://" !in source
