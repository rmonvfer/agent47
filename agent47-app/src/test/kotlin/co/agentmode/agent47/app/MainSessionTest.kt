package co.agentmode.agent47.app

import co.agentmode.agent47.app.cli.findLatestProjectSession
import co.agentmode.agent47.coding.core.session.SessionManager
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class MainSessionTest {

    @Test
    fun `continue selects the latest session belonging to the current project`() {
        val root = createTempDirectory("agent47-session-selection")
        val sessions = root.resolve("sessions")
        val currentProject = root.resolve("current-project")
        val otherProject = root.resolve("other-project")
        currentProject.toFile().mkdirs()
        otherProject.toFile().mkdirs()

        val olderCurrent = sessions.resolve("session-100.jsonl")
        val latestCurrent = sessions.resolve("session-200.jsonl")
        val newestOther = sessions.resolve("session-300.jsonl")
        SessionManager(olderCurrent, projectCwd = currentProject)
        SessionManager(latestCurrent, projectCwd = currentProject)
        SessionManager(newestOther, projectCwd = otherProject)
        sessions.resolve("session-400.jsonl").writeText("not a session")

        assertEquals(latestCurrent, findLatestProjectSession(sessions, currentProject))
    }
}
