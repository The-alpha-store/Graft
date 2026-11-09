package dev.graft.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.graft.app.data.db.dao.CredentialDao
import dev.graft.app.data.db.dao.RepoDao
import dev.graft.app.data.db.dao.SyncLogDao
import dev.graft.app.data.db.entity.CredentialEntity
import dev.graft.app.data.db.entity.RepoEntity
import dev.graft.app.data.db.entity.SyncLogEntity

@Database(
    entities = [RepoEntity::class, CredentialEntity::class, SyncLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun repoDao(): RepoDao
    abstract fun credentialDao(): CredentialDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 -> v2: adds the sync history table. Existing repos and credentials are untouched. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_logs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`repoId` INTEGER NOT NULL, " +
                        "`repoName` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`message` TEXT NOT NULL)",
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "graft.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
