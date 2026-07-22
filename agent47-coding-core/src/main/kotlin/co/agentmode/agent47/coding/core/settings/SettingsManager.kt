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
    private var globalSettings: Settings,
    private val globalPath: Path?,
    private val projectPath: Path?,
) {
    public fun get(): Settings = settings

    public fun update(transform: (Settings) -> Settings) {
        settings = transform(settings)
        // Apply the same change to the global-scope settings and persist only those, so a project's
        // merged values (and encoded defaults) are never written back into the global file.
        globalSettings = transform(globalSettings)
        save()
    }

    private fun save() {
        val path = globalPath ?: return
        path.parent?.let { Files.createDirectories(it) }
        path.writeText(Agent47Json.encodeToString(Settings.serializer(), globalSettings))
    }

    public companion object {
        public fun create(globalPath: Path, projectPath: Path): SettingsManager {
            val globalPatch = loadPatch(globalPath)
            val projectPatch = loadPatch(projectPath)
            val merged = materialize(globalPatch, projectPatch)
            // The global-only view is what save() persists, so a project scope never leaks globally.
            val globalOnly = materialize(globalPatch, null)
            return SettingsManager(merged, globalOnly, globalPath, projectPath)
        }

        public fun inMemory(settings: Settings = Settings()): SettingsManager {
            return SettingsManager(settings, settings, null, null)
        }

        private fun loadPatch(path: Path): SettingsPatch? {
            if (!path.exists()) return null
            return runCatching {
                Agent47Json.decodeFromString(SettingsPatch.serializer(), path.readText())
            }.getOrNull()
        }

        // Deep-merge two scopes: a null field takes the other scope's value, and nested
        // compaction/retry merge field-by-field so an absent object doesn't reset the other scope.
        private fun materialize(global: SettingsPatch?, project: SettingsPatch?): Settings {
            val defaults = Settings()
            return Settings(
                defaultProvider = project?.defaultProvider ?: global?.defaultProvider,
                defaultModel = project?.defaultModel ?: global?.defaultModel,
                defaultThinkingLevel = project?.defaultThinkingLevel ?: global?.defaultThinkingLevel,
                compaction = mergeCompaction(global?.compaction, project?.compaction),
                retry = mergeRetry(global?.retry, project?.retry),
                shellPath = project?.shellPath ?: global?.shellPath,
                shellCommandPrefix = project?.shellCommandPrefix ?: global?.shellCommandPrefix,
                modelRoles = (global?.modelRoles ?: emptyMap()) + (project?.modelRoles ?: emptyMap()),
                taskMaxRecursionDepth = project?.taskMaxRecursionDepth
                    ?: global?.taskMaxRecursionDepth
                    ?: defaults.taskMaxRecursionDepth,
                theme = project?.theme ?: global?.theme,
                themeAppearance = project?.themeAppearance ?: global?.themeAppearance,
                showUsageFooter = project?.showUsageFooter ?: global?.showUsageFooter,
                instructions = (global?.instructions ?: emptyList()) + (project?.instructions ?: emptyList()),
            )
        }

        private fun mergeCompaction(
            global: CompactionSettingsPatch?,
            project: CompactionSettingsPatch?,
        ): CompactionSettings {
            val d = CompactionSettings()
            return CompactionSettings(
                enabled = project?.enabled ?: global?.enabled ?: d.enabled,
                auto = project?.auto ?: global?.auto ?: d.auto,
                prune = project?.prune ?: global?.prune ?: d.prune,
                reserveTokens = project?.reserveTokens ?: global?.reserveTokens ?: d.reserveTokens,
                keepRecentTokens = project?.keepRecentTokens ?: global?.keepRecentTokens ?: d.keepRecentTokens,
            )
        }

        private fun mergeRetry(global: RetrySettingsPatch?, project: RetrySettingsPatch?): RetrySettings {
            val d = RetrySettings()
            return RetrySettings(
                enabled = project?.enabled ?: global?.enabled ?: d.enabled,
                maxRetries = project?.maxRetries ?: global?.maxRetries ?: d.maxRetries,
                baseDelayMs = project?.baseDelayMs ?: global?.baseDelayMs ?: d.baseDelayMs,
                maxDelayMs = project?.maxDelayMs ?: global?.maxDelayMs ?: d.maxDelayMs,
            )
        }
    }
}
