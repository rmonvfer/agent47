package co.agentmode.agent47.coding.core.tools

import co.agentmode.agent47.ai.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadToolSkillProtocolTest {

    private val skillContent = mapOf(
        "my-skill" to mapOf(
            null to "# My Skill\nThis is the SKILL.md content.\nLine 3.",
            "docs/readme.md" to "Readme content here.",
        ),
    )

    private val skillReader = SkillReader { name, relativePath ->
        skillContent[name]?.get(relativePath)
    }

    @Test
    fun `reads skill content via skill protocol`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader)
        val params = buildJsonObject { put("path", "skill://my-skill") }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("# My Skill"))
    }

    @Test
    fun `reads skill relative path`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader)
        val params = buildJsonObject { put("path", "skill://my-skill/docs/readme.md") }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertEquals("Readme content here.", text)
    }

    @Test
    fun `returns error for unknown skill`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader)
        val params = buildJsonObject { put("path", "skill://nonexistent") }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("Skill not found"))
    }

    @Test
    fun `returns error when skill reader is not available`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader = null)
        val params = buildJsonObject { put("path", "skill://my-skill") }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("not available"))
    }

    @Test
    fun `supports offset and limit on skill content`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader)
        val params = buildJsonObject {
            put("path", "skill://my-skill")
            put("offset", 2)
            put("limit", 1)
        }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertEquals("This is the SKILL.md content.", text)
    }

    @Test
    fun `returns error for offset beyond skill content`() = runTest {
        val tool = ReadTool(createTempDirectory("read-test"), skillReader)
        val params = buildJsonObject {
            put("path", "skill://my-skill")
            put("offset", 999)
        }
        val result = tool.execute("r1", params)
        val text = result.content.filterIsInstance<TextContent>().joinToString { it.text }
        assertTrue(text.contains("beyond"))
    }

    @Test
    fun `includes file-scoped skill instructions when reading a matching file`() = runTest {
        val root = createTempDirectory("read-test")
        root.resolve("App.kt").toFile().writeText("fun main() = Unit")
        val reader = object : SkillReader {
            override fun readSkillFile(name: String, relativePath: String?): String? = null

            override fun readApplicableSkills(path: String): List<ApplicableSkill> =
                if (path.endsWith(".kt")) {
                    listOf(ApplicableSkill("kotlin-style", "Use explicit visibility."))
                } else {
                    emptyList()
                }
        }
        val tool = ReadTool(root, reader)

        val result = tool.execute("r1", buildJsonObject { put("path", "App.kt") })
        val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

        assertTrue(text.contains("Applicable skill instructions (kotlin-style)"))
        assertTrue(text.contains("Use explicit visibility."))
        assertTrue(text.contains("fun main() = Unit"))
    }
}
