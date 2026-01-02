plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(24)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(24)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    implementation("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.3.0")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.3.0")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.1.0")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.4")
}
