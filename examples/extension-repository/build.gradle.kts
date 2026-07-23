import org.gradle.api.tasks.Exec

val agent47Executable = providers.gradleProperty("agent47Executable").orElse("agent47")
val extensionDirectory = layout.projectDirectory.dir("extensions")

tasks.register<Exec>("checkExtensions") {
    group = "verification"
    description = "Compile every Kotlin extension with the installed agent47 runtime"
    environment("AGENT47_DIR", layout.buildDirectory.dir("agent47-home").get().asFile.absolutePath)
    environment("AGENT47_NO_AUTO_UPDATE", "1")
    commandLine(
        agent47Executable.get(),
        "--no-extensions",
        "--extension",
        extensionDirectory.asFile.absolutePath,
        "--list-extensions",
    )
}

tasks.register("check") {
    group = "verification"
    description = "Validate the extension repository"
    dependsOn("checkExtensions")
}
