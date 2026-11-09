package dev.graft.app.ui.screens.overview

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.RepoStatusStore
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.db.entity.SyncLogEntity
import dev.graft.app.git.CommitInfo
import dev.graft.app.git.GitEngine
import dev.graft.app.git.GitFileStatus
import dev.graft.app.git.GitResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OverviewUiState(
    val repo: RepoEntity? = null,
    val branch: String = "",
    val changeCount: Int = 0,
    val ahead: Int = 0,
    val behind: Int = 0,
    val hasUpstream: Boolean = false,
    val hasConflicts: Boolean = false,
    val recentCommits: List<CommitInfo> = emptyList(),
    val syncEvents: List<SyncLogEntity> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Read-only snapshot of a repo's state for the Overview tab. It never mutates the repository -
 * every action (commit, push, branch...) lives on its own screen - so this can refresh freely, on
 * pull-to-refresh and whenever the tab returns to the foreground.
 */
class OverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val appRef = app as App
    private val repoRepo = appRef.repoRepository
    private val syncLogs = appRef.syncLogRepository

    private val _state = MutableStateFlow(OverviewUiState())
    val uiState: StateFlow<OverviewUiState> = _state.asStateFlow()

    private var repoId: Long = -1
    private var syncJob: Job? = null

    fun load(id: Long) {
        if (repoId == id && _state.value.repo != null) return
        repoId = id
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            syncLogs.recentForRepo(id, limit = 5).collect { events ->
                _state.update { it.copy(syncEvents = events) }
            }
        }
        refresh()
    }

    fun refresh() {
        val id = repoId
        if (id < 0) return
        viewModelScope.launch {
            val repo = repoRepo.getById(id)
            if (repo == null) {
                _state.update { it.copy(isLoading = false, error = "This repository is no longer tracked.") }
                return@launch
            }
            _state.update { it.copy(repo = repo, isLoading = true) }

            when (val opened = GitEngine.openRepo(repo.fullSavePath)) {
                is GitResult.Error -> _state.update {
                    it.copy(isLoading = false, error = "Couldn't open repository: ${opened.message}")
                }
                is GitResult.Success -> {
                    val git = opened.data
                    try {
                        val branch = GitEngine.getCurrentBranch(git).getOrNull().orEmpty()
                        val status = GitEngine.getStatus(git).getOrNull().orEmpty()
                        val aheadBehind = GitEngine.getAheadBehind(git).getOrNull() ?: Pair(0, 0)
                        val upstream = GitEngine.hasUpstream(git).getOrNull() ?: false
                        val commits = GitEngine.getLog(git, maxCount = 5).getOrNull().orEmpty()
                        val count = status.map { it.path }.distinct().size
                        RepoStatusStore.setChangeCount(repo.id, count)
                        _state.update {
                            it.copy(
                                branch = branch,
                                changeCount = count,
                                ahead = aheadBehind.first,
                                behind = aheadBehind.second,
                                hasUpstream = upstream,
                                hasConflicts = status.any { e -> e.status == GitFileStatus.CONFLICTED },
                                recentCommits = commits,
                                isLoading = false,
                                error = null,
                            )
                        }
                    } finally {
                        git.close()
                    }
                }
            }
        }
    }
}
