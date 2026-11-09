package dev.graft.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One line of background-sync history: what the worker did for a repo and how it went. Shown on the
 * repo Overview and in Settings so "did my last sync actually work?" has an answer inside the app.
 */
@Entity(tableName = "sync_logs")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val repoId: Long,
    val repoName: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** One of [SyncLogStatus]. Stored as text so new kinds never need a migration. */
    val status: String,
    val message: String,
)

object SyncLogStatus {
    const val SUCCESS = "SUCCESS"
    const val UP_TO_DATE = "UP_TO_DATE"
    const val NEW_COMMITS = "NEW_COMMITS"
    const val AUTO_COMMIT = "AUTO_COMMIT"
    const val FAILED = "FAILED"
}
