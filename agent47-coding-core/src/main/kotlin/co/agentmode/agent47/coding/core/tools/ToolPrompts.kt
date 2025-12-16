package co.agentmode.agent47.coding.core.tools

/**
 * Loads a tool prompt from classpath resources under `tool-prompts/`.
 * Falls back to the provided default if the resource is not found.
 */
internal fun loadToolPrompt(toolName: String, fallback: String = ""): String {
    val path = "tool-prompts/$toolName.txt"
    return Thread.currentThread().contextClassLoader
        ?.getResourceAsStream(path)
        ?.bufferedReader()
        ?.readText()
        ?: object {}.javaClass.classLoader
            ?.getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
        ?: fallback
}
