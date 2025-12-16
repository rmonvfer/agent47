plugins {
    id("agent47.kotlin-library-conventions")
}

dependencies {
    api(project(":agent47-ai-core"))
    api(project(":agent47-agent-core"))
    api(project(":agent47-coding-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}
