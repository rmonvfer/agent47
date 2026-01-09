package co.agentmode.agent47.coding.core.instructions

import java.nio.file.Path

public data class InstructionFile(
    val path: Path,
    val content: String,
    val source: InstructionSource,
)

public enum class InstructionSource { PROJECT, GLOBAL, CLAUDE_CODE, SETTINGS }
