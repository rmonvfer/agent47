import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.Short as JavaShort
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("agent47.kotlin-application-conventions")
    id("org.graalvm.buildtools.native")
}

val kotlinExtensionRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val kotlinExtensionNativeCompatibilityClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(project(":agent47-ai-types"))
    implementation(project(":agent47-ai-core"))
    implementation(project(":agent47-ai-providers"))
    implementation(project(":agent47-agent-core"))
    implementation(project(":agent47-coding-core"))
    implementation(project(":agent47-ext-core"))
    implementation(project(":agent47-ui-core"))
    implementation(project(":agent47-tui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-coroutines:3.0.2")
    compileOnly("org.graalvm.sdk:nativeimage:25.1.3")
    kotlinExtensionRuntimeClasspath(project(":agent47-ext-kotlin-runtime"))
    kotlinExtensionNativeCompatibilityClasspath("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.20")

    // JetBrains Compose publishes empty wrapper jars with filenames identical to their
    // AndroidX counterparts. The wrappers are excluded below; these provide the real classes.
    runtimeOnly("androidx.compose.runtime:runtime-desktop:1.10.0-rc01")
    runtimeOnly("androidx.compose.runtime:runtime-saveable-desktop:1.10.0-rc01")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}

configurations.runtimeClasspath {
    // JetBrains Compose thin wrapper jars share filenames with their AndroidX equivalents
    // but contain no classes. Exclude them so the real AndroidX jars win during packaging.
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-desktop")
    exclude(group = "org.jetbrains.compose.runtime", module = "runtime-saveable-desktop")
    // Mordant's FFM backend requires JDK 22+ (finalized Foreign Function API).
    // JBR 21 has the preview FFM API which is incompatible. Exclude it so Mordant uses JNA.
    exclude(group = "com.github.ajalt.mordant", module = "mordant-jvm-ffm")
}

application {
    mainClass.set("co.agentmode.agent47.app.MainKt")
}

val kotlinCompilerCompatibilityJar by tasks.registering(Jar::class) {
    dependsOn(kotlinExtensionNativeCompatibilityClasspath)
    archiveFileName.set("agent47-kotlin-native-compatibility.jar")
    destinationDirectory.set(layout.buildDirectory.dir("kotlin-extension"))
    from({ zipTree(kotlinExtensionNativeCompatibilityClasspath.singleFile) }) {
        include(
            "org/jetbrains/kotlin/load/java/" +
                "SpecialGenericSignatures\$TypeSafeBarrierDescription.class",
            "org/jetbrains/kotlin/load/java/" +
                "SpecialGenericSignatures\$TypeSafeBarrierDescription\$MAP_GET_OR_DEFAULT.class",
        )
    }
}

