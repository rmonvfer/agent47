package co.agentmode.agent47.app.cli

import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension

internal fun detectImageMimeType(path: Path): String? = when (path.extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    else -> null
}

internal fun resizeImage(bytes: ByteArray, mimeType: String): ImageResizeResult {
    val maxDim = 2000
    val image = ImageIO.read(bytes.inputStream())
    if (image == null || (image.width <= maxDim && image.height <= maxDim)) {
        return ImageResizeResult(bytes, mimeType)
    }

    val scale = maxDim.toDouble() / maxOf(image.width, image.height)
    val newWidth = (image.width * scale).toInt()
    val newHeight = (image.height * scale).toInt()

    val resized = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = resized.createGraphics()
    graphics.drawImage(image.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH), 0, 0, null)
    graphics.dispose()

    val outputStream = ByteArrayOutputStream()
    val format = when (mimeType) {
        "image/png" -> "PNG"
        "image/jpeg" -> "JPEG"
        "image/gif" -> "GIF"
        "image/webp" -> "PNG"
        else -> "PNG"
    }
    ImageIO.write(resized, format, outputStream)

    return ImageResizeResult(outputStream.toByteArray(), if (format == "JPEG") "image/jpeg" else mimeType)
}

internal data class ImageResizeResult(val data: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ImageResizeResult

        if (!data.contentEquals(other.data)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + mimeType.hashCode()
        return result
    }
}
