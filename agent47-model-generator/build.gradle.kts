plugins {
    id("agent47.kotlin-application-conventions")
}

dependencies {
    implementation(project(":agent47-ai-types"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass.set("co.agentmode.agent47.model.generator.GenerateModelsKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
