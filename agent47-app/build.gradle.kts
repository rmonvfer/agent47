plugins {
    id("agent47.kotlin-application-conventions")
}

val kotlinExtensionRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
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
    kotlinExtensionRuntimeClasspath(project(":agent47-ext-kotlin-runtime"))

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

tasks.named<JavaExec>("run") {
    classpath(kotlinExtensionRuntimeClasspath)
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

// The distribution bundles a jlinked Java runtime so installations have no JDK
// dependency: <dist>/jre, <dist>/lib (application, dependency, and Kotlin
// extension runtime jars), and <dist>/bin/agent47 (launcher).

fun detectDistTarget(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osName = when {
        os.startsWith("mac") -> "darwin"
        os.startsWith("linux") -> "linux"
        else -> error("Unsupported distribution OS: $os")
    }
    val archName = when (arch) {
        "aarch64", "arm64" -> "arm64"
        "amd64", "x86_64" -> "x86_64"
        else -> error("Unsupported distribution architecture: $arch")
    }
    return "$osName-$archName"
}

val distTarget: String = providers.gradleProperty("agent47.dist.target")
    .getOrElse(detectDistTarget())

// ServiceLoader-driven modules (TLS providers, charsets, zip filesystem) are
// invisible to static analysis, so the module set is fixed rather than derived
// from jdeps at build time.
val jlinkModules = listOf(
    "java.base",
    "java.compiler",
    // The embedded Kotlin compiler's IntelliJ core initializes file types with Swing icons.
    "java.desktop",
    "java.logging",
    "java.management",
    "java.naming",
    "java.net.http",
    "java.scripting",
    "java.xml",
    "jdk.compiler",
    "jdk.crypto.ec",
    "jdk.unsupported",
    "jdk.zipfs",
).joinToString(",")

val distDirectory = layout.buildDirectory.dir("jvm-dist/agent47")
val jdkInstallationPath = javaToolchains
    .launcherFor(java.toolchain)
    .map { launcher -> launcher.metadata.installationPath }

val jvmDistRuntime by tasks.registering {
    description = "Builds the jlinked Java runtime for the distribution"
    inputs.property("modules", jlinkModules)
    val runtimeDirectory = distDirectory.map { it.dir("jre") }
    outputs.dir(runtimeDirectory)
    val jdkHome = jdkInstallationPath
    doLast {
        val output = runtimeDirectory.get().asFile
        output.deleteRecursively()
        val jdk = jdkHome.get().asFile
        val jlink = ProcessBuilder(
            "$jdk/bin/jlink",
            "--module-path", "$jdk/jmods",
            "--add-modules", jlinkModules,
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress", "zip-6",
            "--output", output.absolutePath,
        ).redirectErrorStream(true).start()
        val log = jlink.inputStream.bufferedReader().readText()
        check(jlink.waitFor() == 0) { "jlink failed:\n$log" }
    }
}

val jvmDistLibs by tasks.registering(Sync::class) {
    description = "Collects the application and Kotlin extension runtime jars"
    from(tasks.named("jar"))
    from(configurations.runtimeClasspath)
    from(kotlinExtensionRuntimeClasspath)
    into(distDirectory.map { it.dir("lib") })
}

val jvmDistLauncher by tasks.registering {
    description = "Generates the distribution launcher script"
    dependsOn(jvmDistLibs)
    val libDirectory = distDirectory.map { it.dir("lib") }
    val launcherFile = distDirectory.map { it.file("bin/agent47") }
    outputs.file(launcherFile)
    doLast {
        val jars = libDirectory.get().asFile
            .listFiles { file -> file.extension == "jar" }
            .orEmpty()
            .map(File::getName)
            .sorted()
        check(jars.isNotEmpty()) { "The distribution lib directory contains no jars" }
        val classpath = jars.joinToString(":") { name -> "\$APP_HOME/lib/$name" }
        val launcher = launcherFile.get().asFile
        launcher.parentFile.mkdirs()
        launcher.writeText(
            """
            #!/bin/sh
            INVOKED="$0"
            case "${'$'}INVOKED" in
                /*) ;;
                *) INVOKED="$(pwd)/${'$'}INVOKED" ;;
            esac
            SELF="${'$'}INVOKED"
            while [ -h "${'$'}SELF" ]; do
                DIR=$(cd "$(dirname "${'$'}SELF")" && pwd)
                SELF=$(readlink "${'$'}SELF")
                case "${'$'}SELF" in
                    /*) ;;
                    *) SELF="${'$'}DIR/${'$'}SELF" ;;
                esac
            done
            APP_HOME=$(cd "$(dirname "${'$'}SELF")/.." && pwd)
            exec "${'$'}APP_HOME/jre/bin/java" \
                --enable-native-access=ALL-UNNAMED \
                --sun-misc-unsafe-memory-access=allow \
                -Dagent47.dist.home="${'$'}APP_HOME" \
                -Dagent47.launcher.path="${'$'}INVOKED" \
                -cp "$classpath" \
                co.agentmode.agent47.app.MainKt "${'$'}@"
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)
    }
}

val jvmDist by tasks.registering {
    description = "Assembles the standalone JVM distribution"
    group = "distribution"
    dependsOn(jvmDistRuntime, jvmDistLibs, jvmDistLauncher)
}

val jvmDistArchive by tasks.registering(Tar::class) {
    description = "Packages the standalone JVM distribution as a tar.gz"
    group = "distribution"
    dependsOn(jvmDist)
    compression = Compression.GZIP
    archiveFileName.set("agent47-$distTarget.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into("agent47") {
        from(distDirectory)
        eachFile {
            if (file.canExecute()) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
}
