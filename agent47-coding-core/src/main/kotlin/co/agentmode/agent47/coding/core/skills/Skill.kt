package co.agentmode.agent47.coding.core.skills

import java.nio.file.Path

public data class Skill(
    val name: String,
    val description: String,
    val path: Path,
    val baseDir: Path,
    val content: String,
    val source: SkillSource,
    val globs: List<String>?,
    val alwaysApply: Boolean,
)

public enum class SkillSource { USER, PROJECT }
