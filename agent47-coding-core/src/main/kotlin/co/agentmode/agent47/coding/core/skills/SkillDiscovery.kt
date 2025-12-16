package co.agentmode.agent47.coding.core.skills

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

@Serializable
internal data class SkillFrontmatter(
    val name: String? = null,
    val description: String? = null,
    val globs: List<String>? = null,
    val alwaysApply: Boolean = false,
)

public object SkillDiscovery {

    private val frontmatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n?(.*)$""", RegexOption.DOT_MATCHES_ALL)
    private const val SKILL_FILE = "SKILL.md"

    public fun discover(projectDir: Path?, globalDir: Path?): List<Skill> {
        val seen = mutableSetOf<String>()
        val skills = mutableListOf<Skill>()

        discoverFromDirectory(projectDir, SkillSource.PROJECT, seen, skills)
        discoverFromDirectory(globalDir, SkillSource.USER, seen, skills)

        return skills
    }

    private fun discoverFromDirectory(
        dir: Path?,
        source: SkillSource,
        seen: MutableSet<String>,
        out: MutableList<Skill>,
    ) {
        if (dir == null || !dir.exists()) return

        runCatching {
            Files.list(dir).use { stream ->
                stream
                    .filter { it.isDirectory() }
                    .sorted()
                    .forEach { skillDir ->
                        val skillFile = skillDir.resolve(SKILL_FILE)
                        if (skillFile.exists()) {
                            val content = skillFile.readText()
                            val skill = parseSkill(
                                content = content,
                                skillDir = skillDir,
                                skillFile = skillFile,
                                source = source,
                            )
                            if (skill.name !in seen) {
                                seen += skill.name
                                out += skill
                            }
                        }
                    }
            }
        }
    }

    private fun parseSkill(
        content: String,
        skillDir: Path,
        skillFile: Path,
        source: SkillSource,
    ): Skill {
        val match = frontmatterRegex.matchEntire(content.trimStart())
        val yamlBlock = match?.groupValues?.get(1)?.trim().orEmpty()
        val body = match?.groupValues?.get(2)?.trim() ?: content.trim()

        val frontmatter = if (yamlBlock.isNotBlank()) {
            runCatching {
                Yaml.default.decodeFromString(SkillFrontmatter.serializer(), yamlBlock)
            }.getOrElse { SkillFrontmatter() }
        } else {
            SkillFrontmatter()
        }

        val name = frontmatter.name ?: skillDir.name
        val description = frontmatter.description ?: name

        return Skill(
            name = name,
            description = description,
            path = skillFile.toAbsolutePath(),
            baseDir = skillDir.toAbsolutePath(),
            content = body,
            source = source,
            globs = frontmatter.globs,
            alwaysApply = frontmatter.alwaysApply,
        )
    }
}
