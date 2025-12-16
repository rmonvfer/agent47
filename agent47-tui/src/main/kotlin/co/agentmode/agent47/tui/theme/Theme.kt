package co.agentmode.agent47.tui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.jakewharton.mosaic.ui.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Semantic color roles for the Mosaic TUI.
 */
public data class MosaicThemeColors(
    val accent: Color = Color(86, 182, 194),
    val accentBright: Color = Color(158, 218, 229),
    val success: Color = Color(80, 200, 120),
    val error: Color = Color(220, 80, 80),
    val warning: Color = Color(220, 180, 60),
    val muted: Color = Color(120, 120, 120),
    val dim: Color = Color(72, 72, 72),
)

/**
 * Theme configuration for the Mosaic TUI.
 * Each property is a Mosaic Color value, foreground colors are used directly
 * with Text/SpanStyle, background colors with Modifier.background() or SpanStyle.background.
 */
public data class ThemeConfig(
    val colors: MosaicThemeColors = MosaicThemeColors(),
    val userMessageBg: Color = Color(35, 35, 35),
    val toolPendingBg: Color = Color(35, 35, 35),
    val toolSuccessBg: Color = Color(20, 20, 20),
    val toolErrorBg: Color = Color(80, 30, 30),
    val thinkingText: Color = Color(100, 100, 100),
    val toolTitle: Color = MosaicThemeColors().accent,
    val toolOutput: Color = Color.White,
    val toolDiffAdded: Color = TerminalColors.GREEN,
    val toolDiffRemoved: Color = TerminalColors.RED,
    val toolDiffContext: Color = MosaicThemeColors().dim,
    val codeBlockBg: Color = Color(30, 30, 30),
    val inlineCodeBg: Color = Color(50, 50, 50),
    val link: Color = TerminalColors.CYAN,
    val linkUrl: Color = MosaicThemeColors().dim,
    val statusBarBg: Color = Color(40, 40, 40),
    val overlayBg: Color = Color(25, 25, 25),
    val overlaySelectedBg: Color = Color(55, 55, 55),
    val codeBlockFg: Color = Color(200, 200, 200),

    // Diff colors (backgrounds for diff hunks)
    val diffAddedBg: Color = Color(32, 48, 59),
    val diffRemovedBg: Color = Color(55, 34, 44),
    val diffContextBg: Color = Color(20, 20, 20),
    val diffHunkHeader: Color = Color(130, 139, 184),
    val diffHighlightAdded: Color = Color(184, 219, 135),
    val diffHighlightRemoved: Color = Color(226, 106, 117),
    val diffLineNumber: Color = Color(72, 72, 72),
    val diffAddedLineNumberBg: Color = Color(27, 43, 52),
    val diffRemovedLineNumberBg: Color = Color(45, 31, 38),

    // Markdown rendering colors
    val markdownText: Color = Color(238, 238, 238),
    val markdownHeading: Color = Color(157, 124, 216),
    val markdownLink: Color = Color(250, 178, 131),
    val markdownLinkText: Color = Color(86, 182, 194),
    val markdownCode: Color = Color(127, 216, 143),
    val markdownBlockQuote: Color = Color(229, 192, 123),
    val markdownEmph: Color = Color(229, 192, 123),
    val markdownStrong: Color = Color(245, 167, 66),
    val markdownHorizontalRule: Color = Color(128, 128, 128),
    val markdownListItem: Color = Color(250, 178, 131),
    val markdownListEnumeration: Color = Color(86, 182, 194),
    val markdownCodeBlock: Color = Color(238, 238, 238),

    // Syntax highlighting colors
    val syntaxComment: Color = Color(128, 128, 128),
    val syntaxKeyword: Color = Color(157, 124, 216),
    val syntaxFunction: Color = Color(250, 178, 131),
    val syntaxVariable: Color = Color(224, 108, 117),
    val syntaxString: Color = Color(127, 216, 143),
    val syntaxNumber: Color = Color(245, 167, 66),
    val syntaxType: Color = Color(229, 192, 123),
    val syntaxOperator: Color = Color(86, 182, 194),
    val syntaxPunctuation: Color = Color(238, 238, 238),

    // Todo status colors
    val todoPending: Color = Color(120, 120, 120),
    val todoInProgress: Color = Color(86, 182, 194),
    val todoCompleted: Color = Color(80, 200, 120),
    val todoCancelled: Color = Color(120, 120, 120),
    val todoPriorityHigh: Color = Color(220, 80, 80),
    val todoPriorityMedium: Color = Color(220, 180, 60),
    val todoPriorityLow: Color = Color(120, 120, 120),

    // Border colors
    val border: Color = Color(72, 72, 72),
    val borderActive: Color = Color(96, 96, 96),
    val borderSubtle: Color = Color(60, 60, 60),
) {
    public companion object {
        public val DEFAULT: ThemeConfig by lazy { getMosaicTheme() }
    }
}

