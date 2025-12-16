package co.agentmode.agent47.api

import co.agentmode.agent47.agent.core.Agent
import co.agentmode.agent47.agent.core.AgentEvent
import co.agentmode.agent47.agent.core.AgentOptions
import co.agentmode.agent47.ai.types.ImageContent
import co.agentmode.agent47.ai.types.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

public class AgentClient(options: AgentOptions = AgentOptions()) {
    private val agent: Agent = Agent(options)
    private val _events: MutableSharedFlow<AgentEvent> = MutableSharedFlow(extraBufferCapacity = 1_024)

    init {
        agent.subscribe { event -> _events.tryEmit(event) }
    }

    public val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    public suspend fun prompt(text: String, images: List<ImageContent> = emptyList()) {
        agent.prompt(text, images)
    }

    public suspend fun prompt(messages: List<Message>) {
        agent.prompt(messages)
    }

    public fun steer(message: Message) {
        agent.steer(message)
    }

    public fun followUp(message: Message) {
        agent.followUp(message)
    }

    public suspend fun continueRun() {
        agent.continueRun()
    }

    public suspend fun waitForIdle() {
        agent.waitForIdle()
    }

    public fun rawAgent(): Agent = agent
}
