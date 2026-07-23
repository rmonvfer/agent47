package co.agentmode.agent47.tui.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import co.agentmode.agent47.agent.core.AgentThinkingLevel
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.UserMessage
import co.agentmode.agent47.api.AgentClient
import co.agentmode.agent47.coding.core.agents.BackgroundAgents
import co.agentmode.agent47.tui.controller.CompactionController
import co.agentmode.agent47.tui.controller.ConversationController
import co.agentmode.agent47.tui.state.TranscriptFeed
import co.agentmode.agent47.tui.state.TuiAppState
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
            val job = launch {
                try {
                    client.prompt(listOf(message))
                } catch (_: CancellationException) {
                } catch (error: Throwable) {
                    feed.appendSystemMessage("Failed to submit message: ${error.message ?: error::class.simpleName}")
                } finally {
                    state.currentPromptJob = null
                }
            }
            state.currentPromptJob = job
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
 * Rebuilds the focused agent's transcript from its live messages while focus mode is active.
 */
@Composable
internal fun AgentTranscriptMirror(state: TuiAppState, backgroundAgents: BackgroundAgents?) {
    LaunchedEffect(state.viewingAgentId) {
        val id = state.viewingAgentId ?: return@LaunchedEffect
        while (state.viewingAgentId == id) {
            val ref = backgroundAgents?.runningStatus()?.firstOrNull { it.id == id }?.agentRef
            if (ref != null) {
                state.viewingChat.entries.clear()
                ref.state.messages.forEach { state.viewingChat.appendMessage(it) }
            } else {
                break
            }
            delay(200L)
        }
    }
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