private val TUI_THEME = System.getenv("TUI_THEME") ?: "default"

private fun getMosaicTheme(): ThemeConfig {
    // Try loading from JSON resource first
    val fromJson = loadThemeFromResource(TUI_THEME)
    if (fromJson != null) return fromJson

    // Fall back to hardcoded themes
    return when (TUI_THEME) {
        "bubble" -> MosaicBubbleTheme
        else -> ThemeConfig()
    }
}

/**
 * CompositionLocal that provides the active MosaicTheme throughout the Compose tree.
 * Access via `LocalMosaicTheme.current` from any composable.
 */
public val LocalThemeConfig: androidx.compose.runtime.ProvidableCompositionLocal<ThemeConfig> =
    compositionLocalOf { ThemeConfig.DEFAULT }

/**
 * Convenience wrapper that provides a MosaicTheme to the composable subtree.
 */
@Composable
public fun MosaicThemeProvider(
    theme: ThemeConfig = ThemeConfig.DEFAULT,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalThemeConfig provides theme) {
        content()
    }
}

// ---------------------------------------------------------------------------
// Standard terminal palette mapped to 24-bit RGB.
// These approximate the typical xterm-256 color palette that most modern
// terminals use by default.
// ---------------------------------------------------------------------------

public object TerminalColors {
    // Standard foreground / background colors (ANSI 30-37 / 40-47)
    public val BLACK: Color = Color(0, 0, 0)
    public val RED: Color = Color(205, 0, 0)
    public val GREEN: Color = Color(0, 205, 0)
    public val YELLOW: Color = Color(205, 205, 0)
    public val BLUE: Color = Color(0, 0, 238)
    public val MAGENTA: Color = Color(205, 0, 205)
    public val CYAN: Color = Color(0, 205, 205)
    public val WHITE: Color = Color(229, 229, 229)

    // Bright foreground / background colors (ANSI 90-97 / 100-107)
    public val BRIGHT_BLACK: Color = Color(127, 127, 127)
    public val BRIGHT_RED: Color = Color(255, 0, 0)
    public val BRIGHT_GREEN: Color = Color(0, 255, 0)
    public val BRIGHT_YELLOW: Color = Color(255, 255, 0)
    public val BRIGHT_BLUE: Color = Color(92, 92, 255)
    public val BRIGHT_MAGENTA: Color = Color(255, 0, 255)
    public val BRIGHT_CYAN: Color = Color(0, 255, 255)
    public val BRIGHT_WHITE: Color = Color(255, 255, 255)

    // Background aliases, same RGB values, distinguished by usage context.
    // In Mosaic, background is applied via Modifier.background() or SpanStyle.background,
    // so the Color value itself is the same regardless of fg/bg usage.
    public val BG_BLACK: Color = BLACK
    public val BG_RED: Color = RED
    public val BG_GREEN: Color = GREEN
    public val BG_YELLOW: Color = YELLOW
    public val BG_BLUE: Color = BLUE
    public val BG_MAGENTA: Color = MAGENTA
    public val BG_CYAN: Color = CYAN
    public val BG_WHITE: Color = WHITE

