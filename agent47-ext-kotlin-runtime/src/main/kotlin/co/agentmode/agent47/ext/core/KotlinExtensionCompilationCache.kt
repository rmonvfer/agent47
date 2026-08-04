package co.agentmode.agent47.ext.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

internal class KotlinExtensionCompilationCache(
    baseDirectory: Path,
    runtimeId: String,
) {
    private val runtimeDirectory: Path = baseDirectory
        .resolve("v$CACHE_SCHEMA_VERSION")
        .resolve(runtimeHash(runtimeId))
    private val archive = CompiledScriptArchive(runtimeDirectory)
    private val memoryCache: MutableMap<String, MemoryEntry> = mutableMapOf()

    fun load(
        source: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
    ): CompiledScript? {
        val sourceId = source.cacheSourceId()
        val key = cacheKey(source, compilationConfiguration)
        val memoryScript = synchronized(memoryCache) {
            memoryCache[sourceId]?.takeIf { it.key == key }?.script
        }
        return memoryScript ?: loadArchive(source, compilationConfiguration, sourceId, key)
    }

    private fun loadArchive(
        source: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
        sourceId: String,
        key: String,
    ): CompiledScript? {
        val path = cachePath(sourceId, key)
        val compiledScript = runCatching {
            archive.read(path, key, source.locationId, compilationConfiguration)
        }.getOrElse {
            path.deleteIfExists()
            null
        }
        if (compiledScript == null) {
            path.deleteIfExists()
        }
        compiledScript?.let { script ->
            synchronized(memoryCache) {
                memoryCache[sourceId] = MemoryEntry(key, script)
            }
        }
        return compiledScript
    }

    fun store(
        script: CompiledScript,
        source: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
    ) {
        cacheableOutput(script)?.let { output ->
            val sourceId = source.cacheSourceId()
            val key = cacheKey(source, compilationConfiguration)
            synchronized(memoryCache) {
                memoryCache[sourceId] = MemoryEntry(key, script)
            }
            runCatching {
                archive.write(cachePath(sourceId, key), key, output)
            }
        }
    }

    private fun cachePath(sourceId: String, key: String): Path =
        runtimeDirectory.resolve("${sha256(sourceId)}-$key.zip")

    private fun cacheKey(
        source: SourceCode,
        compilationConfiguration: ScriptCompilationConfiguration,
    ): String = sha256 {
        add(source.cacheSourceId())
        add(source.text)
        compilationConfiguration.notTransientData.entries
            .sortedBy { it.key.name }
            .forEach { (key, value) ->
                add(key.name)
                add(value.toString())
            }
    }

    private fun cacheableOutput(script: CompiledScript): CacheableCompilerOutput? {
        val jvmScript = script as? KJvmCompiledScript
        val module = jvmScript?.getCompiledModule() as? KJvmCompiledModuleInMemory
        return if (jvmScript == null || module == null) {
            null
        } else {
            val scriptClassFqName = jvmScript.scriptClassFQName
            if (
                jvmScript.otherScripts.isEmpty() &&
                jvmScript.resultField == null
            ) {
                CacheableCompilerOutput(scriptClassFqName, module.compilerOutputFiles)
            } else {
                null
            }
        }
    }
}

