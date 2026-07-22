package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.ai.types.Agent47Json
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A streaming JSONL transcript of a sub-agent's conversation, one sidechain-tagged entry per line.
 * Written when `output_transcript` is enabled. Thread-safe; best-effort (I/O
 * failures are swallowed so a transcript problem never fails the agent).
 */
public class AgentTranscript(
    private val path: Path,
    private val agentId: String,
    private val cwd: String,
) {
    private val lock = Any()

    init {
        runCatching { path.parent?.let { Files.createDirectories(it) } }
    }

    public fun writeInitial(prompt: String) {
        appendEntry("user", prompt)
    }

    public fun writeMessage(message: Message) {
        when (message) {
            is UserMessage -> appendEntry("user", extractText(message.content))
            is AssistantMessage -> appendEntry("assistant", extractText(message.content))
            else -> {}
        }
    }

    public fun pathString(): String = path.toString()

    private fun appendEntry(type: String, content: String) {
        val entry: JsonObject = buildJsonObject {
            put("isSidechain", true)
            put("agentId", agentId)
            put("type", type)
            put("timestamp", System.currentTimeMillis())
            put("cwd", cwd)
            put("content", content)
        }
        val line = Agent47Json.encodeToString(JsonObject.serializer(), entry) + "\n"
        synchronized(lock) {
            runCatching {
                Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    private fun extractText(content: List<ContentBlock>): String =
        content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
}