    public val BG_BRIGHT_BLACK: Color = BRIGHT_BLACK
    public val BG_BRIGHT_RED: Color = BRIGHT_RED
    public val BG_BRIGHT_GREEN: Color = BRIGHT_GREEN
    public val BG_BRIGHT_YELLOW: Color = BRIGHT_YELLOW
    public val BG_BRIGHT_BLUE: Color = BRIGHT_BLUE
    public val BG_BRIGHT_MAGENTA: Color = BRIGHT_MAGENTA
    public val BG_BRIGHT_CYAN: Color = BRIGHT_CYAN
    public val BG_BRIGHT_WHITE: Color = BRIGHT_WHITE
}

// ---------------------------------------------------------------------------
// Theme variants (hardcoded fallbacks)
// ---------------------------------------------------------------------------

internal val MosaicBubbleTheme = ThemeConfig(
    colors = MosaicThemeColors(
        accent = TerminalColors.CYAN,
        accentBright = TerminalColors.BRIGHT_CYAN,
        success = TerminalColors.GREEN,
        error = TerminalColors.RED,
        warning = TerminalColors.YELLOW,
        muted = TerminalColors.BRIGHT_BLACK,
        dim = TerminalColors.BRIGHT_BLACK,
    ),
    userMessageBg = TerminalColors.BG_CYAN,
    toolPendingBg = TerminalColors.BG_BRIGHT_BLACK,
    toolSuccessBg = TerminalColors.BG_BLACK,
    toolErrorBg = TerminalColors.BG_RED,
    thinkingText = TerminalColors.BRIGHT_BLACK,
    toolTitle = TerminalColors.CYAN,
    toolOutput = Color.White,
    toolDiffAdded = TerminalColors.GREEN,
    toolDiffRemoved = TerminalColors.RED,
    toolDiffContext = TerminalColors.BRIGHT_BLACK,
    codeBlockBg = TerminalColors.BG_BLACK,
    inlineCodeBg = TerminalColors.BG_BRIGHT_BLACK,
    link = TerminalColors.CYAN,
    linkUrl = TerminalColors.BRIGHT_BLACK,
    statusBarBg = TerminalColors.BG_BLACK,
    overlayBg = TerminalColors.BG_BLACK,
    overlaySelectedBg = TerminalColors.BG_BRIGHT_BLACK,
    codeBlockFg = TerminalColors.WHITE,
)

// ---------------------------------------------------------------------------
// JSON theme loader
// ---------------------------------------------------------------------------

private val themeJson = Json { ignoreUnknownKeys = true }

/**
 * Loads a theme from a JSON resource file under `themes/`.
 * Supports the OpenCode theme format with `defs` for color aliases and
 * `theme` for the actual color mappings.
 */
internal fun loadThemeFromResource(name: String): ThemeConfig? {
    val path = "themes/$name.json"
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        ?: ThemeConfig::class.java.classLoader?.getResourceAsStream(path)
        ?: return null

    val content = stream.bufferedReader().readText()
    return parseThemeJson(content)
}

