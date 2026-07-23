package co.agentmode.agent47.app

import co.agentmode.agent47.ext.core.ExtensionScriptLoader
import co.agentmode.agent47.ext.core.KotlinExtensionRuntimeRegistry
import co.agentmode.agent47.ext.core.RuntimeClassSanitizer
import org.graalvm.nativeimage.ProcessProperties
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLClassLoader
import java.net.URLStreamHandler
import java.nio.file.Path
import java.util.Collections
import java.util.zip.ZipFile

internal object KotlinExtensionSupport {
    private const val LOADER_CLASS = "co.agentmode.agent47.ext.core.KotlinExtensionScriptLoader"
    private const val BOOTSTRAP_CLASS = "co.agentmode.agent47.ext.core.KotlinExtensionRuntimeBootstrapKt"

    fun createLoader(): ExtensionScriptLoader {
        if (!isNativeImageRuntime()) {
            return instantiate(Thread.currentThread().contextClassLoader)
        }

        val executable = ProcessProperties.getExecutableName()
        ZipFile(executable).use { archive ->
            checkNotNull(archive.getEntry(LOADER_CLASS.replace('.', '/') + ".class")) {
                "The agent47 executable does not contain the Kotlin extension runtime"
            }
        }

        System.setProperty("java.home", executable)
        System.setProperty("agent47.kotlin.compiler.jar", executable)
        System.setProperty("kotlin.script.classpath", executable)
        System.setProperty("kotlin.java.stdlib.jar", executable)

        val classLoader = KotlinExtensionClassLoader(
            arrayOf(java.io.File(executable).toURI().toURL()),
            ExtensionScriptLoader::class.java.classLoader,
            Path.of(executable),
        )
        Class.forName(BOOTSTRAP_CLASS, true, classLoader)
        return KotlinExtensionRuntimeRegistry.take()
    }

    private fun instantiate(classLoader: ClassLoader): ExtensionScriptLoader =
        Class.forName(LOADER_CLASS, true, classLoader)
            .getDeclaredConstructor()
            .newInstance() as ExtensionScriptLoader

    private fun isNativeImageRuntime(): Boolean =
        System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
}

private class KotlinExtensionClassLoader(
    urls: Array<java.net.URL>,
    parent: ClassLoader,
    private val archivePath: Path,
) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> =
        if (isRuntimeOwned(name)) loadRuntimeClass(name, resolve) else super.loadClass(name, resolve)

    private fun loadRuntimeClass(name: String, resolve: Boolean): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val runtimeClass = findLoadedClass(name) ?: try {
                findClass(name)
            } catch (_: ClassNotFoundException) {
                return@synchronized super.loadClass(name, resolve)
            }
            if (resolve) resolveClass(runtimeClass)
            runtimeClass
        }

    override fun findClass(name: String): Class<*> {
        if (isPlatformClass(name)) {
            throw ClassNotFoundException(name)
        }
        val entryName = name.replace('.', '/') + ".class"
        val bytes = ZipFile(archivePath.toFile()).use { archive ->
            archive.getEntry(entryName)?.let { entry ->
                archive.getInputStream(entry).use { it.readBytes() }
            }
        } ?: return super.findClass(name)
        val packageName = name.substringBeforeLast('.', "")
        if (packageName.isNotEmpty() && getDefinedPackage(packageName) == null) {
            definePackage(packageName, null, null, null, null, null, null, null)
        }
        val sanitized = RuntimeClassSanitizer.sanitize(bytes)
        return defineClass(name, sanitized, 0, sanitized.size)
    }

    override fun findResource(name: String): URL? =
        super.findResource(name) ?: name.takeIf(::hasEntry)?.let(::resourceUrl)

    override fun findResources(name: String): java.util.Enumeration<URL> {
        val resources = super.findResources(name).toList().toMutableList()
        findResource(name)?.takeIf { it !in resources }?.let(resources::add)
        return Collections.enumeration(resources)
    }

    private fun hasEntry(name: String): Boolean =
        ZipFile(archivePath.toFile()).use { it.getEntry(name) != null }

    private fun readEntry(name: String): ByteArray? =
        ZipFile(archivePath.toFile()).use { archive ->
            val entry = archive.getEntry(name) ?: return null
            archive.getInputStream(entry).use { it.readBytes() }
        }

    private fun resourceUrl(name: String): URL =
        URL.of(URI.create("agent47-resource:/${name.hashCode()}"), object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection =
                object : URLConnection(url) {
                    override fun connect() = Unit

                    override fun getInputStream(): java.io.InputStream =
                        readEntry(name)?.inputStream() ?: throw FileNotFoundException(name)
                }
        })

    private fun isRuntimeOwned(name: String): Boolean =
        RUNTIME_CLASS_PREFIXES.any(name::startsWith) ||
            name.startsWith("kotlin.time.") ||
            name.startsWith("kotlinx.coroutines.")

    private fun isPlatformClass(name: String): Boolean = PLATFORM_PACKAGES.any(name::startsWith)

    private companion object {
        val PLATFORM_PACKAGES: List<String> = listOf("java.", "javax.", "jdk.", "sun.", "com.sun.")
        val RUNTIME_CLASS_PREFIXES: List<String> = listOf(
            "co.agentmode.agent47.ext.core.Agent47ExtensionBuilder",
            "co.agentmode.agent47.ext.core.Agent47Script",
            "co.agentmode.agent47.ext.core.KotlinExtensionRuntimeBootstrapKt",
            "co.agentmode.agent47.ext.core.KotlinExtensionScriptLoader",
            "co.agentmode.agent47.ext.core.SanitizedScriptClassLoader",
        )
    }
}
