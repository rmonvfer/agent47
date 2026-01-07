package co.agentmode.agent47.coding.core.skills

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillDiscoveryTest {

    @Test
    fun `discovers skills from project directory`() {
        val root = createTempDirectory("skill-discovery")
        val projectSkills = root.resolve("project/skills").createDirectories()
        projectSkills.resolve("kotlin-tips").createDirectories()
        projectSkills.resolve("kotlin-tips/SKILL.md").writeText(
            """
            ---
            name: kotlin-tips
            description: Kotlin best practices
            ---
            Use data classes for value types.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = projectSkills, globalDir = null)
        assertEquals(1, skills.size)
        assertEquals("kotlin-tips", skills[0].name)
        assertEquals("Kotlin best practices", skills[0].description)
        assertEquals(SkillSource.PROJECT, skills[0].source)
    }

    @Test
    fun `discovers skills from global directory`() {
        val root = createTempDirectory("skill-discovery")
        val globalSkills = root.resolve("global/skills").createDirectories()
        globalSkills.resolve("git-workflow").createDirectories()
        globalSkills.resolve("git-workflow/SKILL.md").writeText(
            """
            ---
            name: git-workflow
            description: Git workflow conventions
            ---
            Use conventional commits.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = null, globalDir = globalSkills)
        assertEquals(1, skills.size)
        assertEquals("git-workflow", skills[0].name)
        assertEquals(SkillSource.USER, skills[0].source)
    }

    @Test
    fun `project skills override global skills with same name`() {
        val root = createTempDirectory("skill-discovery")
        val projectSkills = root.resolve("project/skills").createDirectories()
        val globalSkills = root.resolve("global/skills").createDirectories()

        globalSkills.resolve("testing").createDirectories()
        globalSkills.resolve("testing/SKILL.md").writeText(
            """
            ---
            name: testing
            description: Global testing skill
            ---
            Global testing content.
            """.trimIndent(),
        )

        projectSkills.resolve("testing").createDirectories()
        projectSkills.resolve("testing/SKILL.md").writeText(
            """
            ---
            name: testing
            description: Project testing skill
            ---
            Project testing content.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = projectSkills, globalDir = globalSkills)
        val testingSkills = skills.filter { it.name == "testing" }
        assertEquals(1, testingSkills.size)
        assertEquals("Project testing skill", testingSkills[0].description)
        assertEquals(SkillSource.PROJECT, testingSkills[0].source)
    }

    @Test
    fun `returns empty list when no directories exist`() {
        val skills = SkillDiscovery.discover(projectDir = null, globalDir = null)
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `handles missing directories gracefully`() {
        val root = createTempDirectory("skill-discovery")
        val nonexistent = root.resolve("does-not-exist/skills")

        val skills = SkillDiscovery.discover(projectDir = nonexistent, globalDir = null)
        assertTrue(skills.isEmpty())
    }

    @Test
    fun `ignores directories without SKILL md`() {
        val root = createTempDirectory("skill-discovery")
        val projectSkills = root.resolve("project/skills").createDirectories()
        projectSkills.resolve("incomplete").createDirectories()
        projectSkills.resolve("incomplete/README.md").writeText("Not a skill file.")

        projectSkills.resolve("valid").createDirectories()
        projectSkills.resolve("valid/SKILL.md").writeText(
            """
            ---
            name: valid
            description: A valid skill
            ---
            Content.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = projectSkills, globalDir = null)
        assertEquals(1, skills.size)
        assertEquals("valid", skills[0].name)
    }

    @Test
    fun `ignores files that are not directories`() {
        val root = createTempDirectory("skill-discovery")
        val projectSkills = root.resolve("project/skills").createDirectories()
        projectSkills.resolve("not-a-dir.md").writeText("Just a file.")

        projectSkills.resolve("real-skill").createDirectories()
        projectSkills.resolve("real-skill/SKILL.md").writeText("Skill content without frontmatter.")

        val skills = SkillDiscovery.discover(projectDir = projectSkills, globalDir = null)
        assertEquals(1, skills.size)
        assertEquals("real-skill", skills[0].name)
    }

    @Test
    fun `parses globs and alwaysApply from frontmatter`() {
        val root = createTempDirectory("skill-discovery")
        val dir = root.resolve("skills").createDirectories()
        dir.resolve("python").createDirectories()
        dir.resolve("python/SKILL.md").writeText(
            """
            ---
            name: python
            description: Python conventions
            globs:
              - "**/*.py"
              - "!**/venv/**"
            alwaysApply: true
            ---
            Use type hints.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = dir, globalDir = null)
        assertEquals(1, skills.size)
        assertEquals(listOf("**/*.py", "!**/venv/**"), skills[0].globs)
        assertEquals(true, skills[0].alwaysApply)
    }

    @Test
    fun `uses directory name as fallback when no frontmatter name`() {
        val root = createTempDirectory("skill-discovery")
        val dir = root.resolve("skills").createDirectories()
        dir.resolve("my-skill").createDirectories()
        dir.resolve("my-skill/SKILL.md").writeText("Plain content, no frontmatter.")

        val skills = SkillDiscovery.discover(projectDir = dir, globalDir = null)
        assertEquals(1, skills.size)
        assertEquals("my-skill", skills[0].name)
    }

    @Test
    fun `discovers skills from both project and global without conflicts`() {
        val root = createTempDirectory("skill-discovery")
        val projectSkills = root.resolve("project/skills").createDirectories()
        val globalSkills = root.resolve("global/skills").createDirectories()

        projectSkills.resolve("project-only").createDirectories()
        projectSkills.resolve("project-only/SKILL.md").writeText(
            """
            ---
            name: project-only
            description: Project-only skill
            ---
            Content.
            """.trimIndent(),
        )

        globalSkills.resolve("global-only").createDirectories()
        globalSkills.resolve("global-only/SKILL.md").writeText(
            """
            ---
            name: global-only
            description: Global-only skill
            ---
            Content.
            """.trimIndent(),
        )

        val skills = SkillDiscovery.discover(projectDir = projectSkills, globalDir = globalSkills)
        assertEquals(2, skills.size)
        assertTrue(skills.any { it.name == "project-only" && it.source == SkillSource.PROJECT })
        assertTrue(skills.any { it.name == "global-only" && it.source == SkillSource.USER })
    }
}
