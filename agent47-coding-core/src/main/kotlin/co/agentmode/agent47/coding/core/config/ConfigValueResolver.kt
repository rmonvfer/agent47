package co.agentmode.agent47.coding.core.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves configuration values that may reference environment variables or shell commands.
 *
 * Value prefixes:
 *  - `!` : Execute a shell command, cache the result for the process lifetime (10s timeout)
 *  - `$` : Look up an environment variable
 *  - otherwise : Use the literal string
 */
public object ConfigValueResolver {

    private val shellCache: ConcurrentHashMap<String, String?> = ConcurrentHashMap()

    public suspend fun resolve(value: String): String? {
        if (value.length < 2) return value
        if (value[0] == '!') return resolveShellCommand(value.substring(1))

        // A whole-value $NAME / ${NAME} returns null when unset so callers can fall through.
        wholeEnvReference(value)?.let { return System.getenv(it) }
        // Otherwise interpolate ${NAME}/$NAME occurrences in place (unset -> empty).
        if (value.contains('$')) return interpolateEnv(value)
        return value
    }

    public fun resolveSync(value: String): String {
        if (value.length < 2) return value
        if (value[0] == '!') return value

        wholeEnvReference(value)?.let { return System.getenv(it) ?: value }
        if (value.contains('$')) return interpolateEnv(value)
        return value
    }

    private fun wholeEnvReference(value: String): String? {
        if (value.startsWith("\${") && value.endsWith("}")) {
            val name = value.substring(2, value.length - 1)
            return if (isEnvName(name)) name else null
        }
        if (value.startsWith("$")) {
            val name = value.substring(1)
            return if (isEnvName(name)) name else null
        }
        return null
    }

    /**
     * Interpolate `${NAME}` and `$NAME` references against the environment. `$$` is a literal `$`,
     * and a `$` not starting a valid reference is kept verbatim. Unset variables expand to empty.
     */
    private fun interpolateEnv(value: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '$' || i + 1 >= value.length) {
                sb.append(c)
                i++
                continue
            }
            when (val next = value[i + 1]) {
                '$' -> {
                    sb.append('$')
                    i += 2
                }
                '{' -> {
                    val end = value.indexOf('}', i + 2)
                    if (end < 0) {
                        sb.append(c)
                        i++
                    } else {
                        sb.append(System.getenv(value.substring(i + 2, end)) ?: "")
                        i = end + 1
                    }
                }
                else -> {
                    if (next == '_' || next.isLetter()) {
                        var j = i + 1
                        while (j < value.length && (value[j] == '_' || value[j].isLetterOrDigit())) j++
                        sb.append(System.getenv(value.substring(i + 1, j)) ?: "")
                        i = j
                    } else {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun isEnvName(name: String): Boolean =
        name.isNotEmpty() && (name[0] == '_' || name[0].isLetter()) &&
            name.all { it == '_' || it.isLetterOrDigit() }

    private suspend fun resolveShellCommand(command: String): String? {
        shellCache[command]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()
                val completed = process.waitFor(10, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@withContext null
                }
                if (process.exitValue() != 0) return@withContext null
                val output = process.inputStream.bufferedReader().readText().trim()
                val result = output.ifBlank { null }
                if (result != null) shellCache[command] = result
                result
            } catch (_: Exception) {
                null
            }
        }
    }
}
