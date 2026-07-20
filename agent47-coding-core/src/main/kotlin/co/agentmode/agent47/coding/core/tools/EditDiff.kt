package co.agentmode.agent47.coding.core.tools

import com.github.difflib.DiffUtils
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.math.max

public fun detectLineEnding(content: String): String {
    val crlfIndex = content.indexOf("\r\n")
    val lfIndex = content.indexOf("\n")
    if (lfIndex == -1) {
        return "\n"
    }
    if (crlfIndex == -1) {
        return "\n"
    }
    return if (crlfIndex < lfIndex) "\r\n" else "\n"
}

public fun normalizeToLf(text: String): String {
    return text.replace("\r\n", "\n").replace("\r", "\n")
}

public fun restoreLineEndings(text: String, ending: String): String {
    return if (ending == "\r\n") text.replace("\n", "\r\n") else text
}

public fun normalizeForFuzzyMatch(text: String): String {
    return Normalizer.normalize(text, Normalizer.Form.NFKC)
        .split("\n")
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("[\\u2018\\u2019\\u201A\\u201B]"), "'")
        .replace(Regex("[\\u201C\\u201D\\u201E\\u201F]"), "\"")
        .replace(Regex("[\\u2010\\u2011\\u2012\\u2013\\u2014\\u2015\\u2212]"), "-")
        .replace(Regex("[\\u00A0\\u2002-\\u200A\\u202F\\u205F\\u3000]"), " ")
}

public data class FuzzyMatchResult(
    val found: Boolean,
    val index: Int,
    val matchLength: Int,
    val usedFuzzyMatch: Boolean,
    val contentForReplacement: String,
)

public fun fuzzyFindText(content: String, oldText: String): FuzzyMatchResult {
    val exactIndex = content.indexOf(oldText)
    if (exactIndex >= 0) {
        return FuzzyMatchResult(
            found = true,
            index = exactIndex,
            matchLength = oldText.length,
            usedFuzzyMatch = false,
            contentForReplacement = content,
        )
    }

    val fuzzyContent = normalizeForFuzzyMatch(content)
    val fuzzyOldText = normalizeForFuzzyMatch(oldText)
    val fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText)

    if (fuzzyIndex < 0) {
        return FuzzyMatchResult(
            found = false,
            index = -1,
            matchLength = 0,
            usedFuzzyMatch = false,
            contentForReplacement = content,
        )
    }

    return FuzzyMatchResult(
        found = true,
        index = fuzzyIndex,
        matchLength = fuzzyOldText.length,
        usedFuzzyMatch = true,
        contentForReplacement = fuzzyContent,
    )
}

public data class TextReplacement(
    val matchIndex: Int,
    val matchLength: Int,
    val newText: String,
)

private data class LineSpan(val start: Int, val end: Int)

/** Split content into lines, keeping each line's trailing newline so a join reconstructs the input exactly. */
private fun splitLinesWithEndings(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    return Regex("[^\\n]*\\n|[^\\n]+").findAll(content).map { it.value }.toList()
}

private fun getLineSpans(content: String): List<LineSpan> {
    var offset = 0
    return splitLinesWithEndings(content).map { line ->
        val span = LineSpan(offset, offset + line.length)
        offset = span.end
        span
    }
}

private fun getReplacementLineRange(lines: List<LineSpan>, replacement: TextReplacement): Pair<Int, Int> {
    val replacementStart = replacement.matchIndex
    val replacementEnd = replacement.matchIndex + replacement.matchLength

    var startLine = -1
    for (i in lines.indices) {
        val line = lines[i]
        if (replacementStart >= line.start && replacementStart < line.end) {
            startLine = i
            break
        }
    }
    require(startLine != -1) { "Replacement range is outside the base content." }

    var endLine = startLine
    while (endLine < lines.size && lines[endLine].end < replacementEnd) {
        endLine++
    }
    require(endLine < lines.size) { "Replacement range is outside the base content." }

    return startLine to (endLine + 1)
}

/** Apply text replacements to [content] in reverse order so earlier offsets stay stable. */
public fun applyReplacements(content: String, replacements: List<TextReplacement>, offset: Int = 0): String {
    var result = content
    for (i in replacements.indices.reversed()) {
        val replacement = replacements[i]
        val matchIndex = replacement.matchIndex - offset
        result = result.substring(0, matchIndex) +
            replacement.newText +
            result.substring(matchIndex + replacement.matchLength)
    }
    return result
}

/**
 * Apply replacements matched against [baseContent] (a normalized view of the original) to
 * [originalContent], preserving unchanged line blocks from the original. Only the lines a
 * replacement actually touches are rewritten from the normalized base; every other line keeps
 * its exact original bytes. Requires both to have the same line count.
 */
