package co.agentmode.agent47.ext.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinExtensionScriptTest {
    @Test
    fun `loads Kotlin extension source`() {
        val script = Files.createTempFile("agent47-extension", ".kts")
        Files.writeString(
            script,
            """
            registerCommand("hello", "Says hello") { arguments, context ->
                context.notify("Hello ${'$'}arguments")
            }
            """.trimIndent(),
        )

        val result = KotlinExtensionScriptLoader().load(script)

        assertIs<ScriptLoadResult.Loaded>(
            result,
            (result as? ScriptLoadResult.Failed)?.failure?.diagnostics?.joinToString("\n"),
        )
        assertEquals(script.toAbsolutePath().normalize().toString(), result.extension.id)
    }

    @Test
    fun `reports Kotlin compiler diagnostics`() {
        val script = Files.createTempFile("agent47-extension-invalid", ".kts")
        Files.writeString(script, "this is not valid Kotlin")

        val failed = assertIs<ScriptLoadResult.Failed>(KotlinExtensionScriptLoader().load(script))

        assertTrue(failed.failure.diagnostics.isNotEmpty())
    }

    @Test
    fun `reload keeps previous runner when requested and compilation fails`() {
        val script = Files.createTempFile("agent47-extension-reload", ".kts")
        Files.writeString(script, "registerCommand(\"hello\", \"Says hello\") { _, _ -> }")
        val runtime = KotlinExtensionRuntime(listOf(script), KotlinExtensionScriptLoader())
        val first = runtime.reload()
        Files.writeString(script, "not valid Kotlin")

        val second = runtime.reload(keepPreviousOnFailure = true)

        assertTrue(first.failures.isEmpty())
        assertTrue(second.failures.isNotEmpty())
        assertEquals(first.runner.loadedExtensionIds(), second.runner.loadedExtensionIds())
    }

    @Test
    fun `configured flags are available while the script is evaluated`() {
        val script = Files.createTempFile("agent47-extension-flags", ".kts")
        Files.writeString(
            script,
            """
            registerFlag("plan", "Enable plan mode")
            val enabled = getFlag("plan") == "true"
            registerCommand(if (enabled) "planned" else "normal", "mode") { _, _ -> }
            """.trimIndent(),
        )
        val loader = KotlinExtensionScriptLoader().also {
            it.configureFlags(mapOf("plan" to "true"))
        }

        val result = loader.load(script)

        val loaded = assertIs<ScriptLoadResult.Loaded>(result)
        val runner = ExtensionRunner().also { it.load(loaded.extension) }
        assertEquals("planned", runner.commands().single().name)
        assertEquals("true", runner.flags().single().value)
    }

    @Test
    fun `compiles the complete declarative extension surface`() {
        val script = Files.createTempFile("agent47-extension-surface", ".kts")
        Files.writeString(
            script,
            """
            import co.agentmode.agent47.ext.core.CompactionHookResult
            import co.agentmode.agent47.ext.core.InputHookResult
            import co.agentmode.agent47.ext.core.MessageRenderer
            import co.agentmode.agent47.ext.core.ToolCallHookResult
            import co.agentmode.agent47.ext.core.ToolRenderer
            import co.agentmode.agent47.ext.core.ToolResultHookResult

            beforeAgent { it }
            afterAgent { }
            transformContext { it }
            on("*") { _, _ -> }
            beforeCompaction { _, _ -> CompactionHookResult() }
            afterCompaction { _, _ -> }
            onToolCall { _, _ -> ToolCallHookResult() }
            onToolResult { _, _ -> ToolResultHookResult() }
            onInput { _, _ -> InputHookResult.Continue }
            onSessionStart { _, _ -> }
            onSessionShutdown { _, _ -> }
            registerShortcut("ctrl+g", "Example") { }
            registerToolRenderer("example", ToolRenderer { _, _ -> listOf("tool") })
            registerMessageRenderer("example", MessageRenderer { _, _ -> listOf("message") })
            registerFlag("example", "Example flag")
            registerCommand("example", "Example command") { _, _ -> }
            """.trimIndent(),
        )

        val loaded = assertIs<ScriptLoadResult.Loaded>(KotlinExtensionScriptLoader().load(script))
        val runner = ExtensionRunner().also { it.load(loaded.extension) }

        assertEquals(1, runner.commands().size)
        assertEquals(1, runner.shortcuts().size)
        assertEquals(1, runner.toolRenderers().size)
        assertEquals(1, runner.messageRenderers().size)
        assertEquals(1, runner.flags().size)
    }
}
