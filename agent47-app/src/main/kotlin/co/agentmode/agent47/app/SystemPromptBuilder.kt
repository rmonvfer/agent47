package co.agentmode.agent47.app

import co.agentmode.agent47.coding.core.skills.Skill
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val SYSTEM_PROMPT_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy, hh:mm:ss a z")

internal fun buildSystemPrompt(
    cwd: Path,
    toolNames: List<String>,
    customPrompt: String?,
    appendPrompt: String?,
    skills: List<Skill> = emptyList(),
    instructions: String = "",
    dateTime: String = ZonedDateTime.now().format(SYSTEM_PROMPT_DATE_FORMAT),
): String {
    val toolList = toolNames.joinToString("\n") { "- $it" }
    val fileExplorationGuideline = if (toolNames.containsAll(listOf("grep", "find", "ls"))) {
        "- Prefer grep/find/ls tools over bash for file exploration (faster, respects .gitignore)"
    } else {
        "- Use bash for file operations (ls, grep, find, etc.)"
    }

    return buildString {
        appendLine(
            customPrompt
                ?: "You are an expert coding assistant operating inside agent47, a coding agent harness. " +
                    "You help users by reading files, executing commands, editing code, and writing new files.",
        )
        appendLine()
        appendLine("Available tools:")
        appendLine(toolList)
        appendLine()
        appendLine("Guidelines:")
        appendLine("- Use read to examine files before editing. You must use this tool instead of cat or sed.")
        appendLine("- Use edit for precise changes (old text must match exactly)")
        appendLine("- Use write only for new files or complete rewrites")
        appendLine(fileExplorationGuideline)
        appendLine("- When summarizing your actions, output plain text directly - do NOT use cat or bash to display what you did")
        appendLine("- Be concise in your responses")
        appendLine("- Show file paths clearly when working with files")

        if (instructions.isNotBlank()) {
            appendLine()
            appendLine(instructions)
        }

        if (skills.isNotEmpty()) {
            appendLine()
            appendLine("Skills are domain-specific knowledge files you can read lazily with the read tool using skill:// URLs.")
            appendLine()
            appendLine("<skills>")
            skills.forEach { skill ->
                val globs = skill.globs?.joinToString(",").orEmpty()
                appendLine("<skill name=\"${skill.name}\" globs=\"$globs\">${skill.description}</skill>")
            }
            appendLine("</skills>")

            skills.filter { it.alwaysApply }.forEach { skill ->
                appendLine()
                appendLine("<applied-skill name=\"${skill.name}\">")
                appendLine(skill.content)
                appendLine("</applied-skill>")
            }
        }

        appendPrompt?.let {
            appendLine()
            appendLine(it)
        }

        appendLine()
        appendLine("Current date and time: $dateTime")
        append("Current working directory: $cwd")
    }
}
