package co.agentmode.agent47.coding.core.settings

import co.agentmode.agent47.ai.types.Agent47Json
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Operational settings for the background-subagent system. Loaded from a global
 * (`~/.agent47/subagents.json`) file merged under a project (`<cwd>/.agent47/subagents.json`) file,
 * with project values overriding global. Absent or malformed files fall back to defaults; every
 * value is clamped/validated on load. This file is separate from the general [Settings]/`settings.json`.
 */
@Serializable
public data class SubagentsSettings(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    /** 0 means unlimited. */
    val defaultMaxTurns: Int = 0,
    val graceTurns: Int = DEFAULT_GRACE_TURNS,
    val defaultJoinMode: String = "smart",
    val schedulingEnabled: Boolean = true,
    val disableDefaultAgents: Boolean = false,
    val outputTranscript: Boolean = true,
    val fleetView: Boolean = true,
    val widgetMode: String = "background",
    /** Push completion notifications into the orchestrator (opt-in). Pull via check_inbox is always available. */
    val pushNotifications: Boolean = false,
    val toolDescriptionMode: String = "full",
) {
    public companion object {
        public const val DEFAULT_MAX_CONCURRENT: Int = 4
        public const val DEFAULT_GRACE_TURNS: Int = 5
        public const val MAX_CONCURRENT_CEILING: Int = 1024
        public const val MAX_TURNS_CEILING: Int = 10_000
        public const val GRACE_TURNS_CEILING: Int = 1_000

        public val JOIN_MODES: Set<String> = setOf("async", "group", "smart")
        public val WIDGET_MODES: Set<String> = setOf("all", "background", "off")
        public val TOOL_DESCRIPTION_MODES: Set<String> = setOf("full", "compact", "custom")
    }
}

/** Thread-safe, session-scoped source of truth for live subagent settings. */
public class SubagentsSettingsState(initial: SubagentsSettings) {
    private val value: AtomicReference<SubagentsSettings> = AtomicReference(initial)

    public fun get(): SubagentsSettings = value.get()

    public fun set(settings: SubagentsSettings) {
        value.set(settings)
    }
}

/** All-nullable view of a [SubagentsSettings] file, so an absent key is distinct from a set default. */
@Serializable
public data class SubagentsSettingsPatch(
    val maxConcurrent: Int? = null,
    val defaultMaxTurns: Int? = null,
    val graceTurns: Int? = null,
    val defaultJoinMode: String? = null,
    val schedulingEnabled: Boolean? = null,
    val disableDefaultAgents: Boolean? = null,
    val outputTranscript: Boolean? = null,
    val fleetView: Boolean? = null,
    val widgetMode: String? = null,
    val pushNotifications: Boolean? = null,
    val toolDescriptionMode: String? = null,
)

public object SubagentsSettingsManager {

    public fun load(globalPath: Path, projectPath: Path): SubagentsSettings {
        val global = loadPatch(globalPath)
        val project = loadPatch(projectPath)
        return materialize(global, project)
    }

    /** Writes [settings] to the project file (creating parent dirs). Returns false on I/O failure. */
    public fun save(projectPath: Path, settings: SubagentsSettings): Boolean = runCatching {
        projectPath.parent?.let { Files.createDirectories(it) }
        projectPath.writeText(Agent47Json.encodeToString(SubagentsSettings.serializer(), settings))
        true
    }.getOrDefault(false)

    private fun loadPatch(path: Path): SubagentsSettingsPatch? {
        if (!path.exists()) return null
        return runCatching {
            Agent47Json.decodeFromString(SubagentsSettingsPatch.serializer(), path.readText())
        }.getOrNull()
    }

    private fun materialize(global: SubagentsSettingsPatch?, project: SubagentsSettingsPatch?): SubagentsSettings {
        val d = SubagentsSettings()
        return SubagentsSettings(
            maxConcurrent = (project?.maxConcurrent ?: global?.maxConcurrent ?: d.maxConcurrent)
                .coerceIn(1, SubagentsSettings.MAX_CONCURRENT_CEILING),
            defaultMaxTurns = (project?.defaultMaxTurns ?: global?.defaultMaxTurns ?: d.defaultMaxTurns)
                .coerceIn(0, SubagentsSettings.MAX_TURNS_CEILING),
            graceTurns = (project?.graceTurns ?: global?.graceTurns ?: d.graceTurns)
                .coerceIn(1, SubagentsSettings.GRACE_TURNS_CEILING),
            defaultJoinMode = pick(project?.defaultJoinMode, global?.defaultJoinMode, d.defaultJoinMode, SubagentsSettings.JOIN_MODES),
            schedulingEnabled = project?.schedulingEnabled ?: global?.schedulingEnabled ?: d.schedulingEnabled,
            disableDefaultAgents = project?.disableDefaultAgents ?: global?.disableDefaultAgents ?: d.disableDefaultAgents,
            outputTranscript = project?.outputTranscript ?: global?.outputTranscript ?: d.outputTranscript,
            fleetView = project?.fleetView ?: global?.fleetView ?: d.fleetView,
            widgetMode = pick(project?.widgetMode, global?.widgetMode, d.widgetMode, SubagentsSettings.WIDGET_MODES),
            pushNotifications = project?.pushNotifications ?: global?.pushNotifications ?: d.pushNotifications,
            toolDescriptionMode = pick(
                project?.toolDescriptionMode,
                global?.toolDescriptionMode,
                d.toolDescriptionMode,
                SubagentsSettings.TOOL_DESCRIPTION_MODES,
            ),
        )
    }

    /**
     * Sanitizes each scope independently (an invalid value is dropped, not clamped), then merges
     * project over global, falling back to [default]. So an invalid project value yields the valid
     * global value rather than the default.
     */
    private fun pick(project: String?, global: String?, default: String, allowed: Set<String>): String {
        val p = project?.takeIf { it in allowed }
        val g = global?.takeIf { it in allowed }
        return p ?: g ?: default
    }
}
