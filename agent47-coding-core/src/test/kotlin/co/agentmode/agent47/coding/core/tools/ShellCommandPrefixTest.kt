package co.agentmode.agent47.coding.core.tools

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ShellCommandPrefixTest {

    @Test
    fun `createCoreTools passes commandPrefix to BashTool`() {
        val cwd = createTempDirectory("shell-prefix-test")
        val tools = createCoreTools(
            cwd = cwd,
            enabled = listOf("bash"),
            commandPrefix = "set -e",
        )

        assertNotNull(tools.bash)
        assertEquals("set -e", tools.bash.commandPrefix)
    }

    @Test
    fun `createCoreTools defaults to null commandPrefix`() {
        val cwd = createTempDirectory("shell-prefix-test")
        val tools = createCoreTools(
            cwd = cwd,
            enabled = listOf("bash"),
        )

        assertNotNull(tools.bash)
        assertNull(tools.bash!!.commandPrefix)
    }

    @Test
    fun `BashTool commandPrefix is accessible`() {
        val cwd = createTempDirectory("shell-prefix-test")
        val bash = BashTool(cwd, commandPrefix = "export FOO=bar")
        assertEquals("export FOO=bar", bash.commandPrefix)
    }
}
