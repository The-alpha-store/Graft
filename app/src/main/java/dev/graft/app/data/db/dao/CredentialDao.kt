package dev.graft.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.graft.app.data.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials ORDER BY name ASC")
    fun getAllFlow(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials ORDER BY name ASC")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: Long): CredentialEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(credential: CredentialEntity): Long

    @Update
    suspend fun update(credential: CredentialEntity)

    @Delete
    suspend fun delete(credential: CredentialEntity)
}
