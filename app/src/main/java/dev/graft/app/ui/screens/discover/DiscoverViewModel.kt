package dev.graft.app.ui.screens.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.RepoStorage
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.github.GitHubApi
import dev.graft.app.data.github.GitHubRepoSummary
import dev.graft.app.data.github.GitHubResult
import dev.graft.app.data.repository.DecryptedCredential
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class DiscoverUiState(
    val query: String = "",
    val results: List<GitHubRepoSummary> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val showingMine: Boolean = false,
    val credentials: List<DecryptedCredential> = emptyList(),
    val selectedCredentialId: Long? = null,
    val cloningFullName: String? = null,
    val isCreating: Boolean = false,
    val deletingFullName: String? = null,
    val message: String? = null,
)

/**
 * Backs the Discover screen: search GitHub (or list a signed-in account's own repos) and
 * clone straight from a result, without ever needing to open github.com to copy a URL.
 */
class DiscoverViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val credRepo = appRef.credentialRepository

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            credRepo.allCredentials.collect { list ->
                _state.value = _state.value.copy(
                    credentials = list,
                    selectedCredentialId = _state.value.selectedCredentialId ?: list.firstOrNull()?.id,
                )
            }
        }
    }

    fun onQueryChanged(q: String) { _state.value = _state.value.copy(query = q) }
    fun onCredentialSelected(id: Long?) { _state.value = _state.value.copy(selectedCredentialId = id) }

    fun search() {
        val q = _state.value.query
        if (q.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true, showingMine = false, hasSearched = true)
            when (val r = GitHubApi.searchRepos(q, selectedToken())) {
                is GitHubResult.Success -> _state.value = _state.value.copy(results = r.data, isSearching = false)
                is GitHubResult.Error -> _state.value = _state.value.copy(isSearching = false, message = r.message)
            }
        }
    }

    fun loadMyRepos() {
        val token = selectedToken()
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(message = "Pick a saved credential with a GitHub token first")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isSearching = true, showingMine = true, hasSearched = true)
            when (val r = GitHubApi.listMyRepos(token)) {
                is GitHubResult.Success -> _state.value = _state.value.copy(results = r.data, isSearching = false)
                is GitHubResult.Error -> _state.value = _state.value.copy(isSearching = false, message = r.message)
            }
        }
    }

    private fun selectedToken(): String? =
        _state.value.credentials.firstOrNull { it.id == _state.value.selectedCredentialId }?.token

    fun cloneRepo(repo: GitHubRepoSummary) {
        val context = getApplication<App>()
        if (!RepoStorage.hasStorageAccess(context)) {
            _state.value = _state.value.copy(
                message = "Graft needs storage access to save repos in a public folder. " +
                    "Grant it from the banner on the repo list, then try again.",
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(cloningFullName = repo.fullName)
            val credentialId = _state.value.selectedCredentialId
            val credential = credentialId?.let { credRepo.getById(it) }
            val message = cloneAndTrack(repo, if (repo.private) credential else null, if (repo.private) (credentialId ?: 0L) else 0L)
            _state.value = _state.value.copy(cloningFullName = null, message = message)
        }
    }

    /**
     * Creates a brand-new repo on GitHub. When [autoClone] is true (the default from the
     * dialog) it's immediately cloned into the app too, so it shows up in the repo list right
     * away — same end result as creating one on github.com in a browser and pasting the clone
     * URL in, just without leaving the app. When false, this only hits the GitHub create-repo
     * endpoint — useful for scaffolding a repo to push an existing local project into later,
     * where auto-cloning an empty repo on top of it would just be extra cleanup.
     */
    fun createRepo(name: String, description: String, private: Boolean, autoClone: Boolean = true) {
        if (name.isBlank()) {
            _state.value = _state.value.copy(message = "Give the repo a name first")
            return
        }
        val credentialId = _state.value.selectedCredentialId
        if (credentialId == null) {
            _state.value = _state.value.copy(message = "Pick a saved credential with a GitHub token first")
            return
        }
        val token = _state.value.credentials.firstOrNull { it.id == credentialId }?.token
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(message = "Pick a saved credential with a GitHub token first")
            return
        }
        val context = getApplication<App>()
        if (autoClone && !RepoStorage.hasStorageAccess(context)) {
            _state.value = _state.value.copy(
                message = "Graft needs storage access to save repos in a public folder. " +
                    "Grant it from the banner on the repo list, then try again.",
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isCreating = true)
            when (val created = GitHubApi.createRepo(name, description, private, token)) {
                is GitHubResult.Error -> _state.value = _state.value.copy(isCreating = false, message = "GitHub: ${created.message}")
                is GitHubResult.Success -> {
                    val message = if (autoClone) {
                        val credential = credRepo.getById(credentialId)
                        cloneAndTrack(created.data, credential, credentialId)
                    } else {
                        "Created ${created.data.fullName} on GitHub"
                    }
                    _state.value = _state.value.copy(isCreating = false, message = message)
                }
            }
        }
    }

    /**
     * Permanently deletes a repo from GitHub — this never touches anything on disk. A local
     * clone tracked in [repoRepo] (if any) is left completely alone; the person can keep
     * working in it offline, or remove it separately from the repo list if they want to.
     * [CreateRepoDialog]/the delete confirmation dialog are what make that split explicit to
     * the person before this ever runs.
     */
    fun deleteRepoOnGithub(repo: GitHubRepoSummary) {
        val token = selectedToken()
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(message = "Pick a saved credential with a GitHub token first")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(deletingFullName = repo.fullName)
            when (val r = GitHubApi.deleteRepo(repo.fullName, token)) {
                is GitHubResult.Error ->
                    _state.value = _state.value.copy(deletingFullName = null, message = "GitHub: ${r.message}")
                is GitHubResult.Success ->
                    _state.value = _state.value.copy(
                        deletingFullName = null,
                        results = _state.value.results.filterNot { it.fullName == repo.fullName },
                        message = "Deleted ${repo.fullName} from GitHub",
                    )
            }
        }
    }

    /** Shared by both cloneRepo and createRepo: clones [repo] into the public repos folder
     *  and registers it, returning a user-facing status message either way. */
    private suspend fun cloneAndTrack(
        repo: GitHubRepoSummary, credential: DecryptedCredential?, credentialId: Long,
    ): String {
        val name = repo.fullName.substringAfterLast("/")
        val destination = File(RepoStorage.reposRootDir(getApplication<App>()), name)
        if (destination.exists()) {
            return "Created on GitHub, but a local folder named \"$name\" already exists — rename or remove it, then clone from Discover."
        }

        return when (val result = GitEngine.cloneRepo(
            url = repo.cloneUrl,
            localPath = destination.absolutePath,
            branch = repo.defaultBranch,
            credential = credential,
        )) {
            is GitResult.Success -> {
                result.data.close()
                repoRepo.addRepo(
                    RepoEntity(
                        name = name,
                        fullSavePath = destination.absolutePath,
                        cloneUrl = repo.cloneUrl,
                        branch = repo.defaultBranch,
                        credentialId = credentialId,
                    ),
                )
                "Cloned $name"
            }
            is GitResult.Error -> {
                destination.deleteRecursively()
                result.message
            }
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
