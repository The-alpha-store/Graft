package dev.graft.app.git

import dev.graft.app.data.github.GitHubApi
import dev.graft.app.data.github.GitHubResult
import dev.graft.app.data.github.WorkflowRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Runs a command in the cloud - on a GitHub-hosted runner - and brings the output back.
 *
 * Why this exists: a phone can't run `cargo`, `npm`, `gradle`, `python`... (see [ShellRunner]), but a GitHub
 * runner has all of them. So the console treats GitHub Actions as a remote machine:
 *
 * 1. `workflow_dispatch` the repo's `graft-run.yml` (installed once with `cloud setup`) with the command,
 * 2. find the run that just started, poll it until it finishes,
 * 3. download the job log, strip the CI chrome, and show only what the command printed.
 *
 * Caveat the console always states up front: the runner checks out what is *pushed*, so unpushed commits and
 * uncommitted edits aren't in it. With `commit = true`, files the command changed (a formatter, say) are
 * committed and pushed back, and the console pulls them in.
 */
object CloudRunner {
    const val WORKFLOW_FILE = "graft-run.yml"
    const val WORKFLOW_PATH = ".github/workflows/$WORKFLOW_FILE"
    const val WORKFLOW_NAME = "Graft remote run"

    private const val FIND_RUN_ATTEMPTS = 20
    private const val FIND_RUN_DELAY_MS = 3_000L
    private const val POLL_MS = 4_000L
    private const val MAX_RUN_MS = 15 * 60 * 1000L

    data class Result(val ok: Boolean, val text: String, val runId: Long = 0, val url: String? = null)

    /** Turns GitHub's terse dispatch failures into the one thing the person should do about them. */
    fun explainDispatchError(message: String): String {
        val m = message.lowercase()
        return when {
            "404" in m || "not found" in m ->
                "GitHub can't find the Graft runner workflow on this repo. Run `cloud setup`, then commit and push it " +
                    "(to the default branch), and try again."
            "401" in m || "403" in m || "bad credentials" in m || "not accessible" in m || "must have" in m ->
                "GitHub refused the request. The credential for this repo needs permission to run workflows: a classic " +
                    "token with the `repo` and `workflow` scopes, or a fine-grained token with Actions: read & write."
            "422" in m || "workflow_dispatch" in m ->
                "The runner workflow on GitHub is out of date or has no workflow_dispatch trigger. Run `cloud setup` " +
                    "again, then commit and push."
            else -> message
        }
    }

    /**
     * Reduces a raw Actions job log to what the command itself printed.
     *
     * A raw log is mostly CI scaffolding: timestamps on every line, "Set up job", the whole checkout, `env:`
     * blocks. User steps' output is the text that sits *outside* `##[group]` sections after the first
     * `Run <script>` group, so that's what we keep (plus `##[error]` lines, shown as `error: …`).
     */
    fun cleanActionsLog(raw: String, maxLines: Int = 160): String {
        val timestamp = Regex("""^\d{4}-\d{2}-\d{2}T[\d:.]+Z ?""")
        var inGroup = false
        var started = false
        val out = ArrayList<String>()
        for (original in raw.lines()) {
            val line = timestamp.replaceFirst(original.trimEnd('\r'), "")
            when {
                line.startsWith("##[group]") -> {
                    inGroup = true
                    val title = line.removePrefix("##[group]")
                    // `run:` steps are titled "Run <script>"; `uses:` steps "Run owner/action@v" - skip those.
                    if (title.startsWith("Run ") && !title.startsWith("Run actions/") && !Regex("""^Run [\w.-]+/[\w.-]+@""").containsMatchIn(title)) {
                        started = true
                    }
                }
                line.startsWith("##[endgroup]") -> inGroup = false
                line.startsWith("##[error]") -> out.add("error: " + line.removePrefix("##[error]"))
                line.startsWith("##[") -> Unit                      // warnings / debug / notices
                started && !inGroup -> out.add(line)
            }
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        if (out.isEmpty()) return "(no output)"
        if (out.size <= maxLines) return out.joinToString("\n")
        return "… ${out.size - maxLines} earlier lines hidden\n" + out.takeLast(maxLines).joinToString("\n")
    }

    private fun millis(iso: String): Long = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

    /**
     * Dispatches, waits and returns the result. [onStatus] is called with short progress text so the console
     * can show a live line. Cancelling the calling coroutine also cancels the run on GitHub.
     */
    suspend fun run(
        fullName: String,
        token: String,
        ref: String,
        command: String,
        commit: Boolean,
        onStatus: (String) -> Unit,
    ): Result {
        val startedMs = System.currentTimeMillis()
        onStatus("Sending to GitHub Actions…")
        val dispatched = GitHubApi.dispatchWorkflow(
            fullName, token, WORKFLOW_FILE, ref, mapOf("command" to command, "commit" to commit.toString()),
        )
        if (dispatched is GitHubResult.Error) return Result(false, explainDispatchError(dispatched.message))

        // The run isn't created instantly; look for ours (right workflow, event, branch, created just now).
        var found: WorkflowRun? = null
        var attempts = 0
        while (found == null && attempts < FIND_RUN_ATTEMPTS) {
            delay(FIND_RUN_DELAY_MS)
            attempts++
            onStatus("Waiting for GitHub to start the run… (${attempts * 3}s)")
            val list = GitHubApi.listWorkflowRuns(fullName, token, perPage = 10)
            if (list is GitHubResult.Success) {
                found = list.data.firstOrNull {
                    it.name == WORKFLOW_NAME && it.event == "workflow_dispatch" && it.headBranch == ref &&
                        millis(it.createdAt) >= startedMs - 30_000
                }
            }
        }
        val first = found ?: return Result(
            false, "The run didn't appear within a minute. It may still start - check this repo's Actions tab.",
        )

        var current = first
        try {
            while (current.isActive) {
                delay(POLL_MS)
                onStatus("Running on GitHub… ${(System.currentTimeMillis() - startedMs) / 1000}s")
                if (System.currentTimeMillis() - startedMs > MAX_RUN_MS) {
                    return Result(false, "Still running after 15 minutes. It keeps going on GitHub: ${first.htmlUrl}", first.id, first.htmlUrl)
                }
                val list = GitHubApi.listWorkflowRuns(fullName, token, perPage = 10)
                if (list is GitHubResult.Success) list.data.firstOrNull { it.id == first.id }?.let { current = it }
            }
        } catch (e: CancellationException) {
            // Stop was pressed: don't leave a billable run going on GitHub.
            withContext(NonCancellable) { GitHubApi.cancelWorkflowRun(fullName, first.id, token) }
            throw e
        }

        onStatus("Fetching the output…")
        val job = (GitHubApi.listJobsForRun(fullName, current.id, token) as? GitHubResult.Success)?.data?.firstOrNull()
        val log = if (job == null) "(no job output)" else when (val l = GitHubApi.getJobLogs(fullName, job.id, token)) {
            is GitHubResult.Success -> cleanActionsLog(l.data)
            is GitHubResult.Error -> "(couldn't download the log: ${l.message})"
        }
        return Result(current.conclusion == "success", log, current.id, current.htmlUrl)
    }
}
