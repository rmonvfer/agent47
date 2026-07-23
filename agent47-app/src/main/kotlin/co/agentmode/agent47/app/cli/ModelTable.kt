package co.agentmode.agent47.app.cli

import co.agentmode.agent47.coding.core.models.ModelRegistry
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal

internal fun listModels(registry: ModelRegistry, search: String?, terminal: Terminal) {
    val available = registry.getAvailable()

    val filtered = if (search != null) {
        available.filter {
            it.id.contains(search, ignoreCase = true) ||
                it.name.contains(search, ignoreCase = true) ||
                it.provider.value.contains(search, ignoreCase = true)
        }
    } else {
        available
    }

    if (filtered.isEmpty()) {
        terminal.printWarning("No models found${search?.let { " matching '$it'" } ?: ""}")
        return
    }

    val table = table {
        header { row("Provider", "Model ID", "Name", "Context", "Max Tokens") }
        body {
            filtered.sortedWith(compareBy({ it.provider.value }, { it.id })).forEach { model ->
                row(
                    model.provider.value,
                    model.id,
                    model.name,
                    formatNumber(model.contextWindow),
                    formatNumber(model.maxTokens),
                )
            }
        }
    }
    terminal.println(table)
}

private fun formatNumber(n: Int): String {
    return if (n >= 1_000_000) {
        String.format("%.1fM", n / 1_000_000.0)
    } else if (n >= 1_000) {
        String.format("%.0fK", n / 1_000.0)
    } else {
        n.toString()
    }
}
