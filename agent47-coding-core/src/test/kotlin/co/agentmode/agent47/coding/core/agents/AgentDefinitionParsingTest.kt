package co.agentmode.agent47.coding.core.agents

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentDefinitionParsingTest {

    @Test
    fun `parses the pi ergonomics frontmatter fields`() {
        val def = AgentParser.parse(
            content = """
            ---
            name: auditor
            display_name: Security Auditor
            description: Reviews code for vulnerabilities
            tools: [read, grep, find]
            disallowed_tools: write, edit
            prompt_mode: append
            inherit_context: true
            isolated: true
            isolation: worktree
            memory: project
            max_turns: 30
            enabled: false
            ---
            You are a security auditor.
            """.trimIndent(),
            fallbackName = "auditor",
            source = AgentSource.PROJECT,
        )

        assertEquals("auditor", def.name)
        assertEquals("Security Auditor", def.displayName)
        assertEquals("Security Auditor", def.label)
        assertEquals("Reviews code for vulnerabilities", def.description)
        assertEquals(listOf("read", "grep", "find"), def.tools)
        assertEquals(listOf("write", "edit"), def.disallowedTools)
        assertEquals(PromptMode.APPEND, def.promptMode)
        assertEquals(true, def.inheritContext)
        assertEquals(true, def.isolated)
        assertEquals(IsolationMode.WORKTREE, def.isolation)
        assertEquals(MemoryScope.PROJECT, def.memory)
        assertEquals(30, def.maxTurns)
        assertFalse(def.enabled)
        assertEquals("You are a security auditor.", def.systemPrompt)
    }

    @Test
    fun `defaults are applied when fields are omitted`() {
        val def = AgentParser.parse(
            content = """
            ---
            name: minimal
            ---
            Prompt.
            """.trimIndent(),
            fallbackName = "minimal",
            source = AgentSource.PROJECT,
        )

        assertEquals("minimal", def.name)
        assertEquals("minimal", def.description)
        assertNull(def.displayName)
        assertNull(def.tools)
        assertEquals(PromptMode.REPLACE, def.promptMode)
        assertNull(def.inheritContext)
        assertNull(def.isolation)
        assertNull(def.memory)
        assertNull(def.maxTurns)
        assertTrue(def.enabled)
    }

    // Regression: bundled explore.md carries `thinking-level`, which is not a data-class field.
    // With strict-mode kaml this silently dropped the whole frontmatter (tools + model + description).
    // Node-tree parsing must preserve them.
    @Test
    fun `bundled explore keeps its read-only tools and fast model`() {
        val explore = AgentDiscovery.discover(projectDir = null, globalDir = null)
            .first { it.name == "explore" }

        assertEquals(listOf("read", "grep", "find", "ls"), explore.tools)
        assertEquals(listOf("pi/smol"), explore.model)
        assertEquals("minimal", explore.thinkingLevel)
        assertEquals("Fast read-only codebase scout", explore.description)
        assertEquals(SpawnsPolicy.None, explore.spawns)
    }

    @Test
    fun `registry lookup is case-insensitive and hides disabled agents`() {
        val root = createTempDirectory("agent-registry")
        val projectAgents = root.resolve("agents").createDirectories()

        projectAgents.resolve("Reviewer.md").writeText(
            """
            ---
            name: Reviewer
            description: A reviewer
            ---
            Review.
            """.trimIndent(),
        )
        projectAgents.resolve("legacy.md").writeText(
            """
            ---
            name: legacy
            description: Disabled agent
            enabled: false
            ---
            Old.
            """.trimIndent(),
        )

        val registry = AgentRegistry(projectDir = projectAgents, globalDir = null)

        // Case-insensitive lookup on the spawnable set.
        assertEquals("Reviewer", registry.get("reviewer")?.name)
        assertEquals("Reviewer", registry.get("REVIEWER")?.name)

        // Disabled agent: absent from get/getAvailable, present in getAll.
        assertNull(registry.get("legacy"))
        assertFalse(registry.getAvailable().any { it.name == "legacy" })
        assertTrue(registry.getAll().any { it.name == "legacy" })
    }

    @Test
    fun `disableDefaultAgents hides bundled agents from the spawnable set`() {
        val registry = AgentRegistry(projectDir = null, globalDir = null, disableDefaultAgents = true)

        assertNull(registry.get("explore"))
        assertTrue(registry.getAvailable().isEmpty())
        // Still discoverable for management UIs.
        assertTrue(registry.getAll().any { it.name == "explore" })
    }
}
