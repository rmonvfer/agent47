package co.agentmode.agent47.coding.core.settings

import co.agentmode.agent47.ai.types.Agent47Json
import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Loads and merges settings from global (~/.agent47/settings.json) and
 * project (<cwd>/.agent47/settings.json) configuration files.
 * Project settings override global settings for fields that are explicitly set.
 */
public class SettingsManager private constructor(
    private var settings: Settings,
    private val globalPath: Path?,
    private val projectPath: Path?,
) {
    public fun get(): Settings = settings

    public fun update(transform: (Settings) -> Settings) {
        settings = transform(settings)
        save()
    }

    private fun save() {
        val path = globalPath ?: return
        Files.createDirectories(path.parent)
        path.writeText(Agent47Json.encodeToString(Settings.serializer(), settings))
    }

    public companion object {
        public fun create(globalPath: Path, projectPath: Path): SettingsManager {
            val global = loadFromFile(globalPath)
            val project = loadFromFile(projectPath)
            val merged = merge(global, project)
            return SettingsManager(merged, globalPath, projectPath)
        }

        public fun inMemory(settings: Settings = Settings()): SettingsManager {
            return SettingsManager(settings, null, null)
        }

        private fun loadFromFile(path: Path): Settings? {
            if (!path.exists()) return null
            return runCatching {
                Agent47Json.decodeFromString(Settings.serializer(), path.readText())
            }.getOrNull()
        }

        private fun merge(global: Settings?, project: Settings?): Settings {
            if (global == null && project == null) return Settings()
            if (global == null) return project!!
            if (project == null) return global

            return Settings(
                defaultProvider = project.defaultProvider ?: global.defaultProvider,
                defaultModel = project.defaultModel ?: global.defaultModel,
                defaultThinkingLevel = project.defaultThinkingLevel ?: global.defaultThinkingLevel,
                compaction = mergeCompaction(global.compaction, project.compaction),
                retry = mergeRetry(global.retry, project.retry),
                shellPath = project.shellPath ?: global.shellPath,
                shellCommandPrefix = project.shellCommandPrefix ?: global.shellCommandPrefix,
                modelRoles = global.modelRoles + project.modelRoles,
                taskMaxRecursionDepth = project.taskMaxRecursionDepth,
                theme = project.theme ?: global.theme,
                showUsageFooter = project.showUsageFooter ?: global.showUsageFooter,
                instructions = global.instructions + project.instructions,
            )
        }

        private fun mergeCompaction(global: CompactionSettings, project: CompactionSettings): CompactionSettings {
            return CompactionSettings(
                enabled = project.enabled,
                reserveTokens = project.reserveTokens,
                keepRecentTokens = project.keepRecentTokens,
            )
        }

        private fun mergeRetry(global: RetrySettings, project: RetrySettings): RetrySettings {
            return RetrySettings(
                enabled = project.enabled,
                maxRetries = project.maxRetries,
                baseDelayMs = project.baseDelayMs,
                maxDelayMs = project.maxDelayMs,
            )
        }
    }
}
