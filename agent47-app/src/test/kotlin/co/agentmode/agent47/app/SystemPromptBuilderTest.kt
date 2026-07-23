package co.agentmode.agent47.app

import co.agentmode.agent47.coding.core.skills.Skill
import co.agentmode.agent47.coding.core.skills.SkillSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptBuilderTest {
    @Test
    fun `always-applied skill content is embedded while other skills stay lazy`() {
        val always = skill("project-rules", alwaysApply = true, content = "Use the project conventions.")
        val scoped = skill("kotlin", globs = listOf("**/*.kt"), content = "Use Kotlin conventions.")

        val prompt = buildSystemPrompt(
            cwd = Path.of("/project"),
            toolNames = listOf("read"),
            customPrompt = null,
            appendPrompt = null,
            skills = listOf(always, scoped),
            dateTime = "Monday, January 1, 2024, 01:00:00 PM UTC",
        )

        assertTrue(prompt.contains("<applied-skill name=\"project-rules\">"))
        assertTrue(prompt.contains("Use the project conventions."))
        assertTrue(prompt.contains("globs=\"**/*.kt\""))
        assertFalse(prompt.contains("Use Kotlin conventions."))
    }

    private fun skill(
        name: String,
        alwaysApply: Boolean = false,
        globs: List<String>? = null,
        content: String,
    ): Skill = Skill(
        name = name,
        description = "$name description",
        path = Path.of("/$name/SKILL.md"),
        baseDir = Path.of("/$name"),
        content = content,
        source = SkillSource.PROJECT,
        globs = globs,
        alwaysApply = alwaysApply,
    )
}
