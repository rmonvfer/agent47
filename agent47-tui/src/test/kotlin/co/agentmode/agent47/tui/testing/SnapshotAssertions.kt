package co.agentmode.agent47.tui.testing

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.fail

object SnapshotAssertions {
    private val updateSnapshots: Boolean = System.getenv("UPDATE_SNAPSHOTS") == "1"

    fun assertSnapshot(snapshotName: String, actual: String) {
        val normalizedActual = normalize(actual)
        val path = snapshotPath(snapshotName)

        if (updateSnapshots || !Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.writeString(path, normalizedActual)
            if (!updateSnapshots) {
                fail("Snapshot did not exist and was created at ${path.toAbsolutePath()}. Re-run tests.")
            }
            return
        }

        val expected = normalize(Files.readString(path))
        assertEquals(expected, normalizedActual, "Snapshot mismatch: $snapshotName")
    }

    private fun snapshotPath(snapshotName: String): Path {
        val root = Path.of(System.getProperty("user.dir"))
        val moduleRoot = if (Files.exists(root.resolve("agent47-tui"))) {
            root.resolve("agent47-tui")
        } else {
            root
        }
        return moduleRoot.resolve("src/test/resources/snapshots").resolve("$snapshotName.snap")
    }

    private fun normalize(value: String): String {
        return value.replace("\r\n", "\n").trimEnd()
    }
}
