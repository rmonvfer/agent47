package co.agentmode.agent47.coding.core.agents

import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Describes an isolated git worktree created for a background agent. */
public data class WorktreeInfo(
    /** Absolute path to the worktree directory (the copied repo's root). */
    val path: String,
    /** Branch name created for this worktree when changes exist. */
    val branch: String,
    /** Commit SHA the worktree was created from. */
    val baseSha: String,
    /**
     * Where the agent should work inside the worktree: the equivalent of the cwd the worktree was
     * created from. Equals [path] when that cwd was the repo root; points at the copied subdirectory
     * when it was deeper (e.g. a monorepo package), so the requested scoping survives isolation.
     */
    val workPath: String,
)

/** Outcome of cleaning up a worktree after an agent finishes. */
public data class WorktreeCleanupResult(
    /** Whether changes were found in the worktree. */
    val hasChanges: Boolean,
    /** Branch name if changes were committed and kept. */
    val branch: String? = null,
    /** Worktree path when cleanup failed and the worktree was preserved for recovery. */
    val path: String? = null,
    /** Cleanup failure. A non-null value means the worktree was not removed. */
    val error: String? = null,
)

/**
 * Git worktree isolation for background agents.
 *
 * Creates a temporary git worktree so an agent works on an isolated copy of the repo. On completion,
 * if no changes were made the worktree is removed; if changes exist a branch is created in the main
 * repo and returned in the cleanup result. Every git invocation runs through [runGit] with an argv
 * list (no shell) and a per-command timeout.
 */
public object Worktree {

    private const val REV_PARSE_TIMEOUT_SECONDS: Long = 5
    private const val GIT_COMMAND_TIMEOUT_SECONDS: Long = 10
    private const val WORKTREE_ADD_TIMEOUT_SECONDS: Long = 30
    private const val PRUNE_TIMEOUT_SECONDS: Long = 5
    private const val READER_JOIN_MILLIS: Long = 1000
    private const val UUID_SUFFIX_LENGTH: Int = 8
    private const val COMMIT_MESSAGE_MAX_LENGTH: Int = 200

    /**
     * Creates a temporary detached git worktree at HEAD for the agent identified by [agentId].
     * Returns null on any failure (not a git repo, no commits yet, or a git command failing).
     */
    public fun createWorktree(cwd: Path, agentId: String): WorktreeInfo? {
        // Verify we're in a git repo with at least one commit (HEAD must exist).
        if (runGit(cwd, listOf("rev-parse", "--is-inside-work-tree"), REV_PARSE_TIMEOUT_SECONDS).exitCode != 0) {
            return null
        }

        val head = runGit(cwd, listOf("rev-parse", "HEAD"), REV_PARSE_TIMEOUT_SECONDS)
        if (head.exitCode != 0) return null
        val baseSha = head.stdout.trim()

        val topLevel = runGit(cwd, listOf("rev-parse", "--show-toplevel"), REV_PARSE_TIMEOUT_SECONDS)
        if (topLevel.exitCode != 0) return null

        // Where cwd sits inside the repo ("" at the root): the agent must work at the same
        // subdirectory inside the copy, or a monorepo-package cwd would silently widen to the whole
        // repo. Resolve symlinks on both sides — git emits resolved paths while cwd may arrive
        // through a symlink (macOS /tmp).
        val subdir = try {
            val topLevelReal = Path.of(topLevel.stdout.trim()).toRealPath()
            topLevelReal.relativize(cwd.toRealPath()).toString()
        } catch (_: IOException) {
            return null
        }

        val branch = "agent47-agent-$agentId"
        val suffix = UUID.randomUUID().toString().take(UUID_SUFFIX_LENGTH)
        val worktreePath = Path.of(System.getProperty("java.io.tmpdir"), "agent47-agent-$agentId-$suffix")

        // Create the detached worktree at HEAD.
        if (runGit(cwd, listOf("worktree", "add", "--detach", worktreePath.toString(), "HEAD"), WORKTREE_ADD_TIMEOUT_SECONDS).exitCode != 0) {
            return null
        }

        val workPath = if (subdir.isEmpty()) worktreePath.toString() else worktreePath.resolve(subdir).toString()
        return WorktreeInfo(path = worktreePath.toString(), branch = branch, baseSha = baseSha, workPath = workPath)
    }

