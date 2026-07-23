package co.agentmode.agent47.coding.core.extensions

import co.agentmode.agent47.ai.types.Agent47Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
public data class ExtensionPackageRecord(
    val source: String,
    val path: String,
    val managed: Boolean,
    val ref: String? = null,
)

@Serializable
public data class ExtensionPackageRegistry(
    val packages: List<ExtensionPackageRecord> = emptyList(),
)

@Serializable
public data class Agent47PackageManifest(
    val extensions: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
)

public data class ExtensionPackageResources(
    val extensions: List<Path> = emptyList(),
    val skillDirectories: List<Path> = emptyList(),
    val promptDirectories: List<Path> = emptyList(),
    val themeFiles: List<Path> = emptyList(),
)

public data class ExtensionPackageUpdate(
    val source: String,
    val updated: Boolean,
    val message: String,
)

@Suppress("TooManyFunctions")
public class ExtensionPackageManager(
    private val registryPath: Path,
    private val installDirectory: Path,
) {
    public fun list(): List<ExtensionPackageRecord> = loadRegistry().packages

    public fun install(source: String): ExtensionPackageRecord {
        require(source.isNotBlank()) { "Package source cannot be blank" }
        val current = loadRegistry()
        val identity = packageIdentity(source)
        require(current.packages.none { packageIdentity(it.source) == identity }) {
            "Package is already installed: $source"
        }

        val parsed = parseSource(source)
        val record = if (parsed.localPath != null) {
            val path = parsed.localPath.toAbsolutePath().normalize()
            require(path.exists()) { "Package path does not exist: $path" }
            ExtensionPackageRecord(source, path.toString(), managed = false)
        } else {
            installDirectory.createDirectories()
            val target = installDirectory.resolve(identity)
            require(!target.exists()) { "Package install directory already exists: $target" }
            val command = buildList {
                add("git")
                add("clone")
                add("--depth")
                add("1")
                parsed.ref?.let {
                    add("--branch")
                    add(it)
                }
                add(checkNotNull(parsed.gitUrl))
                add(target.toString())
            }
            runGit(command, "Failed to clone package $source")
            ExtensionPackageRecord(source, target.toString(), managed = true, ref = parsed.ref)
        }

        runCatching { resources(record) }.getOrElse { error ->
            if (record.managed) {
                deleteRecursively(Path.of(record.path))
            }
            throw error
        }
        saveRegistry(current.copy(packages = current.packages + record))
        return record
    }

    public fun remove(source: String): ExtensionPackageRecord {
        val current = loadRegistry()
        val identity = packageIdentity(source)
        val record = current.packages.firstOrNull { packageIdentity(it.source) == identity }
            ?: error("Package is not installed: $source")
        if (record.managed) {
            deleteRecursively(Path.of(record.path))
        }
        saveRegistry(current.copy(packages = current.packages - record))
        return record
    }

    public fun update(source: String? = null): List<ExtensionPackageUpdate> {
        val records = if (source == null) {
            list()
        } else {
            val identity = packageIdentity(source)
            list().filter { packageIdentity(it.source) == identity }.also {
                require(it.isNotEmpty()) { "Package is not installed: $source" }
            }
        }
        return records.map { record ->
            when {
                !record.managed -> ExtensionPackageUpdate(record.source, false, "local package")
                record.ref != null -> ExtensionPackageUpdate(record.source, false, "pinned to ${record.ref}")
                else -> updateManagedPackage(record)
            }
        }
    }

    public fun resources(): ExtensionPackageResources {
        return list().map(::resources).fold(ExtensionPackageResources()) { all, next ->
            ExtensionPackageResources(
                extensions = all.extensions + next.extensions,
                skillDirectories = all.skillDirectories + next.skillDirectories,
                promptDirectories = all.promptDirectories + next.promptDirectories,
                themeFiles = all.themeFiles + next.themeFiles,
            )
        }
    }

    private fun resources(record: ExtensionPackageRecord): ExtensionPackageResources {
        val root = Path.of(record.path).toAbsolutePath().normalize()
        require(root.exists()) { "Installed package is missing: ${record.source} ($root)" }
        if (root.isRegularFile()) {
            require(root.extension == "kts") { "Package file must be a .kts extension: $root" }
            return ExtensionPackageResources(extensions = listOf(root))
        }
        require(root.isDirectory()) { "Package path is not a file or directory: $root" }

        val manifestPath = root.resolve("agent47.json")
        val manifest = if (manifestPath.isRegularFile()) {
            Agent47Json.decodeFromString(Agent47PackageManifest.serializer(), manifestPath.readText())
        } else {
            Agent47PackageManifest(
                extensions = listOf("extensions"),
                skills = listOf("skills"),
                prompts = listOf("prompts"),
                themes = listOf("themes"),
            )
        }
        return ExtensionPackageResources(
            extensions = resolveExtensionResources(root, manifest.extensions),
            skillDirectories = resolveDirectories(root, manifest.skills),
            promptDirectories = resolveDirectories(root, manifest.prompts),
            themeFiles = resolveThemeResources(root, manifest.themes),
        )
    }

    private fun resolveExtensionResources(root: Path, entries: List<String>): List<Path> =
        entries.flatMap { entry ->
            val path = resolveInside(root, entry)
            when {
                path.isRegularFile() -> {
                    require(path.extension == "kts") { "Extension resource must be a .kts file: $path" }
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
        require(path.startsWith(root)) { "Package resource escapes its root: $entry" }
        return path
    }

    private fun normalizeManifestPath(entry: String): String =
        entry.removePrefix("./").also {
            require(it.isNotBlank()) { "Package resource path cannot be blank" }
            require(!it.contains("*")) { "Package resource globs are not supported: $entry" }
        }

    private fun updateManagedPackage(record: ExtensionPackageRecord): ExtensionPackageUpdate {
        val path = Path.of(record.path)
        require(path.isDirectory()) { "Installed package is missing: ${record.source} ($path)" }
        val before = gitOutput(listOf("git", "-C", path.toString(), "rev-parse", "HEAD"))
        runGit(
            listOf("git", "-C", path.toString(), "pull", "--ff-only"),
            "Failed to update package ${record.source}",
        )
        val after = gitOutput(listOf("git", "-C", path.toString(), "rev-parse", "HEAD"))
        return ExtensionPackageUpdate(
            source = record.source,
            updated = before != after,
            message = if (before == after) "already current" else "${before.take(8)} -> ${after.take(8)}",
        )
    }

    private fun loadRegistry(): ExtensionPackageRegistry {
        if (!registryPath.exists()) return ExtensionPackageRegistry()
        return Agent47Json.decodeFromString(ExtensionPackageRegistry.serializer(), registryPath.readText())
    }

    private fun saveRegistry(registry: ExtensionPackageRegistry) {
        registryPath.parent?.createDirectories()
        registryPath.writeText(Agent47Json.encodeToString(ExtensionPackageRegistry.serializer(), registry) + "\n")
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

    private data class ParsedSource(
        val localPath: Path? = null,
        val gitUrl: String? = null,
        val ref: String? = null,
    )

    private fun parseSource(source: String): ParsedSource {
        val local = runCatching { Path.of(source) }.getOrNull()
        if (local != null && isLocalSource(source, local)) {
            return ParsedSource(localPath = local)
        }
        val raw = source.removePrefix("git:")
        val refSeparator = raw.lastIndexOf('@').takeIf { it > raw.lastIndexOf('/') }
        val ref = refSeparator?.let { raw.substring(it + 1).takeIf(String::isNotBlank) }
        val withoutRef = refSeparator?.let { raw.substring(0, it) } ?: raw
        val url = normalizeExtensionPackageGitUrl(withoutRef) ?: error("Unsupported package source: $source")
        return ParsedSource(gitUrl = url, ref = ref)
    }

    private fun isLocalSource(source: String, path: Path): Boolean =
        source.startsWith(".") || source.startsWith("/") || path.exists()

    private fun packageIdentity(source: String): String {
        val raw = source.removePrefix("git:")
        val refSeparator = raw.lastIndexOf('@').takeIf { it > raw.lastIndexOf('/') }
        val withoutRef = refSeparator?.let { raw.substring(0, it) } ?: raw
        val canonical = runCatching { Path.of(withoutRef).toAbsolutePath().normalize().toString() }
            .getOrDefault(withoutRef)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

internal fun normalizeExtensionPackageGitUrl(source: String): String? {
    val supportedPrefixes = listOf("https://", "http://", "ssh://", "git://", "git@")
    return when {
        supportedPrefixes.any(source::startsWith) -> source
        source.startsWith("github.com/") -> "https://$source"
        source.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) ->
            "https://github.com/${source.removeSuffix(".git")}.git"
        else -> null
    }
}
