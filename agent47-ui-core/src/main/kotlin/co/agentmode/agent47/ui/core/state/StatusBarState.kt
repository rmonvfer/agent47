package co.agentmode.agent47.ui.core.state

/**
 * Data model for the status bar, shared between TUI and GUI.
 */
public data class StatusBarState(
    val cwdName: String,
    val branch: String?,
    val modelId: String?,
    val thinking: Boolean,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
    val cost: Double?,
    val contextTokens: Int?,
    val contextWindow: Int?,
    val busy: Boolean = false,
    val spinnerFrame: Int = 0,
)
