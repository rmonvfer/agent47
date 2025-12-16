package co.agentmode.agent47.coding.core.session

import co.agentmode.agent47.ai.types.Agent47Json
import co.agentmode.agent47.ai.types.AssistantMessage
import co.agentmode.agent47.ai.types.BashExecutionMessage
import co.agentmode.agent47.ai.types.BranchSummaryMessage
import co.agentmode.agent47.ai.types.CompactionSummaryMessage
import co.agentmode.agent47.ai.types.ContentBlock
import co.agentmode.agent47.ai.types.CustomMessage
import co.agentmode.agent47.ai.types.ImageContent
import co.agentmode.agent47.ai.types.Message
import co.agentmode.agent47.ai.types.ThinkingContent
import co.agentmode.agent47.ai.types.TextContent
import co.agentmode.agent47.ai.types.ToolCall
import co.agentmode.agent47.ai.types.ToolResultMessage
import co.agentmode.agent47.ai.types.UserMessage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

public const val CURRENT_SESSION_VERSION: Int = 3

@Serializable
public data class SessionHeader(
    val type: String = "session",
    val version: Int? = CURRENT_SESSION_VERSION,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val parentSession: String? = null,
)

@Serializable
public sealed interface SessionEntry {
    public val type: String
    public val id: String
    public val parentId: String?
    public val timestamp: String
}

@Serializable
public data class SessionMessageEntry(
    override val type: String = "message",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: Message,
) : SessionEntry

