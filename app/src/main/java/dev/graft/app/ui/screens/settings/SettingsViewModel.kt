package dev.graft.app.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.graft.app.App
import dev.graft.app.data.AutomationPrefs
import dev.graft.app.data.GitIdentityPrefs
import dev.graft.app.data.RepoStorage
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.db.entity.SyncLogEntity
import dev.graft.app.sync.SyncPrefs
import dev.graft.app.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val backgroundSyncEnabled: Boolean = false,
    val intervalMinutes: Long = SyncPrefs.DEFAULT_INTERVAL_MINUTES,
    val authorName: String = "",
    val authorEmail: String = "",
    val storageRootPath: String = "",
    val storageMode: RepoStorage.Mode = RepoStorage.Mode.APP,
    val syncEvents: List<SyncLogEntity> = emptyList(),
    val apkCacheBytes: Long = 0L,
    val isCalculatingCache: Boolean = false,
    val allRepos: List<RepoEntity> = emptyList(),
    val automatedRepoIds: Set<Long> = emptySet(),
    val message: String? = null,
)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        SettingsUiState(
            backgroundSyncEnabled = SyncPrefs.isEnabled(app),
            intervalMinutes = SyncPrefs.intervalMinutes(app),
            authorName = GitIdentityPrefs.currentName(app),
            authorEmail = GitIdentityPrefs.currentEmail(app),
            storageRootPath = RepoStorage.rootDir(app).absolutePath,
            storageMode = RepoStorage.mode(app),
            automatedRepoIds = AutomationPrefs.enabledRepoIds(app),
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        refreshCacheSize()
        loadRepos()
        viewModelScope.launch {
            getApplication<App>().syncLogRepository.recent(6).collect { events ->
                _state.value = _state.value.copy(syncEvents = events)
            }
        }
    }

    /** Where *new* repos and APK downloads go. Repos already on disk keep their own paths. */
    fun setStorageMode(mode: RepoStorage.Mode) {
        val app = getApplication<App>()
        RepoStorage.setMode(app, mode)
        _state.value = _state.value.copy(
            storageMode = mode,
            storageRootPath = RepoStorage.rootDir(app).absolutePath,
        )
        refreshCacheSize()
    }

    fun clearSyncHistory() {
        viewModelScope.launch { getApplication<App>().syncLogRepository.clear() }
    }

    private fun loadRepos() {
        viewModelScope.launch {
            val repos = (getApplication<App>()).repoRepository.allRepos.first()
            _state.value = _state.value.copy(allRepos = repos)
        }
    }

    fun setAutomatedRepoIds(ids: Set<Long>) {
        val app = getApplication<android.app.Application>()
        AutomationPrefs.setEnabledRepoIds(app, ids)
        _state.value = _state.value.copy(automatedRepoIds = ids)
    }

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        val app = getApplication<android.app.Application>()
        SyncPrefs.setEnabled(app, enabled)
        SyncScheduler.applyFromPrefs(app)
        _state.value = _state.value.copy(backgroundSyncEnabled = enabled)
    }

    fun setIntervalMinutes(minutes: Long) {
        val app = getApplication<android.app.Application>()
        val clamped = minutes.coerceIn(SyncPrefs.MIN_INTERVAL_MINUTES, SyncPrefs.MAX_INTERVAL_MINUTES)
        SyncPrefs.setIntervalMinutes(app, clamped)
        if (_state.value.backgroundSyncEnabled) SyncScheduler.applyFromPrefs(app)
        _state.value = _state.value.copy(intervalMinutes = clamped)
    }

    fun setGitIdentity(name: String, email: String) {
        val app = getApplication<android.app.Application>()
        GitIdentityPrefs.set(app, name, email)
        _state.value = _state.value.copy(
            authorName = GitIdentityPrefs.currentName(app),
            authorEmail = GitIdentityPrefs.currentEmail(app),
            message = "Commits will now be attributed to ${GitIdentityPrefs.currentName(app)}",
        )
    }

    fun refreshCacheSize() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isCalculatingCache = true)
            val bytes = withContext(Dispatchers.IO) { RepoStorage.directorySizeBytes(RepoStorage.apkDownloadsRootDir(getApplication<App>())) }
            _state.value = _state.value.copy(apkCacheBytes = bytes, isCalculatingCache = false)
        }
    }

    /** Clears every repo's downloaded-APK folder at once — safe to wipe entirely since
     *  [dev.graft.app.data.ApkInstaller] always treats this as disposable, re-downloadable
     *  install media, never something a repo depends on. */
    fun clearApkCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RepoStorage.apkDownloadsRootDir(getApplication<App>()).deleteRecursively() }
            refreshCacheSize()
            _state.value = _state.value.copy(message = "Cleared downloaded APK cache")
        }
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
