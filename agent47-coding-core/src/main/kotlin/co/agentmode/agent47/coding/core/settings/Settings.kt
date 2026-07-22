package co.agentmode.agent47.coding.core.settings

import co.agentmode.agent47.coding.core.compaction.CompactionSettings
import kotlinx.serialization.Serializable

@Serializable
public data class RetrySettings(
    val enabled: Boolean = true,
    val maxRetries: Int = 3,
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 30_000,
)

@Serializable
public data class Settings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val compaction: CompactionSettings = CompactionSettings(),
    val retry: RetrySettings = RetrySettings(),
    val shellPath: String? = null,
    val shellCommandPrefix: String? = null,
    val modelRoles: Map<String, String> = emptyMap(),
    val taskMaxRecursionDepth: Int = 2,
    val theme: String? = null,
    val themeAppearance: String? = null,
    val showUsageFooter: Boolean? = null,
    val instructions: List<String> = emptyList(),
)

/**
 * All-nullable representation of a settings file used only for loading, so a field absent from a
 * scope (null) is distinguishable from one explicitly set to its default value. Scopes are
 * deep-merged as patches before being materialized into [Settings].
 */
@Serializable
public data class CompactionSettingsPatch(
    val enabled: Boolean? = null,
    val auto: Boolean? = null,
    val prune: Boolean? = null,
    val reserveTokens: Int? = null,
    val keepRecentTokens: Int? = null,
)

@Serializable
public data class RetrySettingsPatch(
    val enabled: Boolean? = null,
    val maxRetries: Int? = null,
    val baseDelayMs: Long? = null,
    val maxDelayMs: Long? = null,
)

@Serializable
public data class SettingsPatch(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val compaction: CompactionSettingsPatch? = null,
    val retry: RetrySettingsPatch? = null,
    val shellPath: String? = null,
    val shellCommandPrefix: String? = null,
    val modelRoles: Map<String, String>? = null,
    val taskMaxRecursionDepth: Int? = null,
    val theme: String? = null,
    val themeAppearance: String? = null,
    val showUsageFooter: Boolean? = null,
    val instructions: List<String>? = null,
)
