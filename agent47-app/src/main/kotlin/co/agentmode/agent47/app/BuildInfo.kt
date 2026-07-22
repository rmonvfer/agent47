package co.agentmode.agent47.app

import java.util.Properties

internal object BuildInfo {
    val version: String by lazy {
        val properties = Properties()
        checkNotNull(BuildInfo::class.java.classLoader.getResourceAsStream("agent47-version.properties")) {
            "agent47-version.properties is missing"
        }.use(properties::load)
        checkNotNull(properties.getProperty("version")) {
            "agent47 version is missing"
        }
    }
}
