package co.agentmode.agent47.ext.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/**
 * Removes optional class relationship attributes that GraalVM runtime class loading
 * cannot currently materialize. The JVM resolves nested class names independently
 * of these reflection-only attributes.
 */
public object RuntimeClassSanitizer {
    public fun sanitize(bytes: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) = Unit
                override fun visitNestHost(nestHost: String?) = Unit
                override fun visitNestMember(nestMember: String?) = Unit
                override fun visitInnerClass(
                    name: String?,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) = Unit
            },
            0,
        )
        return writer.toByteArray()
    }
}

public object KotlinExtensionRuntimeRegistry {
    @Volatile
    private var loader: ExtensionScriptLoader? = null

    public fun install(value: ExtensionScriptLoader) {
        loader = value
    }

    public fun take(): ExtensionScriptLoader =
        checkNotNull(loader) { "The Kotlin extension runtime did not initialize" }
            .also { loader = null }
}
