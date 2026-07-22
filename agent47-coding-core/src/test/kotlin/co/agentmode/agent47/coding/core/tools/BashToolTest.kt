package co.agentmode.agent47.coding.core.tools

import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse

class BashToolTest {

    @Test
    fun `cancelling execution terminates the shell and its children`() = runTest {
        val cwd = createTempDirectory("bash-cancellation-test")
        val pidFile = cwd.resolve("child.pid")
        val tool = BashTool(cwd)
        val execution = async {
            tool.execute(
                toolCallId = "bash-1",
                parameters = buildJsonObject {
                    put("command", "sleep 30 & child=\$!; echo \$child > child.pid; wait")
                },
            )
        }

        withTimeout(5_000) {
            while (!Files.exists(pidFile)) delay(10)
        }
        val childPid = pidFile.readText().trim().toLong()

        execution.cancel()
        execution.join()

        withTimeout(5_000) {
            while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false)) delay(10)
        }
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false))
    }
}
