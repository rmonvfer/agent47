package co.agentmode.agent47.tui.components

import co.agentmode.agent47.coding.core.skills.Skill
import co.agentmode.agent47.coding.core.skills.SkillSource
import co.agentmode.agent47.tui.theme.ThemeConfig
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupSummaryTest {
    private val home = Path.of(System.getProperty("user.home"))
    private val cwd = home.resolve("code/agent47")
    private val summary = StartupSummary(
        version = "1.2.3",
        cwd = cwd,
        contextFiles = listOf(home.resolve("AGENTS.md"), cwd.resolve("AGENTS.md")),
        skills = listOf(skill("mongodb", home.resolve(".agent47/skills/mongodb/SKILL.md"))),
        extensionIds = listOf("provider-kimi"),
    )

    @Test
    fun `collapsed summary shows compact loaded resources`() {
        val lines = renderStartupSummary(summary, width = 100, expanded = false, theme = ThemeConfig())
            .map { it.text }

        assertTrue(lines.contains("agent47 v1.2.3"))
        assertTrue(lines.contains("[Context]"))
        assertTrue(lines.contains("  ~${home.fileSystem.separator}AGENTS.md, AGENTS.md"))
        assertTrue(lines.contains("[Skills]"))
        assertTrue(lines.contains("  mongodb"))
        assertTrue(lines.contains("[Extensions]"))
        assertTrue(lines.contains("  provider-kimi"))
        assertTrue(lines.any { it.contains("escape interrupt/double clear") })
        assertTrue(lines.any { it.contains("Press ctrl+o") })
        assertFalse(lines.any { it.contains("SKILL.md") })
    }

    @Test
    fun `expanded summary shows full help and resource paths`() {
        val lines = renderStartupSummary(summary, width = 100, expanded = true, theme = ThemeConfig())
            .map { it.text }

        assertTrue(lines.any { it.contains("ctrl+p/ctrl+n") })
        assertTrue(lines.any { it.contains("press twice to clear input") })
        assertTrue(lines.any { it.contains("/settings") })
        val skillPath = listOf("~", ".agent47", "skills", "mongodb", "SKILL.md")
            .joinToString(home.fileSystem.separator)
        assertTrue(lines.any { it.contains("mongodb  $skillPath") })
        assertFalse(lines.any { it.contains("Press ctrl+o to show") })
    }

    @Test
    fun `summary content wraps within its assigned width`() {
        listOf(1, 2, 24).forEach { width ->
            val lines = renderStartupSummary(summary, width = width, expanded = false, theme = ThemeConfig())
            assertTrue(lines.all { it.text.length <= width })
        }
    }

    private fun skill(name: String, path: Path): Skill = Skill(
        name = name,
        description = name,
        path = path,
        baseDir = path.parent,
        content = "",
        source = SkillSource.USER,
        globs = null,
        alwaysApply = false,
    )
}