private class CompiledScriptArchive(
    private val directory: Path,
) {
    fun read(
        path: Path,
        expectedKey: String,
        sourceLocationId: String?,
        compilationConfiguration: ScriptCompilationConfiguration,
    ): CompiledScript? =
        if (!Files.isRegularFile(path)) {
            null
        } else {
            ZipFile(path.toFile()).use { archive ->
                val metadata = archive.getEntry(METADATA_ENTRY)?.let { entry ->
                    archive.getInputStream(entry).use { input ->
                        DataInputStream(input).use(::readMetadata)
                    }
                }
                if (metadata?.schemaVersion == CACHE_SCHEMA_VERSION && metadata.key == expectedKey) {
                    KJvmCompiledScript(
                        sourceLocationId,
                        compilationConfiguration,
                        metadata.scriptClassFqName,
                        null,
                        emptyList(),
                        InMemoryCompiledModule(readEntries(archive)),
                    )
                } else {
                    null
                }
            }
        }

    private fun readEntries(archive: ZipFile): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        val archiveEntries = archive.entries()
        while (archiveEntries.hasMoreElements()) {
            val entry = archiveEntries.nextElement()
            if (entry.isDirectory || entry.name == METADATA_ENTRY) continue
            require(entry.name.isNotBlank()) { "Cached extension contains an unnamed entry" }
            require(entry.size in 0..MAX_ENTRY_BYTES) { "Cached extension entry is too large: ${entry.name}" }
            totalSize += entry.size
            require(totalSize <= MAX_CACHE_BYTES) { "Cached extension exceeds the size limit" }
            require(entries.size < MAX_CACHE_ENTRIES) { "Cached extension contains too many entries" }
            val bytes = archive.getInputStream(entry).use { it.readBytes() }
            require(bytes.size.toLong() == entry.size) { "Cached extension entry is truncated: ${entry.name}" }
            require(entries.put(entry.name, bytes) == null) { "Duplicate cached extension entry: ${entry.name}" }
        }
        require(entries.isNotEmpty()) { "Cached extension contains no compiler output" }
        return entries
    }

    fun write(target: Path, key: String, output: CacheableCompilerOutput) {
        require(output.entries.isNotEmpty()) { "Compiled extension contains no output" }
        Files.createDirectories(directory)
        restrictPermissions(directory, executable = true)
        val temporary = Files.createTempFile(directory, ".extension-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { archive ->
                writeEntry(archive, METADATA_ENTRY, metadataBytes(key, output.scriptClassFqName))
                output.entries.toSortedMap().forEach { (name, bytes) ->
                    require(name.isNotBlank() && name != METADATA_ENTRY) {
                        "Invalid compiled extension entry: $name"
                    }
                    require(bytes.size.toLong() <= MAX_ENTRY_BYTES) {
                        "Compiled extension entry is too large: $name"
                    }
                    writeEntry(archive, name, bytes)
                }
            }
            if (publish(temporary, target)) {
                restrictPermissions(target, executable = false)
            }
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun metadataBytes(key: String, scriptClassFqName: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(CACHE_SCHEMA_VERSION)
                output.writeUTF(key)
                output.writeUTF(scriptClassFqName)
            }
            bytes.toByteArray()
        }

    private fun readMetadata(input: DataInputStream): CacheMetadata =
        CacheMetadata(
            schemaVersion = input.readInt(),
            key = input.readUTF(),
            scriptClassFqName = input.readUTF(),
        )

    private fun writeEntry(archive: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L }
        archive.putNextEntry(entry)
        ByteArrayInputStream(bytes).use { it.copyTo(archive) }
        archive.closeEntry()
    }

    private fun publish(source: Path, target: Path): Boolean {
        val published = source.toFile().renameTo(target.toFile())
        check(published || Files.isRegularFile(target)) {
            "Could not publish compiled extension cache entry: $target"
        }
        return published
    }

    private fun restrictPermissions(path: Path, executable: Boolean) {
        runCatching {
            path.toFile().apply {
                setReadable(false, false)
                setWritable(false, false)
                setExecutable(false, false)
                setReadable(true, true)
                setWritable(true, true)
                if (executable) {
                    setExecutable(true, true)
                }
            }
        }
    }
}

private fun runtimeHash(runtimeId: String): String = sha256 {
    add(runtimeId)
    add(KotlinVersion.CURRENT.toString())
}

private fun sha256(value: String): String = sha256 { add(value) }

private fun SourceCode.cacheSourceId(): String =
    locationId ?: name ?: "<anonymous-extension>"

private fun sha256(block: DigestBuilder.() -> Unit): String =
    DigestBuilder(MessageDigest.getInstance("SHA-256"))
        .apply(block)
        .digest()
        .joinToString("") { byte -> "%02x".format(byte) }

private data class MemoryEntry(
    val key: String,
    val script: CompiledScript,
)

private data class CacheableCompilerOutput(
    val scriptClassFqName: String,
    val entries: Map<String, ByteArray>,
)

private data class CacheMetadata(
    val schemaVersion: Int,
    val key: String,
    val scriptClassFqName: String,
)

private class DigestBuilder(
    private val digest: MessageDigest,
) {
    fun add(value: String) {
        val bytes = value.toByteArray()
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }

    fun digest(): ByteArray = digest.digest()
}

private const val CACHE_SCHEMA_VERSION: Int = 1
private const val MAX_CACHE_ENTRIES: Int = 10_000
private const val MAX_ENTRY_BYTES: Long = 16L * 1024L * 1024L
private const val MAX_CACHE_BYTES: Long = 64L * 1024L * 1024L
private const val METADATA_ENTRY: String = "META-INF/agent47-extension-cache.bin"

private class InMemoryCompiledModule(
    override val compilerOutputFiles: Map<String, ByteArray>,
) : KJvmCompiledModuleInMemory {
    override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader =
        ExtensionScriptClassLoader(baseClassLoader, compilerOutputFiles)
}

private class ExtensionScriptClassLoader(
    parent: ClassLoader?,
    private val entries: Map<String, ByteArray>,
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        val bytes = entries[path] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResourceAsStream(name: String): InputStream? =
        entries[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
}
