package co.agentmode.agent47.app.cli

import co.agentmode.agent47.ai.types.Model

/**
 * Restrict the models offered for cycling (Ctrl+P/N) to those matching the `--models` glob
 * patterns. Falls back to all models when the flag is absent or matches nothing.
 */
internal fun scopedModels(all: List<Model>, models: String?): List<Model> {
    val patterns = models?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
    if (patterns.isNullOrEmpty()) return all

    val regexes = patterns.map { globToRegex(it) }
    val scoped = all.filter { model ->
        val full = "${model.provider.value}/${model.id}"
        regexes.any { it.matches(full) || it.matches(model.id) }
    }
    return scoped.ifEmpty { all }
}

internal fun globToRegex(pattern: String): Regex {
    val specials = "\\.[]{}()+-^$|"
    val sb = StringBuilder("^")
    for (c in pattern) {
        when {
            c == '*' -> sb.append(".*")
            c == '?' -> sb.append('.')
            specials.contains(c) -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
    sb.append('$')
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}
