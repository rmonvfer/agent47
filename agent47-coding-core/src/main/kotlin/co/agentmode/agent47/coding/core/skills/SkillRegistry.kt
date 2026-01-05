package co.agentmode.agent47.coding.core.skills

import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

public class SkillRegistry(
    private val projectDir: Path?,
    private val globalDir: Path?,
) {
    private var skills: List<Skill> = emptyList()
    private var skillsByName: Map<String, Skill> = emptyMap()

    init {
        refresh()
    }

    public fun refresh() {
        skills = SkillDiscovery.discover(projectDir, globalDir)
        skillsByName = skills.associateBy { it.name }
    }

    public fun get(name: String): Skill? = skillsByName[name]

    public fun getAll(): List<Skill> = skills

    /**
     * Returns skills that are applicable given the current set of active file paths.
     * A skill is applicable if it has `alwaysApply = true`, or if any of its glob
     * patterns match at least one of the [activeFiles]. Skills with no globs and
     * `alwaysApply = false` are on-demand only and never returned here.
     *
     * Negation globs (prefixed with `!`) exclude files that would otherwise match.
     */
    public fun getApplicable(activeFiles: List<String>): List<Skill> {
        return skills.filter { skill ->
            if (skill.alwaysApply) return@filter true
            val globs = skill.globs
            if (globs.isNullOrEmpty()) return@filter false
            matchesAnyFile(globs, activeFiles)
        }
    }

    private fun matchesAnyFile(globs: List<String>, files: List<String>): Boolean {
        val includeMatchers = globs
            .filter { !it.startsWith("!") }
            .map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        val excludeMatchers = globs
            .filter { it.startsWith("!") }
            .map { FileSystems.getDefault().getPathMatcher("glob:${it.removePrefix("!")}") }

        return files.any { file ->
            val path = Path.of(file)
            val included = includeMatchers.any { it.matches(path) }
            val excluded = excludeMatchers.any { it.matches(path) }
            included && !excluded
        }
    }

    /**
     * Read a skill file. If [relativePath] is null, reads the SKILL.md.
     * If [relativePath] is provided, reads a file relative to the skill's base directory,
     * with path traversal protection.
     */
    public fun readSkillFile(name: String, relativePath: String? = null): String? {
        val skill = skillsByName[name] ?: return null

        if (relativePath == null) {
            return if (skill.path.exists()) skill.path.readText() else null
        }

        if (relativePath.contains("..") || Path.of(relativePath).isAbsolute) {
            return null
        }

        val target = skill.baseDir.resolve(relativePath).normalize()
        if (!target.startsWith(skill.baseDir)) {
            return null
        }

        return if (target.exists()) target.readText() else null
    }
}
