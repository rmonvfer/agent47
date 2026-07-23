package co.agentmode.agent47.ext.core

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KotlinExtensionDiscoveryTest {
    @Test
    fun `discovers explicit project and global extensions in precedence order`() {
        val root = createTempDirectory("agent47-extension-discovery")
        val explicit = root.resolve("explicit.kts").also { it.writeText("") }
        val project = root.resolve("project").createDirectories()
        val global = root.resolve("global").createDirectories()
        val projectFile = project.resolve("project.kts").also { it.writeText("") }
        val projectIndex = project.resolve("nested").createDirectories().resolve("index.kts").also { it.writeText("") }
        val globalFile = global.resolve("global.kts").also { it.writeText("") }
        global.resolve("ignored").createDirectories().resolve("other.kts").writeText("")

        val result = KotlinExtensionDiscovery.discover(listOf(explicit), project, global)

        assertEquals(
            listOf(explicit, projectIndex, projectFile, globalFile).map { it.toAbsolutePath().normalize() },
            result,
        )
    }

    @Test
    fun `directory input loads kts files and index entrypoints in stable order`() {
        val root = createTempDirectory("agent47-extension-directory")
        val first = root.resolve("a.kts").also { it.writeText("") }
        val index = root.resolve("b").createDirectories().resolve("index.kts").also { it.writeText("") }
        root.resolve(".hidden.kts").writeText("")
        root.resolve("c").createDirectories().resolve("other.kts").writeText("")

        val result = KotlinExtensionDiscovery.discover(
            explicitPaths = listOf(root),
            projectDirectory = root.resolve("missing-project"),
            globalDirectory = root.resolve("missing-global"),
            autoDiscover = false,
        )

        assertEquals(listOf(first, index).map { it.toAbsolutePath().normalize() }, result)
    }

    @Test
    fun `deduplicates the same extension across explicit and discovered paths`() {
        val root = createTempDirectory("agent47-extension-deduplicate")
        val extension = root.resolve("same.kts").also { it.writeText("") }

        val result = KotlinExtensionDiscovery.discover(
            explicitPaths = listOf(extension),
            projectDirectory = root,
            globalDirectory = root,
        )

        assertEquals(listOf(extension.toAbsolutePath().normalize()), result)
    }

    @Test
    fun `rejects missing and unsupported explicit paths`() {
        val root = createTempDirectory("agent47-extension-invalid-path")
        val unsupported = root.resolve("extension.txt").also { it.writeText("") }

        assertFailsWith<IllegalArgumentException> {
            KotlinExtensionDiscovery.discover(
                explicitPaths = listOf(root.resolve("missing.kts")),
                projectDirectory = root,
                globalDirectory = root,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KotlinExtensionDiscovery.discover(
                explicitPaths = listOf(unsupported),
                projectDirectory = root,
                globalDirectory = root,
            )
        }
    }
}
