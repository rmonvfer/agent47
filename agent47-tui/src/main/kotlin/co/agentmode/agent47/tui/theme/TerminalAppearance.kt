package co.agentmode.agent47.tui.theme

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Terminal appearance (background color scheme) as reported by the terminal emulator.
 */
public enum class TerminalAppearance {
    DARK,
    LIGHT,
}

/**
 * An 8-bit-per-channel RGB color parsed from a terminal response.
 */
public data class TerminalRgb(val r: Int, val g: Int, val b: Int)

/**
 * Where a terminal appearance detection result came from.
 */
public enum class TerminalAppearanceSource {
    /** DSR color-scheme report (`CSI ? 997 ; 1|2 n` in reply to `CSI ? 996 n`). */
    COLOR_SCHEME_REPORT,

    /** OSC 11 background color response. */
    TERMINAL_BACKGROUND,

    /** `COLORFGBG` environment variable. */
    COLORFGBG,

    /** No hint found; defaulted to dark. */
    FALLBACK,
}

/**
 * Result of terminal appearance detection.
 */
public data class TerminalAppearanceDetection(
    val appearance: TerminalAppearance,
    val source: TerminalAppearanceSource,
)

/**
 * Pure parsing and classification helpers plus a bounded synchronous terminal query used to
 * detect whether the terminal has a dark or light background before the Mosaic UI starts.
 */
public object TerminalAppearanceDetector {
    private const val LUMINANCE_LIGHT_THRESHOLD = 0.5

    // `ESC ] 11 ; <value> (BEL | ESC \)`
    private val osc11Pattern = Regex("""\u001b]11;([^\u0007\u001b]*)(?:\u0007|\u001b\\)""")

    // `CSI ? 997 ; 1|2 n` — reply to the `CSI ? 996 n` color-scheme DSR query.
    private val colorSchemeReportPattern = Regex("""\u001b\[\?997;([12])n""")

    /**
     * Parses an OSC 11 background color response. Supports `#RRGGBB`, `#RRRRGGGGBBBB`, and
     * `rgb:`/`rgba:` slash-separated hex channels of 1-4 digits each.
     */
    public fun parseOsc11BackgroundColor(data: String): TerminalRgb? {
        val match = osc11Pattern.find(data) ?: return null
        val value = match.groupValues[1].trim()
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            if (!hex.all { it.isHexDigit() }) return null
            return when (hex.length) {
                6 -> TerminalRgb(
                    r = hex.substring(0, 2).toInt(16),
                    g = hex.substring(2, 4).toInt(16),
                    b = hex.substring(4, 6).toInt(16),
                )
                12 -> {
                    val r = parseHexChannel(hex.substring(0, 4)) ?: return null
                    val g = parseHexChannel(hex.substring(4, 8)) ?: return null
                    val b = parseHexChannel(hex.substring(8, 12)) ?: return null
                    TerminalRgb(r, g, b)
                }
                else -> null
            }
        }

