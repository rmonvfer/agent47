package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

public data class AgentOptions(
    val streamFunction: AgentStreamFunction,
    val initialState: PartialAgentState? = null,
    val convertToLlm: (suspend (messages: List<Message>) -> List<Message>)? = null,
    val transformContext: (suspend (context: Context) -> Context)? = null,
    val beforeAgent: (suspend (messages: List<Message>) -> List<Message>)? = null,
    val afterAgent: (suspend (messages: List<Message>) -> Unit)? = null,
    val onEvent: (suspend (event: AgentEvent) -> Unit)? = null,
    val steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val sessionId: String? = null,
    val getApiKey: (suspend (provider: String) -> String?)? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val maxRetryDelayMs: Long? = null,
)

/**
 * Represents a partial state of an agent, encapsulating various components
 * that contribute to the agent's operation and behavior. Each property is
 * optional, allowing for the representation of an incomplete state when needed.
 *
 * @property systemPrompt The system-level instruction or prompt that provides
 * context or guidance for the agent's overall operation.
 * @property model The model configuration used by the agent, defining aspects
 * such as the underlying model, provider details, and capabilities.
 * @property thinkingLevel The level of reasoning or "thinking" that the agent
 * should apply during its tasks, ranging from off to varying degrees of complexity.
 * @property tools The collection of tools available to the agent. Tools are external
 * functionalities or utilities that the agent can invoke to perform specific tasks
 * or retrieve additional information.
 * @property messages The ordered list of conversation messages, including user input,
 * assistant responses, and other types of communication, reflecting the agent's
 * conversational history.
 */
public data class PartialAgentState(
    val systemPrompt: String? = null,
    val model: Model? = null,
    val thinkingLevel: AgentThinkingLevel? = null,
    val tools: List<AgentTool<*>>? = null,
    val messages: List<Message>? = null,
)

public enum class QueueMode {
    /**
     * All items in the queue are processed simultaneously.
     */
    ALL,

    /**
     * Items in the queue are processed sequentially.
     */
    ONE_AT_A_TIME,
}

/**
 * The top-level agent state machine. Manages the conversation history, model configuration,
 * tool set, and the agentic loop lifecycle.
 *
 * Use [prompt] to send user messages and start the loop. The loop streams the model's
 * response, executes any tool calls, and continues until the model stops or an error occurs.
 * Events are delivered to listeners registered via [subscribe].
 *
 * Mid-conversation, [steer] injects messages that interrupt tool execution (useful for
 * user corrections), while [followUp] queues messages that are sent after the current
 * turn completes. [abort] forcefully stops the loop by interrupting its thread.
 */
public class Agent(options: AgentOptions) {
    // Mutated from both the loop's collector coroutine and abort() on a foreign thread.
    @Volatile
    private var stateValue: AgentState = AgentState(
        systemPrompt = options.initialState?.systemPrompt ?: "",
        model = options.initialState?.model ?: defaultModel(),
        thinkingLevel = options.initialState?.thinkingLevel ?: AgentThinkingLevel.OFF,
        tools = options.initialState?.tools ?: emptyList(),
        messages = options.initialState?.messages ?: emptyList(),
        isStreaming = false,
        streamMessage = null,
        pendingToolCalls = emptySet(),
        error = null,
    )

    // CopyOnWriteArraySet so emit() can iterate while abort()/subscribe() run on another thread.
    private val listeners: MutableSet<(AgentEvent) -> Unit> = CopyOnWriteArraySet()
    private val queueLock = Any()
    private val steeringQueue: MutableList<Message> = mutableListOf()
    private val followUpQueue: MutableList<Message> = mutableListOf()

    private val loopThread: AtomicReference<Thread?> = AtomicReference(null)
    private var loopGeneration: Long = 0
    private var currentStream: EventStream<AgentEvent, List<Message>>? = null

    private val streamFunction: AgentStreamFunction = options.streamFunction
    private var convertToLlm: suspend (messages: List<Message>) -> List<Message> = options.convertToLlm ?: { msgs ->
        defaultConvertToLlm(msgs)
    }
    private var transformContext: (suspend (context: Context) -> Context)? = options.transformContext
    private var beforeAgent: (suspend (messages: List<Message>) -> List<Message>)? = options.beforeAgent
    private var afterAgent: (suspend (messages: List<Message>) -> Unit)? = options.afterAgent
    private var onEvent: (suspend (event: AgentEvent) -> Unit)? = options.onEvent

    private var steeringMode: QueueMode = options.steeringMode
    private var followUpMode: QueueMode = options.followUpMode

    private var runningPrompt: CompletableDeferred<Unit>? = null

