package dev.graft.app

import android.app.Application
import dev.graft.app.data.AppearancePrefs
import dev.graft.app.data.GitIdentityPrefs
import dev.graft.app.data.db.AppDatabase
import dev.graft.app.data.repository.CredentialRepository
import dev.graft.app.data.repository.RepoRepository
import dev.graft.app.data.repository.SyncLogRepository
import dev.graft.app.git.JGitTuning
import dev.graft.app.sync.SyncNotifier
import dev.graft.app.sync.SyncScheduler
import dev.graft.app.transfer.AppVisibility
import dev.graft.app.transfer.TransferManager
import dev.graft.app.transfer.TransferNotifier

/**
 * Application class and the app's tiny service locator: one database, one set of repositories,
 * created once. ViewModels reach them through `getApplication<App>()`. No DI framework - the
 * object graph is three repositories deep.
 */
class App : Application() {

    lateinit var repoRepository: RepoRepository
        private set

    lateinit var credentialRepository: CredentialRepository
        private set

    lateinit var syncLogRepository: SyncLogRepository
        private set

    /** Runs clones/pulls/pushes independent of any screen, with progress and a foreground service. */
    lateinit var transferManager: TransferManager
        private set

    override fun onCreate() {
        super.onCreate()

        val db = AppDatabase.getDatabase(this)
        repoRepository = RepoRepository(db.repoDao())
        credentialRepository = CredentialRepository(db.credentialDao())
        syncLogRepository = SyncLogRepository(db.syncLogDao())

        registerActivityLifecycleCallbacks(AppVisibility)
        transferManager = TransferManager(applicationContext)
        TransferNotifier.createChannels(this)
        JGitTuning.install(cacheDir)
        SyncNotifier.createChannel(this)
        AppearancePrefs.init(this)
        GitIdentityPrefs.init(this)

        // WorkManager schedules survive normal process death but not a data wipe, so re-apply
        // whatever the user last chose in Settings (also what reschedules after a toggle).
        SyncScheduler.applyFromPrefs(this)
    }
}