internal fun parseThemeJson(json: String): ThemeConfig? {
    return runCatching {
        val root = themeJson.parseToJsonElement(json).jsonObject
        val defs = root["defs"]?.jsonObject ?: JsonObject(emptyMap())
        val theme = root["theme"]?.jsonObject ?: return null

        fun resolve(value: String): RgbColor {
            // Direct hex color
            if (value.startsWith("#")) return parseHexColor(value)
            // Reference to a def
            val defValue = defs[value]?.jsonPrimitive?.content
            if (defValue != null) {
                if (defValue.startsWith("#")) return parseHexColor(defValue)
                // Nested ref (def pointing to another def)
                val nested = defs[defValue]?.jsonPrimitive?.content
                if (nested != null && nested.startsWith("#")) return parseHexColor(nested)
            }
            // Reference to another theme key
            val themeRef = theme[value]
            if (themeRef != null) {
                val refStr = extractDarkValue(themeRef)
                if (refStr != null) return resolve(refStr)
            }
            return RgbColor(Color.White, 255, 255, 255)
        }

        fun color(key: String, default: RgbColor): RgbColor {
            val element = theme[key] ?: return default
            val valueStr = extractDarkValue(element) ?: return default
            return resolve(valueStr)
        }

        fun rgb(r: Int, g: Int, b: Int) = RgbColor(Color(r, g, b), r, g, b)

        val primary = color("primary", rgb(250, 178, 131))
        val secondary = color("secondary", rgb(92, 156, 245))
        val accent = color("accent", rgb(157, 124, 216))
        val error = color("error", rgb(224, 108, 117))
        val warning = color("warning", rgb(245, 167, 66))
        val success = color("success", rgb(127, 216, 143))
        val info = color("info", rgb(86, 182, 194))
        val text = color("text", rgb(238, 238, 238))
        val textMuted = color("textMuted", rgb(128, 128, 128))
        val background = color("background", rgb(10, 10, 10))
        val backgroundPanel = color("backgroundPanel", rgb(20, 20, 20))
        val backgroundElement = color("backgroundElement", rgb(30, 30, 30))

        ThemeConfig(
            colors = MosaicThemeColors(
                accent = primary.color,
                accentBright = accent.color,
                success = success.color,
                error = error.color,
                warning = warning.color,
                muted = textMuted.color,
                dim = color("borderSubtle", rgb(72, 72, 72)).color,
            ),
            userMessageBg = backgroundElement.color,
            toolPendingBg = backgroundElement.color,
            toolSuccessBg = background.color,
            toolErrorBg = blendColors(
                error.r, error.g, error.b,
                background.r, background.g, background.b,
            ),
            thinkingText = textMuted.color,
            toolTitle = primary.color,
            toolOutput = text.color,
            toolDiffAdded = color("diffAdded", rgb(0, 255, 0)).color,
            toolDiffRemoved = color("diffRemoved", rgb(255, 0, 0)).color,
            toolDiffContext = color("diffContext", rgb(72, 72, 72)).color,
            codeBlockBg = backgroundPanel.color,
            inlineCodeBg = backgroundElement.color,
            link = info.color,
            linkUrl = textMuted.color,
            statusBarBg = backgroundPanel.color,
            overlayBg = background.color,
            overlaySelectedBg = backgroundElement.color,
            codeBlockFg = text.color,

            diffAddedBg = color("diffAddedBg", rgb(32, 48, 59)).color,
            diffRemovedBg = color("diffRemovedBg", rgb(55, 34, 44)).color,
            diffContextBg = color("diffContextBg", backgroundPanel).color,
            diffHunkHeader = color("diffHunkHeader", textMuted).color,
            diffHighlightAdded = color("diffHighlightAdded", rgb(184, 219, 135)).color,
            diffHighlightRemoved = color("diffHighlightRemoved", rgb(226, 106, 117)).color,
            diffLineNumber = color("diffLineNumber", rgb(72, 72, 72)).color,
            diffAddedLineNumberBg = color("diffAddedLineNumberBg", rgb(27, 43, 52)).color,
            diffRemovedLineNumberBg = color("diffRemovedLineNumberBg", rgb(45, 31, 38)).color,

            markdownText = color("markdownText", text).color,
            markdownHeading = color("markdownHeading", accent).color,
            markdownLink = color("markdownLink", primary).color,
            markdownLinkText = color("markdownLinkText", info).color,
            markdownCode = color("markdownCode", success).color,
            markdownBlockQuote = color("markdownBlockQuote", warning).color,
            markdownEmph = color("markdownEmph", warning).color,
            markdownStrong = color("markdownStrong", rgb(245, 167, 66)).color,
            markdownHorizontalRule = color("markdownHorizontalRule", textMuted).color,
            markdownListItem = color("markdownListItem", primary).color,
            markdownListEnumeration = color("markdownListEnumeration", info).color,
            markdownCodeBlock = color("markdownCodeBlock", text).color,

            syntaxComment = color("syntaxComment", textMuted).color,
            syntaxKeyword = color("syntaxKeyword", accent).color,
            syntaxFunction = color("syntaxFunction", primary).color,
            syntaxVariable = color("syntaxVariable", error).color,
            syntaxString = color("syntaxString", success).color,
            syntaxNumber = color("syntaxNumber", warning).color,
            syntaxType = color("syntaxType", rgb(229, 192, 123)).color,
            syntaxOperator = color("syntaxOperator", info).color,
            syntaxPunctuation = color("syntaxPunctuation", text).color,

            todoPending = textMuted.color,
            todoInProgress = info.color,
            todoCompleted = success.color,
            todoCancelled = textMuted.color,
            todoPriorityHigh = error.color,
            todoPriorityMedium = warning.color,
            todoPriorityLow = textMuted.color,

            border = color("border", rgb(72, 72, 72)).color,
            borderActive = color("borderActive", rgb(96, 96, 96)).color,
            borderSubtle = color("borderSubtle", rgb(60, 60, 60)).color,
        )
    }.getOrNull()
}

