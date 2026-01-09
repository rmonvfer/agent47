package co.agentmode.agent47.coding.core.instructions

import co.agentmode.agent47.coding.core.settings.Settings
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

public object InstructionDiscovery {

    private val FILE_NAMES = listOf("AGENTS.md", "AGENT47.md", "CLAUDE.md")

    public fun discover(
        cwd: Path,
        globalDir: Path,
        claudeDir: Path?,
        settings: Settings,
    ): List<InstructionFile> {
        val seen = mutableSetOf<Path>()
        val results = mutableListOf<InstructionFile>()

        val gitRoot = findGitRoot(cwd)

        val projectFiles = findUp(FILE_NAMES, cwd, gitRoot)
        for (path in projectFiles) {
            val abs = path.toAbsolutePath().normalize()
            if (seen.add(abs)) {
                results += InstructionFile(
                    path = abs,
                    content = abs.readText(),
                    source = InstructionSource.PROJECT,
                )
            }
        }

        val globalFile = globalDir.resolve("AGENTS.md")
        if (globalFile.exists() && globalFile.isRegularFile()) {
            val abs = globalFile.toAbsolutePath().normalize()
            if (seen.add(abs)) {
                results += InstructionFile(
                    path = abs,
                    content = abs.readText(),
                    source = InstructionSource.GLOBAL,
                )
            }
        }

        if (claudeDir != null) {
            val claudeFile = claudeDir.resolve("CLAUDE.md")
            if (claudeFile.exists() && claudeFile.isRegularFile()) {
                val abs = claudeFile.toAbsolutePath().normalize()
                if (seen.add(abs)) {
                    results += InstructionFile(
                        path = abs,
                        content = abs.readText(),
                        source = InstructionSource.CLAUDE_CODE,
                    )
                }
            }
        }

        for (pattern in settings.instructions) {
            val resolved = resolveGlob(pattern, cwd)
            for (path in resolved) {
                val abs = path.toAbsolutePath().normalize()
                if (abs.exists() && abs.isRegularFile() && seen.add(abs)) {
                    results += InstructionFile(
                        path = abs,
                        content = abs.readText(),
                        source = InstructionSource.SETTINGS,
                    )
                }
            }
        }

        return results
    }

    public fun findUp(fileNames: List<String>, start: Path, stop: Path?): List<Path> {
        val stopNormalized = stop?.toAbsolutePath()?.normalize()
        var current: Path? = start.toAbsolutePath().normalize()

        while (current != null) {
            val dir = current
            val matches = fileNames.mapNotNull { name ->
                val candidate = dir.resolve(name)
                if (candidate.exists() && candidate.isRegularFile()) candidate else null
            }
            if (matches.isNotEmpty()) return matches

            if (stopNormalized != null && dir == stopNormalized) break
            current = dir.parent
        }

        return emptyList()
    }

    public fun resolveGlob(pattern: String, cwd: Path): List<Path> {
        val expanded = if (pattern.startsWith("~/")) {
            Path(System.getProperty("user.home") + pattern.substring(1))
        } else if (!Path(pattern).isAbsolute) {
            cwd.resolve(pattern)
        } else {
            Path(pattern)
        }

        val expandedStr = expanded.toString()
        if (!containsGlobChars(expandedStr)) {
            return if (expanded.exists() && expanded.isRegularFile()) listOf(expanded) else emptyList()
        }

        val separatorIndex = findGlobStart(expandedStr)
        val baseDir = Path(expandedStr.substring(0, separatorIndex)).let { base ->
            if (base.toString().isEmpty()) cwd else base
        }

        if (!baseDir.exists()) return emptyList()

        val globPattern = expandedStr.substring(separatorIndex)
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$globPattern")

        return runCatching {
            Files.walk(baseDir).use { stream ->
                stream
                    .filter { it.isRegularFile() }
                    .filter { matcher.matches(baseDir.relativize(it)) }
                    .sorted()
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun findGitRoot(start: Path): Path? {
        var current: Path? = start.toAbsolutePath().normalize()
        while (current != null) {
            if (current.resolve(".git").exists()) return current
            current = current.parent
        }
        return null
    }

    private fun containsGlobChars(path: String): Boolean =
        path.contains('*') || path.contains('?') || path.contains('[')

    private fun findGlobStart(path: String): Int {
        val separator = path.fileSystemSeparator()
        for (i in path.indices) {
            if (path[i] == '*' || path[i] == '?' || path[i] == '[') {
                return path.lastIndexOf(separator, i).let { if (it < 0) 0 else it + 1 }
            }
        }
        return path.length
    }

    private fun String.fileSystemSeparator(): Char = java.io.File.separatorChar
}
