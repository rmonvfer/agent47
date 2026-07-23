package co.agentmode.agent47.coding.core.extensions

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionRepositoryManagerTest {
    @Test
    fun `complete example repository matches the documented resource layout`() {
        val root = createTempDirectory("agent47-example-repository")
        val manager = manager(root)

        manager.install(exampleRepository().toString())
        val resources = manager.resources()

        assertEquals(2, resources.extensions.size)
        assertEquals(1, resources.skillDirectories.size)
        assertEquals(1, resources.promptDirectories.size)
        assertEquals(1, resources.themeFiles.size)
    }

    @Test
    fun `local repository discovers declared Kotlin and companion resources`() {
        val root = createTempDirectory("agent47-repository")
        val repositoryRoot = root.resolve("repository").createDirectories()
        val extension = repositoryRoot.resolve("src/main.kts")
        extension.parent.createDirectories()
        extension.writeText("beforeAgent { it }")
        repositoryRoot.resolve("capabilities/example").createDirectories()
            .resolve("SKILL.md").writeText("# Example")
        repositoryRoot.resolve("prompts").createDirectories()
            .resolve("review.md").writeText("Review this")
        repositoryRoot.resolve("themes").createDirectories()
            .resolve("night.json").writeText("{}")
        repositoryRoot.resolve("agent47.json").writeText(
            """
            {
              "extensions": ["src/main.kts"],
              "skills": ["capabilities"],
              "prompts": ["prompts"],
              "themes": ["themes"]
            }
            """.trimIndent(),
        )
        val manager = manager(root)

        val repository = manager.install(repositoryRoot.toString())
        val resources = manager.resources()

        assertEquals(repositoryRoot.toAbsolutePath().normalize(), repository.path)
        assertEquals(listOf(extension.toAbsolutePath().normalize()), resources.extensions)
        assertEquals(
            listOf(repositoryRoot.resolve("capabilities").toAbsolutePath().normalize()),
            resources.skillDirectories,
        )
        assertEquals(1, resources.promptDirectories.size)
        assertEquals(1, resources.themeFiles.size)
        assertEquals(repository, manager.list().single())
        assertTrue(root.resolve("extensions.json").readText().contains("repositories"))
    }

    @Test
    fun `repository conventions provide the complete extension experience without a manifest`() {
        val root = createTempDirectory("agent47-repository-conventions")
        val repositoryRoot = root.resolve("repository").createDirectories()
        val extension = repositoryRoot.resolve("extensions/example/index.kts")
        extension.parent.createDirectories()
        extension.writeText("beforeAgent { it }")
        repositoryRoot.resolve("extensions/example/helper.kts").writeText("error(\"not an entrypoint\")")
        repositoryRoot.resolve("skills/example").createDirectories().resolve("SKILL.md").writeText("# Example")
        repositoryRoot.resolve("prompts").createDirectories().resolve("review.md").writeText("Review")
        val theme = repositoryRoot.resolve("themes").createDirectories().resolve("night.json")
        theme.writeText("{}")
        val manager = manager(root)

        manager.install(repositoryRoot.toString())
        val resources = manager.resources()

        assertEquals(listOf(extension), resources.extensions)
        assertEquals(listOf(repositoryRoot.resolve("skills")), resources.skillDirectories)
        assertEquals(listOf(repositoryRoot.resolve("prompts")), resources.promptDirectories)
        assertEquals(listOf(theme), resources.themeFiles)
    }

    @Test
    fun `removing a local extension preserves its files`() {
        val root = createTempDirectory("agent47-repository-remove")
        val extension = root.resolve("example.kts").also { it.writeText("beforeAgent { it }") }
        val manager = manager(root)
        manager.install(extension.toString())

        manager.remove(extension.toString())

        assertTrue(extension.exists())
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `excluded repository identities are not updated`() {
        val root = createTempDirectory("agent47-repository-update-precedence")
        val extension = root.resolve("example.kts").also { it.writeText("beforeAgent { it }") }
        val manager = manager(root)
        manager.install(extension.toString())

        assertTrue(manager.update(excluding = manager.identities()).isEmpty())
    }

    @Test
    fun `project registry stores local sources relative to its configuration directory`() {
        val root = createTempDirectory("agent47-project-repository")
        val project = root.resolve("project").createDirectories()
        val config = project.resolve(".agent47").createDirectories()
        val extension = project.resolve("extension.kts").also { it.writeText("beforeAgent { it }") }
        val manager = ExtensionRepositoryManager(
            config.resolve("extensions.json"),
            config.resolve("git"),
            project,
            relativeLocalSources = true,
        )

        manager.install("./extension.kts")

        assertEquals("../extension.kts", manager.list().single().source)
        assertEquals(extension, manager.list().single().path)
    }

    @Test
    fun `manifest resources cannot escape the repository root`() {
        val root = createTempDirectory("agent47-repository-traversal")
        val repositoryRoot = root.resolve("repository").createDirectories()
        repositoryRoot.resolve("agent47.json").writeText("""{"extensions":["../outside.kts"]}""")
        root.resolve("outside.kts").writeText("beforeAgent { it }")
        val manager = manager(root)

        assertFailsWith<IllegalArgumentException> {
            manager.install(repositoryRoot.toString())
        }
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `manifest resources cannot escape through a symbolic link`() {
        val root = createTempDirectory("agent47-repository-symlink")
        val repositoryRoot = root.resolve("repository").createDirectories()
        val outside = root.resolve("outside.kts").also { it.writeText("beforeAgent { it }") }
        Files.createSymbolicLink(repositoryRoot.resolve("extension.kts"), outside)
        repositoryRoot.resolve("agent47.json").writeText("""{"extensions":["extension.kts"]}""")
        val manager = manager(root)

        assertFailsWith<IllegalArgumentException> {
            manager.install(repositoryRoot.toString())
        }
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `Git URL forms share identity while refs remain part of the declared source`() {
        val base = createTempDirectory("agent47-source-parser")
        val shorthand = parseExtensionSource("git:github.com/owner/repository@v1", base)
            as ParsedExtensionSource.Git
        val https = parseExtensionSource("https://github.com/owner/repository.git", base)
            as ParsedExtensionSource.Git
        val ssh = parseExtensionSource("git@github.com:owner/repository.git", base)
            as ParsedExtensionSource.Git

        assertEquals("https://github.com/owner/repository", shorthand.cloneUrl)
        assertEquals("v1", shorthand.ref)
        assertEquals(https.identity, shorthand.identity)
        assertEquals(https.identity, ssh.identity)
        assertEquals(
            "feature/nested",
            (parseExtensionSource("git:github.com/owner/repository@feature/nested", base)
                as ParsedExtensionSource.Git).ref,
        )
        assertTrue(parseExtensionSource("owner/repository", base) is ParsedExtensionSource.Local)
    }

    @Test
    fun `Git repository installs updates and removes through its source URL`() {
        val fixture = createGitFixture()
        fixture.commitAndPush("first")
        val manager = manager(fixture.root)

        val installed = manager.install(fixture.source)
        assertEquals("first", installed.path.resolve("extensions/example.kts").readText())
        assertEquals("already current", manager.update().single().message)

        fixture.commitAndPush("second")
        val update = manager.update(fixture.source).single()

        assertTrue(update.updated)
        assertEquals("second", installed.path.resolve("extensions/example.kts").readText())
        manager.remove(fixture.source)
        assertFalse(installed.path.exists())
    }

    @Test
    fun `declared Git repository is cloned when its resources are first resolved`() {
        val fixture = createGitFixture()
        fixture.commitAndPush("bootstrapped")
        fixture.root.resolve("extensions.json").writeText(
            """{"repositories":["${fixture.source}"]}""",
        )
        val manager = manager(fixture.root)
        val installedPath = manager.list().single().path

        assertFalse(installedPath.exists())
        val resources = manager.resources()

        assertEquals("bootstrapped", resources.extensions.single().readText())
        assertTrue(installedPath.exists())
    }

    @Test
    fun `invalid Git update keeps the installed repository intact`() {
        val fixture = createGitFixture()
        fixture.commitAndPush("valid")
        val manager = manager(fixture.root)
        val installed = manager.install(fixture.source)
        fixture.work.resolve("agent47.json").writeText("""{"extensions":["../outside.kts"]}""")
        fixture.commitAndPush()

        assertFailsWith<IllegalArgumentException> {
            manager.update()
        }

        assertEquals("valid", installed.path.resolve("extensions/example.kts").readText())
        assertFalse(installed.path.resolve("agent47.json").exists())
    }

    private fun manager(root: Path): ExtensionRepositoryManager =
        ExtensionRepositoryManager(root.resolve("extensions.json"), root.resolve("git"), root)

    private fun exampleRepository(): Path =
        listOf(
            Path.of("examples/extension-repository"),
            Path.of("../examples/extension-repository"),
        ).firstOrNull { it.resolve("agent47.json").exists() }
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("Cannot locate examples/extension-repository")

    private fun createGitFixture(): GitFixture {
        val root = createTempDirectory("agent47-git-repository")
        val remote = root.resolve("remote.git")
        val work = root.resolve("work")
        runCommand(root, "git", "init", "--bare", "--initial-branch=main", remote.toString())
        runCommand(root, "git", "init", "--initial-branch=main", work.toString())
        runCommand(work, "git", "config", "user.name", "Agent47 Test")
        runCommand(work, "git", "config", "user.email", "agent47@example.invalid")
        runCommand(work, "git", "remote", "add", "origin", remote.toString())
        return GitFixture(root, work, remote.toUri().toString())
    }

    private fun runCommand(directory: Path, vararg command: String) {
        val process = ProcessBuilder(command.toList())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
    }

    private inner class GitFixture(
        val root: Path,
        val work: Path,
        val source: String,
    ) {
        fun commitAndPush(content: String? = null) {
            if (content != null) {
                work.resolve("extensions").createDirectories()
                    .resolve("example.kts").writeText(content)
            }
            runCommand(work, "git", "add", ".")
            runCommand(work, "git", "commit", "-m", "fixture")
            runCommand(work, "git", "push", "-u", "origin", "main")
        }
    }
}
