package co.agentmode.agent47.tui.theme

import com.jakewharton.mosaic.ui.Color
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class PackageThemeTest {
    @Test
    fun `loads package theme name and appearance variants`() {
        val directory = createTempDirectory("agent47-theme")
        val themeFile = directory.resolve("company.kintsugi.json")
        themeFile.writeText(
            """
            {
              "theme": {
                "primary": { "dark": "#112233", "light": "#445566" },
                "background": { "dark": "#000000", "light": "#ffffff" }
              }
            }
            """.trimIndent(),
        )

        val theme = loadNamedTheme(themeFile)

        assertEquals("company.kintsugi", theme.name)
        assertEquals(Color(0x11, 0x22, 0x33), theme.config.colors.accent)
        assertEquals(Color(0x44, 0x55, 0x66), theme.lightConfig.colors.accent)
    }
}
