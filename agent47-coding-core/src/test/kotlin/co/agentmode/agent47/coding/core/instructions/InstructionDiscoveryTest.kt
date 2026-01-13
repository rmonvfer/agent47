package co.agentmode.agent47.coding.core.instructions

import co.agentmode.agent47.coding.core.settings.Settings
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstructionDiscoveryTest {

    @Test
    fun `findUp returns files from nearest ancestor with a match`() {
        val root = createTempDirectory("findUp-test")
        val parent = root.resolve("a").createDirectories()
        val child = parent.resolve("b").createDirectories()

        parent.resolve("AGENTS.md").writeText("parent instructions")
        root.resolve("AGENTS.md").writeText("root instructions")

        val results = InstructionDiscovery.findUp(listOf("AGENTS.md"), child, root)
        assertEquals(1, results.size)
        assertEquals("parent instructions", results[0].toFile().readText())
    }

    @Test
    fun `findUp stops at stop boundary`() {
        val root = createTempDirectory("findUp-test")
        val stop = root.resolve("stop").createDirectories()
        val child = stop.resolve("child").createDirectories()

        root.resolve("AGENTS.md").writeText("above stop boundary")

        val results = InstructionDiscovery.findUp(listOf("AGENTS.md"), child, stop)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findUp returns empty when no files found`() {
        val root = createTempDirectory("findUp-test")
        val child = root.resolve("a/b").createDirectories()

        val results = InstructionDiscovery.findUp(listOf("AGENTS.md"), child, root)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findUp collects all matching files from the same directory`() {
        val root = createTempDirectory("findUp-test")
        val dir = root.resolve("project").createDirectories()
        dir.resolve("AGENTS.md").writeText("agents")
        dir.resolve("AGENT47.md").writeText("agent47")

        val results = InstructionDiscovery.findUp(
            listOf("AGENTS.md", "AGENT47.md", "CLAUDE.md"),
            dir,
            root,
        )
        assertEquals(2, results.size)
    }

    @Test
    fun `discover finds project-level AGENTS md`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        cwd.resolve("AGENTS.md").writeText("project instructions")

        val results = InstructionDiscovery.discover(cwd, globalDir, null, Settings())
        assertEquals(1, results.size)
        assertEquals(InstructionSource.PROJECT, results[0].source)
        assertEquals("project instructions", results[0].content)
    }

    @Test
    fun `discover finds global AGENTS md`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        globalDir.resolve("AGENTS.md").writeText("global instructions")

        val results = InstructionDiscovery.discover(cwd, globalDir, null, Settings())
        assertEquals(1, results.size)
        assertEquals(InstructionSource.GLOBAL, results[0].source)
        assertEquals("global instructions", results[0].content)
    }

    @Test
    fun `discover finds Claude Code CLAUDE md`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        val claudeDir = root.resolve("claude").createDirectories()
        claudeDir.resolve("CLAUDE.md").writeText("claude instructions")

        val results = InstructionDiscovery.discover(cwd, globalDir, claudeDir, Settings())
        assertEquals(1, results.size)
        assertEquals(InstructionSource.CLAUDE_CODE, results[0].source)
        assertEquals("claude instructions", results[0].content)
    }

    @Test
    fun `discover deduplicates by absolute path`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        val claudeDir = root.resolve("claude").createDirectories()

        cwd.resolve("AGENTS.md").writeText("project agents")
        cwd.resolve("AGENT47.md").writeText("project agent47")
        globalDir.resolve("AGENTS.md").writeText("global agents")
        claudeDir.resolve("CLAUDE.md").writeText("claude")

        val results = InstructionDiscovery.discover(cwd, globalDir, claudeDir, Settings())
        val paths = results.map { it.path }
        assertEquals(paths.toSet().size, paths.size, "All paths should be unique")
        assertEquals(4, results.size)
    }

    @Test
    fun `discover resolves settings-declared paths`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        val customFile = cwd.resolve("docs/rules.md")
        customFile.parent.createDirectories()
        customFile.writeText("custom rules")

        val settings = Settings(instructions = listOf("docs/rules.md"))
        val results = InstructionDiscovery.discover(cwd, globalDir, null, settings)
        assertEquals(1, results.size)
        assertEquals(InstructionSource.SETTINGS, results[0].source)
        assertEquals("custom rules", results[0].content)
    }

    @Test
    fun `discover resolves settings-declared globs`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        val docsDir = cwd.resolve("docs").createDirectories()
        docsDir.resolve("rules1.md").writeText("rules 1")
        docsDir.resolve("rules2.md").writeText("rules 2")
        docsDir.resolve("notes.txt").writeText("not matched")

        val settings = Settings(instructions = listOf("docs/*.md"))
        val results = InstructionDiscovery.discover(cwd, globalDir, null, settings)
        assertEquals(2, results.size)
        assertTrue(results.all { it.source == InstructionSource.SETTINGS })
    }

    @Test
    fun `discover expands tilde paths`() {
        val root = createTempDirectory("discover-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()

        val home = System.getProperty("user.home")
        val settings = Settings(instructions = listOf("~/nonexistent-agent47-test-file.md"))
        val results = InstructionDiscovery.discover(cwd, globalDir, null, settings)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `format wraps content with source header`() {
        val root = createTempDirectory("format-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        cwd.resolve("AGENTS.md").writeText("test content")

        val loader = InstructionLoader(cwd, globalDir, null, Settings())
        val formatted = loader.format()
        assertTrue(formatted.contains("Instructions from:"))
        assertTrue(formatted.contains("test content"))
    }

    @Test
    fun `format joins multiple files with blank lines`() {
        val root = createTempDirectory("format-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()
        cwd.resolve("AGENTS.md").writeText("agents content")
        globalDir.resolve("AGENTS.md").writeText("global content")

        val loader = InstructionLoader(cwd, globalDir, null, Settings())
        val formatted = loader.format()
        assertTrue(formatted.contains("\n\n"))
        assertTrue(formatted.contains("agents content"))
        assertTrue(formatted.contains("global content"))
    }

    @Test
    fun `format returns empty string when no instructions found`() {
        val root = createTempDirectory("format-test")
        val cwd = root.resolve("project").createDirectories()
        val globalDir = root.resolve("global").createDirectories()

        val loader = InstructionLoader(cwd, globalDir, null, Settings())
        assertEquals("", loader.format())
    }

    @Test
    fun `resolveGlob returns empty for nonexistent path`() {
        val root = createTempDirectory("glob-test")
        val results = InstructionDiscovery.resolveGlob("nonexistent.md", root)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `resolveGlob resolves absolute paths`() {
        val root = createTempDirectory("glob-test")
        val file = root.resolve("instructions.md")
        file.writeText("absolute")

        val results = InstructionDiscovery.resolveGlob(file.toString(), root)
        assertEquals(1, results.size)
    }
}
