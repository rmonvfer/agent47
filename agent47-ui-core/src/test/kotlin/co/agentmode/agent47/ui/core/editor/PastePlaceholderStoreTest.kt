package co.agentmode.agent47.ui.core.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PastePlaceholderStoreTest {
    @Test
    fun `store returns a lines marker for multi-line content`() {
        val store = PastePlaceholderStore()
        val content = (1..16).joinToString("\n") { "line $it" }

        assertEquals("[paste #1 +16 lines]", store.store(content))
    }

    @Test
    fun `store returns a chars marker for long single-line content`() {
        val store = PastePlaceholderStore()
        val content = "x".repeat(1234)

        assertEquals("[paste #1 1234 chars]", store.store(content))
    }

    @Test
    fun `expand replaces a marker with its stored content`() {
        val store = PastePlaceholderStore()
        val content = (1..12).joinToString("\n") { "line $it" }
        val marker = store.store(content)

        assertEquals("before $content after", store.expand("before $marker after"))
    }

    @Test
    fun `expand replaces every marker when several pastes are present`() {
        val store = PastePlaceholderStore()
        val first = (1..11).joinToString("\n") { "a$it" }
        val second = "y".repeat(1100)
        val firstMarker = store.store(first)
        val secondMarker = store.store(second)

        assertEquals("$first and $second", store.expand("$firstMarker and $secondMarker"))
    }

    @Test
    fun `expand leaves a marker whose id was never stored as literal text`() {
        val store = PastePlaceholderStore()

        assertEquals("see [paste #7 +99 lines]", store.expand("see [paste #7 +99 lines]"))
    }

    @Test
    fun `expand leaves a removed paste's marker as literal text`() {
        val store = PastePlaceholderStore()
        val marker = store.store("z".repeat(2000))
        store.remove(1)

        assertFalse(store.contains(1))
        assertEquals(marker, store.expand(marker))
    }

    @Test
    fun `ids keep increasing after a removal and are never reused`() {
        val store = PastePlaceholderStore()
        store.store("a".repeat(1500))
        store.remove(1)

        assertEquals("[paste #2 1500 chars]", store.store("b".repeat(1500)))
    }

    @Test
    fun `clear discards stored pastes and restarts ids`() {
        val store = PastePlaceholderStore()
        val marker = store.store("c".repeat(1500))
        store.clear()

        assertFalse(store.contains(1))
        assertEquals(marker, store.expand(marker))
        assertEquals("[paste #1 1500 chars]", store.store("d".repeat(1500)))
    }

    @Test
    fun `isPasteMarker accepts only a complete standalone marker`() {
        assertTrue(isPasteMarker("[paste #1 +16 lines]"))
        assertTrue(isPasteMarker("[paste #2 1234 chars]"))
        assertFalse(isPasteMarker("see [paste #1 +16 lines]"))
        assertFalse(isPasteMarker("[paste #1"))
        assertFalse(isPasteMarker("[paste]"))
    }
}
