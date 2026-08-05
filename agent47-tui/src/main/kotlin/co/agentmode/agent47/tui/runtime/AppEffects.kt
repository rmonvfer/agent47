package co.agentmode.agent47.tui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.coding.core.agents.SubAgentProgress
import co.agentmode.agent47.tui.controller.CompactionController
import co.agentmode.agent47.tui.controller.ConversationController
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
import co.agentmode.agent47.tui.state.activityOf
import co.agentmode.agent47.ui.core.state.ChatHistoryState
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Configures the client on first composition and submits the launch prompt when one was given.
 */
@Suppress("TooGenericExceptionCaught")
@Composable
internal fun InitialAgentSetup(
    state: TuiAppState,
    client: AgentClient,
    feed: TranscriptFeed,
    initialThinkingLevel: AgentThinkingLevel,
    initialModel: Model?,
    initialUserMessage: UserMessage?,
) {
    LaunchedEffect(Unit) {
        client.setThinkingLevel(initialThinkingLevel)
        initialModel?.let(client::setModel)
        initialUserMessage?.let { message ->
            state.chatHistory.appendMessage(message)
            state.activeSessionManager?.appendMessage(message)
            if (initialModel != null) {
                val job = launch {
                    try {
                        client.prompt(listOf(message))
                    } catch (_: CancellationException) {
                    } catch (error: Throwable) {
                        feed.appendErrorMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
                    } finally {
                        state.currentPromptJob = null
                    }
                }
                state.currentPromptJob = job
            }
        }
        if (initialModel == null) {
            feed.appendSystemMessage(
                "No model selected — use /provider to connect a provider, then /model to pick a model",
            )
        }
    }
}

/** Collects agent events into the conversation controller and triggers auto-compaction. */
@Composable
internal fun AgentEventCollector(
    state: TuiAppState,
    client: AgentClient,
    conversation: ConversationController,
    compaction: CompactionController,
) {
    LaunchedEffect(state.running) {
        if (!state.running) return@LaunchedEffect
        client.events.collect { event ->
            conversation.onAgentEvent(event)
            compaction.maybeAutoCompactAfter(event)
        }
    }
}

/** Advances the spinner frame while a response is streaming. */
@Composable
internal fun SpinnerTicker(state: TuiAppState) {
    LaunchedEffect(state.isStreaming) {
        if (!state.isStreaming) return@LaunchedEffect
        while (true) {
            delay(80L)
            state.spinnerFrame++
        }
    }
}

/**
 * Rebuilds the focused agent's transcript from its live messages while focus mode is active. The
 * registry exposes no live event stream for a background agent (only its message list and the
 * latest [SubAgentProgress] snapshot), so this polls both: messages replay the conversation, and
 * an activity line for the in-flight tool (if any) is appended at the tail for fidelity closer to
 * the main transcript's tool cards.
 */
@Composable
internal fun AgentTranscriptMirror(state: TuiAppState, backgroundAgents: BackgroundAgents?) {
    LaunchedEffect(state.viewingAgentId) {
        val id = state.viewingAgentId ?: return@LaunchedEffect
        // Reset scroll state once per focus target (not on every poll below): otherwise a stale
        // scrollTopLine/pinnedToBottom carried over from viewing a different agent — or from this
        // agent's own past scroll position — leaves the viewport anchored to the wrong place once
        // entries are rebuilt. Once reset, the user's own scrolling during this focus session is
        // respected exactly like the main transcript's.
        state.viewingChat.clear()
        state.focusModeNotes.clear()
        while (state.viewingAgentId == id) {
            val running = backgroundAgents?.runningStatus()?.firstOrNull { it.id == id }
            val ref = running?.agentRef
            if (ref != null) {
                state.viewingChat.entries.clear()
                ref.state.messages.forEach { state.viewingChat.appendMessage(it) }
                appendInFlightToolActivity(state.viewingChat, running.progress)
                // @mention echoes: appended directly to viewingChat for instant feedback, but the
                // rebuild above just wiped that append, so replay them at the tail every cycle.
                state.focusModeNotes.forEach { state.viewingChat.appendMessage(it) }
            } else {
                break
            }
            delay(200L)
        }
    }
}

/** Appends a transcript-tail note for the agent's in-flight tool, if it has one right now. */
private fun appendInFlightToolActivity(chat: ChatHistoryState, progress: SubAgentProgress?) {
    val currentTool = progress?.currentTool ?: return
    chat.appendMessage(
        CustomMessage(
            customType = "system_note",
            content = listOf(TextContent(text = activityOf(currentTool, progress.streamingText))),
            display = true,
            timestamp = System.currentTimeMillis(),
        ),
    )
}

/**
 * Keeps the background-agents panel (and its elapsed times) live while agents run, even when the
 * main loop is idle between turns, and drains queued push notifications to the orchestrator.
 */
@Composable
internal fun PushNotificationPump(
    state: TuiAppState,
    backgroundAgents: BackgroundAgents?,
    conversation: ConversationController,
) {
    if (backgroundAgents == null) return
    LaunchedEffect(Unit) {
        while (true) {
            delay(100L)
            if (backgroundAgents.hasRunning()) state.spinnerFrame++
            conversation.deliverPushQueue()
        }
    }
}

/** Tracks the active session so the shutdown hook can print a resume hint on exit. */
@Composable
internal fun ResumeHintTracker(state: TuiAppState) {
    LaunchedEffect(state.activeSessionManager) {
        TerminalSession.trackResumeSession(state.activeSessionManager)
    }
}

/**
 * Keeps the terminal window title in sync with the active session: the application name,
 * the session name when one is set, and the working directory's basename.
 */
@Composable
internal fun TerminalTitleUpdater(state: TuiAppState, cwd: Path) {
    LaunchedEffect(state.activeSessionManager) {
        val cwdBasename = cwd.fileName?.toString() ?: cwd.toString()
        val sessionName = state.activeSessionManager?.let { session ->
            runCatching { session.getSessionName() }.getOrNull()
        }
        val title = if (sessionName.isNullOrBlank()) {
            "agent47 - $cwdBasename"
        } else {
            "agent47 - $sessionName - $cwdBasename"
        }
        TerminalSession.setTitle(title)
    }
}
