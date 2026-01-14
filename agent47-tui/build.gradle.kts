plugins {
    id("agent47.kotlin-library-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    api(project(":agent47-ui-core"))
    implementation(project(":agent47-ai-types"))
    implementation(project(":agent47-agent-core"))
    implementation(project(":agent47-coding-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    implementation("com.jakewharton.mosaic:mosaic-runtime:0.18.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}
