package co.agentmode.agent47.ai.types

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel

public open class EventStream<T : Any, R : Any>(
    private val isComplete: (T) -> Boolean,
    private val extractResult: (T) -> R,
) {
    private val eventsChannel: Channel<T> = Channel(Channel.UNLIMITED)
    private val doneLock: Mutex = Mutex()
    private var closed: Boolean = false
    private val finalResult: CompletableDeferred<R> = CompletableDeferred()

    public val events: Flow<T> = eventsChannel.receiveAsFlow()

    public val isTerminated: Boolean get() = closed

    public fun push(event: T) {
        if (closed) {
            return
        }
        eventsChannel.trySend(event)
        if (isComplete(event) && !finalResult.isCompleted) {
            finalResult.complete(extractResult(event))
            closeChannel()
        }
    }

    public fun cancel() {
        closed = true
        eventsChannel.close()
    }

    public suspend fun end(result: R? = null) {
        doneLock.withLock {
            if (closed) {
                return
            }
            closed = true
            if (result != null && !finalResult.isCompleted) {
                finalResult.complete(result)
            }
            eventsChannel.close()
        }
    }

    public suspend fun result(): R = finalResult.await()

    private fun closeChannel() {
        if (!closed) {
            closed = true
            eventsChannel.close()
        }
    }
}

public class AssistantMessageEventStream : EventStream<AssistantMessageEvent, AssistantMessage>(
    isComplete = { event -> event is DoneEvent || event is ErrorEvent },
    extractResult = { event ->
        when (event) {
            is DoneEvent -> event.message
            is ErrorEvent -> event.error
            else -> error("Unexpected non-terminal event: ${event.type}")
        }
    },
)
