package co.agentmode.agent47.agent.core

import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.core.utils.MessageTransforms
import co.agentmode.agent47.ai.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.concurrent.thread

public fun agentLoop(
    prompts: List<Message>,
    context: AgentContext,
    config: AgentLoopConfig,
    streamFunction: AgentStreamFunction? = null,
    onLoopThread: ((Thread) -> Unit)? = null,
): EventStream<AgentEvent, List<Message>> {
    val stream = createAgentStream()

    val t = thread(name = "agent47-loop", isDaemon = true) {
        try {
            runBlocking {
                val newMessages = mutableListOf<Message>()
                newMessages.addAll(prompts)
                val currentContext = context.copy(messages = context.messages.toMutableList())
                currentContext.messages.addAll(prompts)

                stream.push(AgentStartEvent())
                stream.push(TurnStartEvent())
                prompts.forEach { prompt ->
                    stream.push(MessageStartEvent(prompt))
                    stream.push(MessageEndEvent(prompt))
                }

                try {
                    runLoop(currentContext, newMessages, config, stream, streamFunction)
                } catch (_: CancellationException) {
                    stream.cancel()
                } catch (error: Throwable) {
                    val errorMessage = createLoopErrorMessage(config, error)
                    newMessages.add(errorMessage)
                    stream.push(MessageStartEvent(errorMessage))
                    stream.push(MessageEndEvent(errorMessage))
                    stream.push(AgentEndEvent(messages = newMessages.toList()))
                    stream.end(newMessages.toList())
                }
            }
        } catch (_: InterruptedException) {
            // Agent.abort() interrupts this thread to cancel the loop.
            // Close the stream to unblock any collector.
            stream.cancel()
        }
    }
    onLoopThread?.invoke(t)

    return stream
}

public fun agentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    streamFunction: AgentStreamFunction? = null,
    onLoopThread: ((Thread) -> Unit)? = null,
): EventStream<AgentEvent, List<Message>> {
    require(context.messages.isNotEmpty()) { "Cannot continue: no messages in context" }
    require(context.messages.last().role != "assistant") { "Cannot continue from message role: assistant" }

    val stream = createAgentStream()

    val t = thread(name = "agent47-loop-continue", isDaemon = true) {
        try {
            runBlocking {
                val newMessages = mutableListOf<Message>()
                val currentContext = context.copy(messages = context.messages.toMutableList())

                stream.push(AgentStartEvent())
                stream.push(TurnStartEvent())

                try {
                    runLoop(currentContext, newMessages, config, stream, streamFunction)
                } catch (_: CancellationException) {
                    stream.cancel()
                } catch (error: Throwable) {
                    val errorMessage = createLoopErrorMessage(config, error)
                    newMessages.add(errorMessage)
                    stream.push(MessageStartEvent(errorMessage))
                    stream.push(MessageEndEvent(errorMessage))
                    stream.push(AgentEndEvent(messages = newMessages.toList()))
                    stream.end(newMessages.toList())
                }
            }
        } catch (_: InterruptedException) {
            // Agent.abort() interrupts this thread to cancel the loop.
            // Close the stream to unblock any collector.
            stream.cancel()
        }
    }
    onLoopThread?.invoke(t)

    return stream
}

private fun createAgentStream(): EventStream<AgentEvent, List<Message>> {
    return EventStream(
        isComplete = { event -> event is AgentEndEvent },
        extractResult = { event ->
            if (event is AgentEndEvent) {
                event.messages
            } else {
                emptyList()
            }
        },
    )
}

