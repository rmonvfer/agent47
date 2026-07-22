package co.agentmode.agent47.app

import co.agentmode.agent47.ai.types.Agent47Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val DEFAULT_REPOSITORY = "rmonvfer/agent47"
private val RELEASE_VERSION_PATTERN = Regex(
    "^v?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
        "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
        "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$",
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubReleaseAsset>,
)

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
)

@Serializable
private data class UpdateState(
    val lastCheckedAt: Long,
)

internal sealed interface UpdateResult {
    data class Current(val version: String) : UpdateResult

    data class Installed(
        val previousVersion: String,
        val version: String,
        val executable: Path,
    ) : UpdateResult

    data class Skipped(val reason: String) : UpdateResult

    data class Failed(val message: String) : UpdateResult
}

internal class UpdateService(
    private val currentVersion: String,
    private val statePath: Path,
    private val repository: String = DEFAULT_REPOSITORY,
    private val apiRoot: String = "https://api.github.com",
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val executableProvider: () -> Path? = ::currentExecutable,
    private val platformProvider: () -> String? = ::currentPlatform,
    private val nativeImageProvider: () -> Boolean = ::isNativeImage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val checkIntervalMillis: Long = 24L * 60L * 60L * 1_000L,
    private val progress: (String) -> Unit = {},
) {
    fun checkAndInstall(force: Boolean): UpdateResult {
        if (!nativeImageProvider()) {
            return UpdateResult.Skipped("updates are available only in native agent47 installations")
        }

        return try {
            statePath.parent?.let(Files::createDirectories)
            val lockPath = statePath.resolveSibling("update.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                val lock = runCatching { channel.tryLock() }.getOrNull()
                    ?: return UpdateResult.Skipped("another agent47 process is checking for updates")
                lock.use {
                    try {
                        checkAndInstallLocked(force)
                    } catch (error: Exception) {
                        if (!force) runCatching { recordCheck() }
                        UpdateResult.Failed(error.message ?: error::class.simpleName ?: "update failed")
                    }
                }
            }
        } catch (error: Exception) {
            UpdateResult.Failed(error.message ?: error::class.simpleName ?: "update failed")
        }
    }

    private fun checkAndInstallLocked(force: Boolean): UpdateResult {
        if (!force && wasCheckedRecently()) {
            return UpdateResult.Skipped("an update check is not due yet")
        }

        val executable = executableProvider()?.let { path ->
            runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
        } ?: return UpdateResult.Failed("could not determine the current agent47 executable")
        val platform = platformProvider()
            ?: return UpdateResult.Failed("unsupported platform: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        val release = fetchRelease()
        if (compareReleaseVersions(release.tagName, currentVersion) <= 0) {
            recordCheck()
            return UpdateResult.Current(currentVersion)
        }

        progress("Updating agent47 $currentVersion to ${release.tagName.removePrefix("v")}...")
        return installRelease(release, platform, executable).also { result ->
            if (result is UpdateResult.Installed) recordCheck()
        }
    }

    private fun fetchRelease(): GitHubRelease {
        val request = request("$apiRoot/repos/$repository/releases/latest", Duration.ofSeconds(5))
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        check(response.statusCode() == 200) {
            "GitHub returned HTTP ${response.statusCode()} while checking for updates"
        }
        return Agent47Json.decodeFromString(GitHubRelease.serializer(), response.body())
    }

    private fun installRelease(
        release: GitHubRelease,
        platform: String,
        executable: Path,
    ): UpdateResult {
        val assetName = "agent47-$platform"
        val binaryAsset = release.assets.firstOrNull { it.name == assetName }
            ?: error("release ${release.tagName} does not contain $assetName")
        val checksumAsset = release.assets.firstOrNull { it.name == "checksums-sha256.txt" }
            ?: error("release ${release.tagName} does not contain checksums-sha256.txt")
        val checksums = downloadText(checksumAsset.downloadUrl)
        val expectedChecksum = checksumFor(checksums, assetName)
            ?: error("release checksums do not contain $assetName")

        executable.parent?.let(Files::createDirectories)
        val staged = Files.createTempFile(executable.parent, ".agent47.update.", ".tmp")
        try {
            downloadFile(binaryAsset.downloadUrl, staged)
            val actualChecksum = sha256(staged)
            check(actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                "checksum verification failed for $assetName"
            }
            Files.setPosixFilePermissions(
                staged,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
            replaceFileAtomically(staged, executable)
        } finally {
            Files.deleteIfExists(staged)
        }

        return UpdateResult.Installed(currentVersion, release.tagName.removePrefix("v"), executable)
    }

    private fun downloadText(url: String): String {
        val response = httpClient.send(
            request(url, Duration.ofSeconds(10)),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
        )
        check(response.statusCode() == 200) {
            "download failed with HTTP ${response.statusCode()}: $url"
        }
        return response.body()
    }

    private fun downloadFile(url: String, destination: Path) {
        val response = httpClient.send(
            request(url, Duration.ofMinutes(10)),
            HttpResponse.BodyHandlers.ofFile(destination),
        )
        check(response.statusCode() == 200) {
            "download failed with HTTP ${response.statusCode()}: $url"
        }
    }

    private fun request(url: String, timeout: Duration): HttpRequest = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2026-03-10")
        .header("User-Agent", "agent47/$currentVersion")
        .GET()
        .build()

    private fun wasCheckedRecently(): Boolean {
        if (!statePath.exists()) return false
        val lastCheckedAt = runCatching {
            Agent47Json.decodeFromString(UpdateState.serializer(), statePath.readText()).lastCheckedAt
        }.getOrNull() ?: return false
        return nowMillis() - lastCheckedAt in 0 until checkIntervalMillis
    }

    private fun recordCheck() {
        statePath.parent?.let(Files::createDirectories)
        val staged = Files.createTempFile(statePath.parent, ".update-state.", ".tmp")
        try {
            staged.writeText(Agent47Json.encodeToString(UpdateState.serializer(), UpdateState(nowMillis())))
            replaceFileAtomically(staged, statePath)
        } finally {
            Files.deleteIfExists(staged)
        }
    }
}

internal fun compareReleaseVersions(left: String, right: String): Int {
    return parseReleaseVersion(left).compareTo(parseReleaseVersion(right))
}

private fun parseReleaseVersion(version: String): SemanticVersion {
    val match = RELEASE_VERSION_PATTERN.matchEntire(version)
        ?: error("invalid release version: $version")
    return SemanticVersion(
        major = BigInteger(match.groupValues[1]),
        minor = BigInteger(match.groupValues[2]),
        patch = BigInteger(match.groupValues[3]),
        prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.'),
    )
}

private data class SemanticVersion(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<String>?,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).takeIf { it != 0 }?.let { return it }
        minor.compareTo(other.minor).takeIf { it != 0 }?.let { return it }
        patch.compareTo(other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease == null) return if (other.prerelease == null) 0 else 1
        if (other.prerelease == null) return -1

        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val comparison = comparePrereleaseIdentifier(prerelease[index], other.prerelease[index])
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }
}

private fun comparePrereleaseIdentifier(left: String, right: String): Int {
    val leftNumber = left.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
    val rightNumber = right.takeIf { it.all(Char::isDigit) }?.let(::BigInteger)
    return when {
        leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
        leftNumber != null -> -1
        rightNumber != null -> 1
        else -> left.compareTo(right)
    }
}

private fun checksumFor(checksums: String, assetName: String): String? = checksums
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotEmpty)
    .map { line -> line.split(Regex("\\s+"), limit = 2) }
    .firstOrNull { parts -> parts.size == 2 && parts[1].removePrefix("*") == assetName }
    ?.first()
    ?.takeIf { checksum -> checksum.matches(Regex("^[0-9a-fA-F]{64}$")) }

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun replaceFileAtomically(staged: Path, destination: Path) {
    try {
        Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(staged, destination, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun isNativeImage(): Boolean =
    System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"

private fun currentExecutable(): Path? = ProcessHandle.current().info().command().orElse(null)?.let(Path::of)

private fun currentPlatform(): String? {
    val os = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin"
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
        else -> return null
    }
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x86_64"
        else -> return null
    }
    return "$os-$arch"
}
