package co.agentmode.agent47.coding.core.settings

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsManagerTest {
    @Test
    fun `inMemory returns provided settings`() {
        val settings = Settings(
            defaultProvider = "openai",
            defaultModel = "gpt-4",
        )
        val manager = SettingsManager.inMemory(settings)
        assertEquals("openai", manager.get().defaultProvider)
        assertEquals("gpt-4", manager.get().defaultModel)
    }

    @Test
    fun `inMemory returns defaults when no settings provided`() {
        val manager = SettingsManager.inMemory()
        assertNull(manager.get().defaultProvider)
        assertNull(manager.get().defaultModel)
        assertEquals(true, manager.get().retry.enabled)
    }

    @Test
    fun `create returns defaults when no files exist`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        val manager = SettingsManager.create(globalPath, projectPath)
        assertNull(manager.get().defaultProvider)
        assertNull(manager.get().defaultModel)
    }

    @Test
    fun `create loads global settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "defaultProvider": "anthropic",
              "defaultModel": "claude-sonnet-4-5"
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("anthropic", manager.get().defaultProvider)
        assertEquals("claude-sonnet-4-5", manager.get().defaultModel)
    }

    @Test
    fun `create loads project settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        projectPath.writeText(
            """
            {
              "defaultProvider": "openai",
              "defaultModel": "gpt-4.1-mini"
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("openai", manager.get().defaultProvider)
        assertEquals("gpt-4.1-mini", manager.get().defaultModel)
    }

    @Test
    fun `project settings override global settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "defaultProvider": "anthropic",
              "defaultModel": "claude-sonnet-4-5",
              "defaultThinkingLevel": "high"
            }
            """.trimIndent(),
        )

        projectPath.writeText(
            """
            {
              "defaultProvider": "openai"
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("openai", manager.get().defaultProvider)
        assertEquals("claude-sonnet-4-5", manager.get().defaultModel)
        assertEquals("high", manager.get().defaultThinkingLevel)
    }

    @Test
    fun `handles invalid json gracefully`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText("{ invalid json }")

        val manager = SettingsManager.create(globalPath, projectPath)
        assertNull(manager.get().defaultProvider)
    }

    @Test
    fun `loads compaction settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "compaction": {
                "enabled": false,
                "reserveTokens": 8192,
                "keepRecentTokens": 10000
              }
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals(false, manager.get().compaction.enabled)
        assertEquals(8192, manager.get().compaction.reserveTokens)
        assertEquals(10000, manager.get().compaction.keepRecentTokens)
    }

    @Test
    fun `loads retry settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "retry": {
                "enabled": false,
                "maxRetries": 5,
                "baseDelayMs": 2000,
                "maxDelayMs": 60000
              }
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals(false, manager.get().retry.enabled)
        assertEquals(5, manager.get().retry.maxRetries)
        assertEquals(2000L, manager.get().retry.baseDelayMs)
        assertEquals(60000L, manager.get().retry.maxDelayMs)
    }

    @Test
    fun `loads shell settings`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "shellPath": "/bin/zsh",
              "shellCommandPrefix": "set -e && "
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("/bin/zsh", manager.get().shellPath)
        assertEquals("set -e && ", manager.get().shellCommandPrefix)
    }

    @Test
    fun `modelRoles are merged as union of global and project`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "modelRoles": {
                "smol": "anthropic/claude-haiku-4-5",
                "slow": "anthropic/claude-opus-4-6"
              }
            }
            """.trimIndent(),
        )

        projectPath.writeText(
            """
            {
              "modelRoles": {
                "smol": "google/gemini-2.5-flash",
                "default": "openai/gpt-5.1-codex"
              }
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        val roles = manager.get().modelRoles
        // Project overrides global for "smol"
        assertEquals("google/gemini-2.5-flash", roles["smol"])
        // Global "slow" is preserved
        assertEquals("anthropic/claude-opus-4-6", roles["slow"])
        // Project adds "default"
        assertEquals("openai/gpt-5.1-codex", roles["default"])
    }

    @Test
    fun `project shell settings override global`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "shellPath": "/bin/zsh",
              "shellCommandPrefix": "set -e && "
            }
            """.trimIndent(),
        )

        projectPath.writeText(
            """
            {
              "shellPath": "/bin/bash"
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("/bin/bash", manager.get().shellPath)
        // Global shellCommandPrefix is preserved since project didn't set it
        assertEquals("set -e && ", manager.get().shellCommandPrefix)
    }

    @Test
    fun `loads taskMaxRecursionDepth`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "taskMaxRecursionDepth": 5
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals(5, manager.get().taskMaxRecursionDepth)
    }

    @Test
    fun `loads theme and showUsageFooter`() {
        val dir = createTempDirectory("settings-test")
        val globalPath = dir.resolve("global-settings.json")
        val projectPath = dir.resolve("project-settings.json")

        globalPath.writeText(
            """
            {
              "theme": "dark",
              "showUsageFooter": true
            }
            """.trimIndent(),
        )

        val manager = SettingsManager.create(globalPath, projectPath)
        assertEquals("dark", manager.get().theme)
        assertEquals(true, manager.get().showUsageFooter)
    }
}
