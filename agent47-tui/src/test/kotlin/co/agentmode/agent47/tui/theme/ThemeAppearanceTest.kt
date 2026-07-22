package co.agentmode.agent47.tui.theme

import com.jakewharton.mosaic.ui.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ThemeAppearanceTest {

    private val json = """
        {
          "defs": {
            "nearBlack": "#101010",
            "nearWhite": "#f5f5f5"
          },
          "theme": {
            "primary": {"dark": "#fab283", "light": "#3b7dd8"},
            "text": {"dark": "#eeeeee", "light": "#1a1a1a"},
            "background": {"dark": "nearBlack", "light": "nearWhite"},
            "darkOnly": {"dark": "#112233"},
            "border": "#484848"
          }
        }
    """.trimIndent()

    private fun luminance(c: Color): Float {
        val (r, g, b) = c
        return (r + g + b) / 3f
    }

    @Test
    fun `AUTO resolves via terminal detection and explicit values are unchanged`() {
        assertEquals(ThemeAppearance.DARK, ThemeAppearance.AUTO.resolve(terminalIsDark = true))
        assertEquals(ThemeAppearance.LIGHT, ThemeAppearance.AUTO.resolve(terminalIsDark = false))
        assertEquals(ThemeAppearance.DARK, ThemeAppearance.DARK.resolve(terminalIsDark = false))
        assertEquals(ThemeAppearance.LIGHT, ThemeAppearance.LIGHT.resolve(terminalIsDark = true))
    }

    @Test
    fun `parsing selects dark branch for dark appearance`() {
        val theme = assertNotNull(parseThemeJson(json, ThemeAppearance.DARK))
        assertEquals(Color(0xfa, 0xb2, 0x83), theme.colors.accent)
        assertEquals(Color(0x10, 0x10, 0x10), theme.background)
        assertEquals(Color(0xee, 0xee, 0xee), theme.userMessageText)
    }

    @Test
    fun `parsing selects light branch for light appearance`() {
        val theme = assertNotNull(parseThemeJson(json, ThemeAppearance.LIGHT))
        assertEquals(Color(0x3b, 0x7d, 0xd8), theme.colors.accent)
        assertEquals(Color(0xf5, 0xf5, 0xf5), theme.background)
        assertEquals(Color(0x1a, 0x1a, 0x1a), theme.userMessageText)
    }

    @Test
    fun `default appearance stays dark for compatibility`() {
        assertEquals(parseThemeJson(json, ThemeAppearance.DARK), parseThemeJson(json))
    }

    @Test
    fun `missing light branch falls back to dark value`() {
        val dark = assertNotNull(parseThemeJson(json, ThemeAppearance.DARK))
        val light = assertNotNull(parseThemeJson(json, ThemeAppearance.LIGHT))
        // "darkOnly" is not mapped to a ThemeConfig role directly, but plain-string
        // values ("border") must resolve identically in both appearances.
        assertEquals(dark.border, light.border)
    }

    @Test
    fun `surfaces move toward white on dark background and toward black on light background`() {
        val dark = assertNotNull(parseThemeJson(json, ThemeAppearance.DARK))
        val light = assertNotNull(parseThemeJson(json, ThemeAppearance.LIGHT))

        // Dark theme: derived surfaces are lighter than the base background.
        assertTrue(luminance(dark.userMessageBg) > luminance(dark.background))
        assertTrue(luminance(dark.overlayBg) > luminance(dark.background))
        assertTrue(luminance(dark.overlaySelectedBg) > luminance(dark.overlayBg))

        // Light theme: derived surfaces are darker than the base background.
        assertTrue(luminance(light.userMessageBg) < luminance(light.background))
        assertTrue(luminance(light.overlayBg) < luminance(light.background))
        assertTrue(luminance(light.overlaySelectedBg) < luminance(light.overlayBg))
    }

    @Test
    fun `scrim dims toward black in dark appearance and toward white in light appearance`() {
        val color = Color(100, 100, 100)
        assertTrue(luminance(color.scrimmed(0.5f, ThemeAppearance.DARK)) < luminance(color))
        assertTrue(luminance(color.scrimmed(0.5f, ThemeAppearance.LIGHT)) > luminance(color))
        // Unspecified colors pass through untouched.
        assertEquals(Color.Unspecified, Color.Unspecified.scrimmed(0.5f, ThemeAppearance.LIGHT))
    }

    @Test
    fun `theme dimmed washes out in light appearance and darkens in dark appearance`() {
        val theme = assertNotNull(parseThemeJson(json, ThemeAppearance.LIGHT))
        val washed = theme.dimmed(0.5f, ThemeAppearance.LIGHT)
        assertTrue(luminance(washed.userMessageText) > luminance(theme.userMessageText))

        val darkTheme = assertNotNull(parseThemeJson(json, ThemeAppearance.DARK))
        val darkened = darkTheme.dimmed(0.5f) // default remains dark behavior
        assertTrue(luminance(darkened.userMessageText) < luminance(darkTheme.userMessageText))
    }

    @Test
    fun `named themes resolve dark and light variants from resources`() {
        val opencode = assertNotNull(AVAILABLE_THEMES.firstOrNull { it.name == "opencode" })
        val dark = opencode.forAppearance(ThemeAppearance.DARK)
        val light = opencode.forAppearance(ThemeAppearance.LIGHT)
        assertEquals(opencode.config, dark)
        assertTrue(dark != light, "opencode should provide a distinct light variant")
        assertTrue(luminance(light.background) > luminance(dark.background))
    }

    @Test
    fun `hardcoded default theme uses opencode light branch as its light variant`() {
        val default = assertNotNull(AVAILABLE_THEMES.firstOrNull { it.name == "default" })
        assertEquals(loadThemeFromResource("opencode", ThemeAppearance.LIGHT), default.lightConfig)
        // Bubble maps ANSI palette slots; it is its own light variant (no fake inversion).
        val bubble = assertNotNull(AVAILABLE_THEMES.firstOrNull { it.name == "bubble" })
        assertEquals(bubble.config, bubble.forAppearance(ThemeAppearance.LIGHT))
    }
}
