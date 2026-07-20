package co.agentmode.agent47.coding.core.tools

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes file-mutating operations that target the same file so a concurrent read-modify-write
 * pair (e.g. two edits, or an edit racing a write) cannot lose an update. Operations on different
 * files still run in parallel. The lock is keyed by the canonical (real) path so different spellings
 * of the same file share one lock; a missing file falls back to its normalized absolute path.
 */
public object FileMutationLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun keyFor(path: Path): String {
        return runCatching { path.toRealPath().toString() }
            .getOrElse { path.toAbsolutePath().normalize().toString() }
    }

    public suspend fun <T> withPathLock(path: Path, block: suspend () -> T): T {
        val mutex = locks.getOrPut(keyFor(path)) { Mutex() }
        return mutex.withLock { block() }
    }
}
