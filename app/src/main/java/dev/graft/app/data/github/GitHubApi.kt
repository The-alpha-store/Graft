package dev.graft.app.data.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One row in the Discover list — enough to display and clone, nothing more. */
data class GitHubRepoSummary(
    val fullName: String,
    val description: String?,
    val cloneUrl: String,
    val stars: Int,
    val defaultBranch: String,
    val private: Boolean,
)

/** One Actions run — a single execution of a workflow, triggered by a push, a PR, a manual
 *  dispatch, etc. [status] is the lifecycle state ("queued"/"in_progress"/"completed"/
 *  "waiting"); [conclusion] is only meaningful once status is "completed"
 *  ("success"/"failure"/"cancelled"/"skipped"/"timed_out"/"action_required"/null). */
data class WorkflowRun(
    val id: Long,
    val name: String,
    val displayTitle: String,
    val status: String,
    val conclusion: String?,
    val headBranch: String,
    val headSha: String,
    val event: String,
    val htmlUrl: String,
    val createdAt: String,
    val updatedAt: String,
) {
    val isActive: Boolean get() = status != "completed"
}

data class WorkflowStep(
    val name: String,
    val status: String,
    val conclusion: String?,
    val number: Int,
)

data class WorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val steps: List<WorkflowStep> = emptyList(),
)

/** One file uploaded by a workflow run via `actions/upload-artifact` (what ci.yml/release.yml
 *  produce). [expired] mirrors GitHub's own retention flag — once true the archive is gone
 *  server-side and [GitHubApi.downloadArtifactZip] will fail, so callers should check this
 *  before offering an Install button rather than only finding out after a failed download. */
data class WorkflowArtifact(
    val id: Long,
    val name: String,
    val sizeInBytes: Long,
    val expired: Boolean,
)

sealed class GitHubResult<out T> {
    data class Success<T>(val data: T) : GitHubResult<T>()
    data class Error(val message: String) : GitHubResult<Nothing>()
}

/** Pulls "owner/repo" out of an https or ssh GitHub remote URL, or null if it isn't one.
 *  Shared by anything that needs to turn a repo's stored clone URL into a GitHub API path —
 *  deleting a repo, checking its Actions runs, etc — so there's exactly one place that
 *  knows this parsing, not a copy per feature. */
fun githubFullNameFromUrl(url: String): String? {
    val cleaned = url.trim().removeSuffix(".git").removeSuffix("/")
    val match = Regex("""github\.com[/:]([^/]+)/([^/]+)$""").find(cleaned) ?: return null
    val (owner, repo) = match.destructured
    return "$owner/$repo"
}

/**
 * Minimal GitHub REST client — just enough to search public repos, list/create/delete a
 * signed-in user's own repos, and drive Actions (list/inspect runs, view job logs, re-run,
 * cancel) from inside the app instead of tabbing out to a browser. Deliberately built on
 * HttpURLConnection + org.json (both already part of the Android platform) rather than
 * adding Retrofit/OkHttp/Gson for what's really a handful of REST calls.
 *
 * Search works with no token (GitHub's public search API is unauthenticated, just rate
 * limited — 10 requests/minute per IP). Everything else needs a token, since it's
 * inherently about a specific account/repo: pass any saved credential's PAT and it works
 * the same way it already does for git push/pull. Actions endpoints need at least the
 * "repo" scope (classic) or "Actions: read/write" (fine-grained) depending on the call.
 */
object GitHubApi {
    private const val BASE = "https://api.github.com"

