package co.agentmode.agent47.ui.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentIdentityTest {
    @Test
    fun `the same name always maps to the same slot`() {
        val first = identityPaletteIndex("structure", 5)
        repeat(10) {
            assertEquals(first, identityPaletteIndex("structure", 5))
        }
    }

    @Test
    fun `different names spread across the palette rather than collapsing to one slot`() {
        val names = listOf("structure", "explore", "reviewer", "planner", "tester", "writer", "researcher", "auditor")
        val slots = names.map { identityPaletteIndex(it, 5) }.toSet()
        assertTrue(slots.size > 1, "expected more than one distinct slot across $names, got $slots")
    }

    @Test
    fun `every slot is within the palette bounds regardless of the name's hash`() {
        val names = listOf("a", "b", "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz", "", "🤖", "agent-47")
        names.forEach { name ->
            val index = identityPaletteIndex(name, 5)
            assertTrue(index in 0 until 5, "index $index for '$name' out of bounds")
        }
    }

    @Test
    fun `a palette of one slot always resolves to that slot`() {
        assertEquals(0, identityPaletteIndex("anything", 1))
    }

    @Test
    fun `requires a positive palette size`() {
        assertFailsWith<IllegalArgumentException> { identityPaletteIndex("x", 0) }
    }
}
