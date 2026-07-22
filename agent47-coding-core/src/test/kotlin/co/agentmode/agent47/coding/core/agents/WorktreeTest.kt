package co.agentmode.agent47.coding.core.agents

import co.agentmode.agent47.ai.types.ApiId
import co.agentmode.agent47.ai.types.Model
import co.agentmode.agent47.ai.types.ModelCost
import co.agentmode.agent47.ai.types.ModelInputKind
import co.agentmode.agent47.ai.types.ProviderId
import co.agentmode.agent47.coding.core.auth.AuthStorage
import co.agentmode.agent47.coding.core.models.ModelRegistry
import co.agentmode.agent47.coding.core.settings.Settings
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeTest {

    private val model = Model(
        id = "test-model",
        name = "Test Model",
        api = ApiId("openai-completions"),
        provider = ProviderId("test-provider"),
        baseUrl = "https://api.example.com",
        reasoning = false,
        input = listOf(ModelInputKind.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

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
        assertNull(cleanup.error)
        assertNull(cleanup.path)
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

    @Test
    fun `requested worktree isolation fails instead of using caller directory`() = runTest {
        val notRepo = createTempDirectory("worktree-isolation-failure")
        val auth = AuthStorage(notRepo.resolve("auth.json"), envResolver = { null })
        val definition = AgentDefinition(
            name = "isolated-agent",
            description = "isolated test agent",
            systemPrompt = "test",
            tools = emptyList(),
            spawns = SpawnsPolicy.None,
            model = null,
            thinkingLevel = null,
            output = null,
            source = AgentSource.PROJECT,
            filePath = null,
            isolation = IsolationMode.WORKTREE,
        )

        val result = runSubAgent(
            SubAgentOptions(
                agentDefinition = definition,
                task = "must not execute",
                taskId = "isolation-test",
                description = null,
                context = null,
                cwd = notRepo,
                parentModel = model,
                modelRegistry = ModelRegistry(auth),
                settings = Settings(),
                currentDepth = 0,
                maxDepth = 1,
                agentRegistry = null,
                getApiKey = null,
                onProgress = null,
                onEvent = null,
            ),
        )

        assertEquals(1, result.exitCode)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("Worktree isolation was requested"))
        assertTrue(result.output.startsWith("Sub-agent failed:"))
    }

    @Test
    fun `preserves dirty worktree when pre-commit hook rejects commit`() {
        val repo = initRepo()
        val hook = repo.resolve(".git/hooks/pre-commit")
        hook.writeText("#!/bin/sh\necho 'hook rejected commit' >&2\nexit 1\n")
        assertTrue(hook.toFile().setExecutable(true), "pre-commit hook should be executable")
        val info = Worktree.createWorktree(repo, "hook-failure")
        assertNotNull(info)
        val changedFile = Path.of(info.workPath).resolve("recoverable.txt")
        changedFile.writeText("preserve me\n")

        val cleanup = Worktree.cleanupWorktree(repo, info, "must run hooks")

        assertTrue(cleanup.hasChanges)
        assertEquals(info.path, cleanup.path)
        assertNotNull(cleanup.error)
        assertTrue(cleanup.error!!.contains("hook rejected commit"))
        assertTrue(Path.of(info.path).exists(), "failed cleanup must preserve the worktree")
        assertEquals("preserve me\n", changedFile.readText())

        hook.writeText("#!/bin/sh\nexit 0\n")
        Worktree.removeWorktree(repo, info.path)
    }

    @Test
    fun `preserves worktree when status cannot be inspected`() {
        val repo = initRepo()
        val info = Worktree.createWorktree(repo, "status-failure")
        assertNotNull(info)
        val worktreeDir = Path.of(info.path)
        val gitFile = worktreeDir.resolve(".git")
        val savedGitFile = worktreeDir.resolve(".git.saved")
        gitFile.toFile().renameTo(savedGitFile.toFile())

        val cleanup = Worktree.cleanupWorktree(repo, info, "status fails")

        assertEquals(false, cleanup.hasChanges)
        assertEquals(info.path, cleanup.path)
        assertNotNull(cleanup.error)
        assertTrue(worktreeDir.exists(), "failed status must preserve the worktree")

        savedGitFile.toFile().renameTo(gitFile.toFile())
        Worktree.removeWorktree(repo, info.path)
    }
}
