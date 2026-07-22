package co.agentmode.agent47.coding.core.agents.schedule

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime

@Serializable
public enum class ScheduleType { CRON, ONCE, INTERVAL }

/** A subagent spawn registered to fire on a schedule. Session-scoped state. */
@Serializable
public data class ScheduledSubagent(
    val id: String,
    val name: String,
    val description: String,
    /** Normalized schedule string (ISO for once, raw interval/cron otherwise). */
    val schedule: String,
    val scheduleType: ScheduleType,
    val intervalMs: Long? = null,
    val subagentType: String,
    val prompt: String,
    val model: String? = null,
    val thinking: String? = null,
    val maxTurns: Int? = null,
    /** "worktree" or null. */
    val isolation: String? = null,
    val enabled: Boolean = true,
    val createdAt: String,
    val lastRun: String? = null,
    /** success | error | running */
    val lastStatus: String? = null,
    val nextRun: String? = null,
    val runCount: Int = 0,
)

@Serializable
public data class ScheduleStoreData(
    val version: Int = 1,
    val jobs: List<ScheduledSubagent> = emptyList(),
)

/** Input for creating a scheduled job; id/timestamps/state are derived. */
public data class NewJobInput(
    val name: String,
    val description: String,
    val schedule: String,
    val subagentType: String,
    val prompt: String,
    val model: String? = null,
    val thinking: String? = null,
    val maxTurns: Int? = null,
    val isolation: String? = null,
)

public data class DetectedSchedule(val type: ScheduleType, val intervalMs: Long?, val normalized: String)

/**
 * Parses schedule strings. Order matters: relative (`+10m`) and
 * interval (`5m`) both match digit+unit, so the leading `+` disambiguates. Cron is 6-field
 * (`second minute hour dom month dow`), validated via cron-utils' Spring (with-seconds) dialect.
 */
public object ScheduleParser {

    private val UNIT_MS = mapOf("s" to 1_000L, "m" to 60_000L, "h" to 3_600_000L, "d" to 86_400_000L)
    private val RELATIVE = Regex("""^\+(\d+)(s|m|h|d)$""")
    private val INTERVAL = Regex("""^(\d+)(s|m|h|d)$""")
    private val ISO_PREFIX = Regex("""^\d{4}-\d{2}-\d{2}T""")

    private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING))

    public fun detect(raw: String, now: Instant = Instant.now()): DetectedSchedule {
        val s = raw.trim()

        RELATIVE.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1].toLong() * UNIT_MS.getValue(m.groupValues[2])
            return DetectedSchedule(ScheduleType.ONCE, null, now.plusMillis(ms).toString())
        }

        INTERVAL.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1].toLong() * UNIT_MS.getValue(m.groupValues[2])
            return DetectedSchedule(ScheduleType.INTERVAL, ms, s)
        }

        if (ISO_PREFIX.containsMatchIn(s)) {
            val instant = runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
            if (instant != null) {
                require(instant.isAfter(now)) { "Scheduled time $s is in the past." }
                return DetectedSchedule(ScheduleType.ONCE, null, instant.toString())
            }
        }

        val fields = s.split(Regex("\\s+")).filter { it.isNotBlank() }
        require(fields.size == 6) {
            "Cron must have 6 fields (second minute hour dom month dow), got ${fields.size}. " +
                "Example: \"0 0 9 * * 1\" for 9am every Monday."
        }
        runCatching { cronParser.parse(s).validate() }.getOrElse {
            throw IllegalArgumentException("Invalid cron expression \"$s\": ${it.message}")
        }
        return DetectedSchedule(ScheduleType.CRON, null, s)
    }

    /** Next fire time for a cron expression, or null if none. */
    public fun nextCronRun(expr: String, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? =
        ExecutionTime.forCron(cronParser.parse(expr)).nextExecution(from).orElse(null)
}
