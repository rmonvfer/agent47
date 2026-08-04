package co.agentmode.agent47.app

import co.agentmode.agent47.ext.core.ExtensionScriptLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object KotlinExtensionSupport {
    private const val LOADER_CLASS = "co.agentmode.agent47.ext.core.KotlinExtensionScriptLoader"

    fun createLoader(): ExtensionScriptLoader =
        Class.forName(LOADER_CLASS, true, Thread.currentThread().contextClassLoader)
            .getDeclaredConstructor()
            .newInstance() as ExtensionScriptLoader

    fun compilationRuntimeId(version: String): String {
        val artifacts = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter(String::isNotBlank)
            .map(Path::of)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(version.toByteArray())
        artifacts
            .map { it.toAbsolutePath().normalize() }
            .sorted()
            .forEach { artifact -> updateArtifactDigest(digest, artifact) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun updateArtifactDigest(digest: MessageDigest, artifact: Path) {
        digest.update(artifact.toString().toByteArray())
        when {
            Files.isRegularFile(artifact) -> updatePathMetadata(digest, artifact)
            Files.isDirectory(artifact) -> Files.walk(artifact).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .sorted()
                    .forEach { path ->
                        digest.update(artifact.relativize(path).toString().toByteArray())
                        updatePathMetadata(digest, path)
                    }
            }
        }
    }

    private fun updatePathMetadata(digest: MessageDigest, path: Path) {
        digest.update(Files.size(path).toString().toByteArray())
        digest.update(Files.getLastModifiedTime(path).toMillis().toString().toByteArray())
    }
}
