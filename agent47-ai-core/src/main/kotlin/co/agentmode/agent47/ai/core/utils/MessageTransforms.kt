package co.agentmode.agent47.ai.core.utils

import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ThinkingContent

public object MessageTransforms {
    public fun transformThinkingToText(messages: List<Message>): List<Message> {
        return messages.map { message ->
            if (message !is co.agentmode.agent47.ai.types.AssistantMessage) {
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
}
