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

        return when (value[0]) {
            '!' -> resolveShellCommand(value.substring(1))
            '$' -> System.getenv(value.substring(1))
            else -> value
        }
    }

    public fun resolveSync(value: String): String {
        if (value.length < 2) return value
        if (value[0] == '$') return System.getenv(value.substring(1)) ?: value
        return value
    }

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