/**
 * Extract the "dark" variant from a theme value.
 * Values can be:
 * - A plain string (color name or hex): `"purple"`
 * - A dark/light object: `{"dark": "purple", "light": "purple"}`
 */
private fun extractDarkValue(element: kotlinx.serialization.json.JsonElement): String? {
    return when {
        element is kotlinx.serialization.json.JsonPrimitive -> element.content
        element is JsonObject -> element["dark"]?.jsonPrimitive?.content
        else -> null
    }
}

/**
 * A Color paired with its RGB components for blending operations.
 * Mosaic's Color.value is internal, so we preserve the components at parse time.
 */
private data class RgbColor(val color: Color, val r: Int, val g: Int, val b: Int)

private fun parseHexColor(hex: String): RgbColor {
    val cleaned = hex.removePrefix("#")
    return when (cleaned.length) {
        6 -> {
            val r = cleaned.substring(0, 2).toInt(16)
            val g = cleaned.substring(2, 4).toInt(16)
            val b = cleaned.substring(4, 6).toInt(16)
            RgbColor(Color(r, g, b), r, g, b)
        }
        8 -> {
            val r = cleaned.substring(0, 2).toInt(16)
            val g = cleaned.substring(2, 4).toInt(16)
            val b = cleaned.substring(4, 6).toInt(16)
            // Alpha channel ignored for Mosaic terminal rendering
            RgbColor(Color(r, g, b), r, g, b)
        }
        else -> RgbColor(Color.White, 255, 255, 255)
    }
}

// Blend two colors by mixing their RGB components.
// Since Mosaic Color.value is internal, we accept pre-extracted RGB triples.
private fun blendColors(
    fgR: Int, fgG: Int, fgB: Int,
    bgR: Int, bgG: Int, bgB: Int,
    fgWeight: Float = 0.3f,
): Color {
    val bgWeight = 1f - fgWeight
    return Color(
        (fgR * fgWeight + bgR * bgWeight).toInt().coerceIn(0, 255),
        (fgG * fgWeight + bgG * bgWeight).toInt().coerceIn(0, 255),
        (fgB * fgWeight + bgB * bgWeight).toInt().coerceIn(0, 255),
    )
}

// ---------------------------------------------------------------------------
// Named theme registry
// ---------------------------------------------------------------------------

public data class NamedTheme(
    val name: String,
    val config: ThemeConfig,
)

/**
 * All available themes, loaded from JSON resources where available,
 * with hardcoded fallbacks for "default" and "bubble".
 */
public val AVAILABLE_THEMES: List<NamedTheme> by lazy {
    val jsonThemeNames = listOf(
        "opencode", "aura", "ayu", "carbonfox",
        "catppuccin", "catppuccin-frappe", "catppuccin-macchiato",
        "cobalt2", "cursor", "dracula", "everforest", "flexoki",
        "github", "gruvbox", "kanagawa", "lucent-orng",
        "material", "matrix", "mercury", "monokai", "nightowl",
        "nord", "one-dark", "orng", "osaka-jade", "palenight",
        "rosepine", "solarized", "synthwave84", "tokyonight",
        "vercel", "vesper", "zenburn",
    )

    val themes = mutableListOf(
        NamedTheme("default", ThemeConfig()),
        NamedTheme("bubble", MosaicBubbleTheme),
    )

    for (name in jsonThemeNames) {
        val config = loadThemeFromResource(name)
        if (config != null) {
            themes += NamedTheme(name, config)
        }
    }

    themes.sortedBy { it.name }
}
