package co.agentmode.agent47.app

import co.agentmode.agent47.app.update.UpdateTargets
import co.agentmode.agent47.app.update.resolveUpdateTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateFlowTest {
    @Test
    fun `plain update targets the executable`() {
        assertEquals(
            UpdateTargets(executable = true, extensions = false),
            resolveUpdateTargets(false, false, false, null, null),
        )
    }

    @Test
    fun `extensions and all select their documented targets`() {
        assertEquals(
            UpdateTargets(executable = false, extensions = true),
            resolveUpdateTargets(false, true, false, null, null),
        )
        assertEquals(
            UpdateTargets(executable = true, extensions = true),
            resolveUpdateTargets(false, false, true, null, null),
        )
    }

    @Test
    fun `positional source targets one extension repository`() {
        assertEquals(
            UpdateTargets(
                executable = false,
                extensions = true,
                extensionSource = "git:github.com/owner/repository",
            ),
            resolveUpdateTargets(
                selfOnly = false,
                extensionsOnly = false,
                all = false,
                extensionOption = null,
                positionalTarget = "git:github.com/owner/repository",
            ),
        )
    }

    @Test
    fun `update rejects conflicting targets`() {
        assertFailsWith<IllegalArgumentException> {
            resolveUpdateTargets(true, true, false, null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            resolveUpdateTargets(false, false, false, "./one", "./two")
        }
        assertFailsWith<IllegalArgumentException> {
            resolveUpdateTargets(false, true, false, null, "./extension")
        }
    }
}
