package dev.graft.app.ui.screens.conflicts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import dev.graft.app.App
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ConflictOp { MERGE, REBASE, UNKNOWN }

data class ConflictsUiState(
    val repo: RepoEntity? = null,
    val op: ConflictOp = ConflictOp.UNKNOWN,
    val conflictedPaths: List<String> = emptyList(),
    val commitMessage: String = "",
    val isLoading: Boolean = true,
    val isWorking: Boolean = false,
    val message: String? = null,
    val done: Boolean = false,
)

class ConflictsViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository

    private val _state = MutableStateFlow(ConflictsUiState())
    val uiState: StateFlow<ConflictsUiState> = _state.asStateFlow()

    private var git: Git? = null

    fun load(repoId: Long) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _state.value = _state.value.copy(repo = repo, isLoading = true)
            refresh(repo)
        }
    }

    fun refresh() { viewModelScope.launch { _state.value.repo?.let { refresh(it) } } }

    private suspend fun refresh(repo: RepoEntity) {
        val opened = GitEngine.openRepo(repo.fullSavePath)
        if (opened is GitResult.Error) { err(opened.message); return }
        val g = (opened as GitResult.Success).data
        git = g

        val state = GitEngine.getRepositoryState(g).getOrNull()
        val op = when (state) {
            RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> ConflictOp.MERGE
            RepositoryState.REBASING, RepositoryState.REBASING_MERGE,
            RepositoryState.REBASING_REBASING, RepositoryState.REBASING_INTERACTIVE -> ConflictOp.REBASE
            else -> ConflictOp.UNKNOWN
        }

        when (val r = GitEngine.getConflictedPaths(g)) {
            is GitResult.Error -> err(r.message)
            is GitResult.Success -> _state.value = _state.value.copy(
                conflictedPaths = r.data, op = op, isLoading = false,
            )
        }
    }

    fun setCommitMessage(v: String) { _state.value = _state.value.copy(commitMessage = v) }

    fun useOurs(path: String) = op { g -> GitEngine.resolveConflictOurs(g, path) }
    fun useTheirs(path: String) = op { g -> GitEngine.resolveConflictTheirs(g, path) }
    fun markResolved(path: String) = op { g -> GitEngine.markConflictResolved(g, path) }

    fun continueOperation() {
        val g = git ?: run { err("Repo not open"); return }
        val repo = _state.value.repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            val result = when (_state.value.op) {
                ConflictOp.MERGE -> {
                    val msg = _state.value.commitMessage.ifBlank { "Merge" }
                    GitEngine.continueMerge(g, msg, "Graft", "graft@local")
                }
                ConflictOp.REBASE -> GitEngine.continueRebase(g)
                ConflictOp.UNKNOWN -> GitResult.Error("Nothing in progress")
            }
            when (result) {
                is GitResult.Success -> {
                    _state.value = _state.value.copy(isWorking = false, message = "Done", done = true)
                }
                is GitResult.Error -> {
                    _state.value = _state.value.copy(isWorking = false, message = result.message)
                    refresh(repo)
                }
            }
        }
    }

    fun abortOperation() {
        val g = git ?: run { err("Repo not open"); return }
        val repo = _state.value.repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            val result = when (_state.value.op) {
                ConflictOp.MERGE -> GitEngine.abortMerge(g)
                ConflictOp.REBASE -> GitEngine.abortRebase(g)
                ConflictOp.UNKNOWN -> GitResult.Error("Nothing in progress")
            }
            when (result) {
                is GitResult.Success -> _state.value = _state.value.copy(isWorking = false, message = "Aborted", done = true)
                is GitResult.Error -> _state.value = _state.value.copy(isWorking = false, message = result.message)
            }
        }
    }

    private fun op(block: suspend (Git) -> GitResult<*>) {
        val g = git ?: run { err("Repo not open"); return }
        val repo = _state.value.repo ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isWorking = true)
            when (val r = block(g)) {
                is GitResult.Error -> err(r.message)
                is GitResult.Success -> _state.value = _state.value.copy(message = "Resolved")
            }
            _state.value = _state.value.copy(isWorking = false)
            refresh(repo)
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
    private fun err(msg: String) { _state.value = _state.value.copy(message = msg, isWorking = false, isLoading = false) }

    override fun onCleared() { super.onCleared(); git?.close() }
}
