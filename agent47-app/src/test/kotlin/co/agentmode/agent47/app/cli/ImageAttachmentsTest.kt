package co.agentmode.agent47.app.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageAttachmentsTest {
    @Test
    fun `detects png`() {
        assertEquals("image/png", detectImageMimeType(Path.of("photo.png")))
    }

    @Test
    fun `detects jpeg from jpg and jpeg extensions`() {
        assertEquals("image/jpeg", detectImageMimeType(Path.of("photo.jpg")))
        assertEquals("image/jpeg", detectImageMimeType(Path.of("photo.jpeg")))
    }

    @Test
    fun `detects gif`() {
        assertEquals("image/gif", detectImageMimeType(Path.of("anim.gif")))
    }

    @Test
    fun `detects webp`() {
        assertEquals("image/webp", detectImageMimeType(Path.of("photo.webp")))
    }

    @Test
    fun `detects extensions case insensitively`() {
        assertEquals("image/png", detectImageMimeType(Path.of("PHOTO.PNG")))
    }

    @Test
    fun `returns null for a non-image extension`() {
        assertNull(detectImageMimeType(Path.of("notes.txt")))
        assertNull(detectImageMimeType(Path.of("README")))
    }
}
