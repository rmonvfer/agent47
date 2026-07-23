package co.agentmode.agent47.ai.types

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventStreamTest {

    @Test
    fun `cancel terminates a pending result with cancellation`() = runBlocking {
        val stream = EventStream<String, String>(
            isComplete = { it == "done" },
            extractResult = { it },
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) { stream.result() }

        stream.cancel()

        assertTrue(stream.isTerminated)
        val cancellation = assertFailsWith<CancellationException> {
            withTimeout(1_000) { result.await() }
        }
        assertEquals("Event stream was cancelled", cancellation.message)
    }

    @Test
    fun `cancel preserves an already completed result`() = runBlocking {
        val stream = EventStream<String, String>(
            isComplete = { it == "done" },
            extractResult = { "result" },
        )
        stream.push("done")

        stream.cancel()

        assertEquals("result", stream.result())
    }
}
