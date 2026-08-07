package co.agentmode.agent47.tui.state

import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.tools.TodoState

/**
 * The todo list the task bar shows: the orchestrator's while the conversation is on screen, and
 * the focused background agent's own list while its transcript is in view. An agent the registry
 * does not know has no list, and the bar stays empty rather than showing another agent's tasks.
 */
internal fun taskBarTodoSource(
    mainTodoState: TodoState?,
    viewingAgentId: String?,
    backgroundAgents: BackgroundAgents?,
): TodoState? {
    if (viewingAgentId == null) return mainTodoState
    return backgroundAgents?.todosFor(viewingAgentId)
}
