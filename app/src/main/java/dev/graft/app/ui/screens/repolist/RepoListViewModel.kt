package dev.graft.app.ui.screens.repolist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.RepoStatusStore
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.github.GitHubApi
import dev.graft.app.data.github.GitHubResult
import dev.graft.app.data.github.githubFullNameFromUrl
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

enum class RepoSortMode { RECENT, NAME, HAS_CHANGES }

data class RepoListUiState(
    val repos: List<RepoEntity> = emptyList(),
    val busyRepoId: Long? = null,
    val snackbarMessage: String? = null,
    val searchQuery: String = "",
    val sortMode: RepoSortMode = RepoSortMode.RECENT,
    val isRefreshing: Boolean = false,
    val credentials: List<dev.graft.app.data.repository.DecryptedCredential> = emptyList(),
    /** Uncommitted (staged + unstaged) file count per repo id, for the list badge. Absent
     * key = not computed yet (e.g. still opening the repo), not necessarily zero changes. */
    val changeCounts: Map<Long, Int> = emptyMap(),
) {
    /** Repos filtered by [searchQuery] (name or clone URL, case-insensitive) and sorted by
     * [sortMode]. Computed here rather than stored separately so there's exactly one source
     * of truth for the raw repo list. */
    val visibleRepos: List<RepoEntity> get() {
        val filtered = if (searchQuery.isBlank()) {
            repos
        } else {
            repos.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                    it.cloneUrl.contains(searchQuery, ignoreCase = true)
            }
        }
        return when (sortMode) {
            RepoSortMode.RECENT -> filtered.sortedByDescending { it.addedAt }
            RepoSortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            RepoSortMode.HAS_CHANGES -> filtered.sortedByDescending { changeCounts[it.id] ?: -1 }
        }
    }
}

class RepoListViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepository = appRef.repoRepository
    private val credentialRepository = appRef.credentialRepository
    private val busyRepoId = MutableStateFlow<Long?>(null)
    private val snackbarMessage = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val sortMode = MutableStateFlow(RepoSortMode.RECENT)
    private val changeCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    private val isRefreshing = MutableStateFlow(false)

    private val filters = combine(searchQuery, sortMode, changeCounts) { q, s, c -> Triple(q, s, c) }

    private val baseState = combine(
        repoRepository.allRepos, busyRepoId, snackbarMessage, filters, isRefreshing,
    ) { repos, busy, msg, (query, sort, counts), refreshing ->
        RepoListUiState(repos, busy, msg, query, sort, refreshing, emptyList(), counts)
    }

    val uiState: StateFlow<RepoListUiState> = combine(
        baseState, credentialRepository.allCredentials,
    ) { state, credentials -> state.copy(credentials = credentials) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RepoListUiState())

    init {
        // Counts computed elsewhere (the Changes tab after a commit, the Overview) flow in here too,
        // so returning Home never shows a number from before you committed.
        viewModelScope.launch {
            RepoStatusStore.changeCounts.collect { shared -> changeCounts.value = changeCounts.value + shared }
        }
        // Compute each repo's uncommitted-change count the first time it's seen. Cheap
        // enough for a handful of repos; if this list grows into the hundreds this should
        // move to something lazier (e.g. only visible items), but that's not this app's
        // scale today.
        viewModelScope.launch {
            repoRepository.allRepos.collect { repos ->
                repos.forEach { repo ->
                    if (!changeCounts.value.containsKey(repo.id)) loadChangeCount(repo)
                }
            }
        }
    }

    fun setSearchQuery(q: String) { searchQuery.value = q }
    fun setSortMode(m: RepoSortMode) { sortMode.value = m }

    private fun loadChangeCount(repo: RepoEntity) {
        viewModelScope.launch {
            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> Unit // leave uncomputed rather than showing a false "0"
                is GitResult.Success -> {
                    val git = opened.data
                    val statusResult = GitEngine.getStatus(git)
                    if (statusResult is GitResult.Success) {
                        val count = statusResult.data.map { it.path }.distinct().size
                        changeCounts.value = changeCounts.value + (repo.id to count)
                        RepoStatusStore.setChangeCount(repo.id, count)
                    }
                    git.close()
                }
            }
        }
    }

    fun dismissSnackbar() { snackbarMessage.value = null }

    /** Attaches (or, with credentialId = 0, detaches) a credential on an existing repo —
     *  the piece that was missing for repos added via Clone without picking one, or via
     *  the local-folder scan (which never has one to begin with, since there's no clone
     *  step to pick it during). Pass 0 to clear. */
    fun setCredential(repo: RepoEntity, credentialId: Long) {
        viewModelScope.launch {
            repoRepository.updateRepo(repo.copy(credentialId = credentialId))
        }
    }

    /**
     * Pull-to-refresh on Home: rescans the local repos folder for anything new (same as
     * [scanForLocalRepos]) and recomputes every repo's uncommitted-change count from
     * scratch — not just the ones that haven't been computed yet — since a manual pull
     * implies "things may have changed underneath you" (e.g. edited from another app,
     * a `git` command run from Termux) rather than just "fill in what's missing".
     */
    fun refresh() {
        viewModelScope.launch {
            isRefreshing.value = true
            val added = repoRepository.scanForLocalRepos(appRef)
            repoRepository.allRepos.first().forEach { loadChangeCount(it) }
            if (added > 0) {
                snackbarMessage.value = if (added == 1) "Found 1 local repo" else "Found $added local repos"
            }
            isRefreshing.value = false
        }
    }

    /**
     * Looks for repos sitting in the public repos folder that this app doesn't know about
     * yet — dropped in with a file manager, copied over USB/MTP, checked out from Termux,
     * etc — and registers each one. Called automatically whenever the repo list screen
     * comes back to the foreground, and available as a manual action too.
     */
    fun scanForLocalRepos(silent: Boolean = false) {
        viewModelScope.launch {
            val added = repoRepository.scanForLocalRepos(appRef)
            if (added > 0) {
                snackbarMessage.value = if (added == 1) "Found 1 local repo" else "Found $added local repos"
            } else if (!silent) {
                snackbarMessage.value = "No new repos found"
            }
        }
    }

    fun deleteRepo(repo: RepoEntity, alsoDeleteFiles: Boolean) {
        viewModelScope.launch {
            repoRepository.deleteRepo(repo)
            if (alsoDeleteFiles) File(repo.fullSavePath).deleteRecursively()
        }
    }

    /**
     * Permanently deletes the repo on GitHub itself (not just from this app's list), then
     * removes it from Graft too. Needs a credential with a token that has the
     * "delete_repo" scope — GitHub rejects the call otherwise, and that rejection message
     * is what surfaces in the snackbar, since guessing scopes ahead of time isn't reliable.
     */
    fun deleteRepoOnGitHub(repo: RepoEntity, alsoDeleteFilesLocally: Boolean) {
        viewModelScope.launch {
            val fullName = githubFullNameFromUrl(repo.cloneUrl)
            if (fullName == null) {
                snackbarMessage.value = "Couldn't tell which GitHub repo this is from its remote URL"
                return@launch
            }
            val cred = if (repo.credentialId != 0L) credentialRepository.getById(repo.credentialId) else null
            val token = cred?.token
            if (token.isNullOrBlank()) {
                snackbarMessage.value = "Attach a credential with a GitHub token to this repo first"
                return@launch
            }

            busyRepoId.value = repo.id
            when (val r = GitHubApi.deleteRepo(fullName, token)) {
                is GitHubResult.Error -> snackbarMessage.value = "GitHub: ${r.message}"
                is GitHubResult.Success -> {
                    repoRepository.deleteRepo(repo)
                    if (alsoDeleteFilesLocally) File(repo.fullSavePath).deleteRecursively()
                    snackbarMessage.value = "Deleted ${repo.name} on GitHub"
                }
            }
            busyRepoId.value = null
        }
    }


    fun fetch(repo: RepoEntity) = remoteOp(repo) { git, cred ->
        GitEngine.fetch(git, credential = cred).also { r ->
            if (r is GitResult.Success) snackbarMessage.value = "${repo.name}: ${r.data}"
        }
    }

    fun pull(repo: RepoEntity) = remoteOp(repo) { git, cred ->
        GitEngine.pullMerge(git, cred).also { r ->
            if (r is GitResult.Success) snackbarMessage.value = "${repo.name}: ${r.data}"
        }
    }

    fun pullForce(repo: RepoEntity) = remoteOp(repo) { git, cred ->
        GitEngine.pullForce(git, cred).also { r ->
            if (r is GitResult.Success) snackbarMessage.value = "${repo.name}: ${r.data}"
        }
    }

    fun push(repo: RepoEntity) = remoteOp(repo) { git, cred ->
        GitEngine.push(git, credential = cred).also { r ->
            if (r is GitResult.Success) snackbarMessage.value = "Pushed ${repo.name}"
        }
    }

    fun pushForce(repo: RepoEntity) = remoteOp(repo) { git, cred ->
        GitEngine.push(git, force = true, credential = cred).also { r ->
            if (r is GitResult.Success) snackbarMessage.value = "Force pushed ${repo.name}"
        }
    }

    private fun remoteOp(repo: RepoEntity, block: suspend (org.eclipse.jgit.api.Git, dev.graft.app.data.repository.DecryptedCredential?) -> GitResult<*>) {
        viewModelScope.launch {
            busyRepoId.value = repo.id
            val cred = if (repo.credentialId != 0L) credentialRepository.getById(repo.credentialId) else null
            when (val open = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> fail(repo, open.message)
                is GitResult.Success -> {
                    val git = open.data
                    when (val r = block(git, cred)) {
                        is GitResult.Error -> fail(repo, r.message)
                        is GitResult.Success -> {
                            repoRepository.markSyncSuccess(repo.id)
                            loadChangeCount(repo)
                        }
                    }
                    git.close()
                }
            }
            busyRepoId.value = null
        }
    }

    private suspend fun fail(repo: RepoEntity, message: String) {
        repoRepository.markError(repo.id, message)
        snackbarMessage.value = "${repo.name}: $message"
    }
}
