package co.agentmode.agent47.coding.core.config

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Central directory layout for agent47 configuration files.
 * Mirrors the structure: ~/.agent47/ for global config, <project>/.agent47/ for project config.
 */
public class AgentConfig(
    private val agentDir: Path = resolveAgentDir(),
    private val cwd: Path = Path(System.getProperty("user.dir")),
) {
    public val globalDir: Path
        get() = agentDir
    public val projectDir: Path
        get() = cwd.resolve(".agent47")

    public val authPath: Path
        get() = agentDir.resolve("auth.json")
    public val modelsPath: Path
        get() = agentDir.resolve("models.yml")
    public val modelsJsonLegacyPath: Path
        get() = agentDir.resolve("models.json")
    public val globalSettingsPath: Path
        get() = agentDir.resolve("settings.json")
    public val projectSettingsPath: Path
        get() = projectDir.resolve("settings.json")
    public val sessionsDir: Path
        get() = agentDir.resolve("sessions")

    public val projectAgentsDir: Path
        get() = projectDir.resolve("agents")
    public val globalAgentsDir: Path
        get() = agentDir.resolve("agents")

    public val projectSkillsDir: Path
        get() = projectDir.resolve("skills")
    public val globalSkillsDir: Path
        get() = agentDir.resolve("skills")

    public val projectCommandsDir: Path
        get() = projectDir.resolve("commands")
    public val globalCommandsDir: Path
        get() = agentDir.resolve("commands")

    public companion object {
        private fun resolveAgentDir(): Path {
            val envDir = System.getenv("AGENT47_DIR")
            if (!envDir.isNullOrBlank()) {
                if (envDir == "~") return Path(System.getProperty("user.home"))
                if (envDir.startsWith("~/")) {
                    return Path(System.getProperty("user.home") + envDir.substring(1))
                }
                return Path(envDir)
            }
            return Path(System.getProperty("user.home"), ".agent47")
        }
    }
}
