package co.agentmode.agent47.ui.core.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

public enum class CompletionItemKind {
    Command,
    File,
}

public data class CompletionItem(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
    val kind: CompletionItemKind,
)

public data class CompletionContext(
    val lineText: String,
    val cursorColumn: Int,
    val tokenStart: Int,
    val tokenEnd: Int,
    val trigger: Char,
    val query: String,
)

public interface AutocompleteProvider {
    public fun supports(trigger: Char): Boolean

    public fun complete(context: CompletionContext): List<CompletionItem>
}

public data class AutocompletePopupModel(
    val tokenStart: Int,
    val tokenEnd: Int,
    val trigger: Char,
    val items: List<CompletionItem>,
    val selectedIndex: Int = 0,
)

/**
 * Slash command completion for the commands available in the current session.
 */
public class SlashCommandCompletionProvider(
    commands: List<String>,
    descriptions: Map<String, String> = emptyMap(),
) : AutocompleteProvider {

    @Volatile
    private var uniqueCommands: List<String> = emptyList()

    @Volatile
    private var detailsByCommand: Map<String, String> = emptyMap()

    init {
        update(commands, descriptions)
    }

    public fun update(commands: List<String>, descriptions: Map<String, String> = emptyMap()) {
        uniqueCommands = commands
            .map { it.trim().removePrefix("/") }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        detailsByCommand = descriptions.mapKeys { (key, _) -> key.trim().removePrefix("/") }
    }

    override fun supports(trigger: Char): Boolean = trigger == '/'

    override fun complete(context: CompletionContext): List<CompletionItem> {
        val beforeToken = context.lineText.substring(0, context.tokenStart).trim()
        if (beforeToken.isNotEmpty()) {
            return emptyList()
        }

        return uniqueCommands
            .asSequence()
            .filter { it.startsWith(context.query, ignoreCase = true) }
            .map {
                CompletionItem(
                    label = "/$it",
                    insertText = "/$it ",
                    detail = detailsByCommand[it],
                    kind = CompletionItemKind.Command,
                )
            }
            .take(50)
            .toList()
    }
}

/**
 * File path completion for @ references.
 */
public class FileCompletionProvider(
    private val root: Path,
    private val walkLimit: Int = 5_000,
    private val resultLimit: Int = 100,
    private val cacheTtlMs: Long = 5_000,
) : AutocompleteProvider {
    // The file listing is cached and reused for the (short) TTL. Walking the tree per keystroke on
    // the UI thread froze the editor in large repos; filtering a cached list is cheap.
    private val lock = Any()
    private var cachedCandidates: List<String> = emptyList()
    private var cacheTimestampMs: Long = 0
    private var cacheLoaded: Boolean = false

    override fun supports(trigger: Char): Boolean = trigger == '@'

    override fun complete(context: CompletionContext): List<CompletionItem> {
        val query = context.query
        return candidates().asSequence()
            .filter { query.isBlank() || it.startsWith(query, ignoreCase = true) }
            .sortedWith(compareBy<String> { it.length }.thenComparing(String.CASE_INSENSITIVE_ORDER))
            .take(resultLimit)
            .map { candidate ->
                val isDirectory = candidate.endsWith('/')
                CompletionItem(
                    label = "@$candidate",
                    insertText = "@$candidate",
                    detail = if (isDirectory) "directory" else "file",
                    kind = CompletionItemKind.File,
                )
            }
            .toList()
    }

    private fun candidates(): List<String> {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (cacheLoaded && now - cacheTimestampMs < cacheTtlMs) {
                return cachedCandidates
            }
            cachedCandidates = walk()
            cacheTimestampMs = now
            cacheLoaded = true
            return cachedCandidates
        }
    }

    private fun walk(): List<String> {
        return try {
            Files.walk(root).use { stream ->
                stream
                    .filter { it != root }
                    .filter { path ->
                        val relative = path.relativeTo(root)
                        relative.none { segment -> segment.toString() == "node_modules" || segment.toString() == ".git" }
                    }
                    .filter { Files.isRegularFile(it) || Files.isDirectory(it) }
                    .limit(walkLimit.toLong())
                    .map { path ->
                        val relative = path.relativeTo(root).toString().replace('\\', '/')
                        if (Files.isDirectory(path)) "$relative/" else relative
                    }
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Coordinates providers and selected popup state.
 */
public class AutocompleteManager(
    private val providers: List<AutocompleteProvider>,
) {
    public var popup: AutocompletePopupModel? = null
        private set

    public fun update(lineText: String, cursorColumn: Int): AutocompletePopupModel? {
        val context = detectCompletionContext(lineText, cursorColumn)
        if (context == null) {
            popup = null
            return null
        }

        val items = providers
            .asSequence()
            .filter { it.supports(context.trigger) }
            .flatMap { it.complete(context).asSequence() }
            .distinctBy { it.insertText }
            .toList()

        if (items.isEmpty()) {
            popup = null
            return null
        }

        val selectedLabel = popup?.items?.getOrNull(popup?.selectedIndex ?: -1)?.label
        val selectedIndex = selectedLabel?.let { label ->
            items.indexOfFirst { it.label == label }.takeIf { it >= 0 }
        } ?: 0

        popup = AutocompletePopupModel(
            tokenStart = context.tokenStart,
            tokenEnd = context.tokenEnd,
            trigger = context.trigger,
            items = items,
            selectedIndex = selectedIndex,
        )
        return popup
    }

    public fun dismiss() {
        popup = null
    }

    public fun selectNext() {
        val current = popup ?: return
        popup = current.copy(selectedIndex = (current.selectedIndex + 1) % current.items.size)
    }

    public fun selectPrevious() {
        val current = popup ?: return
        popup = current.copy(
            selectedIndex = (current.selectedIndex - 1 + current.items.size) % current.items.size,
        )
    }

    public fun applySelection(state: EditorState): Boolean {
        val current = popup ?: return false
        val selected = current.items.getOrNull(current.selectedIndex) ?: return false
        state.replaceInCurrentLine(current.tokenStart, current.tokenEnd, selected.insertText)
        popup = null
        return true
    }

    private fun detectCompletionContext(lineText: String, cursorColumn: Int): CompletionContext? {
        val safeCursor = cursorColumn.coerceIn(0, lineText.length)
        if (safeCursor == 0) {
            return null
        }

        var tokenStart = safeCursor - 1
        while (tokenStart >= 0 && !lineText[tokenStart].isWhitespace()) {
            tokenStart -= 1
        }
        tokenStart += 1

        if (tokenStart >= safeCursor) {
            return null
        }

        val token = lineText.substring(tokenStart, safeCursor)
        if (token.isEmpty()) {
            return null
        }

        val trigger = token.first()
        if (trigger != '/' && trigger != '@') {
            return null
        }

        return CompletionContext(
            lineText = lineText,
            cursorColumn = safeCursor,
            tokenStart = tokenStart,
            tokenEnd = safeCursor,
            trigger = trigger,
            query = token.drop(1),
        )
    }
}
