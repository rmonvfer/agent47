package co.agentmode.agent47.coding.core.agents

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Persistent agent memory: per-agent memory directories that persist across sessions.
 *
 * Memory scopes resolve to directories under the project or global agent47 directory:
 *   - [MemoryScope.USER]    -> `globalDir/agent-memory/<agentName>/`
 *   - [MemoryScope.PROJECT] -> `projectDir/agent-memory/<agentName>/`
 *   - [MemoryScope.LOCAL]   -> `projectDir/agent-memory-local/<agentName>/`
 */
public object AgentMemory {

    /** Maximum number of lines read from MEMORY.md before truncation. */
    private const val MAX_MEMORY_LINES = 200

    private val safeNameRegex = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]*$")

    /**
     * Returns true if a name contains characters not allowed in agent/skill names.
     * Uses a whitelist: only alphanumeric, hyphens, underscores, and dots (no leading dot).
     */
    public fun isUnsafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 128) return true
        return !safeNameRegex.matches(name)
    }

    /**
     * Returns true if the given path is a symlink (defense against symlink attacks).
     */
    public fun isSymlink(path: Path): Boolean =
        runCatching { Files.isSymbolicLink(path) }.getOrDefault(false)

    /**
     * Safely read a file, rejecting symlinks.
     * Returns null if the file doesn't exist, is a symlink, or can't be read.
     */
    public fun safeReadFile(filePath: Path): String? {
        if (!filePath.exists()) return null
        if (isSymlink(filePath)) return null
        return runCatching { filePath.readText() }.getOrNull()
    }

    /**
     * Resolve the memory directory path for a given agent, scope, and directory pair.
     * Throws [IllegalArgumentException] if [agentName] contains path traversal characters.
     */
    public fun resolveMemoryDir(
        agentName: String,
        scope: MemoryScope,
        projectDir: Path,
        globalDir: Path,
    ): Path {
        require(!isUnsafeName(agentName)) { "Unsafe agent name for memory directory: \"$agentName\"" }
        return when (scope) {
            MemoryScope.USER -> globalDir.resolve("agent-memory").resolve(agentName)
            MemoryScope.PROJECT -> projectDir.resolve("agent-memory").resolve(agentName)
            MemoryScope.LOCAL -> projectDir.resolve("agent-memory-local").resolve(agentName)
        }
    }

    /**
     * Ensure the memory directory exists, creating it if needed.
     * Refuses to use a directory that is a symlink to prevent symlink-based
     * directory traversal attacks.
     */
    public fun ensureMemoryDir(memoryDir: Path) {
        if (memoryDir.exists()) {
            if (isSymlink(memoryDir)) {
                throw IllegalStateException("Refusing to use symlinked memory directory: $memoryDir")
            }
            return
        }
        Files.createDirectories(memoryDir)
    }

    /**
     * Read the first [MAX_MEMORY_LINES] lines of MEMORY.md from the memory directory, if it exists.
     * Returns null if no MEMORY.md exists or if the directory or file is a symlink.
     */
    public fun readMemoryIndex(memoryDir: Path): String? {
        if (isSymlink(memoryDir)) return null

        val memoryFile = memoryDir.resolve("MEMORY.md")
        val content = safeReadFile(memoryFile) ?: return null

        val lines = content.split("\n")
        if (lines.size > MAX_MEMORY_LINES) {
            return lines.take(MAX_MEMORY_LINES).joinToString("\n") + "\n... (truncated at 200 lines)"
        }
        return content
    }

    /**
     * Build the memory block to inject into the agent's system prompt.
     * Also ensures the memory directory exists (creating it if needed) so the agent
     * can immediately write to it.
     */
    public fun buildMemoryBlock(
        agentName: String,
        scope: MemoryScope,
        projectDir: Path,
        globalDir: Path,
    ): String {
        val memoryDir = resolveMemoryDir(agentName, scope, projectDir, globalDir)
        // Create the memory directory so the agent can immediately write to it
        ensureMemoryDir(memoryDir)

        val existingMemory = readMemoryIndex(memoryDir)
        val scopeLabel = scope.name.lowercase()

        val header = """
            # Agent Memory

            You have a persistent memory directory at: $memoryDir/
            Memory scope: $scopeLabel

            This memory persists across sessions. Use it to build up knowledge over time.
        """.trimIndent()

        val memoryContent = if (existingMemory != null) {
            "\n\n## Current MEMORY.md\n$existingMemory"
        } else {
            "\n\nNo MEMORY.md exists yet. Create one at ${memoryDir.resolve("MEMORY.md")} to start building persistent memory."
        }

        val instructions = "\n\n" + """
            ## Memory Instructions
            - MEMORY.md is an index file — keep it concise (under 200 lines). Lines after 200 are truncated.
            - Store detailed memories in separate files within $memoryDir/ and link to them from MEMORY.md.
            - Each memory file should use this frontmatter format:
              ```markdown
              ---
              name: <memory name>
              description: <one-line description>
              type: <user|feedback|project|reference>
              ---
              <memory content>
              ```
            - Update or remove memories that become outdated. Check for existing memories before creating duplicates.
            - You have Read, Write, and Edit tools available for managing memory files.
        """.trimIndent()

        return header + memoryContent + instructions
    }

    /**
     * Build a read-only memory block for agents that lack write/edit tools.
     * Reads existing MEMORY.md but does NOT create the memory directory — agents can
     * only consume existing memory.
     */
    public fun buildReadOnlyMemoryBlock(
        agentName: String,
        scope: MemoryScope,
        projectDir: Path,
        globalDir: Path,
    ): String {
        val memoryDir = resolveMemoryDir(agentName, scope, projectDir, globalDir)
        val existingMemory = readMemoryIndex(memoryDir)
        val scopeLabel = scope.name.lowercase()

        val header = """
            # Agent Memory (read-only)

            Memory scope: $scopeLabel
            You have read-only access to memory. You can reference existing memories but cannot create or modify them.
        """.trimIndent()

        val memoryContent = if (existingMemory != null) {
            "\n\n## Current MEMORY.md\n$existingMemory"
        } else {
            "\n\nNo memory is available yet. Other agents or sessions with write access can create memories for you to consume."
        }

        return header + memoryContent
    }
}
