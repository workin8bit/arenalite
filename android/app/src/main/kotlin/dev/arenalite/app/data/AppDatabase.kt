package dev.arenalite.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence. Note what is *not* here: no quota column, no expiry column,
 * no "max rows" cleanup job. History grows for as long as the user keeps it.
 */

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val id: String,
    val title: String,
    val model: String,
    val plan: String = "unlimited",
    val createdAt: String,
    val updatedAt: String,
    val messageCount: Int = 0,
    val status: String = "idle",
    val remoteId: String? = null,
    val lastSyncedAt: String? = null,
)

@Entity(tableName = "messages")
data class MessageRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Int,
    val role: String,
    val content: String,
    val createdAt: String,
)

@Entity(tableName = "sync_state")
data class SyncStateRow(
    @PrimaryKey val sessionId: String,
    val remoteId: String,
    val backend: String,
    val cursor: Long = 0,
    val lastPushedAt: String? = null,
    val lastPulledAt: String? = null,
    val pushedFiles: Int = 0,
    val pulledFiles: Int = 0,
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionRow>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SessionRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionRow)

    @Update
    suspend fun update(session: SessionRow)

    @Delete
    suspend fun delete(session: SessionRow)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun listFor(sessionId: String): List<MessageRow>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY seq ASC")
    fun observeFor(sessionId: String): Flow<List<MessageRow>>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countFor(sessionId: String): Int

    @Insert
    suspend fun insert(message: MessageRow): Long

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteFor(sessionId: String)
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE sessionId = :sessionId LIMIT 1")
    suspend fun find(sessionId: String): SyncStateRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateRow)

    @Query("DELETE FROM sync_state WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}

@Database(
    entities = [SessionRow::class, MessageRow::class, SyncStateRow::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun messages(): MessageDao
    abstract fun syncState(): SyncStateDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "arenalite.db",
            ).build().also { instance = it }
        }
    }
}