    public var sessionId: String? = options.sessionId
    public var getApiKey: (suspend (provider: String) -> String?)? = options.getApiKey
    public var thinkingBudgets: ThinkingBudgets? = options.thinkingBudgets
    public var maxRetryDelayMs: Long? = options.maxRetryDelayMs

    public val state: AgentState
        get() = stateValue

    public fun subscribe(listener: (AgentEvent) -> Unit): () -> Unit {
        listeners.add(listener)
        return {
            listeners.remove(listener)
        }
    }

    public fun setSystemPrompt(value: String) {
        stateValue = stateValue.copy(systemPrompt = value)
    }

    public fun setModel(model: Model) {
        stateValue = stateValue.copy(model = model)
    }

    public fun setThinkingLevel(level: AgentThinkingLevel) {
        stateValue = stateValue.copy(thinkingLevel = level)
    }

    public fun setSteeringMode(mode: QueueMode) {
        steeringMode = mode
    }

    public fun setFollowUpMode(mode: QueueMode) {
        followUpMode = mode
    }

    public fun setTools(tools: List<AgentTool<*>>) {
        stateValue = stateValue.copy(tools = tools)
    }

    public fun replaceMessages(messages: List<Message>) {
        stateValue = stateValue.copy(messages = messages.toList())
    }

    public fun appendMessage(message: Message) {
        stateValue = stateValue.copy(messages = stateValue.messages + message)
    }

    public fun steer(message: Message) {
        synchronized(queueLock) { steeringQueue.add(message) }
    }

    public fun followUp(message: Message) {
        synchronized(queueLock) { followUpQueue.add(message) }
    }

    public fun clearMessages() {
        stateValue = stateValue.copy(messages = emptyList())
    }

    public fun clearQueues() {
        synchronized(queueLock) {
            steeringQueue.clear()
            followUpQueue.clear()
        }
    }

    /**
     * Forcefully stops the running agent loop by interrupting the daemon thread.
     * This causes the `runBlocking` inside the loop thread to receive an
     * `InterruptedException`, which cancels all coroutines within it (API calls,
     * tool executions, subagent loops). Safe to call from any thread; no-op if
     * no loop is running.
     */
    public fun abort() {
        val thread = loopThread.getAndSet(null) ?: return
        thread.interrupt()
        currentStream?.cancel()
        currentStream = null
        clearQueues()
        stateValue = stateValue.copy(
            isStreaming = false,
            streamMessage = null,
            pendingToolCalls = emptySet(),
        )
        emit(AgentEndEvent(messages = emptyList()))
        runningPrompt?.complete(Unit)
        runningPrompt = null
    }

    public suspend fun waitForIdle() {
        runningPrompt?.await()
    }

    public fun reset() {
        stateValue = stateValue.copy(
            messages = emptyList(),
            isStreaming = false,
            streamMessage = null,
            pendingToolCalls = emptySet(),
            error = null,
        )
        clearQueues()
    }

    public suspend fun prompt(messages: List<Message>) {
        require(!stateValue.isStreaming) {
            "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."
        }
        runLoop(messages)
    }