    /**
     * Cleans up a worktree after agent completion. If the worktree is dirty, changes are staged and
     * committed. If it is clean and still at [WorktreeInfo.baseSha], the worktree is removed and no
     * changes are reported. Otherwise a branch is created at the worktree HEAD (with a timestamp
     * suffix if the branch name is taken) and the worktree is removed, leaving the branch in the main
     * repo. If inspection, staging, committing, or branch creation fails, the worktree is preserved
     * and its path and the error are returned for recovery. [agentDescription] is truncated to 200
     * chars for the commit message.
     */
    public fun cleanupWorktree(cwd: Path, worktree: WorktreeInfo, agentDescription: String): WorktreeCleanupResult {
        val worktreeDir = Path.of(worktree.path)
        if (!worktreeDir.exists()) {
            return WorktreeCleanupResult(
                hasChanges = false,
                error = "Worktree directory no longer exists: ${worktree.path}",
            )
        }

        val status = runGit(worktreeDir, listOf("status", "--porcelain"), GIT_COMMAND_TIMEOUT_SECONDS)
        if (status.exitCode != 0) {
            return preserveWorktree(worktree.path, "inspect worktree status", status, hasChanges = false)
        }

        if (status.stdout.trim().isNotEmpty()) {
            // Changes exist — stage and commit them. No shell sanitization is needed: runGit passes
            // an argv list, so the description can never break out into shell metacharacters.
            val add = runGit(worktreeDir, listOf("add", "-A"), GIT_COMMAND_TIMEOUT_SECONDS)
            if (add.exitCode != 0) {
                return preserveWorktree(worktree.path, "stage worktree changes", add, hasChanges = true)
            }
            val commitMessage = "agent47-agent: ${agentDescription.take(COMMIT_MESSAGE_MAX_LENGTH)}"
            val commit = runGit(worktreeDir, listOf("commit", "-m", commitMessage), GIT_COMMAND_TIMEOUT_SECONDS)
            if (commit.exitCode != 0) {
                return preserveWorktree(worktree.path, "commit worktree changes", commit, hasChanges = true)
            }
        } else {
            val current = runGit(worktreeDir, listOf("rev-parse", "HEAD"), REV_PARSE_TIMEOUT_SECONDS)
            if (current.exitCode == 0 && current.stdout.trim() == worktree.baseSha) {
                // No changes — remove the worktree.
                removeWorktree(cwd, worktree.path)
                return WorktreeCleanupResult(hasChanges = false)
            }
        }

        // Create a branch pointing at the worktree's HEAD. If the branch already exists, append a
        // timestamp suffix to avoid overwriting previous work.
        var branchName = worktree.branch
        val createBranch = runGit(worktreeDir, listOf("branch", branchName), GIT_COMMAND_TIMEOUT_SECONDS)
        if (createBranch.exitCode != 0) {
            branchName = "${worktree.branch}-${System.currentTimeMillis()}"
            val createFallbackBranch = runGit(worktreeDir, listOf("branch", branchName), GIT_COMMAND_TIMEOUT_SECONDS)
            if (createFallbackBranch.exitCode != 0) {
                return preserveWorktree(worktree.path, "create recovery branch", createFallbackBranch, hasChanges = true)
            }
        }

        // Remove the worktree; the branch persists in the main repo.
        removeWorktree(cwd, worktree.path)
        return WorktreeCleanupResult(hasChanges = true, branch = branchName)
    }

    /** Force-removes the worktree at [path]; if git refuses, falls back to pruning stale entries. */
    public fun removeWorktree(cwd: Path, path: String) {
        if (runGit(cwd, listOf("worktree", "remove", "--force", path), GIT_COMMAND_TIMEOUT_SECONDS).exitCode != 0) {
            runGit(cwd, listOf("worktree", "prune"), PRUNE_TIMEOUT_SECONDS)
        }
    }

    /** Prunes orphaned worktree entries (crash recovery). Best-effort; failures are swallowed. */
    public fun pruneWorktrees(cwd: Path) {
        runGit(cwd, listOf("worktree", "prune"), PRUNE_TIMEOUT_SECONDS)
    }

    /** Reports a cleanup failure without removing the worktree so its contents remain recoverable. */
    private fun preserveWorktree(
        path: String,
        operation: String,
        result: GitResult,
        hasChanges: Boolean,
    ): WorktreeCleanupResult {
        val detail = result.stderr.trim().ifEmpty { result.stdout.trim() }.ifEmpty { "git exited with ${result.exitCode}" }
        return WorktreeCleanupResult(
            hasChanges = hasChanges,
            path = path,
            error = "Could not $operation: $detail",
        )
    }

    /**
     * Runs `git` with [args] in [cwd] and returns its exit code and captured streams. The command is
     * never routed through a shell. If it does not finish within [timeoutSeconds] it is destroyed and
     * reported as a failure (exit code -1); failing to start git is likewise a failure rather than a
     * thrown exception, so callers only ever inspect [GitResult.exitCode].
     */
    private fun runGit(cwd: Path, args: List<String>, timeoutSeconds: Long): GitResult {
        val process = try {
            ProcessBuilder(listOf("git") + args)
                .directory(cwd.toFile())
                .start()
        } catch (e: IOException) {
            return GitResult(exitCode = -1, stdout = "", stderr = e.message ?: "failed to start git")
        }

        // Drain both pipes on daemon threads so a chatty command can never deadlock on a full buffer.
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutReader = Thread {
            process.inputStream.bufferedReader().use { reader -> stdout.append(reader.readText()) }
        }.apply { isDaemon = true }
        val stderrReader = Thread {
            process.errorStream.bufferedReader().use { reader -> stderr.append(reader.readText()) }
        }.apply { isDaemon = true }
        stdoutReader.start()
        stderrReader.start()

        val finished = try {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            return GitResult(exitCode = -1, stdout = "", stderr = "git interrupted")
        }

        if (!finished) {
            process.destroyForcibly()
            stdoutReader.join(READER_JOIN_MILLIS)
            stderrReader.join(READER_JOIN_MILLIS)
            return GitResult(exitCode = -1, stdout = stdout.toString(), stderr = "git timed out after ${timeoutSeconds}s")
        }

        stdoutReader.join(READER_JOIN_MILLIS)
        stderrReader.join(READER_JOIN_MILLIS)
        return GitResult(exitCode = process.exitValue(), stdout = stdout.toString(), stderr = stderr.toString())
    }

    private data class GitResult(val exitCode: Int, val stdout: String, val stderr: String)
}
