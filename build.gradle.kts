plugins {
    id("org.graalvm.buildtools.native") version "0.11.1" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    kotlin("plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.10.0-rc01" apply false
    id("org.jetbrains.dokka") version "2.1.0"
    id("org.jetbrains.kotlinx.kover") version "0.9.4"
}

group = "co.agentmode"
version = "0.1.0-SNAPSHOT"

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
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
    kover(project(":agent47-gui"))
    kover(project(":agent47-app"))
}
