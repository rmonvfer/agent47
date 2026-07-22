package co.agentmode.agent47.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateServiceTest {

    @Test
    fun `newer release replaces executable after checksum verification`() {
        val root = createTempDirectory("agent47-update")
        val executable = root.resolve("agent47").also { it.writeBytes("old binary".toByteArray()) }
        val replacement = "new binary".toByteArray()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            respond(exchange, releaseJson(baseUrl, "v1.2.0"))
        }
        server.createContext("/agent47-darwin-arm64") { exchange -> respond(exchange, replacement) }
        server.createContext("/checksums-sha256.txt") { exchange ->
            respond(exchange, "${sha256(replacement)}  agent47-darwin-arm64\n")
        }
        server.start()

        try {
            val result = service(root, executable, baseUrl, currentVersion = "1.1.0").checkAndInstall(force = true)

            assertIs<UpdateResult.Installed>(result, result.toString())
            assertEquals("1.2.0", result.version)
            assertContentEquals(replacement, executable.readBytes())
            assertTrue(Files.isExecutable(executable))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `checksum mismatch preserves existing executable`() {
        val root = createTempDirectory("agent47-update")
        val original = "old binary".toByteArray()
        val executable = root.resolve("agent47").also { it.writeBytes(original) }
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            respond(exchange, releaseJson(baseUrl, "v1.2.0"))
        }
        server.createContext("/agent47-darwin-arm64") { exchange ->
            respond(exchange, "tampered binary".toByteArray())
        }
        server.createContext("/checksums-sha256.txt") { exchange ->
            respond(exchange, "${sha256("expected binary".toByteArray())}  agent47-darwin-arm64\n")
        }
        server.start()

        try {
            val result = service(root, executable, baseUrl, currentVersion = "1.1.0").checkAndInstall(force = true)

            assertIs<UpdateResult.Failed>(result)
            assertTrue(result.message.contains("checksum verification failed"), result.message)
            assertContentEquals(original, executable.readBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `automatic checks respect the configured interval`() {
        val root = createTempDirectory("agent47-update")
        val executable = root.resolve("agent47").also { it.writeBytes("current".toByteArray()) }
        val requestCount = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val baseUrl = "http://127.0.0.1:${server.address.port}"
        server.createContext("/repos/rmonvfer/agent47/releases/latest") { exchange ->
            requestCount.incrementAndGet()
            respond(exchange, releaseJson(baseUrl, "v1.1.0"))
        }
        server.start()

        try {
            val service = service(root, executable, baseUrl, currentVersion = "1.1.0")
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
        val root = createTempDirectory("agent47-update")
        val executable = root.resolve("agent47").also { it.writeBytes("current".toByteArray()) }
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
            val service = service(root, executable, baseUrl, currentVersion = "1.1.0")
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
    fun `non-native executions never contact the release service`() {
        val root = createTempDirectory("agent47-update")
        val result = UpdateService(
            currentVersion = "1.1.0",
            statePath = root.resolve("update-state.json"),
            nativeImageProvider = { false },
        ).checkAndInstall(force = true)

        assertIs<UpdateResult.Skipped>(result)
    }

    @Test
    fun `concurrent update check skips while the global lock is held`() {
        val root = createTempDirectory("agent47-update")
        val executable = root.resolve("agent47").also { it.writeBytes("current".toByteArray()) }
        FileChannel.open(
            root.resolve("update.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                val result = service(root, executable, "http://127.0.0.1:1", "1.1.0")
                    .checkAndInstall(force = false)
                assertIs<UpdateResult.Skipped>(result)
            }
        }
    }

    private fun service(
        root: java.nio.file.Path,
        executable: java.nio.file.Path,
        baseUrl: String,
        currentVersion: String,
    ): UpdateService = UpdateService(
        currentVersion = currentVersion,
        statePath = root.resolve("update-state.json"),
        apiRoot = baseUrl,
        executableProvider = { executable },
        platformProvider = { "darwin-arm64" },
        nativeImageProvider = { true },
        nowMillis = { 1_000_000L },
    )

    private fun releaseJson(baseUrl: String, version: String): String = """
        {
          "tag_name": "$version",
          "assets": [
            {
              "name": "agent47-darwin-arm64",
              "browser_download_url": "$baseUrl/agent47-darwin-arm64"
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
