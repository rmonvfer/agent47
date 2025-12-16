package co.agentmode.agent47.coding.core.skills

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
