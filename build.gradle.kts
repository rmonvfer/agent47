plugins {
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    kotlin("plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.10.0-rc01" apply false
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.4"
}

group = "co.agentmode"
version = "0.3.1"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        // Patched Mosaic modules (see third_party/mosaic/README.md). Declared first and scoped to
        // exactly the two coordinates we vendor, so every other dependency still resolves normally
        // from the repositories below.
        exclusiveContent {
            forRepository {
                maven(rootProject.layout.projectDirectory.dir("third_party/mosaic/repo"))
            }
            filter {
                includeModule("com.jakewharton.mosaic", "mosaic-runtime-jvm")
                includeModule("com.jakewharton.mosaic", "mosaic-tty-terminal-jvm")
            }
        }
        mavenCentral()
        google()
    }
}

dependencies {
    kover(project(":agent47-ai-types"))
    kover(project(":agent47-ai-core"))
    kover(project(":agent47-ai-providers"))
    kover(project(":agent47-agent-core"))
    kover(project(":agent47-coding-core"))
    kover(project(":agent47-ext-core"))
    kover(project(":agent47-ui-core"))
    kover(project(":agent47-tui"))
    kover(project(":agent47-app"))
}

kover {
    reports {
        verify {
            rule("aggregate line coverage") {
                minBound(40)
            }
        }
    }
}

tasks.named("check") {
    dependsOn("koverVerify")
}