val kotlinExtensionRuntimeArchive by tasks.registering(Jar::class) {
    dependsOn(configurations.runtimeClasspath, kotlinExtensionRuntimeClasspath)
    archiveFileName.set("agent47-kotlin-runtime.zip")
    destinationDirectory.set(layout.buildDirectory.dir("kotlin-extension"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        kotlinExtensionRuntimeClasspath.files.map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
    from({
        val javaHome = providers.environmentVariable("JAVA_HOME").orNull
            ?: error("JAVA_HOME is required to build the Kotlin extension runtime")
        zipTree("$javaHome/lib/ct.sym")
    }) {
        eachFile {
            val segments = path.split('/')
            if (segments.size < 3 || 'L' !in segments.first() || !path.endsWith(".sig")) {
                exclude()
            } else {
                path = segments.drop(2).joinToString("/").removePrefix("/").removeSuffix(".sig") + ".class"
            }
        }
        includeEmptyDirs = false
    }
    exclude("META-INF/versions/**")
    exclude("META-INF/native-image/org.jline/**")
    exclude("module-info.class")
}

tasks.named<JavaExec>("run") {
    classpath(kotlinExtensionRuntimeClasspath)
    providers.gradleProperty("nativeAgentOutput").orNull?.let { outputDirectory ->
        jvmArgs("-agentlib:native-image-agent=config-output-dir=$outputDirectory")
    }
}

tasks.withType<AbstractCopyTask> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("agent47-version.properties") {
        expand("version" to project.version)
    }
}

graalvmNative {
    toolchainDetection.set(false)
    binaries {
        named("main") {
            classpath.from(sourceSets.main.get().runtimeClasspath, kotlinCompilerCompatibilityJar)
            // Force a fat classpath JAR so the stripping task below can remove
            // multi-release FFM entries before native-image processes the classpath.
            useFatJar.set(true)
            imageName.set("agent47")
            mainClass.set("co.agentmode.agent47.app.MainKt")
            resources.autodetect()
            resources.includedPatterns.add("agent47-version\\.properties")
            buildArgs.add("-Os")
            buildArgs.add("-H:+ReportExceptionStackTraces")
            buildArgs.add("-Dsun.misc.unsafe.memory.access=allow")
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:+RuntimeClassLoading")
            buildArgs.add("--features=co.agentmode.agent47.app.KotlinCompilerCompatibilityFeature")
            buildArgs.add("-H:+AddAllCharsets")
            buildArgs.add("-H:+AllowJRTFileSystem")
            buildArgs.add("--add-modules=java.logging,java.management,java.xml,jdk.unsupported")
            buildArgs.add("-H:Preserve=package=co.agentmode.agent47.ext.core")
            buildArgs.add("-H:Preserve=package=kotlin")
            buildArgs.add("-H:Preserve=package=kotlin.jvm.internal")
            buildArgs.add("-H:Preserve=package=kotlin.reflect.jvm")
            buildArgs.add("-H:Preserve=package=kotlin.collections")
            buildArgs.add("-H:Preserve=package=kotlin.sequences")
            buildArgs.add("-H:Preserve=package=kotlin.coroutines")
            buildArgs.add("-H:Preserve=package=kotlin.coroutines.intrinsics")
            buildArgs.add("-H:Preserve=package=kotlin.coroutines.jvm.internal")
            buildArgs.add("-H:Preserve=package=kotlin.io")
            buildArgs.add("-H:Preserve=package=kotlin.text")
            buildArgs.add("-H:Preserve=package=kotlin.ranges")
            buildArgs.add("-H:Preserve=package=kotlin.properties")
            buildArgs.add("-H:Preserve=package=kotlin.annotation")
            buildArgs.add("-H:Preserve=package=kotlin.comparisons")
            buildArgs.add("-H:Preserve=package=java.lang")
            buildArgs.add("-H:Preserve=package=java.lang.invoke")
            buildArgs.add("-H:Preserve=package=java.lang.ref")
            buildArgs.add("-H:Preserve=package=java.lang.reflect")
            buildArgs.add("-H:Preserve=package=java.io")
            buildArgs.add("-H:Preserve=package=javax.xml.stream")
            buildArgs.add("-H:Preserve=package=java.util")
            buildArgs.add("-H:Preserve=package=java.util.function")
            buildArgs.add("-H:Preserve=package=java.util.concurrent.atomic")
            buildArgs.add("-H:Preserve=package=java.util.concurrent.locks")
            buildArgs.add("-H:Preserve=package=kotlinx.serialization.json")
            buildArgs.add("-H:-UnlockExperimentalVMOptions")
        }
    }
}

fun RandomAccessFile.readUnsignedIntLittleEndian(): Long =
    Integer.toUnsignedLong(Integer.reverseBytes(readInt()))

fun RandomAccessFile.writeUnsignedIntLittleEndian(value: Long) {
    require(value in 0..0xffff_ffffL) { "ZIP offset exceeds the ZIP32 limit" }
    writeInt(Integer.reverseBytes(value.toInt()))
}

fun adjustZipOffsets(archive: File, prefixLength: Long) {
    RandomAccessFile(archive, "rw").use { file ->
        val minimumEocdSize = 22L
        val maximumCommentSize = 0xffffL
        val searchStart = (file.length() - minimumEocdSize - maximumCommentSize).coerceAtLeast(prefixLength)
        var eocdOffset = file.length() - minimumEocdSize
        while (eocdOffset >= searchStart) {
            file.seek(eocdOffset)
            if (file.readInt() == 0x504b0506) break
            eocdOffset--
        }
        check(eocdOffset >= searchStart) { "Could not find the Kotlin runtime ZIP directory" }

        file.seek(eocdOffset + 10)
        val entryCount = JavaShort.toUnsignedInt(JavaShort.reverseBytes(file.readShort()))
        file.seek(eocdOffset + 16)
        val centralDirectoryOffset = file.readUnsignedIntLittleEndian()
        var entryOffset = prefixLength + centralDirectoryOffset

        repeat(entryCount) {
            file.seek(entryOffset)
            check(file.readInt() == 0x504b0102) { "Invalid Kotlin runtime ZIP directory" }
            file.seek(entryOffset + 28)
            val nameLength = JavaShort.toUnsignedInt(JavaShort.reverseBytes(file.readShort()))
            val extraLength = JavaShort.toUnsignedInt(JavaShort.reverseBytes(file.readShort()))
            val commentLength = JavaShort.toUnsignedInt(JavaShort.reverseBytes(file.readShort()))
            file.seek(entryOffset + 42)
            val localHeaderOffset = file.readUnsignedIntLittleEndian()
            file.seek(entryOffset + 42)
            file.writeUnsignedIntLittleEndian(prefixLength + localHeaderOffset)
            entryOffset += 46L + nameLength + extraLength + commentLength
        }

        file.seek(eocdOffset + 16)
        file.writeUnsignedIntLittleEndian(prefixLength + centralDirectoryOffset)
    }
}

tasks.named("nativeCompile") {
    dependsOn(kotlinExtensionRuntimeArchive)
    doLast {
        val executable = layout.buildDirectory.file("native/nativeCompile/agent47").get().asFile
        val runtime = kotlinExtensionRuntimeArchive.get().archiveFile.get().asFile
        check(executable.isFile) { "Native executable does not exist: $executable" }
        val alreadyEmbedded = runCatching {
            ZipFile(executable).use { archive ->
                archive.getEntry("co/agentmode/agent47/ext/core/KotlinExtensionScriptLoader.class") != null
            }
        }.getOrDefault(false)
        if (alreadyEmbedded) return@doLast

        val prefixLength = executable.length()
        FileOutputStream(executable, true).buffered().use { output ->
            runtime.inputStream().buffered().use { input -> input.copyTo(output) }
        }
        adjustZipOffsets(executable, prefixLength)
    }
}

// Mosaic's multi-release JAR ships FFM/Panama bindings under META-INF/versions/22/ that
// replace the JNI bindings on JDK 22+. GraalVM 25 picks up the FFM path, but FFM downcalls
// require explicit registration in reachability-metadata.json for every function descriptor
// (and Mosaic doesn't ship those registrations). Stripping the version-22 entries from the
// native image classpath JAR forces Mosaic to use its JNI fallback, which GraalVM handles
// without additional configuration.
tasks.named("nativeCompileClasspathJar") {
    doLast {
        val jar = outputs.files.singleFile
        val tmpJar = File(jar.parentFile, "${jar.name}.tmp")

        ZipFile(jar).use { zip ->
            ZipOutputStream(tmpJar.outputStream()).use { zos ->
                for (entry in zip.entries()) {
                    if (entry.name.startsWith("META-INF/versions/")) continue
                    // kotlin-compiler-embeddable shades JLine's native-image.properties without
                    // the reflection/resource JSON files referenced by those properties.
                    if (entry.name.startsWith("META-INF/native-image/org.jline/")) continue
                    zos.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        zip.getInputStream(entry).use { it.copyTo(zos) }
                    }
                    zos.closeEntry()
                }
            }
        }

        jar.delete()
        tmpJar.renameTo(jar)
    }
}
