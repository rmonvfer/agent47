package co.agentmode.agent47.coding.core.agents

import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMemoryTest {

    @Test
    fun `buildMemoryBlock creates the directory and describes the scope`() {
        val root = createTempDirectory("agent-memory")
        val projectDir = root.resolve("project/.agent47")
        val globalDir = root.resolve("global/.agent47")

        val block = AgentMemory.buildMemoryBlock("reviewer", MemoryScope.PROJECT, projectDir, globalDir)

        val memoryDir = projectDir.resolve("agent-memory/reviewer")
        assertTrue(memoryDir.exists(), "memory dir should be created")
        assertTrue(block.contains("project"), "block should mention the scope")
        assertTrue(block.contains("MEMORY.md"), "block should reference MEMORY.md")
    }

    @Test
    fun `buildReadOnlyMemoryBlock does not create the directory`() {
        val root = createTempDirectory("agent-memory")
        val projectDir = root.resolve("project/.agent47")
        val globalDir = root.resolve("global/.agent47")

        val block = AgentMemory.buildReadOnlyMemoryBlock("scout", MemoryScope.USER, projectDir, globalDir)

        val memoryDir = globalDir.resolve("agent-memory/scout")
        assertFalse(memoryDir.exists(), "read-only block must not create the dir")
        assertTrue(block.contains("read-only"))
    }

    @Test
    fun `existing MEMORY_md is surfaced in the block`() {
        val root = createTempDirectory("agent-memory")
        val projectDir = root.resolve("project/.agent47")
        val globalDir = root.resolve("global/.agent47")
        val memoryDir = projectDir.resolve("agent-memory/keeper")
        java.nio.file.Files.createDirectories(memoryDir)
        memoryDir.resolve("MEMORY.md").writeText("- remembered fact\n")

        val block = AgentMemory.buildMemoryBlock("keeper", MemoryScope.PROJECT, projectDir, globalDir)
        assertTrue(block.contains("remembered fact"))
    }

    @Test
    fun `unsafe names are rejected`() {
        assertTrue(AgentMemory.isUnsafeName(""))
        assertTrue(AgentMemory.isUnsafeName("../escape"))
        assertTrue(AgentMemory.isUnsafeName(".hidden"))
        assertFalse(AgentMemory.isUnsafeName("valid-name_1"))
    }

    @Test
    fun `readMemoryIndex caps at 200 lines`() {
        val root = createTempDirectory("agent-memory")
        val memoryDir = root.resolve("m")
        java.nio.file.Files.createDirectories(memoryDir)
        memoryDir.resolve("MEMORY.md").writeText((1..500).joinToString("\n") { "line $it" })

        val index = AgentMemory.readMemoryIndex(memoryDir)
        assertNull(index?.let { if (it.contains("line 300")) it else null }, "content past 200 lines must be cut")
        assertTrue(index!!.contains("truncated"))
    }
}
