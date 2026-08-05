@file:Suppress("MatchingDeclarationName")

package co.agentmode.agent47.tui.input

import co.agentmode.agent47.coding.core.commands.SlashCommand
import co.agentmode.agent47.coding.core.commands.SlashCommandExpander
import co.agentmode.agent47.ext.core.RegisteredCommand
import co.agentmode.agent47.tui.commands.builtinSlashCommands

/**
 * A parsed submission from the editor. Pure classification of the raw input; applying it is the
 * dispatcher's job.
 */
internal sealed interface Submission {
    /** A recognized builtin slash command, e.g. /model or /session. */
    data class Builtin(val command: String, val args: List<String>, val raw: String) : Submission

    /** A slash command registered by an extension. */
    data class Extension(val command: RegisteredCommand, val rawArgs: String, val raw: String) : Submission

    /** A file-based slash command expanded into a prompt. */
    data class FileExpansion(val expanded: String) : Submission

    /** A shell command entered with the ! prefix (command may be blank). */
    data class Bash(val command: String) : Submission

    /** Plain prompt text. */
    data class Prompt(val text: String) : Submission

    /** A slash command that matches no builtin, extension, or file command. */
    data class UnknownSlash(val command: String, val raw: String) : Submission

    /** An `@name message` mention routing the message to a background agent (or "main"/"orchestrator"). */
    data class AgentMention(val target: String, val text: String, val raw: String) : Submission
}

private val builtinCommandNames: Set<String> = builtinSlashCommands.map { it.command }.toSet()

// `@name` followed by at least one space and non-blank text; DOT_MATCHES_ALL lets the message span
// multiple lines. A bare "@name" with nothing after it is not a mention — it falls through to Prompt.
private val agentMentionPattern = Regex("^@(\\S+)\\s+(.+)$", RegexOption.DOT_MATCHES_ALL)

internal fun parseSubmission(
    rawInput: String,
    extensionCommands: List<RegisteredCommand>,
    fileSlashCommands: List<SlashCommand>,
): Submission = when {
    rawInput.startsWith("/") -> parseSlash(rawInput, extensionCommands, fileSlashCommands)
    rawInput.startsWith("@") -> parseAgentMention(rawInput) ?: Submission.Prompt(rawInput)
    rawInput.startsWith("!") -> Submission.Bash(rawInput.removePrefix("!").trim())
    else -> Submission.Prompt(rawInput)
}

private fun parseAgentMention(rawInput: String): Submission.AgentMention? {
    val match = agentMentionPattern.find(rawInput) ?: return null
    val text = match.groupValues[2].trim()
    return if (text.isBlank()) null else Submission.AgentMention(target = match.groupValues[1], text = text, raw = rawInput)
}

@Suppress("ReturnCount")
private fun parseSlash(
    rawInput: String,
    extensionCommands: List<RegisteredCommand>,
    fileSlashCommands: List<SlashCommand>,
): Submission {
    val tokens = rawInput.trim().split(Regex("\\s+"))
    val command = tokens.firstOrNull().orEmpty()
    val args = tokens.drop(1)

    if (command in builtinCommandNames) return Submission.Builtin(command, args, rawInput)

    val extensionCommand = extensionCommands.firstOrNull {
        "/${it.name}".equals(command, ignoreCase = true)
    }
    if (extensionCommand != null) {
        val rawArgs = rawInput.trim().removePrefix(command).trimStart()
        return Submission.Extension(extensionCommand, rawArgs, rawInput)
    }

    val expanded = SlashCommandExpander.expand(rawInput, fileSlashCommands)
    if (expanded != null) return Submission.FileExpansion(expanded)

    return Submission.UnknownSlash(command, rawInput)
}
