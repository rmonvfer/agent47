package co.agentmode.agent47.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateServiceTest {

    @Test
    fun `newer release installs a versioned dist and repoints the launcher symlink`() {
        val install = installedDist(version = "1.1.0")
        val archive = buildReleaseArchive("1.2.0")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            respond(exchange, releaseJson(baseUrl, "v1.2.0"))
        }
        server.createContext("/agent47-darwin-arm64.tar.gz") { exchange -> respond(exchange, archive) }
        server.createContext("/checksums-sha256.txt") { exchange ->
            respond(exchange, "${sha256(archive)}  agent47-darwin-arm64.tar.gz\n")
        }
        server.start()

        try {
            val result = service(install, baseUrl, currentVersion = "1.1.0").checkAndInstall(force = true)

            assertIs<UpdateResult.Installed>(result, result.toString())
            assertEquals("1.2.0", result.version)
            assertEquals(install.launcher, result.executable)
            val installedLauncher = install.distStore.resolve("1.2.0/bin/agent47")
            assertTrue(Files.isSymbolicLink(install.launcher))
            assertEquals(installedLauncher, Files.readSymbolicLink(install.launcher))
            assertTrue(Files.isExecutable(installedLauncher))
            assertTrue(installedLauncher.readText().contains("1.2.0"))
            assertTrue(install.distHome.exists(), "the running version must be preserved")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `checksum mismatch leaves the installation untouched`() {
        val install = installedDist(version = "1.1.0")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            respond(exchange, releaseJson(baseUrl, "v1.2.0"))
        }
        server.createContext("/agent47-darwin-arm64.tar.gz") { exchange ->
            respond(exchange, "tampered archive".toByteArray())
        }
        server.createContext("/checksums-sha256.txt") { exchange ->
            respond(exchange, "${sha256("expected archive".toByteArray())}  agent47-darwin-arm64.tar.gz\n")
        }
        server.start()

        try {
            val result = service(install, baseUrl, currentVersion = "1.1.0").checkAndInstall(force = true)

            assertIs<UpdateResult.Failed>(result)
            assertTrue(result.message.contains("checksum verification failed"), result.message)
            assertEquals(
                install.distHome.resolve("bin/agent47"),
                Files.readSymbolicLink(install.launcher),
            )
            val visibleVersions = Files.list(install.distStore).use { entries ->
                entries.map { entry -> entry.fileName.toString() }
                    .filter { name -> !name.startsWith(".") }
                    .toList()
            }
            assertEquals(listOf("1.1.0"), visibleVersions)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `automatic checks respect the configured interval`() {
        val install = installedDist(version = "1.1.0")
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            requestCount.incrementAndGet()
            respond(exchange, releaseJson(baseUrl, "v1.1.0"))
        }
        server.start()

        try {
            val service = service(install, baseUrl, currentVersion = "1.1.0")
            val firstResult = service.checkAndInstall(force = false)
            assertIs<UpdateResult.Current>(firstResult, firstResult.toString())
            assertIs<UpdateResult.Skipped>(service.checkAndInstall(force = false))
            assertEquals(1, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `failed automatic check is nonfatal and throttled`() {
        val install = installedDist(version = "1.1.0")
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()

        try {
            val service = service(install, baseUrl, currentVersion = "1.1.0")
            assertIs<UpdateResult.Failed>(service.checkAndInstall(force = false))
            assertIs<UpdateResult.Skipped>(service.checkAndInstall(force = false))
            assertEquals(1, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `release versions compare numerically`() {
        assertTrue(compareReleaseVersions("v0.1.10", "0.1.9") > 0)
        assertTrue(compareReleaseVersions("v2.0.0", "1.99.99") > 0)
        assertTrue(compareReleaseVersions("v1.0.0", "1.0.0-rc.1") > 0)
        assertTrue(compareReleaseVersions("v1.0.0-rc.10", "1.0.0-rc.2") > 0)
        assertEquals(0, compareReleaseVersions("v1.2.3+build.9", "1.2.3+build.1"))
        assertEquals(0, compareReleaseVersions("v1.2.3", "1.2.3"))
    }

    @Test
    fun `executions outside a packaged installation never contact the release service`() {
        val root = createTempDirectory("agent47-update")
        val result = UpdateService(
            currentVersion = "1.1.0",
            statePath = root.resolve("update-state.json"),
            distHomeProvider = { null },
        ).checkAndInstall(force = true)

        assertIs<UpdateResult.Skipped>(result)
    }

    @Test
    fun `installations without a managed launcher symlink skip self-update`() {
        val install = installedDist(version = "1.1.0")
        Files.delete(install.launcher)
        install.launcher.writeText("#!/bin/sh\nexec true\n")
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            respond(exchange, releaseJson(baseUrl, "v1.2.0"))
        }
        server.start()

        try {
            val result = service(install, baseUrl, currentVersion = "1.1.0").checkAndInstall(force = true)

            assertIs<UpdateResult.Skipped>(result)
            assertTrue(result.reason.contains("symlink"), result.reason)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `concurrent update check skips while the global lock is held`() {
        val install = installedDist(version = "1.1.0")
        FileChannel.open(
            install.stateRoot.resolve("update.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val result = service(install, "http://127.0.0.1:1", "1.1.0")
                    .checkAndInstall(force = false)
                assertIs<UpdateResult.Skipped>(result)
            }
        }
    }

    private data class InstalledDist(
        val stateRoot: Path,
        val distStore: Path,
        val distHome: Path,
        val launcher: Path,
    )

    private fun installedDist(version: String): InstalledDist {
        val root = createTempDirectory("agent47-update").toRealPath()
        val distStore = Files.createDirectories(root.resolve("dist"))
        val distHome = Files.createDirectories(distStore.resolve(version))
        val distLauncher = Files.createDirectories(distHome.resolve("bin")).resolve("agent47")
        distLauncher.writeText("#!/bin/sh\necho agent47 $version\n")
        distLauncher.toFile().setExecutable(true)
        val launcher = Files.createDirectories(root.resolve("bin")).resolve("agent47")
        Files.createSymbolicLink(launcher, distLauncher)
        return InstalledDist(
            stateRoot = Files.createDirectories(root.resolve("state")),
            distStore = distStore,
            distHome = distHome,
            launcher = launcher,
        )
    }

    private fun buildReleaseArchive(version: String): ByteArray {
        val work = createTempDirectory("agent47-release")
        val distRoot = work.resolve("agent47")
        val bin = Files.createDirectories(distRoot.resolve("bin"))
        val launcher = bin.resolve("agent47")
        launcher.writeText("#!/bin/sh\necho agent47 $version\n")
        launcher.toFile().setExecutable(true)
        Files.createDirectories(distRoot.resolve("lib")).resolve("agent47.jar").writeText("jar-$version")
        val archive = work.resolve("agent47.tar.gz")
        val tar = ProcessBuilder("tar", "-czf", archive.toString(), "-C", work.toString(), "agent47")
            .redirectErrorStream(true)
            .start()
        check(tar.waitFor() == 0) { tar.inputStream.bufferedReader().readText() }
        return archive.readBytes()
    }

    private fun service(
        install: InstalledDist,
        baseUrl: String,
        currentVersion: String,
    ): UpdateService = UpdateService(
        currentVersion = currentVersion,
        statePath = install.stateRoot.resolve("update-state.json"),
        apiRoot = baseUrl,
        distHomeProvider = { install.distHome },
        launcherProvider = { install.launcher },
        platformProvider = { "darwin-arm64" },
        nowMillis = { 1_000_000L },
    )

    private fun releaseJson(baseUrl: String, version: String): String = """
        {
          "tag_name": "$version",
          "assets": [
            {
              "name": "agent47-darwin-arm64.tar.gz",
              "browser_download_url": "$baseUrl/agent47-darwin-arm64.tar.gz"
            },
            {
              "name": "checksums-sha256.txt",
              "browser_download_url": "$baseUrl/checksums-sha256.txt"
            }
          ]
        }
    """.trimIndent()

    private fun respond(exchange: HttpExchange, body: String) {
        respond(exchange, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun respond(exchange: HttpExchange, body: ByteArray) {
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { output -> output.write(body) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }
}
