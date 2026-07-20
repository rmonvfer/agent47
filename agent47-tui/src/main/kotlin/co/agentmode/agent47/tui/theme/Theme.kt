package co.agentmode.agent47.tui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.isSpecifiedColor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Semantic color roles for the Mosaic TUI.
 */
public data class MosaicThemeColors(
    val accent: Color = Color(138, 190, 183), // #8abeb7
    val accentBright: Color = Color(0, 215, 255), // #00d7ff (borderAccent/cyan)
    val success: Color = Color(181, 189, 104), // #b5bd68 (green)
    val error: Color = Color(204, 102, 102), // #cc6666 (red)
    val warning: Color = Color(255, 255, 0), // #ffff00 (yellow)
    val muted: Color = Color(128, 128, 128), // #808080 (gray)
    val dim: Color = Color(102, 102, 102), // #666666 (dimGray)
)

/**
 * Theme configuration for the Mosaic TUI.
 * Each property is a Mosaic Color value, foreground colors are used directly
 * with Text/SpanStyle, background colors with Modifier.background() or SpanStyle.background.
 */
public data class ThemeConfig(
    val colors: MosaicThemeColors = MosaicThemeColors(),
    val background: Color = Color.Unspecified,
    val userMessageBg: Color = Color(52, 53, 65), // #343541
    val userMessageText: Color = Color(212, 212, 212), // #d4d4d4 (text)
    val customMessageBg: Color = Color(45, 40, 56), // #2d2838
    val customMessageText: Color = Color(212, 212, 212), // #d4d4d4 (text)
    val customMessageLabel: Color = Color(149, 117, 205), // #9575cd
    val toolPendingBg: Color = Color(40, 40, 50), // #282832
    val toolSuccessBg: Color = Color(40, 50, 40), // #283228
    val toolErrorBg: Color = Color(60, 40, 40), // #3c2828
    val thinkingText: Color = Color(128, 128, 128), // #808080 (gray)
    val toolTitle: Color = Color(212, 212, 212), // #d4d4d4 (text) — ohm tool titles are plain bold
    val toolOutput: Color = Color(128, 128, 128), // #808080 (gray)
    val toolDiffAdded: Color = Color(181, 189, 104), // #b5bd68 (green)
    val toolDiffRemoved: Color = Color(204, 102, 102), // #cc6666 (red)
    val toolDiffContext: Color = Color(128, 128, 128), // #808080 (gray)
    val codeBlockBg: Color = Color(30, 30, 30),
    val inlineCodeBg: Color = Color(50, 50, 50),
    val link: Color = Color(129, 162, 190), // #81a2be (mdLink)
    val linkUrl: Color = Color(102, 102, 102), // #666666 (dimGray)
    val statusBarBg: Color = Color.Unspecified, // ohm footer has no background fill
    val overlayBg: Color = Color(25, 25, 25),
    val overlaySelectedBg: Color = Color(58, 58, 74), // #3a3a4a (selectedBg)
    val codeBlockFg: Color = Color(181, 189, 104), // #b5bd68 (mdCodeBlock flat fallback)

    // Diff colors (backgrounds for diff hunks)
    val diffAddedBg: Color = Color(32, 48, 59),
    val diffRemovedBg: Color = Color(55, 34, 44),
    val diffContextBg: Color = Color(20, 20, 20),
    val diffHunkHeader: Color = Color(130, 139, 184),
    val diffHighlightAdded: Color = Color(184, 219, 135),
    val diffHighlightRemoved: Color = Color(226, 106, 117),
    val diffLineNumber: Color = Color(102, 102, 102),
    val diffAddedLineNumberBg: Color = Color(27, 43, 52),
    val diffRemovedLineNumberBg: Color = Color(45, 31, 38),

    // Markdown rendering colors
    val markdownText: Color = Color(212, 212, 212), // #d4d4d4 (text)
    val markdownHeading: Color = Color(240, 198, 116), // #f0c674 (mdHeading gold)
    val markdownLink: Color = Color(129, 162, 190), // #81a2be
    val markdownLinkText: Color = Color(138, 190, 183), // #8abeb7 (accent)
    val markdownCode: Color = Color(138, 190, 183), // #8abeb7 (accent — inline code)
    val markdownBlockQuote: Color = Color(128, 128, 128), // #808080 (mdQuote gray)
    val markdownEmph: Color = Color(212, 212, 212), // #d4d4d4 (italic only, no hue)
    val markdownStrong: Color = Color(212, 212, 212), // #d4d4d4 (bold only, no hue)
    val markdownHorizontalRule: Color = Color(128, 128, 128), // #808080 (mdHr)
    val markdownListItem: Color = Color(138, 190, 183), // #8abeb7 (mdListBullet accent)
    val markdownListEnumeration: Color = Color(138, 190, 183), // #8abeb7 (accent)
    val markdownCodeBlock: Color = Color(181, 189, 104), // #b5bd68 (green)

    // Syntax highlighting colors (ohm's VS Code-derived palette)
    val syntaxComment: Color = Color(106, 153, 85), // #6A9955
    val syntaxKeyword: Color = Color(86, 156, 214), // #569CD6
    val syntaxFunction: Color = Color(220, 220, 170), // #DCDCAA
    val syntaxVariable: Color = Color(156, 220, 254), // #9CDCFE
    val syntaxString: Color = Color(206, 145, 120), // #CE9178
    val syntaxNumber: Color = Color(181, 206, 168), // #B5CEA8
    val syntaxType: Color = Color(78, 201, 176), // #4EC9B0
    val syntaxOperator: Color = Color(212, 212, 212), // #D4D4D4
    val syntaxPunctuation: Color = Color(212, 212, 212), // #D4D4D4

    // Todo status colors
    val todoPending: Color = Color(128, 128, 128), // muted
    val todoInProgress: Color = Color(138, 190, 183), // accent
    val todoCompleted: Color = Color(181, 189, 104), // success
    val todoCancelled: Color = Color(128, 128, 128), // muted
    val todoPriorityHigh: Color = Color(204, 102, 102), // error
    val todoPriorityMedium: Color = Color(255, 255, 0), // warning
    val todoPriorityLow: Color = Color(128, 128, 128), // muted

    // Border colors
    val border: Color = Color(95, 135, 255), // #5f87ff (blue)
    val borderActive: Color = Color(0, 215, 255), // #00d7ff (borderAccent/cyan)
    val borderSubtle: Color = Color(80, 80, 80), // #505050 (borderMuted/darkGray)

    // Editor border colors keyed to thinking level (ohm parity — the input rule
    // color is the primary mode indicator; bash mode overrides all of these).
    val thinkingOff: Color = Color(80, 80, 80), // #505050 (darkGray)
    val thinkingMinimal: Color = Color(110, 110, 110), // #6e6e6e
    val thinkingLow: Color = Color(95, 135, 175), // #5f87af
    val thinkingMedium: Color = Color(129, 162, 190), // #81a2be
    val thinkingHigh: Color = Color(178, 148, 187), // #b294bb
    val thinkingXhigh: Color = Color(209, 131, 232), // #d183e8
    val thinkingMax: Color = Color(255, 95, 255), // #ff5fff
    val bashModeBorder: Color = Color(181, 189, 104), // #b5bd68 (green)
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

/**
 * Returns this color scaled toward black by [factor] (1 = unchanged, 0 = black).
 * Unspecified colors (terminal default) are returned untouched.
 */
public fun Color.darken(factor: Float): Color {
    if (!isSpecifiedColor) return this
    val (r, g, b) = this
    return Color(red = r * factor, green = g * factor, blue = b * factor)
}

/** Uniformly darkens every semantic color by [factor]. */
public fun MosaicThemeColors.dimmed(factor: Float): MosaicThemeColors = MosaicThemeColors(
    accent = accent.darken(factor),
    accentBright = accentBright.darken(factor),
    success = success.darken(factor),
    error = error.darken(factor),
    warning = warning.darken(factor),
    muted = muted.darken(factor),
    dim = dim.darken(factor),
)

/**
 * Returns a copy of this theme with every color uniformly darkened by [factor]. Used to
 * render the base layer as a dimmed scrim behind a floating dialog, since terminals have
 * no real alpha to draw a translucent film with.
 */
@Suppress("LongMethod")
public fun ThemeConfig.dimmed(factor: Float): ThemeConfig = copy(
    colors = colors.dimmed(factor),
    background = background.darken(factor),
    userMessageBg = userMessageBg.darken(factor),
    userMessageText = userMessageText.darken(factor),
    customMessageBg = customMessageBg.darken(factor),
    customMessageText = customMessageText.darken(factor),
    customMessageLabel = customMessageLabel.darken(factor),
    toolPendingBg = toolPendingBg.darken(factor),
    toolSuccessBg = toolSuccessBg.darken(factor),
    toolErrorBg = toolErrorBg.darken(factor),
    thinkingText = thinkingText.darken(factor),
    toolTitle = toolTitle.darken(factor),
    toolOutput = toolOutput.darken(factor),
    toolDiffAdded = toolDiffAdded.darken(factor),
    toolDiffRemoved = toolDiffRemoved.darken(factor),
    toolDiffContext = toolDiffContext.darken(factor),
    codeBlockBg = codeBlockBg.darken(factor),
    inlineCodeBg = inlineCodeBg.darken(factor),
    link = link.darken(factor),
    linkUrl = linkUrl.darken(factor),
    statusBarBg = statusBarBg.darken(factor),
    overlayBg = overlayBg.darken(factor),
    overlaySelectedBg = overlaySelectedBg.darken(factor),
    codeBlockFg = codeBlockFg.darken(factor),
    diffAddedBg = diffAddedBg.darken(factor),
    diffRemovedBg = diffRemovedBg.darken(factor),
    diffContextBg = diffContextBg.darken(factor),
    diffHunkHeader = diffHunkHeader.darken(factor),
    diffHighlightAdded = diffHighlightAdded.darken(factor),
    diffHighlightRemoved = diffHighlightRemoved.darken(factor),
    diffLineNumber = diffLineNumber.darken(factor),
    diffAddedLineNumberBg = diffAddedLineNumberBg.darken(factor),
    diffRemovedLineNumberBg = diffRemovedLineNumberBg.darken(factor),
    markdownText = markdownText.darken(factor),
    markdownHeading = markdownHeading.darken(factor),
    markdownLink = markdownLink.darken(factor),
    markdownLinkText = markdownLinkText.darken(factor),
    markdownCode = markdownCode.darken(factor),
    markdownBlockQuote = markdownBlockQuote.darken(factor),
    markdownEmph = markdownEmph.darken(factor),
    markdownStrong = markdownStrong.darken(factor),
    markdownHorizontalRule = markdownHorizontalRule.darken(factor),
    markdownListItem = markdownListItem.darken(factor),
    markdownListEnumeration = markdownListEnumeration.darken(factor),
    markdownCodeBlock = markdownCodeBlock.darken(factor),
    syntaxComment = syntaxComment.darken(factor),
    syntaxKeyword = syntaxKeyword.darken(factor),
    syntaxFunction = syntaxFunction.darken(factor),
    syntaxVariable = syntaxVariable.darken(factor),
    syntaxString = syntaxString.darken(factor),
    syntaxNumber = syntaxNumber.darken(factor),
    syntaxType = syntaxType.darken(factor),
    syntaxOperator = syntaxOperator.darken(factor),
    syntaxPunctuation = syntaxPunctuation.darken(factor),
    todoPending = todoPending.darken(factor),
    todoInProgress = todoInProgress.darken(factor),
    todoCompleted = todoCompleted.darken(factor),
    todoCancelled = todoCancelled.darken(factor),
    todoPriorityHigh = todoPriorityHigh.darken(factor),
    todoPriorityMedium = todoPriorityMedium.darken(factor),
    todoPriorityLow = todoPriorityLow.darken(factor),
    border = border.darken(factor),
    borderActive = borderActive.darken(factor),
    borderSubtle = borderSubtle.darken(factor),
    thinkingOff = thinkingOff.darken(factor),
    thinkingMinimal = thinkingMinimal.darken(factor),
    thinkingLow = thinkingLow.darken(factor),
    thinkingMedium = thinkingMedium.darken(factor),
    thinkingHigh = thinkingHigh.darken(factor),
    thinkingXhigh = thinkingXhigh.darken(factor),
    thinkingMax = thinkingMax.darken(factor),
    bashModeBorder = bashModeBorder.darken(factor),
)

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
    background = TerminalColors.BG_BLACK,
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

        // Derive distinct, tonal surfaces from the theme's own colors rather than trusting
        // backgroundPanel/backgroundElement, which some themes (e.g. Vesper) leave equal to
        // background — which would make every tinted block and dialog vanish into the base.
        fun lighten(c: RgbColor, amount: Float): Color = blendColors(255, 255, 255, c.r, c.g, c.b, amount)
        fun tint(base: RgbColor, hue: RgbColor, amount: Float): Color =
            blendColors(hue.r, hue.g, hue.b, base.r, base.g, base.b, amount)

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
                // A readable de-emphasized text color — NOT borderSubtle, which many themes set
                // near the background (e.g. Vesper #1C1C1C) and would render dim text invisible.
                dim = tint(textMuted, background, 0.35f),
            ),
            background = background.color,
            userMessageBg = lighten(background, 0.09f),
            userMessageText = text.color,
            customMessageBg = tint(background, accent, 0.12f),
            customMessageText = text.color,
            customMessageLabel = accent.color,
            toolPendingBg = lighten(background, 0.06f),
            toolSuccessBg = tint(background, success, 0.12f),
            toolErrorBg = tint(background, error, 0.16f),
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
            statusBarBg = Color.Unspecified,
            overlayBg = lighten(background, 0.13f),
            overlaySelectedBg = lighten(background, 0.24f),
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
