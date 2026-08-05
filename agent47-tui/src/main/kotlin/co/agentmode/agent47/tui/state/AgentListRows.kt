package co.agentmode.agent47.tui.state

import co.agentmode.agent47.coding.core.agents.RunningAgent

/** Where a row's state dot lands on the running/idle/done spectrum. */
internal enum class AgentRowState { RUNNING, IDLE, DONE }

/** One row of the runtime agent list: the orchestrator ("main") or a background agent. */
internal data class AgentListRow(
    val id: String,
    val label: String,
    val state: AgentRowState,
    val activity: String,
    val elapsedMs: Long?,
    val tokens: Long?,
    val isMain: Boolean,
)

/** Id/label of the synthetic row representing the orchestrator. */
internal const val MAIN_AGENT_ROW_ID: String = "main"

/**
 * Builds the rows for the runtime agent list: a `main` row for the orchestrator, followed by
 * every visible background agent (running, queued, or completed-unread) except [focusedAgentId],
 * which is hidden because its own transcript already fills the screen in focus mode.
 */
internal fun buildAgentListRows(
    agents: List<RunningAgent>,
    focusedAgentId: String?,
    mainRunning: Boolean,
    mainActivity: String,
    now: Long,
): List<AgentListRow> {
    if (agents.isEmpty()) return emptyList()
    val mainRow = AgentListRow(
        id = MAIN_AGENT_ROW_ID,
        label = MAIN_AGENT_ROW_ID,
        state = if (mainRunning) AgentRowState.RUNNING else AgentRowState.IDLE,
        activity = mainActivity,
        elapsedMs = null,
        tokens = null,
        isMain = true,
    )
    val agentRows = agents
        .filter { it.id != focusedAgentId }
        .map { it.toRow(now) }
    return listOf(mainRow) + agentRows
}

private fun RunningAgent.toRow(now: Long): AgentListRow = AgentListRow(
    id = id,
    label = id,
    state = rowState(),
    activity = rowActivity(),
    elapsedMs = rowElapsedMs(now),
    tokens = progress?.tokens,
    isMain = false,
)

private fun RunningAgent.rowState(): AgentRowState = when {
    done -> AgentRowState.DONE
    status == RunningAgent.Status.RUNNING -> AgentRowState.RUNNING
    else -> AgentRowState.IDLE
}

private fun RunningAgent.rowElapsedMs(now: Long): Long? = when {
    startedAt <= 0L -> null
    done -> (completedAt - startedAt).coerceAtLeast(0)
    else -> (now - startedAt).coerceAtLeast(0)
}

private fun RunningAgent.rowActivity(): String = when {
    done -> if (rowFailed()) "failed" else "done"
    status == RunningAgent.Status.QUEUED -> "queued"
    else -> activityOf(progress?.currentTool, progress?.streamingText)
}

private fun RunningAgent.rowFailed(): Boolean = result?.let { it.aborted || it.error != null || it.exitCode != 0 } ?: true

/** Prefer the running tool, else the text the agent is currently streaming, else a starting hint. */
internal fun activityOf(currentTool: String?, streamingText: String?): String = when {
    currentTool != null -> "running $currentTool…"
    !streamingText.isNullOrBlank() -> streamingText.replace('\n', ' ').trim()
    else -> "starting…"
}

/** Next selection index, wrapping from the last row back to the first. */
internal fun agentSelectionMoveDown(current: Int, rowCount: Int): Int {
    if (rowCount <= 0) return 0
    return if (current >= rowCount - 1) 0 else current + 1
}

/** Previous selection index, wrapping from the first row back to the last. */
internal fun agentSelectionMoveUp(current: Int, rowCount: Int): Int {
    if (rowCount <= 0) return 0
    return if (current <= 0) rowCount - 1 else current - 1
}
