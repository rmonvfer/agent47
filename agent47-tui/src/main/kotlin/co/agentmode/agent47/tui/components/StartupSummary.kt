package co.agentmode.agent47.tui.components

import co.agentmode.agent47.coding.core.skills.Skill
import co.agentmode.agent47.tui.rendering.annotated
import co.agentmode.agent47.tui.rendering.wrapAnnotated
import co.agentmode.agent47.tui.theme.ThemeConfig
import com.jakewharton.mosaic.text.AnnotatedString
import com.jakewharton.mosaic.text.SpanStyle
import com.jakewharton.mosaic.text.buildAnnotatedString
import com.jakewharton.mosaic.text.withStyle
import com.jakewharton.mosaic.ui.TextStyle
import java.nio.file.Path

internal data class StartupSummary(
    val version: String,
    val cwd: Path,
    val contextFiles: List<Path>,
    val skills: List<Skill>,
    val extensionIds: List<String>,
)

internal fun renderStartupSummary(
    summary: StartupSummary,
    width: Int,
    expanded: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val contentWidth = width.coerceAtLeast(1)
    return (
        renderStartupHeader(summary.version, contentWidth, expanded, theme) +
            renderStartupSections(summary, contentWidth, expanded, theme)
        ).flatMap { wrapAnnotated(it, contentWidth) }
}

private fun renderStartupHeader(
    version: String,
    width: Int,
    expanded: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> = buildList {
    val dimStyle = SpanStyle(color = theme.colors.dim)

    add(annotated(""))
    add(
        buildAnnotatedString {
            withStyle(SpanStyle(color = theme.colors.accent, textStyle = TextStyle.Bold)) {
                append("agent47")
            }
            if (version.isNotBlank()) {
                withStyle(dimStyle) { append(" v$version") }
            }
        },
    )

    if (expanded) {
        expandedStartupHints(theme).forEach { addAll(wrapAnnotated(it, width)) }
    } else {
        addAll(wrapAnnotated(compactStartupHints(theme), width))
        addAll(
            wrapAnnotated(
                annotated("Press ctrl+o to show full startup help and loaded resources.", dimStyle),
                width,
            ),
        )
    }

    add(annotated(""))
    addAll(
        wrapAnnotated(
            annotated(
                "Agent47 can explain its own features and loaded configuration. " +
                    "Ask it how to use or extend Agent47.",
                dimStyle,
            ),
            width,
        ),
    )
}

private fun renderStartupSections(
    summary: StartupSummary,
    width: Int,
    expanded: Boolean,
    theme: ThemeConfig,
): List<AnnotatedString> {
    val sections = startupSections(summary)
    if (sections.isEmpty()) return emptyList()

    val dimStyle = SpanStyle(color = theme.colors.dim)
    val headingStyle = SpanStyle(color = theme.markdownHeading)
    return buildList {
        sections.forEach { section ->
            add(annotated(""))
            add(annotated("[${section.name}]", headingStyle))
            val items = if (expanded) section.expandedItems else section.compactItems
            val ordered = if (section.preserveOrder) items else items.sorted()
            if (expanded) {
                ordered.forEach { item -> addAll(indentedWrapped(item, width, dimStyle)) }
            } else {
                addAll(indentedWrapped(ordered.joinToString(", "), width, dimStyle))
            }
        }
    }
}

private fun startupSections(summary: StartupSummary): List<StartupSection> =
    buildList {
        if (summary.contextFiles.isNotEmpty()) {
            add(
                StartupSection(
                    name = "Context",
                    compactItems = summary.contextFiles.map { compactPath(it, summary.cwd) },
                    expandedItems = summary.contextFiles.map(::displayPath),
                    preserveOrder = true,
                ),
            )
        }
        if (summary.skills.isNotEmpty()) {
            add(
                StartupSection(
                    name = "Skills",
                    compactItems = summary.skills.map { it.name },
                    expandedItems = summary.skills.map { "${it.name}  ${displayPath(it.path)}" },
                ),
            )
        }
        if (summary.extensionIds.isNotEmpty()) {
            add(
                StartupSection(
                    name = "Extensions",
                    compactItems = summary.extensionIds,
                    expandedItems = summary.extensionIds,
                ),
            )
        }
    }

private data class StartupSection(
    val name: String,
    val compactItems: List<String>,
    val expandedItems: List<String>,
    val preserveOrder: Boolean = false,
)

private fun compactStartupHints(theme: ThemeConfig): AnnotatedString = buildAnnotatedString {
    appendHint("escape", "interrupt/double clear", theme)
    appendSeparator(theme)
    appendHint("ctrl+c", "interrupt/exit", theme)
    appendSeparator(theme)
    appendHint("ctrl+l", "clear", theme)
    appendSeparator(theme)
    appendHint("/", "commands", theme)
    appendSeparator(theme)
    appendHint("!", "bash", theme)
    appendSeparator(theme)
    appendHint("ctrl+o", "more", theme)
}

private fun expandedStartupHints(theme: ThemeConfig): List<AnnotatedString> =
    listOf(
        "escape" to "interrupt the active response, close a modal, or press twice to clear input",
        "ctrl+c" to "interrupt, then press twice to exit",
        "ctrl+l" to "clear the visible conversation",
        "ctrl+t" to "toggle thinking",
        "ctrl+p/ctrl+n" to "cycle models",
        "ctrl+g" to "toggle the latest thinking block",
        "ctrl+e" to "toggle the latest tool details",
        "ctrl+o" to "collapse startup help and loaded resources",
        "/" to "show commands",
        "!" to "run a local shell command",
        "/settings" to "open interactive settings",
    ).map { (key, description) ->
        buildAnnotatedString {
            appendHint(key, "to $description", theme)
        }
    }

private fun com.jakewharton.mosaic.text.AnnotatedString.Builder.appendHint(
    key: String,
    description: String,
    theme: ThemeConfig,
) {
    withStyle(SpanStyle(color = theme.colors.accent)) { append(key) }
    withStyle(SpanStyle(color = theme.colors.dim)) {
        append(" ")
        append(description)
    }
}

private fun com.jakewharton.mosaic.text.AnnotatedString.Builder.appendSeparator(theme: ThemeConfig) {
    withStyle(SpanStyle(color = theme.colors.muted)) { append(" · ") }
}

private fun indentedWrapped(
    text: String,
    width: Int,
    style: SpanStyle,
): List<AnnotatedString> {
    val contentWidth = (width - RESOURCE_INDENT.length).coerceAtLeast(1)
    return wrapAnnotated(annotated(text, style), contentWidth).map { line ->
        annotated(RESOURCE_INDENT, style) + line
    }
}

private fun compactPath(path: Path, cwd: Path): String {
    val absolute = path.toAbsolutePath().normalize()
    val absoluteCwd = cwd.toAbsolutePath().normalize()
    if (absolute.startsWith(absoluteCwd)) {
        return absoluteCwd.relativize(absolute).toString().ifBlank { "." }
    }
    return displayPath(absolute)
}

private fun displayPath(path: Path): String {
    val absolute = path.toAbsolutePath().normalize()
    val home = System.getProperty("user.home")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
    if (home != null && absolute.startsWith(home)) {
        val relative = home.relativize(absolute).toString()
        return if (relative.isBlank()) "~" else "~${absolute.fileSystem.separator}$relative"
    }
    return absolute.toString()
}

private const val RESOURCE_INDENT = "  "
