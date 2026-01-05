package co.agentmode.agent47.coding.core.skills

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillFilteringTest {

    private fun createSkillDir(
        root: java.nio.file.Path,
        name: String,
        description: String = name,
        globs: List<String>? = null,
        alwaysApply: Boolean = false,
        content: String = "Skill content for $name.",
    ): java.nio.file.Path {
        val dir = root.resolve(name).createDirectories()
        val frontmatter = buildString {
            appendLine("---")
            appendLine("name: $name")
            appendLine("description: $description")
            if (globs != null) {
                appendLine("globs:")
                globs.forEach { appendLine("  - \"$it\"") }
            }
            if (alwaysApply) {
                appendLine("alwaysApply: true")
            }
            appendLine("---")
            appendLine()
            appendLine(content)
        }
        dir.resolve("SKILL.md").writeText(frontmatter)
        return dir
    }

    @Test
    fun `getApplicable returns alwaysApply skills regardless of active files`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "always-on", alwaysApply = true)
        createSkillDir(skillsDir, "python", globs = listOf("**/*.py"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)
        val applicable = registry.getApplicable(activeFiles = emptyList())

        assertEquals(1, applicable.size)
        assertEquals("always-on", applicable[0].name)
    }

    @Test
    fun `getApplicable returns skills matching active file globs`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "python", globs = listOf("**/*.py"))
        createSkillDir(skillsDir, "kotlin", globs = listOf("**/*.kt"))
        createSkillDir(skillsDir, "rust", globs = listOf("**/*.rs"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)
        val applicable = registry.getApplicable(activeFiles = listOf("src/main.py", "tests/test_app.py"))

        assertEquals(1, applicable.size)
        assertEquals("python", applicable[0].name)
    }

    @Test
    fun `getApplicable returns both alwaysApply and glob-matched skills`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "coding-standards", alwaysApply = true)
        createSkillDir(skillsDir, "kotlin", globs = listOf("**/*.kt"))
        createSkillDir(skillsDir, "python", globs = listOf("**/*.py"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)
        val applicable = registry.getApplicable(activeFiles = listOf("src/App.kt"))

        assertEquals(2, applicable.size)
        val names = applicable.map { it.name }.toSet()
        assertTrue("coding-standards" in names)
        assertTrue("kotlin" in names)
    }

    @Test
    fun `getApplicable excludes glob skills when no files match`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "python", globs = listOf("**/*.py"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)
        val applicable = registry.getApplicable(activeFiles = listOf("src/App.kt"))

        assertTrue(applicable.isEmpty())
    }

    @Test
    fun `getApplicable returns skills without globs and without alwaysApply as on-demand only`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "general-tips")
        createSkillDir(skillsDir, "always-on", alwaysApply = true)

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)
        val applicable = registry.getApplicable(activeFiles = emptyList())

        // Only alwaysApply skills are returned; on-demand skills (no globs, no alwaysApply) are excluded
        assertEquals(1, applicable.size)
        assertEquals("always-on", applicable[0].name)
    }

    @Test
    fun `getApplicable handles negation globs`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "python-app", globs = listOf("**/*.py", "!**/test_*.py"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)

        val withAppFile = registry.getApplicable(activeFiles = listOf("src/app.py"))
        assertEquals(1, withAppFile.size)

        val withTestFile = registry.getApplicable(activeFiles = listOf("tests/test_app.py"))
        assertTrue(withTestFile.isEmpty())
    }

    @Test
    fun `getApplicable matches multiple glob patterns`() {
        val root = createTempDirectory("skill-filter")
        val skillsDir = root.resolve("skills").createDirectories()

        createSkillDir(skillsDir, "jvm", globs = listOf("**/*.kt", "**/*.java", "**/*.scala"))

        val registry = SkillRegistry(projectDir = skillsDir, globalDir = null)

        val kotlinMatch = registry.getApplicable(activeFiles = listOf("src/Main.kt"))
        assertEquals(1, kotlinMatch.size)

        val javaMatch = registry.getApplicable(activeFiles = listOf("src/Main.java"))
        assertEquals(1, javaMatch.size)

        val noMatch = registry.getApplicable(activeFiles = listOf("src/main.py"))
        assertTrue(noMatch.isEmpty())
    }
}