private suspend fun runLoop(
    currentContext: AgentContext,
    newMessages: MutableList<Message>,
    config: AgentLoopConfig,
    stream: EventStream<AgentEvent, List<Message>>,
    streamFunction: AgentStreamFunction?,
) {
    var firstTurn = true
    var pendingMessages = config.getSteeringMessages?.invoke()?.toMutableList() ?: mutableListOf()

    while (true) {
        var hasMoreToolCalls = true
        var steeringAfterTools: List<Message>? = null

        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            if (!firstTurn) {
                stream.push(TurnStartEvent())
            } else {
                firstTurn = false
            }

            if (pendingMessages.isNotEmpty()) {
                pendingMessages.forEach { pending ->
                    stream.push(MessageStartEvent(pending))
                    stream.push(MessageEndEvent(pending))
                    currentContext.messages.add(pending)
                    newMessages.add(pending)
                }
                pendingMessages.clear()
            }

            if (config.beforeAgent != null) {
                val transformed = config.beforeAgent.invoke(currentContext.messages.toList())
                currentContext.messages.clear()
                currentContext.messages.addAll(transformed)
            }

            val assistantMessage = streamAssistantResponse(currentContext, config, stream, streamFunction)
            newMessages.add(assistantMessage)

            if (assistantMessage.stopReason == StopReason.ERROR || assistantMessage.stopReason == StopReason.ABORTED) {
                stream.push(TurnEndEvent(message = assistantMessage, toolResults = emptyList()))
                stream.push(AgentEndEvent(messages = newMessages.toList()))
                stream.end(newMessages.toList())
                return
            }

            val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
            hasMoreToolCalls = toolCalls.isNotEmpty()

            val toolResults = mutableListOf<ToolResultMessage>()
            if (hasMoreToolCalls) {
                val toolExecution = executeToolCalls(
                    tools = currentContext.tools,
                    assistantMessage = assistantMessage,
                    stream = stream,
                    getSteeringMessages = config.getSteeringMessages,
                )
                toolResults.addAll(toolExecution.toolResults)
                steeringAfterTools = toolExecution.steeringMessages
                toolResults.forEach { toolResult ->
                    currentContext.messages.add(toolResult)
                    newMessages.add(toolResult)
                }
            }

            stream.push(TurnEndEvent(message = assistantMessage, toolResults = toolResults))

            pendingMessages = when {
                !steeringAfterTools.isNullOrEmpty() -> steeringAfterTools.toMutableList()
                else -> (config.getSteeringMessages?.invoke() ?: emptyList()).toMutableList()
            }
        }

        val followUpMessages = config.getFollowUpMessages?.invoke().orEmpty()
        if (followUpMessages.isNotEmpty()) {
            pendingMessages = followUpMessages.toMutableList()
            continue
        }

        break
    }

    config.afterAgent?.invoke(currentContext.messages.toList())
    stream.push(AgentEndEvent(newMessages.toList()))
    stream.end(newMessages.toList())
}

private suspend fun streamAssistantResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    stream: EventStream<AgentEvent, List<Message>>,
    streamFunction: AgentStreamFunction?,
): AssistantMessage {
    val transformed = config.transformContext?.invoke(context.messages.toList()) ?: context.messages.toList()
    val converted = config.convertToLlm(transformed)
    val llmMessages = MessageTransforms.convertCrossProviderThinking(
        converted,
        targetApi = config.model.api,
        targetProvider = config.model.provider,
    )
    val llmContext = Context(
        systemPrompt = context.systemPrompt,
        messages = llmMessages,
        tools = context.tools.map { it.definition },
    )

    val options = SimpleStreamOptions(
        apiKey = config.getApiKey?.invoke(config.model.provider.value),
        reasoning = config.reasoning,
        sessionId = config.sessionId,
        thinkingBudgets = config.thinkingBudgets,
        maxRetryDelayMs = config.maxRetryDelayMs,
    )

    val response = streamFunction?.invoke(config.model, llmContext, options) ?: AiRuntime.streamSimple(
        config.model,
        llmContext,
        options
    )

    var partial: AssistantMessage? = null
    var addedPartial = false

    response.events.collect { event ->
        when (event) {
            is StartEvent -> {
                partial = event.partial
                context.messages.add(event.partial)
                addedPartial = true
                stream.push(MessageStartEvent(event.partial))
            }

            is TextStartEvent,
            is TextDeltaEvent,
            is TextEndEvent,
            is ThinkingStartEvent,
            is ThinkingDeltaEvent,
            is ThinkingEndEvent,
            is ToolCallStartEvent,
            is ToolCallDeltaEvent,
            is ToolCallEndEvent,
                -> {
                val newPartial = when (event) {
                    is TextStartEvent -> event.partial
                    is TextDeltaEvent -> event.partial
                    is TextEndEvent -> event.partial
                    is ThinkingStartEvent -> event.partial
                    is ThinkingDeltaEvent -> event.partial
                    is ThinkingEndEvent -> event.partial
                    is ToolCallStartEvent -> event.partial
                    is ToolCallDeltaEvent -> event.partial
                    is ToolCallEndEvent -> event.partial
                    else -> error("Unexpected event type: $event")
                }

                partial = newPartial
                context.messages[context.messages.lastIndex] = newPartial
                stream.push(MessageUpdateEvent(message = newPartial, assistantMessageEvent = event))
            }

            is DoneEvent,
            is ErrorEvent,
                -> {
                val final = response.result()
                if (addedPartial) {
                    context.messages[context.messages.lastIndex] = final
                } else {
                    context.messages.add(final)
                    stream.push(MessageStartEvent(final))
                }
                stream.push(MessageEndEvent(final))
            }
        }
    }

    return response.result()
}

