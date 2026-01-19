pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "agent47"

include(
    ":agent47-ai-types",
    ":agent47-ai-core",
    ":agent47-ai-providers",
    ":agent47-agent-core",
    ":agent47-coding-core",
    ":agent47-ext-core",
    ":agent47-ui-core",
    ":agent47-tui",
    ":agent47-gui",
    ":agent47-app",
    ":agent47-test-fixtures",
    ":agent47-model-generator",
)
