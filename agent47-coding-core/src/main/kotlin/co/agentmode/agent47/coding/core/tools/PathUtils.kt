package co.agentmode.agent47.coding.core.tools

import java.nio.file.Path
import kotlin.io.path.exists

private val unicodeSpaces: Regex = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")
private const val narrowNoBreakSpace: String = "\u202F"

public fun expandPath(rawPath: String): String {
    val withoutAt = if (rawPath.startsWith("@")) rawPath.substring(1) else rawPath
    val normalizedSpaces = withoutAt.replace(unicodeSpaces, " ")

    if (normalizedSpaces == "~") {
        return System.getProperty("user.home")
    }
    if (normalizedSpaces.startsWith("~/")) {
        return System.getProperty("user.home") + normalizedSpaces.substring(1)
    }
    return normalizedSpaces
}

public fun resolveToCwd(path: String, cwd: Path): Path {
    val expanded = Path.of(expandPath(path))
    return if (expanded.isAbsolute) expanded else cwd.resolve(expanded).normalize()
}

public fun resolveReadPath(path: String, cwd: Path): Path {
    val resolved = resolveToCwd(path, cwd)
    if (resolved.exists()) {
        return resolved
    }

    val amPmVariant = Path.of(resolved.toString().replace(Regex(" (AM|PM)\\."), "${narrowNoBreakSpace}$1."))
    if (amPmVariant.exists()) {
        return amPmVariant
    }

    val nfdVariant = Path.of(resolved.toString().normalizeNfd())
    if (nfdVariant.exists()) {
        return nfdVariant
    }

    val curlyVariant = Path.of(resolved.toString().replace("'", "’"))
    if (curlyVariant.exists()) {
        return curlyVariant
    }

    val nfdCurly = Path.of(nfdVariant.toString().replace("'", "’"))
    if (nfdCurly.exists()) {
        return nfdCurly
    }

    return resolved
}

internal fun Path.matchesGlob(glob: String): Boolean {
    val matcher = fileSystem.getPathMatcher("glob:$glob")
    return matcher.matches(this) || matcher.matches(fileName)
}

private fun String.normalizeNfd(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
