package co.agentmode.agent47.api

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.ai.types.ImageContent
import co.agentmode.agent47.ai.types.Message
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A client implementation for interacting with an `Agent` instance. Provides high-level methods
 * to interact with the agent, including sending prompts, managing conversational flow, and accessing
 * the underlying agent for low-level operations.
 *
 * The `AgentClient` serves as the primary interface for sending messages and controlling the agent’s
 * behavior. It facilitates managing conversational state and provides a `Flow` of agent events to
 * enable reactive handling of updates or state changes.
 *
 * @constructor Creates an instance of `AgentClient` configured with the provided `AgentOptions`.
 * If no options are specified, the default `AgentOptions` configuration is used.
 *
 * @param options The configuration options for the underlying agent. These options control
 * various behaviors, such as message processing, steering mode, follow-up mode, and API interaction.
 *
 * @property events A `Flow` of `AgentEvent` instances emitted by the agent. This provides
 * a reactive stream of state changes and events related to the agent's operation.
 */
public class AgentClient(options: AgentOptions = AgentOptions()) {
    private val agent: Agent = Agent(options)
    private val _eventsChannel: Channel<AgentEvent> = Channel(Channel.UNLIMITED)

    init {
        agent.subscribe { event -> _eventsChannel.trySend(event) }
    }

    public val events: Flow<AgentEvent> = _eventsChannel.receiveAsFlow()

    public suspend fun prompt(text: String, images: List<ImageContent> = emptyList()) {
        agent.prompt(text, images)
    }

    /**
     * Sends a list of messages to the agent for processing. This method is used to provide
     * input to the agent, initiating its core processing loop and allowing it to generate
     * responses or perform actions based on the provided messages.
     *
     * The messages represent a sequence of conversational entries, such as user inputs,
     * assistant responses, or other types of information relevant to the agent's operation.
     *
     * @param messages A list of messages to be processed by the agent. Each message encapsulates
     * details such as its role and timestamp, and can represent various conversational elements.
     *
     * @throws IllegalArgumentException If the agent is already in a streaming state. Use `steer()`
     * or `followUp()` for adding messages, or wait for ongoing processing to complete.
     */
    public suspend fun prompt(messages: List<Message>) {
        agent.prompt(messages)
    }

    /**
     * Adds a message to the agent's steering queue, allowing the behavior or direction
     * of the agent to be adjusted dynamically.
     *
     * @param message The message used to influence the agent's behavior. It represents
     * a conversational entry, which could be user input, assistant responses, tool results,
     * or other message types.
     */
    public fun steer(message: Message) {
        agent.steer(message)
    }

    /**
     * Queues a follow-up message to influence the agent's later behavior or responses.
     * The follow-up message is added to the agent's internal processing queue.
     *
     * @param message The message to be added as a follow-up. This message represents a
     * conversational entry that may affect the agent's behavior. It could be user input,
     * an assistant response, a tool result, or another type of message.
     */
    public fun followUp(message: Message) {
        agent.followUp(message)
    }

    /**
     * Resumes the execution of the agent's processing, continuing from the current state.
     * This method ensures that the agent is not already in a streaming state and verifies
     * that there are messages available to proceed.
     *
     * If the last message in the agent's state has the role "assistant", this method attempts
     * to process steering messages or follow-up messages if available. Otherwise, it raises an
     * error if no valid continuation is possible from the current state.
     *
     * If the last message role is not "assistant", the agent continues processing from the
     * current state without additional modifications.
     *
     * @throws IllegalArgumentException If the agent is currently streaming, or if there
     * are no messages to continue from.
     * @throws IllegalStateException If continuation is attempted from an invalid state,
     * such as when the last message role is "assistant" but no steering or follow-up messages
     * are available.
     */
    public suspend fun continueRun() {
        agent.continueRun()
    }

    /**
     * Suspends the coroutine until the associated agent completes its current processing and becomes idle.
     *
     * This method ensures that any ongoing operations or tasks being handled by the agent are fully
     * completed before proceeding. It is useful in scenarios where synchronization with the agent's state
     * is required, particularly when later operations depend on the agent having no pending tasks.
     *
     * This function behaves asynchronously, allowing other coroutines to continue execution while
     * waiting for the agent to reach an idle state.
     *
     * @throws IllegalStateException If the agent's internal state prevents it from becoming idle.
     */
    public suspend fun waitForIdle() {
        agent.waitForIdle()
    }

    /**
     * Retrieves the raw agent associated with the `AgentClient`.
     *
     * This method provides direct access to the underlying `Agent` instance tied to the `AgentClient`.
     * It is typically used for lower-level interactions or configurations that require direct manipulation
     * of the agent, bypassing higher-level abstractions or functionality provided by the `AgentClient`.
     *
     * @return The `Agent` instance associated with the `AgentClient`.
     */
    public fun rawAgent(): Agent = agent
}