@Serializable
public data class ThinkingLevelChangeEntry(
    override val type: String = "thinking_level_change",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionEntry

@Serializable
public data class ModelChangeEntry(
    override val type: String = "model_change",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionEntry

@Serializable
public data class CompactionEntry(
    override val type: String = "compaction",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
) : SessionEntry

@Serializable
public data class BranchSummaryEntry(
    override val type: String = "branch_summary",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
) : SessionEntry

@Serializable
public data class CustomEntry(
    override val type: String = "custom",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: kotlinx.serialization.json.JsonObject? = null,
) : SessionEntry

@Serializable
public data class CustomMessageEntry(
    override val type: String = "custom_message",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: List<TextContent>,
    val display: Boolean,
    val details: kotlinx.serialization.json.JsonObject? = null,
) : SessionEntry

@Serializable
public data class LabelEntry(
    override val type: String = "label",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionEntry

@Serializable
public data class SessionInfoEntry(
    override val type: String = "session_info",
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionEntry

@Serializable
public sealed interface FileEntry

@Serializable
public data class HeaderFileEntry(val header: SessionHeader) : FileEntry

@Serializable
public data class SessionFileEntry(val entry: SessionEntry) : FileEntry

public data class SessionContext(
    val messages: List<Message>,
    val thinkingLevel: String,
    val model: Pair<String, String>?,
)

public data class SessionTreeNode(
    val entry: SessionEntry,
    val children: List<SessionTreeNode>,
    val label: String? = null,
)

public class SessionManager(
    private val sessionFile: Path,
) {
    private var header: SessionHeader
    private val entries: MutableList<SessionEntry> = mutableListOf()
    private val byId: MutableMap<String, SessionEntry> = linkedMapOf()
    private var leafId: String? = null

    init {
        if (sessionFile.exists()) {
            val parsed = parseSessionEntries(sessionFile.readLines().joinToString("\n"))
            val migrated = migrateToCurrentVersion(parsed)

            header = parsed.firstOrNull { it is HeaderFileEntry }
                ?.let { (it as HeaderFileEntry).header }
                ?: SessionHeader(
                    id = UUID.randomUUID().toString(),
                    timestamp = Instant.now().toString(),
                    cwd = sessionFile.parent.toString(),
                )

            entries.clear()
            entries += parsed.filterIsInstance<SessionFileEntry>().map { it.entry }
            rebuildIndexes()

            if (migrated) {
                save()
            }
        } else {
            Files.createDirectories(sessionFile.parent)
            header = SessionHeader(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now().toString(),
                cwd = sessionFile.parent.toString(),
            )
            save()
        }
    }

    public fun getHeader(): SessionHeader = header

    public fun getEntries(): List<SessionEntry> = entries.toList()

    public fun getLeafId(): String? = leafId

    public fun getLeafEntry(): SessionEntry? = leafId?.let(byId::get)

    public fun getEntry(id: String): SessionEntry? = byId[id]

    public fun appendMessage(message: Message): SessionMessageEntry {
        val entry = SessionMessageEntry(
            id = generateId(),
            parentId = leafId,
            timestamp = Instant.now().toString(),
            message = message,
        )
        append(entry)
        return entry
    }

    public fun append(entry: SessionEntry): Unit {
        entries += entry
        byId[entry.id] = entry
        leafId = entry.id
        save()
    }

    public fun buildContext(targetLeafId: String? = leafId): SessionContext {
        if (entries.isEmpty()) {
            return SessionContext(messages = emptyList(), thinkingLevel = "off", model = null)
        }

        val path = resolvePathToRoot(targetLeafId)
        val pathIds = path.map { it.id }.toSet()

        var thinking = "off"
        var model: Pair<String, String>? = null
        val messages = mutableListOf<Message>()

        entries.forEach { entry ->
            if (!pathIds.contains(entry.id)) {
                return@forEach
            }
            when (entry) {
                is SessionMessageEntry -> messages += entry.message
                is ThinkingLevelChangeEntry -> thinking = entry.thinkingLevel
                is ModelChangeEntry -> model = entry.provider to entry.modelId
                is CompactionEntry -> messages += CompactionSummaryMessage(
                    summary = entry.summary,
                    tokensBefore = entry.tokensBefore,
                    timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                )

                is BranchSummaryEntry -> messages += BranchSummaryMessage(
                    fromId = entry.fromId,
                    summary = entry.summary,
                    timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                )

                is CustomMessageEntry -> messages += CustomMessage(
                    customType = entry.customType,
                    content = entry.content,
                    display = entry.display,
                    details = entry.details,
                    timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                )

                is CustomEntry,
                is LabelEntry,
                is SessionInfoEntry,
                -> {
                    // not part of llm context
                }
            }
        }

        return SessionContext(messages = messages, thinkingLevel = thinking, model = model)
    }

    public fun getTree(): List<SessionTreeNode> {
        val childrenByParent = mutableMapOf<String?, MutableList<SessionEntry>>()
        entries.forEach { entry ->
            childrenByParent.getOrPut(entry.parentId) { mutableListOf() }.add(entry)
        }

        val labelsByTarget = entries.filterIsInstance<LabelEntry>().associate { it.targetId to it.label }

        fun buildNode(entry: SessionEntry): SessionTreeNode {
            val children = childrenByParent[entry.id].orEmpty().map(::buildNode)
            return SessionTreeNode(entry = entry, children = children, label = labelsByTarget[entry.id])
        }

        return childrenByParent[null].orEmpty().map(::buildNode)
    }

    public fun save(): Unit {
        val lines = mutableListOf<String>()
        lines += Agent47Json.encodeToString(SessionHeader.serializer(), header)
        entries.forEach { entry ->
            lines += encodeSessionEntry(entry)
        }
        sessionFile.writeText(lines.joinToString("\n") + "\n")
    }

    private fun rebuildIndexes(): Unit {
        byId.clear()
        entries.forEach { entry -> byId[entry.id] = entry }
        leafId = entries.lastOrNull()?.id
    }

    private fun resolvePathToRoot(targetLeafId: String?): List<SessionEntry> {
        if (targetLeafId == null) {
            return entries.toList()
        }

        val collected = mutableListOf<SessionEntry>()
        var cursor = byId[targetLeafId]

        while (cursor != null) {
            collected += cursor
            cursor = cursor.parentId?.let(byId::get)
        }

        return collected.reversed()
    }

    private fun generateId(): String {
        repeat(100) {
            val id = UUID.randomUUID().toString().substring(0, 8)
            if (!byId.containsKey(id)) {
                return id
            }
        }
        return UUID.randomUUID().toString()
    }
}

public fun parseSessionEntries(content: String): MutableList<FileEntry> {
    val entries = mutableListOf<FileEntry>()
    content.lines().forEach { line ->
        if (line.isBlank()) {
            return@forEach
        }

        val parsed: JsonObject = runCatching {
            Agent47Json.parseToJsonElement(line).jsonObject
        }.getOrNull() ?: return@forEach

        if (parsed["type"]?.toString()?.contains("session") == true && parsed.containsKey("cwd")) {
            val header = Agent47Json.decodeFromJsonElement(SessionHeader.serializer(), parsed)
            entries += HeaderFileEntry(header)
        } else {
            val entry = decodeSessionEntry(parsed)
            if (entry != null) {
                entries += SessionFileEntry(entry)
            }
        }
    }
    return entries
}

private fun decodeSessionEntry(json: kotlinx.serialization.json.JsonObject): SessionEntry? {
    return when (json["type"]?.toString()?.replace("\"", "")) {
        "message" -> Agent47Json.decodeFromJsonElement(SessionMessageEntry.serializer(), json)
        "thinking_level_change" -> Agent47Json.decodeFromJsonElement(ThinkingLevelChangeEntry.serializer(), json)
        "model_change" -> Agent47Json.decodeFromJsonElement(ModelChangeEntry.serializer(), json)
        "compaction" -> Agent47Json.decodeFromJsonElement(CompactionEntry.serializer(), json)
        "branch_summary" -> Agent47Json.decodeFromJsonElement(BranchSummaryEntry.serializer(), json)
        "custom" -> Agent47Json.decodeFromJsonElement(CustomEntry.serializer(), json)
        "custom_message" -> Agent47Json.decodeFromJsonElement(CustomMessageEntry.serializer(), json)
        "label" -> Agent47Json.decodeFromJsonElement(LabelEntry.serializer(), json)
        "session_info" -> Agent47Json.decodeFromJsonElement(SessionInfoEntry.serializer(), json)
        else -> null
    }
}

private fun encodeSessionEntry(entry: SessionEntry): String {
    return when (entry) {
        is SessionMessageEntry -> {
            val entryJson = buildJsonObject {
                put("type", entry.type)
                put("id", entry.id)
                put("parentId", entry.parentId?.let(::JsonPrimitive) ?: JsonNull)
                put("timestamp", entry.timestamp)
                put("message", encodeMessage(entry.message))
            }
            Agent47Json.encodeToString(JsonObject.serializer(), entryJson)
        }
        is ThinkingLevelChangeEntry -> Agent47Json.encodeToString(ThinkingLevelChangeEntry.serializer(), entry)
        is ModelChangeEntry -> Agent47Json.encodeToString(ModelChangeEntry.serializer(), entry)
        is CompactionEntry -> Agent47Json.encodeToString(CompactionEntry.serializer(), entry)
        is BranchSummaryEntry -> Agent47Json.encodeToString(BranchSummaryEntry.serializer(), entry)
        is CustomEntry -> Agent47Json.encodeToString(CustomEntry.serializer(), entry)
        is CustomMessageEntry -> Agent47Json.encodeToString(CustomMessageEntry.serializer(), entry)
        is LabelEntry -> Agent47Json.encodeToString(LabelEntry.serializer(), entry)
        is SessionInfoEntry -> Agent47Json.encodeToString(SessionInfoEntry.serializer(), entry)
    }
}

private fun encodeMessage(message: Message): JsonObject {
    return when (message) {
        is UserMessage -> buildJsonObject {
            put("type", "user")
            put("role", message.role)
            put("content", encodeContent(message.content))
            put("timestamp", message.timestamp)
        }
        is AssistantMessage -> buildJsonObject {
            put("type", "assistant")
            put("role", message.role)
            put("content", encodeContent(message.content))
            put("api", message.api.value)
            put("provider", message.provider.value)
            put("model", message.model)
            put("usage", buildJsonObject {
                put("input", message.usage.input)
                put("output", message.usage.output)
                put("cacheRead", message.usage.cacheRead)
                put("cacheWrite", message.usage.cacheWrite)
                put("totalTokens", message.usage.totalTokens)
                put("cost", buildJsonObject {
                    put("input", message.usage.cost.input)
                    put("output", message.usage.cost.output)
                    put("cacheRead", message.usage.cost.cacheRead)
                    put("cacheWrite", message.usage.cost.cacheWrite)
                    put("total", message.usage.cost.total)
                })
            })
            put("stopReason", message.stopReason.name.lowercase())
            put("errorMessage", message.errorMessage?.let(::JsonPrimitive) ?: JsonNull)
            put("timestamp", message.timestamp)
        }
        is ToolResultMessage -> buildJsonObject {
            put("type", "toolResult")
            put("role", message.role)
            put("toolCallId", message.toolCallId)
            put("toolName", message.toolName)
            put("content", encodeContent(message.content))
            put("details", message.details ?: JsonNull)
            put("isError", message.isError)
            put("timestamp", message.timestamp)
        }
        is CustomMessage -> buildJsonObject {
            put("type", "custom")
            put("role", message.role)
            put("customType", message.customType)
            put("content", encodeContent(message.content))
            put("display", message.display)
            put("details", message.details ?: JsonNull)
            put("timestamp", message.timestamp)
        }
        is BashExecutionMessage -> buildJsonObject {
            put("type", "bashExecution")
            put("role", message.role)
            put("command", message.command)
            put("output", message.output)
            put("exitCode", message.exitCode?.let(::JsonPrimitive) ?: JsonNull)
            put("timestamp", message.timestamp)
        }
        is BranchSummaryMessage -> buildJsonObject {
            put("type", "branchSummary")
            put("role", message.role)
            put("fromId", message.fromId)
            put("summary", message.summary)
            put("timestamp", message.timestamp)
        }
        is CompactionSummaryMessage -> buildJsonObject {
            put("type", "compactionSummary")
            put("role", message.role)
            put("summary", message.summary)
            put("tokensBefore", message.tokensBefore)
            put("timestamp", message.timestamp)
        }
    }
}

private fun encodeContent(content: List<ContentBlock>): JsonArray = buildJsonArray {
    content.forEach { block ->
        add(
            when (block) {
                is TextContent -> buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                    put("textSignature", block.textSignature?.let(::JsonPrimitive) ?: JsonNull)
                }
                is ThinkingContent -> buildJsonObject {
                    put("type", "thinking")
                    put("thinking", block.thinking)
                    put("thinkingSignature", block.thinkingSignature?.let(::JsonPrimitive) ?: JsonNull)
                }
                is ImageContent -> buildJsonObject {
                    put("type", "image")
                    put("data", block.data)
                    put("mimeType", block.mimeType)
                }
                is ToolCall -> buildJsonObject {
                    put("type", "toolCall")
                    put("id", block.id)
                    put("name", block.name)
                    put("arguments", block.arguments)
                    put("thoughtSignature", block.thoughtSignature?.let(::JsonPrimitive) ?: JsonNull)
                }
            },
        )
    }
}

public fun migrateToCurrentVersion(entries: MutableList<FileEntry>): Boolean {
    val headerIndex = entries.indexOfFirst { it is HeaderFileEntry }
    if (headerIndex < 0) {
        return false
    }

    val header = (entries[headerIndex] as HeaderFileEntry).header
    val version = header.version ?: 1
    if (version >= CURRENT_SESSION_VERSION) {
        return false
    }

    if (version < 2) {
        migrateV1ToV2(entries)
    }
    migrateV2ToV3(entries)

    return true
}

private fun migrateV1ToV2(entries: MutableList<FileEntry>) {
    var previousId: String? = null

    entries.forEachIndexed { index, fileEntry ->
        when (fileEntry) {
            is HeaderFileEntry -> entries[index] = fileEntry.copy(header = fileEntry.header.copy(version = 2))
            is SessionFileEntry -> {
                val entry = fileEntry.entry
                val id = entry.id.ifBlank {
                    UUID.randomUUID().toString().substring(0, 8)
                }
                val migrated = when (entry) {
                    is SessionMessageEntry -> entry.copy(id = id, parentId = previousId)
                    is ThinkingLevelChangeEntry -> entry.copy(id = id, parentId = previousId)
                    is ModelChangeEntry -> entry.copy(id = id, parentId = previousId)
                    is CompactionEntry -> entry.copy(id = id, parentId = previousId)
                    is BranchSummaryEntry -> entry.copy(id = id, parentId = previousId)
                    is CustomEntry -> entry.copy(id = id, parentId = previousId)
                    is CustomMessageEntry -> entry.copy(id = id, parentId = previousId)
                    is LabelEntry -> entry.copy(id = id, parentId = previousId)
                    is SessionInfoEntry -> entry.copy(id = id, parentId = previousId)
                }
                previousId = id
                entries[index] = SessionFileEntry(migrated)
            }
        }
    }
}

private fun migrateV2ToV3(entries: MutableList<FileEntry>) {
    entries.forEachIndexed { index, fileEntry ->
        when (fileEntry) {
            is HeaderFileEntry -> entries[index] = fileEntry.copy(header = fileEntry.header.copy(version = 3))
            is SessionFileEntry -> {
                val entry = fileEntry.entry
                if (entry is SessionMessageEntry && entry.message.role == "hookMessage") {
                    val migratedMessage = CustomMessage(
                        customType = "hookMessage",
                        content = listOf(TextContent(text = "")),
                        display = true,
                        timestamp = entry.message.timestamp,
                    )
                    entries[index] = SessionFileEntry(entry.copy(message = migratedMessage))
                }
            }
        }
    }
}
