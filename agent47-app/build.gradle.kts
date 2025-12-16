import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    id("agent47.kotlin-application-conventions")
    id("org.graalvm.buildtools.native")
}

dependencies {
    implementation(project(":agent47-ai-types"))
    implementation(project(":agent47-ai-core"))
    implementation(project(":agent47-ai-providers-openai"))
    implementation(project(":agent47-ai-providers-anthropic"))
    implementation(project(":agent47-ai-providers-google"))
    implementation(project(":agent47-agent-core"))
    implementation(project(":agent47-coding-core"))
    implementation(project(":agent47-ext-core"))
    implementation(project(":agent47-tui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.mordant:mordant:3.0.2")
    implementation("com.github.ajalt.mordant:mordant-coroutines:3.0.2")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}

application {
    mainClass.set("co.agentmode.agent47.app.MainKt")
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
