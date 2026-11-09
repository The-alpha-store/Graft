package dev.graft.app.ui.screens.clone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.GitIdentityPrefs
import dev.graft.app.data.RepoStorage
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.repository.DecryptedCredential
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class AddRepoMode { CLONE, CREATE }

data class CloneUiState(
    val mode: AddRepoMode = AddRepoMode.CLONE,
    val createInitialCommit: Boolean = true,
    /** Latest JGit phase while cloning ("Receiving objects"...). Null when idle. */
    val progress: String? = null,
    val url: String = "",
    val branch: String = "",
    val repoName: String = "",
    val credentials: List<DecryptedCredential> = emptyList(),
    val selectedCredentialId: Long? = null,
    val isCloning: Boolean = false,
    val errorMessage: String? = null,
    val done: Boolean = false
)

class CloneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val repoRepository = app.repoRepository
    private val credentialRepository = app.credentialRepository

    private val _uiState = MutableStateFlow(CloneUiState())
    val uiState: StateFlow<CloneUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            credentialRepository.allCredentials.collect { list ->
                _uiState.value = _uiState.value.copy(credentials = list)
            }
        }
    }

    fun onModeChanged(mode: AddRepoMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null) }
    }

    fun onInitialCommitChanged(enabled: Boolean) {
        _uiState.update { it.copy(createInitialCommit = enabled) }
    }

    fun onUrlChanged(url: String) {
        val guessedName = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        _uiState.value = _uiState.value.copy(
            url = url,
            repoName = if (_uiState.value.repoName.isBlank() || _uiState.value.repoName == guessNameFor(_uiState.value.url)) {
                guessedName
            } else {
                _uiState.value.repoName
            }
        )
    }

    private fun guessNameFor(url: String) =
        url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    fun onBranchChanged(branch: String) {
        _uiState.value = _uiState.value.copy(branch = branch)
    }

    fun onRepoNameChanged(name: String) {
        _uiState.value = _uiState.value.copy(repoName = name)
    }

    fun onCredentialSelected(id: Long?) {
        _uiState.value = _uiState.value.copy(selectedCredentialId = id)
    }

    fun startClone() {
        val state = _uiState.value
        if (state.url.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Enter a repo URL first.")
            return
        }
        val name = state.repoName.ifBlank { guessNameFor(state.url) }
        val context = getApplication<App>()

        if (!dev.graft.app.data.RepoStorage.hasStorageAccess(context)) {
            _uiState.value = state.copy(
                errorMessage = "Graft needs storage access to save repos in a public folder. " +
                    "Grant it from the banner on the repo list, then try again."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCloning = true, errorMessage = null, progress = "Connecting…")

            val reposDir = dev.graft.app.data.RepoStorage.reposRootDir(context)
            val destination = File(reposDir, name)

            if (destination.exists()) {
                _uiState.value = _uiState.value.copy(
                    isCloning = false,
                    errorMessage = "A folder named \"$name\" already exists. Pick a different name."
                )
                return@launch
            }

            val credential = state.selectedCredentialId?.let { credentialRepository.getById(it) }

            when (val result = GitEngine.cloneRepo(
                url = state.url,
                localPath = destination.absolutePath,
                branch = state.branch,
                credential = credential,
                onProgress = { phase -> _uiState.update { it.copy(progress = phase) } },
            )) {
                is GitResult.Success -> {
                    result.data.close()
                    repoRepository.addRepo(
                        RepoEntity(
                            name = name,
                            fullSavePath = destination.absolutePath,
                            cloneUrl = state.url,
                            branch = state.branch,
                            credentialId = state.selectedCredentialId ?: 0L
                        )
                    )
                    _uiState.value = _uiState.value.copy(isCloning = false, progress = null, done = true)
                }
                is GitResult.Error -> {
                    destination.deleteRecursively()
                    _uiState.value = _uiState.value.copy(
                        isCloning = false,
                        progress = null,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    /** Creates an empty local repository (no remote) under the repos folder and tracks it. */
    fun startCreate() {
        val state = _uiState.value
        val name = state.repoName.trim()
        val context = getApplication<App>()
        when {
            name.isBlank() -> {
                _uiState.value = state.copy(errorMessage = "Give the repository a name.")
                return
            }
            !name.matches(Regex("[A-Za-z0-9._-]+")) -> {
                _uiState.value = state.copy(errorMessage = "Use only letters, numbers, dots, dashes and underscores.")
                return
            }
            !RepoStorage.hasStorageAccess(context) -> {
                _uiState.value = state.copy(
                    errorMessage = "Graft needs storage access for the public folder. " +
                        "Grant it from the banner on the repo list, or switch to app storage in Settings.",
                )
                return
            }
        }

        val branch = state.branch.trim().ifBlank { "main" }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCloning = true, errorMessage = null, progress = null)
            val destination = File(RepoStorage.reposRootDir(context), name)
            if (destination.exists()) {
                _uiState.value = _uiState.value.copy(
                    isCloning = false,
                    errorMessage = "A folder named \"$name\" already exists. Pick a different name.",
                )
                return@launch
            }
            when (val result = GitEngine.initRepo(
                localPath = destination.absolutePath,
                name = name,
                authorName = GitIdentityPrefs.currentName(context).ifBlank { "Graft" },
                authorEmail = GitIdentityPrefs.currentEmail(context).ifBlank { "graft@localhost" },
                branch = branch,
                createInitialCommit = state.createInitialCommit,
            )) {
                is GitResult.Success -> {
                    result.data.close()
                    repoRepository.addRepo(
                        RepoEntity(name = name, fullSavePath = destination.absolutePath, cloneUrl = "", branch = branch),
                    )
                    _uiState.value = _uiState.value.copy(isCloning = false, done = true)
                }
                is GitResult.Error -> {
                    destination.deleteRecursively()
                    _uiState.value = _uiState.value.copy(isCloning = false, errorMessage = result.message)
                }
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** Clears the form and the one-shot `done` flag after a successful clone.
     *  Called once the sheet has told the caller it's done — without this, since
     *  this ViewModel is scoped to the screen behind the sheet (not to the sheet's
     *  own visibility), the next time the sheet opens it would immediately see
     *  the previous `done = true` and close itself before appearing. */
    fun resetForm() {
        _uiState.value = _uiState.value.copy(
            url = "",
            branch = "",
            repoName = "",
            selectedCredentialId = null,
            isCloning = false,
            progress = null,
            errorMessage = null,
            done = false,
        )
    }
}
