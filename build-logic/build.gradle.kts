plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.20")
    implementation("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:2.2.20")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.20")
    implementation("org.jetbrains.compose:compose-gradle-plugin:1.10.0-rc01")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:2.1.0")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:0.9.4")
}
