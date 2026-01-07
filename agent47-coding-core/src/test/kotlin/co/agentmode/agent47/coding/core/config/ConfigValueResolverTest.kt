package co.agentmode.agent47.coding.core.config

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigValueResolverTest {

    @Test
    fun `resolve returns literal value for plain strings`() = runTest {
        assertEquals("hello", ConfigValueResolver.resolve("hello"))
    }

    @Test
    fun `resolve returns short string unchanged even if starting with prefix char`() = runTest {
        // Single-char strings (length < 2) are returned as-is
        assertEquals("$", ConfigValueResolver.resolve("$"))
        assertEquals("!", ConfigValueResolver.resolve("!"))
    }

    @Test
    fun `resolve expands dollar-prefix to environment variable`() = runTest {
        // PATH should always exist on unix systems
        val result = ConfigValueResolver.resolve($$"$PATH")
        assertEquals(System.getenv("PATH"), result)
    }

    @Test
    fun `resolve returns null for missing environment variable`() = runTest {
        val result = ConfigValueResolver.resolve($$"$AGENT47_TEST_NONEXISTENT_VAR_XYZZY")
        assertNull(result)
    }

    @Test
    fun `resolve expands bang-prefix as shell command`() = runTest {
        val result = ConfigValueResolver.resolve("!echo hello-from-shell")
        assertEquals("hello-from-shell", result)
    }

    @Test
    fun `resolve returns null for failing shell command`() = runTest {
        val result = ConfigValueResolver.resolve("!exit 1")
        assertNull(result)
    }

    @Test
    fun `resolve caches shell command results`() = runTest {
        // Run the same command twice; second should hit cache
        val first = ConfigValueResolver.resolve("!echo cache-test-42")
        val second = ConfigValueResolver.resolve("!echo cache-test-42")
        assertEquals("cache-test-42", first)
        assertEquals(first, second)
    }

    @Test
    fun `resolveSync expands dollar-prefix to environment variable`() {
        val result = ConfigValueResolver.resolveSync($$"$PATH")
        assertEquals(System.getenv("PATH"), result)
    }

    @Test
    fun `resolveSync returns original string for missing env var`() {
        val result = ConfigValueResolver.resolveSync($$"$AGENT47_TEST_NONEXISTENT_VAR_XYZZY")
        assertEquals($$"$AGENT47_TEST_NONEXISTENT_VAR_XYZZY", result)
    }

    @Test
    fun `resolveSync returns literal value for plain strings`() {
        assertEquals("foobar", ConfigValueResolver.resolveSync("foobar"))
    }
}
