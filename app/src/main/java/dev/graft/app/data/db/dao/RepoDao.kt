package dev.graft.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.graft.app.data.db.entity.RepoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoDao {

    /** Live stream of all repos, newest first. The repo list screen observes this. */
    @Query("SELECT * FROM repos ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM repos WHERE id = :id")
    suspend fun getById(id: Long): RepoEntity?

    @Query("SELECT * FROM repos WHERE fullSavePath = :path LIMIT 1")
    suspend fun getByPath(path: String): RepoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(repo: RepoEntity): Long

    @Update
    suspend fun update(repo: RepoEntity)

    @Query("UPDATE repos SET lastSyncTime = :time, lastError = '' WHERE id = :id")
    suspend fun markSyncSuccess(id: Long, time: Long = System.currentTimeMillis())

    @Query("UPDATE repos SET lastError = :error WHERE id = :id")
    suspend fun markError(id: Long, error: String)

    @Query("UPDATE repos SET branch = :branch WHERE id = :id")
    suspend fun updateBranch(id: Long, branch: String)

    @Delete
    suspend fun delete(repo: RepoEntity)
}
