package co.agentmode.agent47.app.compaction

import co.agentmode.agent47.ai.core.AiRuntime
import co.agentmode.agent47.ai.types.Context
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.SimpleStreamOptions
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.coding.core.compaction.CompactionResult
import co.agentmode.agent47.coding.core.compaction.buildCompactionMessages
import co.agentmode.agent47.coding.core.compaction.estimateContextTokens
import co.agentmode.agent47.coding.core.compaction.findCutPoint
import co.agentmode.agent47.coding.core.compaction.pruneToolOutputs
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.SettingsManager
import co.agentmode.agent47.ext.core.BeforeCompactionEvent
import co.agentmode.agent47.ext.core.CompactionReason
import co.agentmode.agent47.ext.core.ExtensionContext
import co.agentmode.agent47.ext.core.KotlinExtensionRuntime
import co.agentmode.agent47.ext.core.PreparedCompaction

internal class CompactionService(
    private val extensionRuntime: KotlinExtensionRuntime,
    private val extensionContext: ExtensionContext,
    private val settings: SettingsManager,
    private val aiRuntime: AiRuntime,
    private val modelRegistry: ModelRegistry,
) {
    suspend fun compact(
        messages: List<Message>,
        activeModel: Model,
        reason: CompactionReason,
    ): PreparedCompaction? {
        val hookResult = extensionRuntime.runner.prepareCompaction(
            BeforeCompactionEvent(messages, activeModel, reason),
            extensionContext,
        )
        if (hookResult?.cancel == true) return null
        val extensionCompaction = hookResult?.compaction
        return if (extensionCompaction != null) {
            PreparedCompaction(extensionCompaction, fromExtension = true)
        } else {
            summarize(messages, activeModel)
        }
    }

    private suspend fun summarize(messages: List<Message>, activeModel: Model): PreparedCompaction? {
        val compactionSettings = settings.get().compaction
        val pruned = if (compactionSettings.prune) {
            pruneToolOutputs(messages, compactionSettings.keepRecentTokens)
        } else {
            messages
        }
        val compactionMessages = buildCompactionMessages(pruned)
        val context = Context(
            messages = compactionMessages,
        )
        val response = runCatching {
            aiRuntime.completeSimple(
                activeModel,
                context,
                SimpleStreamOptions(apiKey = modelRegistry.getApiKeyForProvider(activeModel.provider.value)),
            )
        }.getOrNull()
        val summaryText = response?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("\n") { it.text }
        return if (summaryText.isNullOrBlank()) {
            null
        } else {
            val estimate = estimateContextTokens(messages)
            val cutPoint = findCutPoint(messages, compactionSettings.keepRecentTokens)
            PreparedCompaction(
                compaction = CompactionResult(
                    summary = summaryText,
                    firstKeptEntryId = messages.getOrNull(cutPoint.firstKeptEntryIndex)
                        ?.timestamp?.toString() ?: "",
                    tokensBefore = estimate.tokens,
                ),
                fromExtension = false,
            )
        }
    }
}
