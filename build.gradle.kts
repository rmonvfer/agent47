plugins {
    id("org.graalvm.buildtools.native") version "0.11.1" apply false
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.serialization") version "2.3.0" apply false
    kotlin("plugin.compose") version "2.3.0" apply false
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
