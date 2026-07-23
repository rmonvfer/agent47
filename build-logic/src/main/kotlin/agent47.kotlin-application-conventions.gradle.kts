import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    application
}

kotlin {
    // JDK 25: on JDK 22+ Mosaic uses its FFM terminal backend. The JNI backend (JDK <22)
    // segfaults in its native SIGWINCH callback on macOS/aarch64 when the terminal is resized.
    jvmToolchain(25)
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline/${project.name}.xml")
    basePath.set(rootProject.projectDir)
}

tasks.withType<Detekt>().configureEach {
    jvmTarget.set("21")
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget.set("21")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjsr305=strict")
        allWarningsAsErrors.set(providers.environmentVariable("CI").isPresent)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
