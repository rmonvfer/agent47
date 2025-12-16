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
    val showUsageFooter: Boolean? = null,
)
