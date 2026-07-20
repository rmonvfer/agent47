package co.agentmode.agent47.coding.core.commands

public object SlashCommandExpander {

    /**
     * If [text] starts with `/` and matches a known command, expand it.
     * Returns null if no command matched.
     */
    public fun expand(text: String, commands: List<SlashCommand>): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null

        val spaceIndex = trimmed.indexOf(' ')
        val commandName = if (spaceIndex > 0) {
            trimmed.substring(1, spaceIndex)
        } else {
            trimmed.substring(1)
        }

        val command = commands.firstOrNull { it.name.equals(commandName, ignoreCase = true) }
            ?: return null

        val argsString = if (spaceIndex > 0) trimmed.substring(spaceIndex + 1).trim() else ""
        val args = parseArgs(argsString)

        return substituteArgs(command.content, args)
    }

    /**
     * Parse arguments with bash-style quoting. Supports single quotes, double quotes, and backslash escapes.
     */
    public fun parseArgs(argsString: String): List<String> {
        if (argsString.isBlank()) return emptyList()

        val args = mutableListOf<String>()
        val current = StringBuilder()
        var inSingleQuote = false
        var inDoubleQuote = false
        var escaped = false
        var i = 0

        while (i < argsString.length) {
            val c = argsString[i]

            if (escaped) {
                current.append(c)
                escaped = false
                i++
                continue
            }

            if (c == '\\' && !inSingleQuote) {
                escaped = true
                i++
                continue
            }

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote
                i++
                continue
            }

            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote
                i++
                continue
            }

            if (c.isWhitespace() && !inSingleQuote && !inDoubleQuote) {
                if (current.isNotEmpty()) {
                    args += current.toString()
                    current.clear()
                }
                i++
                continue
            }

            current.append(c)
            i++
        }

        if (current.isNotEmpty()) {
            args += current.toString()
        }

        return args
    }

    /**
     * Substitute `$1`, `$2`, ..., `$@`, and `$ARGUMENTS` in the template with parsed arguments.
     */
    public fun substituteArgs(content: String, args: List<String>): String {
        val allArgs = args.joinToString(" ")
        // A single left-to-right scan: replacements are not re-scanned, so an argument value that
        // itself contains "$5" stays literal, and "$10" is matched whole rather than as "$1" + "0".
        val pattern = Regex("\\\$ARGUMENTS|\\\$@|\\\$(\\d+)")
        val result = pattern.replace(content) { match ->
            val digits = match.groupValues[1]
            when {
                digits.isNotEmpty() -> {
                    val index = digits.toIntOrNull()
                    if (index != null && index in 1..args.size) args[index - 1] else ""
                }
                else -> allArgs
            }
        }
        return result.trim()
    }
}
