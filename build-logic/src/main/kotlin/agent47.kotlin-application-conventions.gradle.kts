import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.dokka")
    id("org.jetbrains.kotlinx.kover")
    application
}

kotlin {
    jvmToolchain(24)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_24)
        freeCompilerArgs.add("-Xjsr305=strict")
        allWarningsAsErrors.set(providers.environmentVariable("CI").isPresent)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
