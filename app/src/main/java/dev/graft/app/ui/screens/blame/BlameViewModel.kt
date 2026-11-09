package dev.graft.app.ui.screens.blame

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.git.BlameLine
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BlameUiState(
    val repo: RepoEntity? = null,
    val path: String = "",
    val lines: List<BlameLine> = emptyList(),
    val isLoading: Boolean = true,
    val message: String? = null,
)

class BlameViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository

    private val _state = MutableStateFlow(BlameUiState())
    val uiState: StateFlow<BlameUiState> = _state.asStateFlow()

    fun load(repoId: Long, path: String) {
        viewModelScope.launch {
            val repo = repoRepo.getById(repoId) ?: return@launch
            _state.value = _state.value.copy(repo = repo, path = path, isLoading = true)

            val opened = GitEngine.openRepo(repo.fullSavePath)
            if (opened is GitResult.Error) {
                _state.value = _state.value.copy(isLoading = false, message = opened.message)
                return@launch
            }
            val git = (opened as GitResult.Success).data
            when (val result = GitEngine.getBlame(git, path)) {
                is GitResult.Success -> _state.value = _state.value.copy(lines = result.data, isLoading = false)
                is GitResult.Error -> _state.value = _state.value.copy(isLoading = false, message = result.message)
            }
            git.close()
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
