package dev.graft.app.data.repository

import dev.graft.app.data.db.dao.SyncLogDao
import dev.graft.app.data.db.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

class SyncLogRepository(private val dao: SyncLogDao) {

    fun recent(limit: Int = 50): Flow<List<SyncLogEntity>> = dao.recentFlow(limit)

    fun recentForRepo(repoId: Long, limit: Int = 20): Flow<List<SyncLogEntity>> =
        dao.recentForRepoFlow(repoId, limit)

    suspend fun record(repoId: Long, repoName: String, status: String, message: String) {
        dao.insert(SyncLogEntity(repoId = repoId, repoName = repoName, status = status, message = message))
        dao.trim(MAX_ROWS)
    }

    suspend fun clear() = dao.clear()

    private companion object {
        const val MAX_ROWS = 200
    }
}
