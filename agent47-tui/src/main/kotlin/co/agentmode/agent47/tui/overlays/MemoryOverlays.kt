package co.agentmode.agent47.tui.overlays

import co.agentmode.agent47.coding.core.instructions.InstructionSource
import co.agentmode.agent47.ui.core.state.SelectItem

internal fun OverlayNavigator.openMemoryOverlay() {
    if (instructionFiles.isEmpty()) {
        feed.appendSystemMessage("No instruction files loaded")
        return
    }
    val options = instructionFiles.map { file ->
        val sourceLabel = when (file.source) {
            InstructionSource.PROJECT -> "Project"
            InstructionSource.GLOBAL -> "Global"
            InstructionSource.CLAUDE_CODE -> "Claude Code"
            InstructionSource.SETTINGS -> "Settings"
        }
        val relativePath = runCatching {
            cwd.relativize(file.path).toString()
        }.getOrElse { file.path.toString() }
        val lineCount = file.content.count { it == '\n' } + 1
        SelectItem(
            label = "$sourceLabel · ${file.path.fileName}    $relativePath  ${lineCount}L",
            value = file,
        )
    }
    overlays.push(
        title = "Instructions",
        items = options,
        keepOpenOnSubmit = true,
        onSubmit = { file ->
            overlays.pushScrollableText(
                title = "${file.path.fileName} (${file.source.name.lowercase()})",
                lines = file.content.split("\n"),
            )
        },
    )
}
