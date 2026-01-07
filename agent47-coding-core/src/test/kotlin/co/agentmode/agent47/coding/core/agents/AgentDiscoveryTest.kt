package co.agentmode.agent47.coding.core.agents

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentDiscoveryTest {

    @Test
    fun `discovers agents from project directory`() {
        val root = createTempDirectory("agent-discovery")
        val projectAgents = root.resolve("project/agents").createDirectories()

        projectAgents.resolve("helper.md").writeText(
            """
            ---
            name: helper
            description: A helper agent
            tools: [read, bash]
            spawns: none
            ---
            You are a helpful assistant.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = projectAgents, globalDir = null)
        assertEquals(4 + 1, agents.size) // 4 bundled + 1 project
        val helper = agents.first { it.name == "helper" }
        assertEquals("A helper agent", helper.description)
        assertEquals(listOf("read", "bash"), helper.tools)
        assertEquals(SpawnsPolicy.None, helper.spawns)
        assertEquals(AgentSource.PROJECT, helper.source)
    }

    @Test
    fun `discovers agents from global directory`() {
        val root = createTempDirectory("agent-discovery")
        val globalAgents = root.resolve("global/agents").createDirectories()

        globalAgents.resolve("reviewer.md").writeText(
            """
            ---
            name: reviewer
            description: Code reviewer
            tools: [read, grep]
            ---
            Review code for issues.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = null, globalDir = globalAgents)
        val reviewer = agents.first { it.name == "reviewer" }
        assertEquals("Code reviewer", reviewer.description)
        assertEquals(AgentSource.USER, reviewer.source)
    }

    @Test
    fun `project agents override global agents with same name`() {
        val root = createTempDirectory("agent-discovery")
        val projectAgents = root.resolve("project/agents").createDirectories()
        val globalAgents = root.resolve("global/agents").createDirectories()

        globalAgents.resolve("helper.md").writeText(
            """
            ---
            name: helper
            description: Global helper
            ---
            Global system prompt.
            """.trimIndent(),
        )

        projectAgents.resolve("helper.md").writeText(
            """
            ---
            name: helper
            description: Project helper
            ---
            Project system prompt.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = projectAgents, globalDir = globalAgents)
        val helpers = agents.filter { it.name == "helper" }
        assertEquals(1, helpers.size)
        assertEquals("Project helper", helpers.first().description)
        assertEquals(AgentSource.PROJECT, helpers.first().source)
    }

    @Test
    fun `project agents override bundled agents with same name`() {
        val root = createTempDirectory("agent-discovery")
        val projectAgents = root.resolve("project/agents").createDirectories()

        projectAgents.resolve("explore.md").writeText(
            """
            ---
            name: explore
            description: Custom explore agent
            tools: [read, grep, find]
            ---
            Custom explore instructions.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = projectAgents, globalDir = null)
        val explore = agents.first { it.name == "explore" }
        assertEquals("Custom explore agent", explore.description)
        assertEquals(AgentSource.PROJECT, explore.source)
    }

    @Test
    fun `discovers bundled agents when no directories provided`() {
        val agents = AgentDiscovery.discover(projectDir = null, globalDir = null)
        val bundledNames = agents.filter { it.source == AgentSource.BUNDLED }.map { it.name }.toSet()
        assertTrue("explore" in bundledNames)
        assertTrue("plan" in bundledNames)
        assertTrue("task" in bundledNames)
        assertTrue("quick_task" in bundledNames)
    }

    @Test
    fun `handles missing directories gracefully`() {
        val root = createTempDirectory("agent-discovery")
        val nonexistent = root.resolve("does-not-exist/agents")

        val agents = AgentDiscovery.discover(projectDir = nonexistent, globalDir = null)
        // Still has bundled agents
        assertTrue(agents.any { it.source == AgentSource.BUNDLED })
    }

    @Test
    fun `ignores non-md files`() {
        val root = createTempDirectory("agent-discovery")
        val projectAgents = root.resolve("project/agents").createDirectories()

        projectAgents.resolve("notes.txt").writeText("not an agent")
        projectAgents.resolve("helper.md").writeText(
            """
            ---
            name: helper
            description: Real agent
            ---
            System prompt.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = projectAgents, globalDir = null)
        assertTrue(agents.none { it.name == "notes" })
        assertTrue(agents.any { it.name == "helper" })
    }

    @Test
    fun `parses spawns policy variants`() {
        val root = createTempDirectory("agent-discovery")
        val dir = root.resolve("agents").createDirectories()

        dir.resolve("spawns-all.md").writeText(
            """
            ---
            name: spawns-all
            spawns: all
            ---
            Prompt.
            """.trimIndent(),
        )

        dir.resolve("spawns-star.md").writeText(
            """
            ---
            name: spawns-star
            spawns: "*"
            ---
            Prompt.
            """.trimIndent(),
        )

        dir.resolve("spawns-named.md").writeText(
            """
            ---
            name: spawns-named
            spawns: explore, plan
            ---
            Prompt.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = dir, globalDir = null)
        assertEquals(SpawnsPolicy.All, agents.first { it.name == "spawns-all" }.spawns)
        assertEquals(SpawnsPolicy.All, agents.first { it.name == "spawns-star" }.spawns)
        assertEquals(SpawnsPolicy.Named(listOf("explore", "plan")), agents.first { it.name == "spawns-named" }.spawns)
    }

    @Test
    fun `uses filename as fallback name when no frontmatter name`() {
        val root = createTempDirectory("agent-discovery")
        val dir = root.resolve("agents").createDirectories()

        dir.resolve("my-agent.md").writeText("Just a system prompt, no frontmatter.")

        val agents = AgentDiscovery.discover(projectDir = dir, globalDir = null)
        assertTrue(agents.any { it.name == "my-agent" })
    }

    @Test
    fun `parses model patterns from frontmatter`() {
        val root = createTempDirectory("agent-discovery")
        val dir = root.resolve("agents").createDirectories()

        dir.resolve("fast.md").writeText(
            """
            ---
            name: fast
            model: haiku, flash, mini
            ---
            Fast agent.
            """.trimIndent(),
        )

        val agents = AgentDiscovery.discover(projectDir = dir, globalDir = null)
        val fast = agents.first { it.name == "fast" }
        assertEquals(listOf("haiku", "flash", "mini"), fast.model)
    }
}
