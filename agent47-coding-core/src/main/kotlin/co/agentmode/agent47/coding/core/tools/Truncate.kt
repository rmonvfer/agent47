package co.agentmode.agent47.coding.core.tools

import java.nio.charset.StandardCharsets

public const val DEFAULT_MAX_LINES: Int = 2_000
public const val DEFAULT_MAX_BYTES: Int = 50 * 1_024
public const val GREP_MAX_LINE_LENGTH: Int = 500

public data class TruncationResult(
    val content: String,
    val truncated: Boolean,
    val truncatedBy: TruncatedBy?,
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    val lastLinePartial: Boolean,
    val firstLineExceedsLimit: Boolean,
    val maxLines: Int,
    val maxBytes: Int,
)

public enum class TruncatedBy {
    LINES,
    BYTES,
}

public data class TruncationOptions(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxBytes: Int = DEFAULT_MAX_BYTES,
)

public fun formatSize(bytes: Int): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }
}

private fun checkNoTruncationNeeded(
    content: String,
    totalLines: Int,
    totalBytes: Int,
    options: TruncationOptions,
): TruncationResult? {
    if (totalLines <= options.maxLines && totalBytes <= options.maxBytes) {
        return TruncationResult(
            content = content,
            truncated = false,
            truncatedBy = null,
            totalLines = totalLines,
            totalBytes = totalBytes,
            outputLines = totalLines,
            outputBytes = totalBytes,
            lastLinePartial = false,
            firstLineExceedsLimit = false,
            maxLines = options.maxLines,
            maxBytes = options.maxBytes,
        )
    }
    return null
}

public fun truncateHead(content: String, options: TruncationOptions = TruncationOptions()): TruncationResult {
    val totalBytes = content.toByteArray(StandardCharsets.UTF_8).size
    val lines = content.split("\n")
    val totalLines = lines.size

    checkNoTruncationNeeded(content, totalLines, totalBytes, options)?.let { return it }

    val firstLineBytes = lines.firstOrNull()?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
    if (firstLineBytes > options.maxBytes) {
        return TruncationResult(
            content = "",
            truncated = true,
            truncatedBy = TruncatedBy.BYTES,
            totalLines = totalLines,
            totalBytes = totalBytes,
            outputLines = 0,
            outputBytes = 0,
            lastLinePartial = false,
            firstLineExceedsLimit = true,
            maxLines = options.maxLines,
            maxBytes = options.maxBytes,
        )
    }

    val output = mutableListOf<String>()
    var bytes = 0
    var truncatedBy = TruncatedBy.LINES

    for (index in lines.indices) {
        if (index >= options.maxLines) {
            truncatedBy = TruncatedBy.LINES
            break
        }
        val line = lines[index]
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (bytes + lineBytes > options.maxBytes) {
            truncatedBy = TruncatedBy.BYTES
            break
        }
        output += line
        bytes += lineBytes
    }

    val outputContent = output.joinToString("\n")
    val outputBytes = outputContent.toByteArray(StandardCharsets.UTF_8).size

    return TruncationResult(
        content = outputContent,
        truncated = true,
        truncatedBy = truncatedBy,
        totalLines = totalLines,
        totalBytes = totalBytes,
        outputLines = output.size,
        outputBytes = outputBytes,
        lastLinePartial = false,
        firstLineExceedsLimit = false,
        maxLines = options.maxLines,
        maxBytes = options.maxBytes,
    )
}

public fun truncateTail(content: String, options: TruncationOptions = TruncationOptions()): TruncationResult {
    val totalBytes = content.toByteArray(StandardCharsets.UTF_8).size
    val lines = content.split("\n")
    val totalLines = lines.size

    checkNoTruncationNeeded(content, totalLines, totalBytes, options)?.let { return it }

    val output = ArrayDeque<String>()
    var bytes = 0
    var truncatedBy = TruncatedBy.LINES
    var lastLinePartial = false

    for (index in lines.lastIndex downTo 0) {
        if (output.size >= options.maxLines) {
            truncatedBy = TruncatedBy.LINES
            break
        }

        val line = lines[index]
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (bytes + lineBytes > options.maxBytes) {
            truncatedBy = TruncatedBy.BYTES
            if (output.isEmpty()) {
                output.addFirst(truncateToBytesFromEnd(line, options.maxBytes))
                bytes = output.first().toByteArray(StandardCharsets.UTF_8).size
                lastLinePartial = true
            }
            break
        }

        output.addFirst(line)
        bytes += lineBytes
    }

    val outputContent = output.joinToString("\n")
    val outputBytes = outputContent.toByteArray(StandardCharsets.UTF_8).size

    return TruncationResult(
        content = outputContent,
        truncated = true,
        truncatedBy = truncatedBy,
        totalLines = totalLines,
        totalBytes = totalBytes,
        outputLines = output.size,
        outputBytes = outputBytes,
        lastLinePartial = lastLinePartial,
        firstLineExceedsLimit = false,
        maxLines = options.maxLines,
        maxBytes = options.maxBytes,
    )
}

public fun truncateLine(line: String, maxChars: Int = GREP_MAX_LINE_LENGTH): Pair<String, Boolean> {
    if (line.length <= maxChars) {
        return line to false
    }
    return "${line.take(maxChars)}... [truncated]" to true
}

private fun truncateToBytesFromEnd(text: String, maxBytes: Int): String {
    val data = text.toByteArray(StandardCharsets.UTF_8)
    if (data.size <= maxBytes) {
        return text
    }

    var start = data.size - maxBytes
    while (start < data.size && (data[start].toInt() and 0b1100_0000) == 0b1000_0000) {
        start += 1
    }

    return String(data.copyOfRange(start, data.size), StandardCharsets.UTF_8)
}