    public suspend fun prompt(text: String, images: List<ImageContent> = emptyList()) {
        val blocks: MutableList<ContentBlock> = mutableListOf(TextContent(text = text))
        blocks.addAll(images)
        prompt(
            listOf(
                UserMessage(
                    content = blocks,
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        )
    }

    public suspend fun continueRun() {
        require(!stateValue.isStreaming) {
            "Agent is already processing. Wait for completion before continuing."
        }

        val messages = stateValue.messages
        require(messages.isNotEmpty()) { "No messages to continue from" }

        if (messages.last().role == "assistant") {
            val steering = dequeueSteeringMessages()
            if (steering.isNotEmpty()) {
                runLoop(steering, skipInitialSteeringPoll = true)
                return
            }
            val followUp = dequeueFollowUpMessages()
            if (followUp.isNotEmpty()) {
                runLoop(followUp)
                return
            }
            error("Cannot continue from message role: assistant")
        }

        runLoop(messages = null)
    }

    private suspend fun runLoop(messages: List<Message>? = null, skipInitialSteeringPoll: Boolean = false) {
        val generation = ++loopGeneration
        runningPrompt = CompletableDeferred()
        stateValue = stateValue.copy(isStreaming = true, streamMessage = null, error = null)

        val context = AgentContext(
            systemPrompt = stateValue.systemPrompt,
            messages = stateValue.messages.toMutableList(),
            tools = stateValue.tools,
        )

        var skipInitialSteering = skipInitialSteeringPoll

        val config = AgentLoopConfig(
            model = stateValue.model,
            reasoning = when (stateValue.thinkingLevel) {
                AgentThinkingLevel.OFF -> null
                AgentThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
                AgentThinkingLevel.LOW -> ThinkingLevel.LOW
                AgentThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
                AgentThinkingLevel.HIGH -> ThinkingLevel.HIGH
                AgentThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
            },
            sessionId = sessionId,
            thinkingBudgets = thinkingBudgets,
            maxRetryDelayMs = maxRetryDelayMs,
            convertToLlm = convertToLlm,
            transformContext = transformContext,
            beforeAgent = beforeAgent,
            afterAgent = afterAgent,
            getApiKey = getApiKey,
            getSteeringMessages = {
                if (skipInitialSteering) {
                    skipInitialSteering = false
                    emptyList()
                } else {
                    dequeueSteeringMessages()
                }
            },
            getFollowUpMessages = {
                dequeueFollowUpMessages()
            },
        )

        try {
            val threadCallback: (Thread) -> Unit = { t -> loopThread.set(t) }
            val stream = if (messages != null) {
                agentLoop(messages, context, config, streamFunction, onLoopThread = threadCallback)
            } else {
                agentLoopContinue(context, config, streamFunction, onLoopThread = threadCallback)
            }
            currentStream = stream

            stream.events.collect { event ->
                onEvent?.invoke(event)
                when (event) {
                    is MessageStartEvent -> {
                        stateValue = stateValue.copy(streamMessage = event.message)
                    }

                    is MessageUpdateEvent -> {
                        stateValue = stateValue.copy(streamMessage = event.message)
                    }

                    is MessageEndEvent -> {
                        stateValue = stateValue.copy(
                            streamMessage = null,
                            messages = stateValue.messages + event.message,
                        )
                    }

                    is ToolExecutionStartEvent -> {
                        stateValue = stateValue.copy(pendingToolCalls = stateValue.pendingToolCalls + event.toolCallId)
                    }

                    is ToolExecutionEndEvent -> {
                        stateValue = stateValue.copy(pendingToolCalls = stateValue.pendingToolCalls - event.toolCallId)
                    }

                    is TurnEndEvent -> {
                        val message = event.message
                        if (message is AssistantMessage && message.errorMessage != null) {
                            stateValue = stateValue.copy(error = message.errorMessage)
                        }
                    }

                    is AgentEndEvent -> {
                        stateValue = stateValue.copy(isStreaming = false, streamMessage = null)
                    }

                    else -> {
                        // no-op
                    }
                }

                emit(event)
            }
        } catch (cancellation: CancellationException) {
            if (loopGeneration == generation) {
                emit(AgentEndEvent(messages = emptyList()))
            }
            throw cancellation

        } catch (error: Throwable) {
            if (loopGeneration == generation) {
                val errText = error.message ?: error.toString()
                val errorMessage = AssistantMessage(
                    content = listOf(TextContent(text = "")),
                    api = stateValue.model.api,
                    provider = stateValue.model.provider,
                    model = stateValue.model.id,
                    usage = emptyUsage(),
                    stopReason = StopReason.ERROR,
                    errorMessage = errText,
                    timestamp = System.currentTimeMillis(),
                )
                stateValue = stateValue.copy(messages = stateValue.messages + errorMessage, error = errText)
                emit(AgentEndEvent(messages = listOf(errorMessage)))
            }
        } finally {
            if (loopGeneration == generation) {
                loopThread.set(null)
                currentStream = null
                stateValue = stateValue.copy(
                    isStreaming = false,
                    streamMessage = null,
                    pendingToolCalls = emptySet(),
                )
                runningPrompt?.complete(Unit)
                runningPrompt = null
            }
        }
    }

    private fun dequeueSteeringMessages(): List<Message> = drainQueue(steeringQueue, steeringMode)

    private fun dequeueFollowUpMessages(): List<Message> = drainQueue(followUpQueue, followUpMode)

    private fun drainQueue(queue: MutableList<Message>, mode: QueueMode): List<Message> {
        return synchronized(queueLock) {
            when (mode) {
                QueueMode.ONE_AT_A_TIME ->
                    if (queue.isEmpty()) emptyList() else listOf(queue.removeAt(0))

                QueueMode.ALL -> {
                    val messages = queue.toList()
                    queue.clear()
                    messages
                }
            }
        }
    }

    private fun emit(event: AgentEvent) {
        listeners.forEach { listener -> listener(event) }
    }

    private fun defaultModel(): Model {
        // TODO this might need some tweaking
        return Model(
            id = "gpt-5.3-codex",
            name = "GPT-5.3 Codex",
            api = KnownApis.OpenAiResponses,
            provider = KnownProviders.OpenAi,
            baseUrl = "https://api.openai.com/v1",
            reasoning = true,
            input = listOf(ModelInputKind.TEXT, ModelInputKind.IMAGE),
            cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
            contextWindow = 128_000,
            maxTokens = 16_384,
        )
    }
}
