package io.github.trevarj.motd.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

// These DAO interfaces are implemented in place by WP4, which fills in the missing @Query
// strings / @Transaction bodies. Method names, parameters, and return types are frozen
// (plans/10). Room does not process them until WP4 wires up MotdDatabase, so they compile
// as plain annotated interfaces here.

@Dao
interface NetworkDao {
    @Query("SELECT * FROM networks ORDER BY ordering")
    fun observeAll(): Flow<List<NetworkEntity>>

    @Query("SELECT * FROM networks WHERE autoConnect = 1")
    suspend fun connectable(): List<NetworkEntity>

    @Query("SELECT * FROM networks WHERE id = :id")
    suspend fun byId(id: Long): NetworkEntity?

    @Insert
    suspend fun insert(n: NetworkEntity): Long

    @Update
    suspend fun update(n: NetworkEntity)

    @Delete
    suspend fun delete(n: NetworkEntity)

    @Query("SELECT * FROM networks WHERE parentId = :rootId")
    suspend fun childrenOf(rootId: Long): List<NetworkEntity>
}

@Dao
interface BufferDao {
    fun observeChatList(): Flow<List<ChatListRow>>   // @Query join: buffer + last msg + unread/mention counts

    @Query("SELECT * FROM buffers WHERE id = :id")
    fun observe(id: Long): Flow<BufferEntity?>

    @Query("SELECT * FROM buffers WHERE networkId = :nid AND name = :normName")
    suspend fun byName(nid: Long, normName: String): BufferEntity?

    @Insert
    suspend fun insert(b: BufferEntity): Long

    @Update
    suspend fun update(b: BufferEntity)

    @Query("UPDATE buffers SET readMarkerTime = :ts WHERE id = :id AND (readMarkerTime IS NULL OR readMarkerTime < :ts)")
    suspend fun advanceReadMarker(id: Long, ts: Long)
}

/** Projection for the chat list screen. */
data class ChatListRow(
    val bufferId: Long, val networkId: Long, val networkName: String,
    val displayName: String, val type: BufferType,
    val pinned: Boolean, val muted: Boolean,
    val lastMessageText: String?, val lastMessageSender: String?, val lastMessageTime: Long?,
    val unreadCount: Int, val mentionCount: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, id DESC")
    fun pagingSource(bufferId: Long): PagingSource<Int, MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(msgs: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND pendingLabel = :label")
    suspend fun byPendingLabel(bufferId: Long, label: String): MessageEntity?

    @Update
    suspend fun update(m: MessageEntity)

    @Query("SELECT MAX(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun newestTime(bufferId: Long): Long?

    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun oldestTime(bufferId: Long): Long?

    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>>  // @Query over messages_fts MATCH
}

data class SearchHit(@Embedded val message: MessageEntity, val bufferDisplayName: String, val networkName: String)

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    fun observe(bufferId: Long): Flow<List<MemberEntity>>

    @Transaction
    suspend fun replaceAll(bufferId: Long, members: List<MemberEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MemberEntity)

    @Query("DELETE FROM members WHERE bufferId = :bufferId AND nick = :nick")
    suspend fun remove(bufferId: Long, nick: String)
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE bufferId = :bufferId AND targetMsgid IN (:msgids)")
    fun observeFor(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ReactionEntity)
}

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(u: UserEntity)

    @Query("SELECT * FROM users WHERE networkId = :nid AND nick = :nick")
    suspend fun byNick(nid: Long, nick: String): UserEntity?
}
