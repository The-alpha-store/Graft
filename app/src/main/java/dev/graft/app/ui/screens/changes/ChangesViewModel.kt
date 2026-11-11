package dev.graft.app.ui.screens.changes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.CommitPrefs
import dev.graft.app.data.RepoStatusStore
import java.io.File
import org.eclipse.jgit.lib.ProgressMonitor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import dev.graft.app.transfer.awaitResult
import dev.graft.app.transfer.TransferKind
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.github.GitHubApi
import dev.graft.app.data.github.GitHubResult
import dev.graft.app.data.github.WorkflowRun
import dev.graft.app.data.github.githubFullNameFromUrl
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitFileEntry
import dev.graft.app.git.GitFileStatus
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git

/**
 * Heuristic commit message from a set of changed files — no network call, no AI, just the
 * same information already visible in the Changes list, reworded into prose. For one file,
 * names it directly ("Update FooScreen.kt"); for several, groups by status ("Update 3 files,
 * add 1") and appends the files' common parent directory if they share one, since "in
 * ui/screens/changes" is often more useful at a glance than the individual file list would be
 * in a single-line commit title.
 */
fun buildCommitMessageSummary(entries: List<GitFileEntry>): String {
    if (entries.isEmpty()) return ""

    fun verb(status: GitFileStatus, plural: Boolean) = when (status) {
        GitFileStatus.ADDED -> if (plural) "Add" else "Add"
        GitFileStatus.MODIFIED -> "Update"
        GitFileStatus.DELETED -> "Remove"
        GitFileStatus.RENAMED -> "Rename"
        GitFileStatus.TYPE_CHANGED -> "Change"
        GitFileStatus.CONFLICTED -> "Resolve conflicts in"
    }

    if (entries.size == 1) {
        val e = entries.first()
        val name = e.path.substringAfterLast('/')
        return "${verb(e.status, plural = false)} $name"
    }

    // Common parent directory across every changed file, if there is one — comparing path
    // segments position-by-position rather than string prefixes, so "src/foo.kt" and
    // "src2/bar.kt" correctly share nothing instead of falsely matching on "src".
    val dirSegments = entries.map { it.path.substringBeforeLast('/', missingDelimiterValue = "").split('/').filter { s -> s.isNotEmpty() } }
    val minDepth = dirSegments.minOf { it.size }
    var commonDepth = 0
    while (commonDepth < minDepth && dirSegments.all { it[commonDepth] == dirSegments.first()[commonDepth] }) commonDepth++
    val commonDir = dirSegments.first().take(commonDepth).joinToString("/")

    val counts = entries.groupingBy { it.status }.eachCount()
    // A stable, most-significant-first order so the summary reads naturally regardless of
    // which statuses happen to be present.
    val order = listOf(
        GitFileStatus.MODIFIED, GitFileStatus.ADDED, GitFileStatus.DELETED,
        GitFileStatus.RENAMED, GitFileStatus.TYPE_CHANGED, GitFileStatus.CONFLICTED,
    )
    val parts = order.mapNotNull { status ->
        val n = counts[status] ?: return@mapNotNull null
        val word = verb(status, plural = n > 1).lowercase()
        "$word $n file${if (n == 1) "" else "s"}"
    }

    var summary = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
    if (commonDir.isNotBlank()) summary += " in $commonDir"
    return summary
}

data class ChangesUiState(
    val repo: RepoEntity? = null,
    val currentBranch: String = "",
    val staged: List<GitFileEntry> = emptyList(),
    val unstaged: List<GitFileEntry> = emptyList(),
    val commitMessage: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val statusLine: String = "",
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val message: String? = null,
    val hasConflicts: Boolean = false,
    val recentMessages: List<String> = emptyList(),
    val commitTemplate: CommitPrefs.Template = CommitPrefs.Template(),
    /** True once we know for sure the current branch has no remote-tracking branch set up —
     *  null means "not checked yet", not "has upstream", so the nudge doesn't flash on then
     *  off while the repo is still loading. */
    val hasUpstream: Boolean? = null,
    /** Latest Actions run for this repo, if it has a GitHub remote and a token attached —
     *  null just means "not known yet / not applicable", not "no runs". */
    val ciRun: WorkflowRun? = null,
    val ciApplicable: Boolean = false,
    /** Bumped once per successful push — the Composable watches this to fire the
     *  "Pushed — checking CI…" snackbar (spec's "auto-prompt after push" trigger point),
     *  separate from [message] since it needs its own action button (View → Actions screen)
     *  rather than being just a plain text toast. */
    val pushSuccessTick: Int = 0,
)

class ChangesViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(ChangesUiState())
    val uiState: StateFlow<ChangesUiState> = _state.asStateFlow()

    private var git: Git? = null
    private var repoId: Long = -1

    fun load(id: Long) {
        repoId = id
        viewModelScope.launch {
            val repo = repoRepo.getById(id) ?: return@launch
            _state.value = _state.value.copy(
                repo = repo, isLoading = true,
                recentMessages = CommitPrefs.recentMessages(appRef, id),
                commitTemplate = CommitPrefs.template(appRef, id),
            )
            openAndRefresh(repo)
            refreshCiStatus(repo)
        }
    }

    fun refresh() { viewModelScope.launch { _state.value.repo?.let { openAndRefresh(it) } } }

    /**
     * Reuses the open repository handle (re-opening on every refresh re-reads config and re-scans
     * packs each time, and the old handles were never closed), then gathers everything the screen
     * needs *in parallel* - branch, ahead/behind, upstream, last commit and working-tree status are
     * independent reads, so waiting on them one after another just added up their latencies.
     */
    private suspend fun openAndRefresh(repo: RepoEntity) {
        val g = ensureOpen(repo) ?: return

        val snapshot = coroutineScope {
            val branch = async { GitEngine.getCurrentBranch(g).getOrNull() ?: "" }
            val ab = async { GitEngine.getAheadBehind(g).getOrNull() ?: Pair(0, 0) }
            val upstream = async { GitEngine.hasUpstream(g).getOrNull() }
            val line = async { buildStatusLine(g) }
            val status = async { GitEngine.getStatus(g) }
            Snapshot(branch.await(), ab.await(), upstream.await(), line.await(), status.await())
        }

        when (val sr = snapshot.status) {
            is GitResult.Error -> err("Status failed: ${sr.message}")
            is GitResult.Success -> {
                RepoStatusStore.setChangeCount(repo.id, sr.data.map { it.path }.distinct().size)
                val (stg, unstg) = sr.data.partition { it.staged }
                _state.value = _state.value.copy(
                    staged = stg, unstaged = unstg, isLoading = false,
                    currentBranch = snapshot.branch, ahead = snapshot.aheadBehind.first,
                    behind = snapshot.aheadBehind.second,
                    statusLine = snapshot.statusLine, hasUpstream = snapshot.upstream,
                    hasConflicts = unstg.any { it.status == dev.graft.app.git.GitFileStatus.CONFLICTED },
                )
            }
        }
    }

    /**
     * Cheaper refresh for operations that can only change the working tree / index (stage, unstage,
     * discard): the branch, upstream, ahead/behind and last commit can't have moved, so only the
     * status needs recomputing.
     */
    private suspend fun refreshStatusOnly(repo: RepoEntity) {
        val g = ensureOpen(repo) ?: return
        when (val sr = GitEngine.getStatus(g)) {
            is GitResult.Error -> err("Status failed: ${sr.message}")
            is GitResult.Success -> {
                RepoStatusStore.setChangeCount(repo.id, sr.data.map { it.path }.distinct().size)
                val (stg, unstg) = sr.data.partition { it.staged }
                _state.value = _state.value.copy(
                    staged = stg, unstaged = unstg, isLoading = false,
                    hasConflicts = unstg.any { it.status == dev.graft.app.git.GitFileStatus.CONFLICTED },
                )
            }
        }
    }

    private suspend fun ensureOpen(repo: RepoEntity): Git? {
        git?.let { return it }
        return when (val result = GitEngine.openRepo(repo.fullSavePath)) {
            is GitResult.Error -> { err("Open failed: ${result.message}"); null }
            is GitResult.Success -> result.data.also { git = it }
        }
    }

    private class Snapshot(
        val branch: String,
        val aheadBehind: Pair<Int, Int>,
        val upstream: Boolean?,
        val statusLine: String,
        val status: GitResult<List<GitFileEntry>>,
    )

    override fun onCleared() {
        runCatching { git?.close() }
        git = null
        super.onCleared()
    }

    private suspend fun buildStatusLine(g: Git): String {
        val head = GitEngine.getHeadCommit(g).getOrNull()
        return if (head != null) "${head.shortSha} ${head.message}" else "No commits yet"
    }

    fun onCommitMessageChanged(v: String) { _state.value = _state.value.copy(commitMessage = v) }

    fun stageFile(e: GitFileEntry) = gitOp(light = true) { g -> GitEngine.stageFile(g, e.path) }
    fun unstageFile(e: GitFileEntry) = gitOp(light = true) { g -> GitEngine.unstageFile(g, e.path) }
    fun stageAll() = gitOp(light = true) { g -> GitEngine.stageAll(g) }
    fun unstageAll() = gitOp(light = true) { g ->
        GitEngine.unstageFiles(g, _state.value.staged.map { it.path })
    }
    fun discardFile(e: GitFileEntry) = gitOp(light = true) { g -> GitEngine.discardFile(g, e.path) }
    fun discardAll() = gitOp(light = true) { g -> GitEngine.discardAllChanges(g) }

    fun stageAllAndCommit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) { err("Write a commit message first"); return }
        gitOp { g ->
            val r1 = GitEngine.stageAll(g)
            if (r1 is GitResult.Error) return@gitOp r1
            GitEngine.commit(g, msg, authorName(), authorEmail())
        }
    }

    fun commit() {
        val msg = _state.value.commitMessage
        if (msg.isBlank()) { err("Write a commit message first"); return }
        if (_state.value.staged.isEmpty()) { err("Stage at least one file first"); return }
        gitOp { g ->
            val r = GitEngine.commit(g, msg, authorName(), authorEmail())
            if (r is GitResult.Success) {
                _state.value = _state.value.copy(commitMessage = "")
                CommitPrefs.recordMessage(appRef, repoId, msg)
                _state.value = _state.value.copy(recentMessages = CommitPrefs.recentMessages(appRef, repoId))
            }
            r
        }
    }

    /**
     * Fills the commit message from whatever's actually changed — a heuristic summary, not
     * an AI-written one, so it's instant and needs no network or credential. Prefers
     * summarizing [staged] (what would actually be committed right now); falls back to
     * [unstaged] only so there's still something to preview before Stage+Commit+Push, which
     * stages everything first anyway.
     */
    fun generateCommitMessage() {
        val entries = _state.value.staged.ifEmpty { _state.value.unstaged }
        if (entries.isEmpty()) { err("No changes to summarize"); return }
        val body = buildCommitMessageSummary(entries)
        _state.value = _state.value.copy(commitMessage = _state.value.commitTemplate.apply(body))
    }

    /** Picks a message straight from history — no template re-application, since a message
     *  pulled from history was already whatever it was when it got committed the first time. */
    fun applyRecentMessage(message: String) { _state.value = _state.value.copy(commitMessage = message) }

    fun setCommitTemplate(prefix: String, footer: String) {
        val repo = _state.value.repo ?: return
        CommitPrefs.setTemplate(appRef, repo.id, prefix, footer)
        _state.value = _state.value.copy(commitTemplate = CommitPrefs.Template(prefix, footer))
    }

    /**
     * One tap instead of three separate screens of taps: stages everything, commits with
     * whatever's in the message box, then pushes — the common "just ship it" path when
     * there's nothing to review file-by-file. Stops and reports at whichever step actually
     * fails rather than assuming the whole chain succeeded; [openAndRefresh] still runs
     * afterward either way so the file list reflects reality even on a partial failure
     * (e.g. staged + committed locally, but the push itself failed).
     */
    fun stageCommitAndPush() {
        val msg = _state.value.commitMessage
        val repo = _state.value.repo
        val g = git
        if (repo == null || g == null) { err("Repo not open"); return }
        if (_state.value.staged.isEmpty() && _state.value.unstaged.isEmpty()) { err("Nothing to commit"); return }
        if (msg.isBlank()) { err("Write a commit message first — or tap Generate"); return }

        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            var failure: String? = null

            val stageResult = GitEngine.stageAll(g)
            if (stageResult is GitResult.Error) failure = stageResult.message

            if (failure == null) {
                val commitResult = GitEngine.commit(g, msg, authorName(), authorEmail())
                if (commitResult is GitResult.Error) failure = commitResult.message
                else {
                    _state.value = _state.value.copy(commitMessage = "")
                    CommitPrefs.recordMessage(appRef, repoId, msg)
                    _state.value = _state.value.copy(recentMessages = CommitPrefs.recentMessages(appRef, repoId))
                }
            }

            if (failure == null) {
                val cred = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
                val manager = appRef.transferManager
                val pushOutcome = manager.enqueue(
                    kind = TransferKind.PUSH, repoId = repo.id, title = repo.name, watchDir = File(repo.fullSavePath),
                ) { reporter ->
                    manager.withGit(repo.fullSavePath) { pg -> GitEngine.push(pg, credential = cred, monitor = reporter.monitor) }
                }.awaitResult()
                when (val pushResult = pushOutcome) {
                    is GitResult.Error -> {
                        repoRepo.markError(repo.id, pushResult.message)
                        failure = pushResult.message
                    }
                    is GitResult.Success -> {
                        repoRepo.markSyncSuccess(repo.id)
                        _state.value = _state.value.copy(pushSuccessTick = _state.value.pushSuccessTick + 1)
                    }
                }
            }

            openAndRefresh(repo)
            refreshCiStatus(repo)
            _state.value = _state.value.copy(isWorking = false, message = failure ?: _state.value.message)
        }
    }

    fun amendCommit(newMessage: String) {
        if (newMessage.isBlank()) { err("Message required"); return }
        gitOp { g -> GitEngine.commit(g, newMessage, authorName(), authorEmail(), amend = true) }
    }

    fun squash(n: Int, message: String) {
        if (message.isBlank()) { err("Write a commit message first"); return }
        gitOp { g -> GitEngine.squashLastN(g, n, message, authorName(), authorEmail()) }
    }

    fun cherryPick(sha: String) {
        if (sha.isBlank()) { err("Enter a commit SHA"); return }
        gitOp { g -> GitEngine.cherryPick(g, sha) }
    }

    fun fetch() = remoteOp(TransferKind.FETCH) { g, cred, m -> GitEngine.fetch(g, credential = cred, monitor = m) }
    fun pullMerge() = remoteOp(TransferKind.PULL) { g, cred, m -> GitEngine.pullMerge(g, cred, m) }
    fun pullRebase() = remoteOp(TransferKind.PULL) { g, cred, m -> GitEngine.pullRebase(g, cred, m) }
    fun pullForce() = remoteOp(TransferKind.PULL) { g, cred, m -> GitEngine.pullForce(g, cred, m) }
    fun push() = remoteOp(TransferKind.PUSH, isPush = true) { g, cred, m -> GitEngine.push(g, credential = cred, monitor = m) }
    fun pushForce() = remoteOp(TransferKind.PUSH, isPush = true) { g, cred, m -> GitEngine.push(g, force = true, credential = cred, monitor = m) }
    fun syncMerge() = remoteOp(TransferKind.SYNC) { g, cred, m -> GitEngine.syncMerge(g, cred, m) }
    fun syncRebase() = remoteOp(TransferKind.SYNC) { g, cred, m -> GitEngine.syncRebase(g, cred, m) }

    fun resetSoft() = gitOp { g -> GitEngine.resetSoft(g) }
    fun resetMixed() = gitOp { g -> GitEngine.resetMixed(g) }
    fun resetHard() = gitOp { g -> GitEngine.resetHard(g) }

    /**
     * Checks the latest GitHub Actions run for this repo, if it has a GitHub remote and a
     * credential with a token attached. Silent on failure (no snackbar, no error state) —
     * this is a nice-to-have status chip, not a git operation the person is waiting on, so
     * a rate limit or network blip here shouldn't interrupt anything.
     */
    private fun refreshCiStatus(repo: RepoEntity) {
        val fullName = githubFullNameFromUrl(repo.cloneUrl)
        if (fullName == null) {
            _state.value = _state.value.copy(ciApplicable = false, ciRun = null)
            return
        }
        viewModelScope.launch {
            val token = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId)?.token else null
            if (token.isNullOrBlank()) {
                _state.value = _state.value.copy(ciApplicable = false, ciRun = null)
                return@launch
            }
            _state.value = _state.value.copy(ciApplicable = true)
            when (val r = GitHubApi.listWorkflowRuns(fullName, token, perPage = 1)) {
                is GitHubResult.Success -> _state.value = _state.value.copy(ciRun = r.data.firstOrNull())
                is GitHubResult.Error -> Unit
            }
        }
    }

    /** Manual re-check, e.g. from tapping the CI chip's refresh affordance. */
    fun refreshCiStatus() { _state.value.repo?.let { refreshCiStatus(it) } }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }

    /** For snackbar messages triggered purely from the UI layer (e.g. "Branch name copied"
     * after a clipboard action) that don't come from a git operation result. */
    fun showTransientMessage(msg: String) { _state.value = _state.value.copy(message = msg) }

    private fun authorName(): String = dev.graft.app.data.GitIdentityPrefs.currentName(appRef)
    private fun authorEmail(): String = dev.graft.app.data.GitIdentityPrefs.currentEmail(appRef)

    private fun gitOp(light: Boolean = false, block: suspend (Git) -> GitResult<*>) {
        val g = git ?: run { err("Repo not open"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            when (val r = block(g)) {
                is GitResult.Error -> err(r.message)
                is GitResult.Success -> {
                    val msg = when (val d = r.data) {
                        is String -> d
                        is Unit -> "Done"
                        else -> "Done"
                    }
                    ok(msg)
                    _state.value.repo?.let { if (light) refreshStatusOnly(it) else openAndRefresh(it) }
                }
            }
            _state.value = _state.value.copy(isWorking = false)
        }
    }

    /**
     * Runs a network operation as a background *transfer*: it shows up in the Transfers list with
     * live progress, keeps going if you leave this screen (or the app), and works on its own repo
     * handle so nothing here can pull it out from under it. This function just waits for the
     * outcome so it can report it and refresh the file list.
     */
    private fun remoteOp(
        kind: TransferKind,
        isPush: Boolean = false,
        block: suspend (Git, dev.graft.app.data.repository.DecryptedCredential?, ProgressMonitor) -> GitResult<*>,
    ) {
        val repo = _state.value.repo ?: run { err("Repo not loaded"); return }
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            val cred = if (repo.credentialId != 0L) credRepo.getById(repo.credentialId) else null
            val manager = appRef.transferManager
            val outcome = manager.enqueue(
                kind = kind, repoId = repo.id, title = repo.name, watchDir = File(repo.fullSavePath),
            ) { reporter ->
                manager.withGit(repo.fullSavePath) { g -> block(g, cred, reporter.monitor) }
            }.awaitResult()

            when (outcome) {
                is GitResult.Error -> {
                    repoRepo.markError(repo.id, outcome.message)
                    err(outcome.message)
                }
                is GitResult.Success -> {
                    repoRepo.markSyncSuccess(repo.id)
                    val msg = when (val d = outcome.data) { is String -> d; else -> "Done" }
                    if (isPush) {
                        _state.value = _state.value.copy(pushSuccessTick = _state.value.pushSuccessTick + 1)
                    } else {
                        ok(msg)
                    }
                    openAndRefresh(repo)
                    refreshCiStatus(repo)
                }
            }
            _state.value = _state.value.copy(isWorking = false)
        }
    }

    private fun err(msg: String) { _state.value = _state.value.copy(message = msg, isWorking = false) }
    private fun ok(msg: String) { _state.value = _state.value.copy(message = msg) }

    // squash helper exposed from GitEngine
    private suspend fun GitEngine.squashLastN(git: Git, n: Int, msg: String, name: String, email: String): GitResult<String> {
        val reset = resetSoft(git, "HEAD~$n")
        if (reset is GitResult.Error) return GitResult.Error(reset.message)
        return commit(git, msg, name, email)
    }

    override fun onCleared() { super.onCleared(); git?.close() }
}
