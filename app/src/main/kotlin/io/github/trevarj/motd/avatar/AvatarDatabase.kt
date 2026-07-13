package io.github.trevarj.motd.avatar

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "remote_avatars")
data class AvatarRecordEntity(
    val networkId: Long,
    @PrimaryKey val scopedIdentity: String,
    val identity: String,
    val nick: String,
    val account: String?,
    val url: String,
    val updatedAt: Long,
)

@Dao
interface AvatarDao {
    @Query("SELECT * FROM remote_avatars ORDER BY networkId, scopedIdentity")
    fun observeAll(): Flow<List<AvatarRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: AvatarRecordEntity)

    @Transaction
    suspend fun replaceIdentity(record: AvatarRecordEntity) {
        remove(record.networkId, record.identity, record.nick)
        upsert(record)
    }

    @Query("DELETE FROM remote_avatars WHERE networkId = :networkId AND (identity = :identity OR nick = :normalizedNick)")
    suspend fun remove(networkId: Long, identity: String, normalizedNick: String)

    @Query("SELECT * FROM remote_avatars WHERE networkId = :networkId AND (identity = :identity OR nick = :normalizedNick) LIMIT 1")
    suspend fun find(networkId: Long, identity: String, normalizedNick: String): AvatarRecordEntity?

    @Query("DELETE FROM remote_avatars WHERE networkId = :networkId")
    suspend fun clearNetwork(networkId: Long)

    @Query("DELETE FROM remote_avatars")
    suspend fun clearAll()
}

@Database(entities = [AvatarRecordEntity::class], version = 1, exportSchema = false)
abstract class AvatarDatabase : RoomDatabase() {
    abstract fun avatarDao(): AvatarDao
}
