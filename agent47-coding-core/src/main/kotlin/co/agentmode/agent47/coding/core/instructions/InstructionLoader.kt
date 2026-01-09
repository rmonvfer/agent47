package co.agentmode.agent47.coding.core.instructions

import co.agentmode.agent47.coding.core.settings.Settings
import java.nio.file.Path

public class InstructionLoader(
    private val cwd: Path,
    private val globalDir: Path,
    private val claudeDir: Path?,
    private val settings: Settings,
) {
    private var cached: List<InstructionFile>? = null

    public fun load(): List<InstructionFile> {
        cached?.let { return it }
        val result = InstructionDiscovery.discover(cwd, globalDir, claudeDir, settings)
        cached = result
        return result
    }

    public fun format(): String {
        val files = load()
        if (files.isEmpty()) return ""
        return files.joinToString("\n\n") { file ->
            "Instructions from: ${file.path}\n${file.content}"
        }
    }
}
