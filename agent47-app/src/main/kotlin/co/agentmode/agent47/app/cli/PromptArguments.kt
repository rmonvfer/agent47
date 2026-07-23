package co.agentmode.agent47.app.cli

import co.agentmode.agent47.ai.types.ImageContent
import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

internal fun escapePromptFileArguments(args: Array<String>): Array<String> =
    args.map { argument ->
        if (argument.startsWith("@") && !argument.startsWith("@@")) "@$argument" else argument
    }.toTypedArray()

internal fun processPrompt(args: List<String>, terminal: Terminal): Pair<String, List<ImageContent>> {
    val textParts = mutableListOf<String>()
    val images = mutableListOf<ImageContent>()

    for (arg in args) {
        if (arg.startsWith("@")) {
            val filePath = Path.of(arg.substring(1))
            if (!filePath.exists()) {
                terminal.printError("File not found: ${filePath.toAbsolutePath()}")
                throw Abort()
            }

            val mimeType = detectImageMimeType(filePath)
            if (mimeType != null) {
                val bytes = filePath.readBytes()
                val resized = try {
                    resizeImage(bytes, mimeType)
                } catch (_: LinkageError) {
                    ImageResizeResult(bytes, mimeType)
                }
                images.add(
                    ImageContent(
                        data = Base64.getEncoder().encodeToString(resized.data),
                        mimeType = resized.mimeType,
                    ),
                )
                textParts.add("<file name=\"${filePath.name}\"></file>")
            } else {
                val content = filePath.readText()
                textParts.add("<file name=\"${filePath.name}\">\n$content\n</file>")
            }
        } else {
            textParts.add(arg)
        }
    }

    return Pair(textParts.joinToString("\n\n"), images)
}