public fun applyReplacementsPreservingUnchangedLines(
    originalContent: String,
    baseContent: String,
    replacements: List<TextReplacement>,
): String {
    val originalLines = splitLinesWithEndings(originalContent)
    val baseLines = getLineSpans(baseContent)
    require(originalLines.size == baseLines.size) {
        "Cannot preserve unchanged lines because the base content has a different line count."
    }

    data class Group(val startLine: Int, var endLine: Int, val replacements: MutableList<TextReplacement>)

    val groups = mutableListOf<Group>()
    val sorted = replacements.sortedBy { it.matchIndex }
    for (replacement in sorted) {
        val (rangeStart, rangeEnd) = getReplacementLineRange(baseLines, replacement)
        val current = groups.lastOrNull()
        if (current != null && rangeStart < current.endLine) {
            current.endLine = max(current.endLine, rangeEnd)
            current.replacements.add(replacement)
        } else {
            groups.add(Group(rangeStart, rangeEnd, mutableListOf(replacement)))
        }
    }

    var originalLineIndex = 0
    val result = StringBuilder()
    for (group in groups) {
        for (i in originalLineIndex until group.startLine) {
            result.append(originalLines[i])
        }
        val groupStartOffset = baseLines[group.startLine].start
        val groupEndOffset = baseLines[group.endLine - 1].end
        result.append(
            applyReplacements(
                baseContent.substring(groupStartOffset, groupEndOffset),
                group.replacements,
                groupStartOffset,
            ),
        )
        originalLineIndex = group.endLine
    }
    for (i in originalLineIndex until originalLines.size) {
        result.append(originalLines[i])
    }
    return result.toString()
}

/**
 * Apply a single matched replacement to LF-normalized content. When the match used fuzzy
 * normalization, only the touched line span is rewritten from the normalized base while every
 * other line keeps its exact original bytes; an exact match splices directly.
 */
public fun applyMatchedReplacement(
    normalizedContent: String,
    match: FuzzyMatchResult,
    newText: String,
): String {
    val replacement = TextReplacement(match.index, match.matchLength, newText)
    return if (match.usedFuzzyMatch) {
        applyReplacementsPreservingUnchangedLines(normalizedContent, match.contentForReplacement, listOf(replacement))
    } else {
        applyReplacements(match.contentForReplacement, listOf(replacement))
    }
}

public data class StrippedBom(
    val bom: String,
    val text: String,
)

public fun stripBom(content: String): StrippedBom {
    return if (content.startsWith("\uFEFF")) {
        StrippedBom(bom = "\uFEFF", text = content.substring(1))
    } else {
        StrippedBom(bom = "", text = content)
    }
}

public data class DiffResult(
    val diff: String,
    val firstChangedLine: Int?,
)

public fun generateDiffString(oldContent: String, newContent: String, contextLines: Int = 4): DiffResult {
    val oldLines = oldContent.split("\n")
    val newLines = newContent.split("\n")
    val patch = DiffUtils.diff(oldLines, newLines)

    if (patch.deltas.isEmpty()) {
        return DiffResult(diff = "", firstChangedLine = null)
    }

    val maxLine = max(oldLines.size, newLines.size)
    val width = maxLine.toString().length
    val output = mutableListOf<String>()
    var firstChangedLine: Int? = null

    patch.deltas.forEach { delta ->
        val source = delta.source
        val target = delta.target

        if (firstChangedLine == null) {
            firstChangedLine = target.position + 1
        }

        val contextStart = max(0, source.position - contextLines)
        if (contextStart < source.position) {
            for (index in contextStart until source.position) {
                val lineNo = (index + 1).toString().padStart(width, ' ')
                output += " $lineNo ${oldLines[index]}"
            }
        }

        source.lines.forEachIndexed { index, line ->
            val lineNo = (source.position + index + 1).toString().padStart(width, ' ')
            output += "-$lineNo $line"
        }

        target.lines.forEachIndexed { index, line ->
            val lineNo = (target.position + index + 1).toString().padStart(width, ' ')
            output += "+$lineNo $line"
        }
    }

    return DiffResult(diff = output.joinToString("\n"), firstChangedLine = firstChangedLine)
}

public sealed interface EditDiffPreview {
    public data class Success(val result: DiffResult) : EditDiffPreview
    public data class Error(val message: String) : EditDiffPreview
}

public fun computeEditDiff(path: String, oldText: String, newText: String, cwd: Path): EditDiffPreview {
    val absolutePath = resolveToCwd(path, cwd)

    if (!Files.exists(absolutePath)) {
        return EditDiffPreview.Error("File not found: $path")
    }

    val raw = Files.readString(absolutePath)
    val stripped = stripBom(raw)

    val normalizedContent = normalizeToLf(stripped.text)
    val normalizedOld = normalizeToLf(oldText)
    val normalizedNew = normalizeToLf(newText)

    if (normalizedOld.isEmpty()) {
        return EditDiffPreview.Error("oldText must not be empty in $path.")
    }

    val match = fuzzyFindText(normalizedContent, normalizedOld)
    if (!match.found) {
        return EditDiffPreview.Error(
            "Could not find the exact text in $path. The old text must match exactly including all whitespace and newlines.",
        )
    }

    val fuzzyContent = normalizeForFuzzyMatch(normalizedContent)
    val fuzzyOld = normalizeForFuzzyMatch(normalizedOld)
    val occurrences = fuzzyContent.split(fuzzyOld).size - 1

    if (occurrences > 1) {
        return EditDiffPreview.Error(
            "Found $occurrences occurrences of the text in $path. The text must be unique. Please provide more context.",
        )
    }

    val updated = applyMatchedReplacement(normalizedContent, match, normalizedNew)

    if (normalizedContent == updated) {
        return EditDiffPreview.Error("No changes would be made to $path. The replacement produces identical content.")
    }

    return EditDiffPreview.Success(generateDiffString(normalizedContent, updated))
}
