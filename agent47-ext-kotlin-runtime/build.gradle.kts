plugins {
    id("agent47.kotlin-library-conventions")
}

dependencies {
    api(project(":agent47-ext-core"))
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.2.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.2.20")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.0")
}
