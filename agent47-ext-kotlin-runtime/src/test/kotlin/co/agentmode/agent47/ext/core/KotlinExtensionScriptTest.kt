package co.agentmode.agent47.ext.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinExtensionScriptTest {
    @Test
    fun `complete repository example compiles every Kotlin entrypoint`() {
        val extensions = exampleRepository().resolve("extensions")
        val scripts = Files.walk(extensions).use { paths ->
            paths
                .filter { path -> path.fileName.toString() == "index.kts" }
                .sorted()
                .toList()
        }

        val loaded = scripts.map { script -> KotlinExtensionScriptLoader().load(script) }

        assertEquals(2, loaded.size)
        loaded.forEach { result ->
            assertIs<ScriptLoadResult.Loaded>(
                result,
                (result as? ScriptLoadResult.Failed)?.failure?.diagnostics?.joinToString("\n"),
            )
        }
    }

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
    fun `cached extension bypasses compilation and reevaluates flags`() {
        val cache = Files.createTempDirectory("agent47-extension-cache")
        val script = Files.createTempFile("agent47-extension-cached", ".kts")
        Files.writeString(
            script,
            """
            registerFlag("plan", "Enable plan mode")
            val enabled = getFlag("plan") == "true"
            registerCommand(if (enabled) "planned" else "normal", "mode") { _, _ -> }
            """.trimIndent(),
        )
        val firstLoader = cachedLoader(cache, flags = mapOf("plan" to "false"))

        val first = assertIs<ScriptLoadResult.Loaded>(firstLoader.load(script))

        assertTrue(firstLoader.compilerWasInitialized())
        assertEquals("normal", commands(first).single().name)
        assertEquals(1, cacheFiles(cache).size)

        val secondLoader = cachedLoader(cache, flags = mapOf("plan" to "true"))
        val second = assertIs<ScriptLoadResult.Loaded>(secondLoader.load(script))

        assertFalse(secondLoader.compilerWasInitialized())
        assertEquals("planned", commands(second).single().name)
        assertEquals(1, cacheFiles(cache).size)
    }

    @Test
    fun `script changes and runtime changes invalidate compiled extensions`() {
        val cache = Files.createTempDirectory("agent47-extension-cache")
        val script = Files.createTempFile("agent47-extension-cached", ".kts")
        Files.writeString(script, "registerCommand(\"first\", \"first\") { _, _ -> }")
        assertIs<ScriptLoadResult.Loaded>(cachedLoader(cache).load(script))

        Files.writeString(script, "registerCommand(\"second\", \"second\") { _, _ -> }")
        val changedSourceLoader = cachedLoader(cache)
        val changedSource = assertIs<ScriptLoadResult.Loaded>(changedSourceLoader.load(script))

        assertTrue(changedSourceLoader.compilerWasInitialized())
        assertEquals("second", commands(changedSource).single().name)

        val changedRuntimeLoader = cachedLoader(cache, runtimeId = "runtime-2")
        assertIs<ScriptLoadResult.Loaded>(changedRuntimeLoader.load(script))

        assertTrue(changedRuntimeLoader.compilerWasInitialized())
    }

    @Test
    fun `corrupt compiled extension is replaced by a successful compilation`() {
        val cache = Files.createTempDirectory("agent47-extension-cache")
        val script = Files.createTempFile("agent47-extension-cached", ".kts")
        Files.writeString(script, "registerCommand(\"cached\", \"cached\") { _, _ -> }")
        assertIs<ScriptLoadResult.Loaded>(cachedLoader(cache).load(script))
        val cachedFile = cacheFiles(cache).single()
        Files.writeString(cachedFile, "not a zip archive")
        val recoveringLoader = cachedLoader(cache)

        val recovered = assertIs<ScriptLoadResult.Loaded>(recoveringLoader.load(script))

        assertTrue(recoveringLoader.compilerWasInitialized())
        assertEquals("cached", commands(recovered).single().name)
        assertTrue(Files.size(cachedFile) > "not a zip archive".length)
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

    private fun exampleRepository(): Path =
        listOf(
            Path.of("examples/extension-repository"),
            Path.of("../examples/extension-repository"),
        ).firstOrNull { it.resolve("agent47.json").exists() }
            ?.toAbsolutePath()
            ?.normalize()
            ?: error("Cannot locate examples/extension-repository")

    private fun cachedLoader(
        cache: Path,
        runtimeId: String = "runtime-1",
        flags: Map<String, String> = emptyMap(),
    ): KotlinExtensionScriptLoader =
        KotlinExtensionScriptLoader().also { loader ->
            loader.configureFlags(flags)
            loader.configureCompilationCache(cache, runtimeId)
        }

    private fun commands(loaded: ScriptLoadResult.Loaded): List<RegisteredCommand> =
        ExtensionRunner().also { it.load(loaded.extension) }.commands()

    private fun cacheFiles(cache: Path): List<Path> =
        Files.walk(cache).use { paths ->
            paths.filter(Files::isRegularFile).toList()
        }
}
