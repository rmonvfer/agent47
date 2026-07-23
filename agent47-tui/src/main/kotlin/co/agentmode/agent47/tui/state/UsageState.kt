package co.agentmode.agent47.tui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.agentmode.agent47.ai.types.Usage
import co.agentmode.agent47.ai.types.UsageCost

/**
 * Mutable holder for usage stats that persists across recompositions.
 * Using a class with var fields instead of mutableStateOf for each field
 * because these are updated from the agent event handler (non-composable)
 * and read during composition to build the status bar state.
 */
internal class UsageState {
    var inputTokens: Int by mutableIntStateOf(0)
    var outputTokens: Int by mutableIntStateOf(0)
    var cacheReadTokens: Int by mutableIntStateOf(0)
    var cacheWriteTokens: Int by mutableIntStateOf(0)
    var latestCacheHitRate: Double? by mutableStateOf(null)
    var cost: Double by mutableDoubleStateOf(0.0)

    fun reset(usages: List<Usage>) {
        inputTokens = 0
        outputTokens = 0
        cacheReadTokens = 0
        cacheWriteTokens = 0
        latestCacheHitRate = null
        cost = 0.0
        usages.forEach(::add)
    }

    fun add(usage: Usage) {
        inputTokens += usage.input
        outputTokens += usage.output
        cacheReadTokens += usage.cacheRead
        cacheWriteTokens += usage.cacheWrite
        cost += usage.cost.total
        val promptTokens = usage.input + usage.cacheRead + usage.cacheWrite
        latestCacheHitRate = if (promptTokens > 0) usage.cacheRead.toDouble() / promptTokens * 100.0 else null
    }
}

internal fun emptyUsage(): Usage = Usage(
    input = 0,
    output = 0,
    cacheRead = 0,
    cacheWrite = 0,
    totalTokens = 0,
    cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
)
