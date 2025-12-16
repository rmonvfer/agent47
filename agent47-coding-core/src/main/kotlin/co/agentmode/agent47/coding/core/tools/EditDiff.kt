package co.agentmode.agent47.coding.core.tools

import com.github.difflib.DiffUtils
import java.nio.file.Files
import java.nio.file.Path
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
    return text
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

    val base = match.contentForReplacement
    val updated = base.substring(0, match.index) + normalizedNew + base.substring(match.index + match.matchLength)

    if (base == updated) {
        return EditDiffPreview.Error("No changes would be made to $path. The replacement produces identical content.")
    }

    return EditDiffPreview.Success(generateDiffString(base, updated))
}
