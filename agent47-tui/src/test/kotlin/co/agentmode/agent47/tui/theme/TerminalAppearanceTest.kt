package co.agentmode.agent47.tui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalAppearanceTest {

    @Test
    fun `parses OSC 11 six digit hex response`() {
        val rgb = TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;#1e1e2e\u0007")
        assertEquals(TerminalRgb(0x1e, 0x1e, 0x2e), rgb)
    }

    @Test
    fun `parses OSC 11 twelve digit hex response with ST terminator`() {
        val rgb = TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;#ffffffff0000\u001b\\")
        assertEquals(TerminalRgb(255, 255, 0), rgb)
    }

    @Test
    fun `parses OSC 11 rgb colon slash channels`() {
        val rgb = TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;rgb:1e1e/1e1e/2e2e\u0007")
        assertEquals(TerminalRgb(0x1e, 0x1e, 0x2e), rgb)
    }

    @Test
    fun `parses OSC 11 rgba channels and short channels`() {
        val rgb = TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;rgba:f/0/8\u0007")
        assertEquals(TerminalRgb(255, 0, 136), rgb)
    }

    @Test
    fun `rejects malformed OSC 11 responses`() {
        assertNull(TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;#12345\u0007"))
        assertNull(TerminalAppearanceDetector.parseOsc11BackgroundColor("\u001b]11;rgb:zz/00/00\u0007"))
        assertNull(TerminalAppearanceDetector.parseOsc11BackgroundColor("not a response"))
    }

    @Test
    fun `parses DSR color scheme reports`() {
        assertEquals(
            TerminalAppearance.DARK,
            TerminalAppearanceDetector.parseColorSchemeReport("\u001b[?997;1n"),
        )
        assertEquals(
            TerminalAppearance.LIGHT,
            TerminalAppearanceDetector.parseColorSchemeReport("\u001b[?997;2n"),
        )
        assertNull(TerminalAppearanceDetector.parseColorSchemeReport("\u001b[?997;3n"))
    }

    @Test
    fun `classifies colors by luminance threshold`() {
        assertEquals(TerminalAppearance.DARK, TerminalAppearanceDetector.classify(TerminalRgb(0, 0, 0)))
        assertEquals(TerminalAppearance.DARK, TerminalAppearanceDetector.classify(TerminalRgb(30, 30, 46)))
        assertEquals(TerminalAppearance.LIGHT, TerminalAppearanceDetector.classify(TerminalRgb(255, 255, 255)))
        assertEquals(TerminalAppearance.LIGHT, TerminalAppearanceDetector.classify(TerminalRgb(238, 238, 238)))
    }

    @Test
    fun `converts ANSI 256 indexes to rgb`() {
        assertEquals(TerminalRgb(0, 0, 0), TerminalAppearanceDetector.ansi256ToRgb(0))
        assertEquals(TerminalRgb(255, 255, 255), TerminalAppearanceDetector.ansi256ToRgb(15))
        assertEquals(TerminalRgb(0, 0, 0), TerminalAppearanceDetector.ansi256ToRgb(16))
        assertEquals(TerminalRgb(255, 255, 255), TerminalAppearanceDetector.ansi256ToRgb(231))
        assertEquals(TerminalRgb(95, 135, 175), TerminalAppearanceDetector.ansi256ToRgb(67))
        assertEquals(TerminalRgb(8, 8, 8), TerminalAppearanceDetector.ansi256ToRgb(232))
        assertEquals(TerminalRgb(238, 238, 238), TerminalAppearanceDetector.ansi256ToRgb(255))
    }

    @Test
    fun `COLORFGBG uses last valid index as background`() {
        assertEquals(TerminalAppearance.DARK, TerminalAppearanceDetector.detectFromColorFgBg("15;0"))
        assertEquals(TerminalAppearance.LIGHT, TerminalAppearanceDetector.detectFromColorFgBg("0;15"))
        assertEquals(TerminalAppearance.LIGHT, TerminalAppearanceDetector.detectFromColorFgBg("0;default;15"))
        assertNull(TerminalAppearanceDetector.detectFromColorFgBg("default;default"))
        assertNull(TerminalAppearanceDetector.detectFromColorFgBg(""))
        assertNull(TerminalAppearanceDetector.detectFromColorFgBg(null))
    }

    @Test
    fun `environment detection falls back to dark`() {
        val fromEnv = TerminalAppearanceDetector.detectFromEnvironment(mapOf("COLORFGBG" to "0;15"))
        assertEquals(TerminalAppearance.LIGHT, fromEnv.appearance)
        assertEquals(TerminalAppearanceSource.COLORFGBG, fromEnv.source)

        val fallback = TerminalAppearanceDetector.detectFromEnvironment(emptyMap())
        assertEquals(TerminalAppearance.DARK, fallback.appearance)
        assertEquals(TerminalAppearanceSource.FALLBACK, fallback.source)
    }

    @Test
    fun `query response parsing prefers color scheme report over OSC 11`() {
        val both = "\u001b[?997;2n\u001b]11;#000000\u0007"
        val detection = TerminalAppearanceDetector.parseQueryResponse(both)
        assertEquals(TerminalAppearance.LIGHT, detection?.appearance)
        assertEquals(TerminalAppearanceSource.COLOR_SCHEME_REPORT, detection?.source)

        val oscOnly = TerminalAppearanceDetector.parseQueryResponse("\u001b]11;#ffffff\u0007")
        assertEquals(TerminalAppearance.LIGHT, oscOnly?.appearance)
        assertEquals(TerminalAppearanceSource.TERMINAL_BACKGROUND, oscOnly?.source)

        assertNull(TerminalAppearanceDetector.parseQueryResponse("garbage"))
    }
}
