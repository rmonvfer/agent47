package co.agentmode.agent47.coding.core.settings

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class SubagentsSettingsTest {

    @Test
    fun `missing files yield defaults`() {
        val root = createTempDirectory("subagents-settings")
        val settings = SubagentsSettingsManager.load(
            globalPath = root.resolve("global.json"),
            projectPath = root.resolve("project.json"),
        )
        assertEquals(SubagentsSettings(), settings)
    }

    @Test
    fun `project overrides global, clamps ranges, and validates enums`() {
        val root = createTempDirectory("subagents-settings")
        val global = root.resolve("global.json")
        val project = root.resolve("project.json")

        global.writeText(
            """
            { "maxConcurrent": 8, "graceTurns": 3, "defaultJoinMode": "async", "widgetMode": "all" }
            """.trimIndent(),
        )
        project.writeText(
            """
            { "maxConcurrent": 5000, "widgetMode": "bogus", "toolDescriptionMode": "compact", "pushNotifications": true }
            """.trimIndent(),
        )

        val settings = SubagentsSettingsManager.load(global, project)

        assertEquals(1024, settings.maxConcurrent) // project 5000 clamped to ceiling
        assertEquals(3, settings.graceTurns) // from global
        assertEquals("async", settings.defaultJoinMode) // from global
        assertEquals("all", settings.widgetMode) // project "bogus" invalid -> falls back to global
        assertEquals("compact", settings.toolDescriptionMode) // from project
        assertEquals(true, settings.pushNotifications) // from project
        assertEquals(true, settings.schedulingEnabled) // untouched default
    }

    @Test
    fun `malformed file falls back to defaults`() {
        val root = createTempDirectory("subagents-settings")
        val project = root.resolve("project.json")
        project.writeText("{ not valid json ")

        val settings = SubagentsSettingsManager.load(root.resolve("global.json"), project)
        assertEquals(SubagentsSettings(), settings)
    }
}
