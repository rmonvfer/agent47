plugins {
    id("agent47.kotlin-library-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    api("org.jetbrains.compose.runtime:runtime:1.10.0-rc01")
    api(project(":agent47-ai-types"))
    api(project(":agent47-agent-core"))
    implementation(project(":agent47-coding-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
}
