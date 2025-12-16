plugins {
    id("agent47.kotlin-library-conventions")
}

dependencies {
    api(project(":agent47-agent-core"))
    implementation(project(":agent47-ai-core"))
    implementation(project(":agent47-ai-types"))
    implementation("com.networknt:json-schema-validator:1.5.8")
    implementation("io.github.java-diff-utils:java-diff-utils:4.15")
    implementation("com.squareup.okio:okio:3.16.0")
    implementation("com.charleskorn.kaml:kaml:0.77.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}