        val rgbValue = value.replaceFirst(Regex("^rgba?:", RegexOption.IGNORE_CASE), "")
        if (rgbValue == value && !value.lowercase().startsWith("rgb")) return null
        val channels = rgbValue.split("/")
        if (channels.size < 3) return null
        val r = parseHexChannel(channels[0]) ?: return null
        val g = parseHexChannel(channels[1]) ?: return null
        val b = parseHexChannel(channels[2]) ?: return null
        return TerminalRgb(r, g, b)
    }

    /**
     * Parses a DSR color-scheme report (`CSI ? 997 ; 1 n` = dark, `CSI ? 997 ; 2 n` = light).
     */
    public fun parseColorSchemeReport(data: String): TerminalAppearance? {
        val match = colorSchemeReportPattern.find(data) ?: return null
        return if (match.groupValues[1] == "2") TerminalAppearance.LIGHT else TerminalAppearance.DARK
    }

    /**
     * WCAG relative luminance of [rgb], in `[0, 1]`.
     */
    public fun relativeLuminance(rgb: TerminalRgb): Double {
        fun toLinear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * toLinear(rgb.r) + 0.7152 * toLinear(rgb.g) + 0.0722 * toLinear(rgb.b)
    }

    /**
     * Classifies [rgb] as [TerminalAppearance.LIGHT] when its relative luminance is >= 0.5.
     */
    public fun classify(rgb: TerminalRgb): TerminalAppearance =
        if (relativeLuminance(rgb) >= LUMINANCE_LIGHT_THRESHOLD) TerminalAppearance.LIGHT else TerminalAppearance.DARK

    /**
     * Converts an ANSI 256-color palette index to its conventional RGB value.
     */
    public fun ansi256ToRgb(index: Int): TerminalRgb {
        require(index in 0..255) { "ANSI 256 color index out of range: $index" }
        if (index < 16) {
            val basic = intArrayOf(
                0x000000, 0x800000, 0x008000, 0x808000, 0x000080, 0x800080, 0x008080, 0xc0c0c0,
                0x808080, 0xff0000, 0x00ff00, 0xffff00, 0x0000ff, 0xff00ff, 0x00ffff, 0xffffff,
            )
            val value = basic[index]
            return TerminalRgb((value shr 16) and 0xff, (value shr 8) and 0xff, value and 0xff)
        }
        if (index < 232) {
            val cubeIndex = index - 16
            fun level(n: Int): Int = if (n == 0) 0 else 55 + n * 40
            return TerminalRgb(
                r = level(cubeIndex / 36),
                g = level((cubeIndex % 36) / 6),
                b = level(cubeIndex % 6),
            )
        }
        val gray = 8 + (index - 232) * 10
        return TerminalRgb(gray, gray, gray)
    }

    /**
     * Detects appearance from a `COLORFGBG` value (e.g. `"15;0"`), using the last valid
     * color index (the background) and returning null when no valid index is present.
     */
    public fun detectFromColorFgBg(colorFgBg: String?): TerminalAppearance? {
        if (colorFgBg.isNullOrBlank()) return null
        val parts = colorFgBg.split(";")
        for (i in parts.indices.reversed()) {
            val bg = parts[i].trim().toIntOrNull() ?: continue
            if (bg in 0..255) return classify(ansi256ToRgb(bg))
        }
        return null
    }

    /**
     * Detects the terminal appearance without touching the terminal, using the given
     * environment ([TerminalAppearanceSource.COLORFGBG]) and falling back to dark.
     */
    public fun detectFromEnvironment(env: Map<String, String> = System.getenv()): TerminalAppearanceDetection {
        detectFromColorFgBg(env["COLORFGBG"])?.let {
            return TerminalAppearanceDetection(it, TerminalAppearanceSource.COLORFGBG)
        }
        return TerminalAppearanceDetection(TerminalAppearance.DARK, TerminalAppearanceSource.FALLBACK)
    }

    /**
     * Detects the terminal appearance with a bounded synchronous query, suitable to run
     * once before Mosaic takes over the terminal.
     *
     * Sends the color-scheme DSR query (`CSI ? 996 n`) followed by the OSC 11 background
     * color query to `/dev/tty` in raw mode, waits up to [timeoutMs] for a response, then
     * restores the terminal settings. On timeout, missing `/dev/tty`, or any failure, it
     * gracefully falls back to `COLORFGBG` and finally dark.
     */
    @JvmStatic
    @JvmOverloads
    public fun detect(
        env: Map<String, String> = System.getenv(),
        timeoutMs: Long = 100,
    ): TerminalAppearanceDetection {
        val queried = runCatching { queryTerminal(timeoutMs) }.getOrNull()
        if (queried != null) return queried
        return detectFromEnvironment(env)
    }

    /**
     * Parses a raw response buffer, preferring the DSR color-scheme report over OSC 11.
     * Returns null when the buffer contains neither.
     */
    internal fun parseQueryResponse(data: String): TerminalAppearanceDetection? {
        parseColorSchemeReport(data)?.let {
            return TerminalAppearanceDetection(it, TerminalAppearanceSource.COLOR_SCHEME_REPORT)
        }
        parseOsc11BackgroundColor(data)?.let {
            return TerminalAppearanceDetection(classify(it), TerminalAppearanceSource.TERMINAL_BACKGROUND)
        }
        return null
    }

    private fun queryTerminal(timeoutMs: Long): TerminalAppearanceDetection? {
        val tty = File("/dev/tty")
        if (!tty.exists()) return null

        val savedSettings = sttyCapture("-g", timeoutMs) ?: return null
        try {
            // Raw mode, no echo, non-blocking reads so we never hang.
            if (sttyCapture("raw -echo min 0 time 0", timeoutMs) == null) return null
            FileOutputStream(tty).use { out ->
                FileInputStream(tty).use { input ->
                    // DSR color-scheme query first, then OSC 11 background color query.
                    out.write("\u001b[?996n\u001b]11;?\u0007".toByteArray(Charsets.ISO_8859_1))
                    out.flush()

                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                    val buffer = StringBuilder()
                    val bytes = ByteArray(256)
                    while (System.nanoTime() < deadline) {
                        val available = input.available()
                        if (available > 0) {
                            val read = input.read(bytes, 0, minOf(available, bytes.size))
                            if (read > 0) {
                                buffer.append(String(bytes, 0, read, Charsets.ISO_8859_1))
                                parseQueryResponse(buffer.toString())?.let { return it }
                            }
                        } else {
                            Thread.sleep(5)
                        }
                    }
                    return parseQueryResponse(buffer.toString())
                }
            }
        } finally {
            sttyCapture(savedSettings, timeoutMs)
        }
    }

    private fun sttyCapture(args: String, timeoutMs: Long): String? = runCatching {
        val process = ProcessBuilder(listOf("stty") + args.split(" "))
            .redirectInput(File("/dev/tty"))
            .redirectErrorStream(false)
            .start()
        if (!process.waitFor(maxOf(timeoutMs, 100), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        if (process.exitValue() != 0) {
            null
        } else {
            process.inputStream.bufferedReader().readText().trim()
        }
    }.getOrNull()

    private fun parseHexChannel(channel: String): Int? {
        if (channel.isEmpty() || channel.length > 4 || !channel.all { it.isHexDigit() }) return null
        val max = (1L shl (4 * channel.length)) - 1
        return Math.round(channel.toLong(16).toDouble() / max * 255).toInt()
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