    suspend fun searchRepos(query: String, token: String? = null): GitHubResult<List<GitHubRepoSummary>> {
        if (query.isBlank()) return GitHubResult.Success(emptyList())
        val url = "$BASE/search/repositories?q=${URLEncoder.encode(query, "UTF-8")}&per_page=30"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                val json = JSONObject(r.data)
                GitHubResult.Success(parseRepoArray(json.getJSONArray("items")))
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    suspend fun listMyRepos(token: String): GitHubResult<List<GitHubRepoSummary>> {
        val url = "$BASE/user/repos?per_page=50&sort=updated"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                GitHubResult.Success(parseRepoArray(JSONArray(r.data)))
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    /**
     * Creates a new repo under the token's own account. Needs a PAT with the "repo" scope
     * (classic) or "Administration: write" (fine-grained). GitHub's create-repo endpoint
     * always acts on the authenticated user's account — there's no separate "as org" call
     * here, matching what a person can do from github.com's own "New repository" button.
     */
    suspend fun createRepo(
        name: String, description: String = "", private: Boolean = true, token: String,
    ): GitHubResult<GitHubRepoSummary> {
        val body = JSONObject().apply {
            put("name", name)
            if (description.isNotBlank()) put("description", description)
            put("private", private)
            put("auto_init", true) // creates an initial commit so the repo is clonable right away
        }
        return when (val r = http("$BASE/user/repos", "POST", token, body.toString())) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                GitHubResult.Success(parseRepoObject(JSONObject(r.data)))
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    /**
     * Permanently deletes a repo from GitHub. Needs a PAT with the "delete_repo" scope
     * (classic) — GitHub rejects this call with a 403 for tokens that only have "repo",
     * same as it would from the API docs' own example, so the error message from [http]
     * surfaces that directly rather than this function guessing at scopes up front.
     */
    suspend fun deleteRepo(fullName: String, token: String): GitHubResult<Unit> {
        return when (val r = http("$BASE/repos/$fullName", "DELETE", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> GitHubResult.Success(Unit)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    /** Most recent runs for the repo overall, newest first — used for the Actions tab's run
     *  list. */
    suspend fun listWorkflowRuns(fullName: String, token: String, perPage: Int = 20): GitHubResult<List<WorkflowRun>> {
        val url = "$BASE/repos/$fullName/actions/runs?per_page=$perPage"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                val arr = JSONObject(r.data).getJSONArray("workflow_runs")
                GitHubResult.Success((0 until arr.length()).map { parseRun(arr.getJSONObject(it)) })
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    /** Runs triggered by one specific commit — used to show "did my last push's build pass?"
     *  right on the Changes screen without opening the full Actions list. */
    suspend fun listWorkflowRunsForCommit(fullName: String, sha: String, token: String): GitHubResult<List<WorkflowRun>> {
        val url = "$BASE/repos/$fullName/actions/runs?head_sha=$sha&per_page=10"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                val arr = JSONObject(r.data).getJSONArray("workflow_runs")
                GitHubResult.Success((0 until arr.length()).map { parseRun(arr.getJSONObject(it)) })
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    suspend fun listJobsForRun(fullName: String, runId: Long, token: String): GitHubResult<List<WorkflowJob>> {
        val url = "$BASE/repos/$fullName/actions/runs/$runId/jobs"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                val arr = JSONObject(r.data).getJSONArray("jobs")
                GitHubResult.Success((0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val stepsArr = o.optJSONArray("steps")
                    val steps = if (stepsArr == null) emptyList() else (0 until stepsArr.length()).map { si ->
                        val s = stepsArr.getJSONObject(si)
                        WorkflowStep(
                            name = s.optString("name", "Step"),
                            status = s.optString("status", "unknown"),
                            conclusion = if (s.isNull("conclusion")) null else s.optString("conclusion").ifBlank { null },
                            number = s.optInt("number", si + 1),
                        )
                    }
                    WorkflowJob(
                        id = o.getLong("id"),
                        name = o.optString("name", "Job"),
                        status = o.optString("status", "unknown"),
                        conclusion = if (o.isNull("conclusion")) null else o.optString("conclusion").ifBlank { null },
                        htmlUrl = o.optString("html_url", ""),
                        steps = steps,
                    )
                })
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    /** Re-runs every job in the run from scratch. */
    suspend fun rerunWorkflow(fullName: String, runId: Long, token: String): GitHubResult<Unit> =
        when (val r = http("$BASE/repos/$fullName/actions/runs/$runId/rerun", "POST", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> GitHubResult.Success(Unit)
        }

    /** Re-runs only the jobs that failed, leaving the ones that already passed alone —
     *  usually what you want after fixing a build error, since it's faster and doesn't
     *  re-burn CI minutes on steps that were already fine. */
    suspend fun rerunFailedJobs(fullName: String, runId: Long, token: String): GitHubResult<Unit> =
        when (val r = http("$BASE/repos/$fullName/actions/runs/$runId/rerun-failed-jobs", "POST", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> GitHubResult.Success(Unit)
        }

    suspend fun cancelWorkflowRun(fullName: String, runId: Long, token: String): GitHubResult<Unit> =
        when (val r = http("$BASE/repos/$fullName/actions/runs/$runId/cancel", "POST", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> GitHubResult.Success(Unit)
        }

    /** Files a run uploaded via `actions/upload-artifact` (e.g. `ci.yml`/`release.yml`
     *  publishing a built APK) — backs the Actions screen's "Build outputs" section. */
    suspend fun listRunArtifacts(fullName: String, runId: Long, token: String): GitHubResult<List<WorkflowArtifact>> {
        val url = "$BASE/repos/$fullName/actions/runs/$runId/artifacts"
        return when (val r = http(url, "GET", token)) {
            is GitHubResult.Error -> r
            is GitHubResult.Success -> try {
                val arr = JSONObject(r.data).getJSONArray("artifacts")
                GitHubResult.Success((0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WorkflowArtifact(
                        id = o.getLong("id"),
                        name = o.optString("name", "artifact"),
                        sizeInBytes = o.optLong("size_in_bytes", 0L),
                        expired = o.optBoolean("expired", false),
                    )
                })
            } catch (e: Exception) {
                GitHubResult.Error("Couldn't read GitHub's response: ${e.message}")
            }
        }
    }

    /**
     * Downloads one artifact's zip archive (GitHub always wraps uploaded artifacts in a zip,
     * even for a single file) as raw bytes. Same redirect-without-auth-header dance as
     * [getJobLogs] — the archive lives on blob storage behind a pre-signed URL, and forwarding
     * the GitHub bearer token to that host gets the request rejected. A 410 here means the
     * artifact already expired server-side (retention window passed) even if the list call
     * that found it still reported `expired = false` a moment earlier, so this is surfaced as
     * its own error rather than folded into the generic "network error" case.
     */
    suspend fun downloadArtifactZip(fullName: String, artifactId: Long, token: String): GitHubResult<ByteArray> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$BASE/repos/$fullName/actions/artifacts/$artifactId/zip").openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                var code = conn.responseCode

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) return@withContext GitHubResult.Error("GitHub didn't provide a download location")
                    conn = (URL(location).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 30_000
                    }
                    code = conn.responseCode
                }

                when {
                    code == 410 -> GitHubResult.Error("This artifact has expired and is no longer available on GitHub")
                    code in 200..299 -> GitHubResult.Success(conn.inputStream.use { it.readBytes() })
                    else -> GitHubResult.Error("GitHub returned HTTP $code downloading the artifact")
                }
            } catch (e: Exception) {
                GitHubResult.Error(e.message ?: "Network error downloading artifact")
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * Plain-text log for one job — this is the piece that actually replaces "go read the
     * error on GitHub". GitHub's job-logs endpoint responds with a redirect to a pre-signed
     * blob storage URL; that URL must be fetched *without* our GitHub Authorization header
     * (some storage backends reject a request carrying an auth header they don't recognize),
     * so the redirect is followed manually here instead of leaving it to
     * HttpURLConnection's default auto-follow, which would forward every header along.
     */
    suspend fun getJobLogs(fullName: String, jobId: Long, token: String): GitHubResult<String> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$BASE/repos/$fullName/actions/jobs/$jobId/logs").openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                var code = conn.responseCode

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location.isNullOrBlank()) return@withContext GitHubResult.Error("GitHub didn't provide a log location")
                    conn = (URL(location).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"; connectTimeout = 10_000; readTimeout = 15_000
                    }
                    code = conn.responseCode
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) GitHubResult.Error("GitHub returned HTTP $code fetching logs")
                else GitHubResult.Success(text)
            } catch (e: Exception) {
                GitHubResult.Error(e.message ?: "Network error fetching logs")
            } finally {
                conn?.disconnect()
            }
        }

    private fun parseRun(o: JSONObject): WorkflowRun = WorkflowRun(
        id = o.getLong("id"),
        name = o.optString("name", "Workflow"),
        displayTitle = o.optString("display_title", o.optString("name", "Workflow")),
        status = o.optString("status", "unknown"),
        conclusion = if (o.isNull("conclusion")) null else o.optString("conclusion").ifBlank { null },
        headBranch = o.optString("head_branch", ""),
        headSha = o.optString("head_sha", ""),
        event = o.optString("event", ""),
        htmlUrl = o.optString("html_url", ""),
        createdAt = o.optString("created_at", ""),
        updatedAt = o.optString("updated_at", ""),
    )

    private fun parseRepoArray(arr: JSONArray): List<GitHubRepoSummary> =
        (0 until arr.length()).map { i -> parseRepoObject(arr.getJSONObject(i)) }

    private fun parseRepoObject(o: JSONObject): GitHubRepoSummary = GitHubRepoSummary(
        fullName = o.getString("full_name"),
        description = if (o.isNull("description")) null else o.optString("description").ifBlank { null },
        cloneUrl = o.getString("clone_url"),
        stars = o.optInt("stargazers_count", 0),
        defaultBranch = o.optString("default_branch", "main"),
        private = o.optBoolean("private", false),
    )

    private suspend fun http(
        urlStr: String, method: String, token: String?, jsonBody: String? = null,
    ): GitHubResult<String> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
                    if (jsonBody != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                    }
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(body).optString("message", "GitHub returned HTTP $code")
                    } catch (e: Exception) {
                        "GitHub returned HTTP $code"
                    }
                    GitHubResult.Error(msg)
                } else {
                    GitHubResult.Success(body)
                }
            } catch (e: Exception) {
                GitHubResult.Error(e.message ?: "Network error reaching GitHub")
            } finally {
                conn?.disconnect()
            }
        }
}
