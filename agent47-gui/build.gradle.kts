plugins {
    id("agent47.kotlin-library-conventions")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":agent47-ui-core"))
    implementation(project(":agent47-ai-types"))
    implementation(project(":agent47-agent-core"))
    implementation(project(":agent47-coding-core"))

    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }

    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:0.33.0-253.29795")
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window:0.33.0-253.29795")
    implementation("org.jetbrains.jewel:jewel-markdown-core:0.33.0-253.29795")
    implementation("org.jetbrains.jewel:jewel-markdown-int-ui-standalone-styling:0.33.0-253.29795")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}
