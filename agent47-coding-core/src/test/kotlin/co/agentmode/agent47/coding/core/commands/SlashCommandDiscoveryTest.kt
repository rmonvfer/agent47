package co.agentmode.agent47.coding.core.commands

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlashCommandDiscoveryTest {

    @Test
    fun `discovers commands from project directory`() {
        val root = createTempDirectory("command-discovery")
        val projectCommands = root.resolve("project/commands").createDirectories()

        projectCommands.resolve("deploy.md").writeText(
            $$"""
            ---
            name: deploy
            description: Deploy to staging
            ---
            Run the deployment pipeline for $1.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = projectCommands, globalDir = null)
        val deploy = commands.first { it.name == "deploy" }
        assertEquals("Deploy to staging", deploy.description)
        assertEquals(SlashCommandSource.PROJECT, deploy.source)
        assertTrue(deploy.content.contains("deployment pipeline"))
    }

    @Test
    fun `discovers commands from global directory`() {
        val root = createTempDirectory("command-discovery")
        val globalCommands = root.resolve("global/commands").createDirectories()

        globalCommands.resolve("status.md").writeText(
            """
            ---
            name: status
            description: Show git status summary
            ---
            Summarize the current git status.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = null, globalDir = globalCommands)
        val status = commands.first { it.name == "status" }
        assertEquals("Show git status summary", status.description)
        assertEquals(SlashCommandSource.USER, status.source)
    }

    @Test
    fun `project commands override global commands with same name`() {
        val root = createTempDirectory("command-discovery")
        val projectCommands = root.resolve("project/commands").createDirectories()
        val globalCommands = root.resolve("global/commands").createDirectories()

        globalCommands.resolve("review.md").writeText(
            """
            ---
            name: review
            description: Global review
            ---
            Global review prompt.
            """.trimIndent(),
        )

        projectCommands.resolve("review.md").writeText(
            """
            ---
            name: review
            description: Project review
            ---
            Project review prompt with custom rules.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = projectCommands, globalDir = globalCommands)
        val reviews = commands.filter { it.name == "review" }
        assertEquals(1, reviews.size)
        assertEquals("Project review", reviews[0].description)
        assertEquals(SlashCommandSource.PROJECT, reviews[0].source)
    }

    @Test
    fun `handles missing directories gracefully`() {
        val root = createTempDirectory("command-discovery")
        val nonexistent = root.resolve("does-not-exist/commands")

        val commands = SlashCommandDiscovery.discover(projectDir = nonexistent, globalDir = null)
        // May only have bundled commands (currently none)
        assertTrue(commands.none { it.source == SlashCommandSource.PROJECT })
    }

    @Test
    fun `returns empty-ish list when no directories provided`() {
        val commands = SlashCommandDiscovery.discover(projectDir = null, globalDir = null)
        // No user/project commands, may have bundled
        assertTrue(commands.none { it.source == SlashCommandSource.PROJECT })
        assertTrue(commands.none { it.source == SlashCommandSource.USER })
    }

    @Test
    fun `ignores non-md files`() {
        val root = createTempDirectory("command-discovery")
        val dir = root.resolve("commands").createDirectories()

        dir.resolve("notes.txt").writeText("not a command")
        dir.resolve("valid.md").writeText(
            """
            ---
            name: valid
            description: A valid command
            ---
            Do something.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = dir, globalDir = null)
        assertTrue(commands.none { it.name == "notes" })
        assertTrue(commands.any { it.name == "valid" })
    }

    @Test
    fun `uses filename as fallback name when no frontmatter name`() {
        val root = createTempDirectory("command-discovery")
        val dir = root.resolve("commands").createDirectories()

        dir.resolve("my-command.md").writeText("Just a prompt, no frontmatter.")

        val commands = SlashCommandDiscovery.discover(projectDir = dir, globalDir = null)
        assertTrue(commands.any { it.name == "my-command" })
    }

    @Test
    fun `discovers commands from both project and global without conflicts`() {
        val root = createTempDirectory("command-discovery")
        val projectCommands = root.resolve("project/commands").createDirectories()
        val globalCommands = root.resolve("global/commands").createDirectories()

        projectCommands.resolve("build.md").writeText(
            """
            ---
            name: build
            description: Build project
            ---
            Run the build.
            """.trimIndent(),
        )

        globalCommands.resolve("test.md").writeText(
            """
            ---
            name: test
            description: Run tests
            ---
            Run all tests.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = projectCommands, globalDir = globalCommands)
        assertTrue(commands.any { it.name == "build" && it.source == SlashCommandSource.PROJECT })
        assertTrue(commands.any { it.name == "test" && it.source == SlashCommandSource.USER })
    }

    @Test
    fun `preserves command content with argument placeholders`() {
        val root = createTempDirectory("command-discovery")
        val dir = root.resolve("commands").createDirectories()

        dir.resolve("explain.md").writeText(
            $$"""
            ---
            name: explain
            description: Explain code between lines
            ---
            Read $1 from line $2 to line $3 and explain what the code does.
            """.trimIndent(),
        )

        val commands = SlashCommandDiscovery.discover(projectDir = dir, globalDir = null)
        val explain = commands.first { it.name == "explain" }
        assertTrue(explain.content.contains($$"$1"))
        assertTrue(explain.content.contains($$"$2"))
        assertTrue(explain.content.contains($$"$3"))
    }
}
