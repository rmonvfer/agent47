import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("agent47.kotlin-application-conventions")
    id("org.graalvm.buildtools.native")
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
    implementation(project(":agent47-gui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-coroutines:3.0.2")

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

tasks.withType<AbstractCopyTask> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("agent47")
            mainClass.set("co.agentmode.agent47.app.MainKt")
            javaLauncher.set(
                javaToolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(25))
                },
            )
            resources.autodetect()
            buildArgs.add("--no-fallback")
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}
