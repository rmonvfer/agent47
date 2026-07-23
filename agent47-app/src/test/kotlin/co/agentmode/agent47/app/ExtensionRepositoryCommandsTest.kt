package co.agentmode.agent47.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionRepositoryCommandsTest {
    @Test
    fun `install accepts project scope before or after the source`() {
        val before = parseExtensionRepositoryCommand(listOf("install", "--local", "git:github.com/a/b"))
        val after = parseExtensionRepositoryCommand(listOf("install", "git:github.com/a/b", "-l"))

        assertEquals(ExtensionRepositoryAction.INSTALL, before.action)
        assertEquals("git:github.com/a/b", before.source)
        assertTrue(before.local)
        assertEquals(before, after)
    }

    @Test
    fun `uninstall aliases remove`() {
        val options = parseExtensionRepositoryCommand(listOf("uninstall", "./extension"))

        assertEquals(ExtensionRepositoryAction.REMOVE, options.action)
        assertEquals("./extension", options.source)
        assertFalse(options.local)
    }

    @Test
    fun `list rejects scope and source arguments`() {
        assertFailsWith<IllegalArgumentException> {
            parseExtensionRepositoryCommand(listOf("list", "--local"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseExtensionRepositoryCommand(listOf("list", "git:github.com/a/b"))
        }
    }

    @Test
    fun `install requires exactly one source`() {
        assertFailsWith<IllegalArgumentException> {
            parseExtensionRepositoryCommand(listOf("install"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseExtensionRepositoryCommand(listOf("install", "./one", "./two"))
        }
    }
}
