package dev.graft.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.graft.app.data.db.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {

    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    fun recentFlow(limit: Int): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_logs WHERE repoId = :repoId ORDER BY timestamp DESC LIMIT :limit")
    fun recentForRepoFlow(repoId: Long, limit: Int): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(log: SyncLogEntity): Long

    /** Keeps only the newest [keep] rows so the table can't grow without bound. */
    @Query("DELETE FROM sync_logs WHERE id NOT IN (SELECT id FROM sync_logs ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM sync_logs")
    suspend fun clear()
}