private data class ToolExecutionResult(
    val toolResults: List<ToolResultMessage>,
    val steeringMessages: List<Message>?,
)

private suspend fun executeToolCalls(
    tools: List<AgentTool<*>>,
    assistantMessage: AssistantMessage,
    stream: EventStream<AgentEvent, List<Message>>,
    getSteeringMessages: (suspend () -> List<Message>)?,
): ToolExecutionResult {
    val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
    val results = mutableListOf<ToolResultMessage>()
    var steeringMessages: List<Message>? = null

    for ((index, toolCall) in toolCalls.withIndex()) {
        val tool = tools.firstOrNull { it.definition.name == toolCall.name }

        stream.push(ToolExecutionStartEvent(toolCall.id, toolCall.name, toolCall.arguments))

        var executionResult: AgentToolResult<*>
        var isError = false

        try {
            require(tool != null) { "Tool ${toolCall.name} not found" }
            executionResult = tool.execute(
                toolCallId = toolCall.id,
                parameters = toolCall.arguments,
                onUpdate = { partial ->
                    stream.push(
                        ToolExecutionUpdateEvent(
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            arguments = toolCall.arguments,
                            partialResult = partial,
                        ),
                    )
                },
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            executionResult = AgentToolResult(
                content = listOf(TextContent(text = error.message ?: error.toString())),
                details = buildJsonObject { },
            )
            isError = true
        }

        stream.push(ToolExecutionEndEvent(toolCall.id, toolCall.name, executionResult, isError))

        val resultMessage = ToolResultMessage(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            content = executionResult.content,
            details = executionResult.details as? JsonObject,
            isError = isError,
            timestamp = System.currentTimeMillis(),
        )

        results.add(resultMessage)
        stream.push(MessageStartEvent(resultMessage))
        stream.push(MessageEndEvent(resultMessage))

        if (getSteeringMessages != null) {
            val queued = getSteeringMessages()
            if (queued.isNotEmpty()) {
                steeringMessages = queued
                val remaining = toolCalls.drop(index + 1)
                remaining.forEach { skipped ->
                    val skippedResult = createSkippedToolResult(skipped)
                    results.add(skippedResult)
                    stream.push(ToolExecutionStartEvent(skipped.id, skipped.name, skipped.arguments))
                    stream.push(
                        ToolExecutionEndEvent(
                            toolCallId = skipped.id,
                            toolName = skipped.name,
                            result = AgentToolResult(skippedResult.content, buildJsonObject { }),
                            isError = true,
                        ),
                    )
                    stream.push(MessageStartEvent(skippedResult))
                    stream.push(MessageEndEvent(skippedResult))
                }
                break
            }
        }
    }

    return ToolExecutionResult(toolResults = results, steeringMessages = steeringMessages)
}

private fun createLoopErrorMessage(config: AgentLoopConfig, error: Throwable): AssistantMessage {
    val errText = error.message ?: error.toString()
    return AssistantMessage(
        content = listOf(TextContent(text = "")),
        api = config.model.api,
        provider = config.model.provider,
        model = config.model.id,
        usage = emptyUsage(),
        stopReason = StopReason.ERROR,
        errorMessage = errText,
        timestamp = System.currentTimeMillis(),
    )
}
