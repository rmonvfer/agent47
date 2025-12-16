package co.agentmode.agent47.ai.core.utils

import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.Usage
import co.agentmode.agent47.ai.types.UsageCost

public object UsageUtils {
    public fun withTokenTotals(
        input: Int,
        output: Int,
        cacheRead: Int,
        cacheWrite: Int,
        model: Model,
    ): Usage {
        val totalTokens = input + output + cacheRead + cacheWrite
        val inputCost = (input.toDouble() / 1_000_000.0) * model.cost.input
        val outputCost = (output.toDouble() / 1_000_000.0) * model.cost.output
        val cacheReadCost = (cacheRead.toDouble() / 1_000_000.0) * model.cost.cacheRead
        val cacheWriteCost = (cacheWrite.toDouble() / 1_000_000.0) * model.cost.cacheWrite
        return Usage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = totalTokens,
            cost = UsageCost(
                input = inputCost,
                output = outputCost,
                cacheRead = cacheReadCost,
                cacheWrite = cacheWriteCost,
                total = inputCost + outputCost + cacheReadCost + cacheWriteCost,
            ),
        )
    }

    public fun add(left: Usage, right: Usage): Usage {
        return Usage(
            input = left.input + right.input,
            output = left.output + right.output,
            cacheRead = left.cacheRead + right.cacheRead,
            cacheWrite = left.cacheWrite + right.cacheWrite,
            totalTokens = left.totalTokens + right.totalTokens,
            cost = UsageCost(
                input = left.cost.input + right.cost.input,
                output = left.cost.output + right.cost.output,
                cacheRead = left.cost.cacheRead + right.cost.cacheRead,
                cacheWrite = left.cost.cacheWrite + right.cost.cacheWrite,
                total = left.cost.total + right.cost.total,
            ),
        )
    }
}
