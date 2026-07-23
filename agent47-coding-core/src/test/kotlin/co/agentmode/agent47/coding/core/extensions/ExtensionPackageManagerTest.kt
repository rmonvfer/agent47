package co.agentmode.agent47.coding.core.extensions

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionPackageManagerTest {
    @Test
    fun `local package discovers declared Kotlin and companion resources`() {
        val root = createTempDirectory("agent47-package")
        val packageRoot = root.resolve("package").createDirectories()
        val extension = packageRoot.resolve("src/main.kts")
        extension.parent.createDirectories()
        extension.writeText("beforeAgent { it }")
        packageRoot.resolve("capabilities/example").createDirectories()
            .resolve("SKILL.md").writeText("# Example")
        packageRoot.resolve("prompts").createDirectories()
            .resolve("review.md").writeText("Review this")
        packageRoot.resolve("themes").createDirectories()
            .resolve("night.json").writeText("{}")
        packageRoot.resolve("agent47.json").writeText(
            """
            {
              "extensions": ["src/main.kts"],
              "skills": ["capabilities"],
              "prompts": ["prompts"],
              "themes": ["themes"]
            }
            """.trimIndent(),
        )
        val manager = ExtensionPackageManager(root.resolve("packages.json"), root.resolve("managed"))

        val record = manager.install(packageRoot.toString())
        val resources = manager.resources()

        assertEquals(packageRoot.toAbsolutePath().normalize().toString(), record.path)
        assertEquals(listOf(extension.toAbsolutePath().normalize()), resources.extensions)
        assertEquals(listOf(packageRoot.resolve("capabilities").toAbsolutePath().normalize()), resources.skillDirectories)
        assertEquals(1, resources.promptDirectories.size)
        assertEquals(1, resources.themeFiles.size)
        assertEquals(record, manager.list().single())
    }

    @Test
    fun `removing a local package preserves its files`() {
        val root = createTempDirectory("agent47-package-remove")
        val extension = root.resolve("example.kts").also { it.writeText("beforeAgent { it }") }
        val manager = ExtensionPackageManager(root.resolve("packages.json"), root.resolve("managed"))
        manager.install(extension.toString())

        manager.remove(extension.toString())

        assertTrue(extension.exists())
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `manifest resources cannot escape package root`() {
        val root = createTempDirectory("agent47-package-traversal")
        val packageRoot = root.resolve("package").createDirectories()
        packageRoot.resolve("agent47.json").writeText("""{"extensions":["../outside.kts"]}""")
        root.resolve("outside.kts").writeText("beforeAgent { it }")
        val manager = ExtensionPackageManager(root.resolve("packages.json"), root.resolve("managed"))

        assertFailsWith<IllegalArgumentException> {
            manager.install(packageRoot.toString())
        }
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `GitHub shorthand resolves to an HTTPS clone URL`() {
        assertEquals(
            "https://github.com/owner/repository.git",
            normalizeExtensionPackageGitUrl("owner/repository"),
        )
    }
}
