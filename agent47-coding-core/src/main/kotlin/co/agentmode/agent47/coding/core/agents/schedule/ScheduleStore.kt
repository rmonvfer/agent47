package co.agentmode.agent47.coding.core.agents.schedule

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Session-scoped persistence for scheduled subagents. Backed by a single JSON file
 * (`<projectDir>/subagent-schedules/<sessionId>.json`). Reads hit an in-memory cache; writes take
 * an in-process [Mutex] and persist atomically (temp file + move). A single JVM process owns the
 * file, so an in-process mutex suffices (no cross-process lock).
 */
public class ScheduleStore(private val filePath: Path) {

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Volatile
    private var jobs: Map<String, ScheduledSubagent> = loadFromDisk()

    public fun list(): List<ScheduledSubagent> = jobs.values.sortedBy { it.createdAt }

    public fun get(id: String): ScheduledSubagent? = jobs[id]

    public fun hasName(name: String): Boolean = jobs.values.any { it.name == name }

    public suspend fun add(job: ScheduledSubagent): Unit = mutex.withLock {
        jobs = jobs + (job.id to job)
        persist()
    }

    public suspend fun remove(id: String): Boolean = mutex.withLock {
        if (!jobs.containsKey(id)) return@withLock false
        jobs = jobs - id
        persist()
        true
    }

    /** Applies [transform] to the job with [id] (if present), persists, and returns the updated job. */
    public suspend fun update(id: String, transform: (ScheduledSubagent) -> ScheduledSubagent): ScheduledSubagent? =
        mutex.withLock {
            val current = jobs[id] ?: return@withLock null
            val updated = transform(current)
            jobs = jobs + (id to updated)
            persist()
            updated
        }

    private fun persist() {
        runCatching {
            filePath.parent?.let { Files.createDirectories(it) }
            val data = ScheduleStoreData(version = 1, jobs = jobs.values.sortedBy { it.createdAt })
            val tmp = filePath.resolveSibling("${filePath.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(ScheduleStoreData.serializer(), data))
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun loadFromDisk(): Map<String, ScheduledSubagent> {
        if (!filePath.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(ScheduleStoreData.serializer(), filePath.readText())
                .jobs.associateBy { it.id }
        }.getOrDefault(emptyMap())
    }
}
