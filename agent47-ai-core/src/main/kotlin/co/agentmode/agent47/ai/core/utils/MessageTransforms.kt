package co.agentmode.agent47.ai.core.utils

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.ToolCall

public object MessageTransforms {
    public fun transformThinkingToText(messages: List<Message>): List<Message> {
        return messages.map { message ->
            if (message !is AssistantMessage) {
                return@map message
            }

            val transformedBlocks: List<ContentBlock> = message.content.map { block ->
                when (block) {
                    is ThinkingContent -> {
                        TextContent(text = "<thinking>\n${block.thinking}\n</thinking>")
                    }

                    else -> block
                }
            }

            message.copy(content = transformedBlocks)
        }
    }

    public fun convertCrossProviderThinking(
        messages: List<Message>,
        targetApi: ApiId,
        targetProvider: ProviderId,
    ): List<Message> {
        return messages.map { message ->
            if (message !is AssistantMessage) return@map message
            if (message.api == targetApi && message.provider == targetProvider) return@map message

            val transformedBlocks: List<ContentBlock> = message.content.map { block ->
                when (block) {
                    is ThinkingContent -> TextContent(
                        text = "<thinking>\n${block.thinking}\n</thinking>",
                    )
                    is TextContent -> block.copy(textSignature = null)
                    is ToolCall -> block.copy(thoughtSignature = null)
                    else -> block
                }
            }

            message.copy(content = transformedBlocks)
        }
    }
}
