package co.agentmode.agent47.coding.core.agents

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeTest {

    private fun git(cwd: Path, vararg args: String): Int {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(cwd.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(30, TimeUnit.SECONDS)
        return process.exitValue()
    }

    private fun initRepo(): Path {
        val dir = createTempDirectory("worktree-test")
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "test@agent47.dev")
        git(dir, "config", "user.name", "Agent47 Test")
        git(dir, "config", "commit.gpgsign", "false")
        dir.resolve("README.md").writeText("initial\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "initial")
        return dir
    }

    @Test
    fun `creates a worktree and commits changes to a branch`() {
        val repo = initRepo()

        val info = Worktree.createWorktree(repo, "test1")
        assertNotNull(info, "worktree should be created in a git repo")
        assertEquals("agent47-agent-test1", info.branch)
        assertTrue(Path.of(info.workPath).toFile().isDirectory)

        // Make a change inside the worktree.
        Path.of(info.workPath).resolve("new-file.txt").writeText("work done\n")

        val cleanup = Worktree.cleanupWorktree(repo, info, "did some work")
        assertTrue(cleanup.hasChanges, "dirty worktree should report changes")
        assertNotNull(cleanup.branch)
        // The branch persists in the main repo with the committed change.
        assertEquals(0, git(repo, "rev-parse", "--verify", cleanup.branch!!))
    }

    @Test
    fun `clean worktree with no new commits reports no changes`() {
        val repo = initRepo()
        val info = Worktree.createWorktree(repo, "clean1")
        assertNotNull(info)

        val cleanup = Worktree.cleanupWorktree(repo, info, "did nothing")
        assertTrue(!cleanup.hasChanges)
    }

    @Test
    fun `returns null outside a git repo`() {
        val notRepo = createTempDirectory("worktree-not-repo")
        assertNull(Worktree.createWorktree(notRepo, "x"))
    }
}
