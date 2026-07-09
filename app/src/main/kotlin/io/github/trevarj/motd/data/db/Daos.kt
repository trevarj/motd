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

    // Snapshot of all rows for the app-level duplicate check in NetworkRepositoryImpl.addNetwork.
    // A one-shot read (not the observed Flow) so dedup is a simple suspend call; the networks
    // table is tiny (a handful of rows) so a full scan is cheap and avoids a per-identity index
    // that would need a schema bump (DB is v1, no migrations).
    @Query("SELECT * FROM networks")
    suspend fun allNow(): List<NetworkEntity>
}

@Dao
interface BufferDao {
    // Chat-list projection: each non-SERVER buffer joined with its latest message (correlated
    // subqueries on the (bufferId, serverTime, id) index) plus unread/mention counts relative to
    // the buffer's readMarkerTime. Chat kinds only (PRIVMSG/NOTICE/ACTION); self messages never
    // count as unread. Sort: pinned first, then latest activity DESC (nulls last).
    @Transaction
    @Query(
        """
        SELECT
            b.id AS bufferId,
            b.networkId AS networkId,
            n.name AS networkName,
            b.displayName AS displayName,
            b.type AS type,
            b.pinned AS pinned,
            b.muted AS muted,
            (SELECT m.text FROM messages m WHERE m.bufferId = b.id
                ORDER BY m.serverTime DESC, m.id DESC LIMIT 1) AS lastMessageText,
            (SELECT m.sender FROM messages m WHERE m.bufferId = b.id
                ORDER BY m.serverTime DESC, m.id DESC LIMIT 1) AS lastMessageSender,
            (SELECT m.serverTime FROM messages m WHERE m.bufferId = b.id
                ORDER BY m.serverTime DESC, m.id DESC LIMIT 1) AS lastMessageTime,
            (SELECT COUNT(*) FROM messages m WHERE m.bufferId = b.id
                AND m.serverTime > COALESCE(b.readMarkerTime, 0)
                AND m.isSelf = 0
                AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')) AS unreadCount,
            (SELECT COUNT(*) FROM messages m WHERE m.bufferId = b.id
                AND m.serverTime > COALESCE(b.readMarkerTime, 0)
                AND m.isSelf = 0
                AND m.hasMention = 1
                AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')) AS mentionCount
        FROM buffers b
        JOIN networks n ON n.id = b.networkId
        WHERE b.type != 'SERVER'
        ORDER BY b.pinned DESC,
                 (lastMessageTime IS NULL) ASC,
                 lastMessageTime DESC,
                 b.id DESC
        """
    )
    fun observeChatList(): Flow<List<ChatListRow>>

    @Query("SELECT * FROM buffers WHERE id = :id")
    fun observe(id: Long): Flow<BufferEntity?>

    // Point read for read-modify-write toggles (pin/mute); not part of the frozen surface.
    @Query("SELECT * FROM buffers WHERE id = :id")
    suspend fun observeById(id: Long): BufferEntity?

    @Query("SELECT * FROM buffers WHERE networkId = :nid AND name = :normName")
    suspend fun byName(nid: Long, normName: String): BufferEntity?

    @Insert
    suspend fun insert(b: BufferEntity): Long

    @Update
    suspend fun update(b: BufferEntity)

    @Query("UPDATE buffers SET readMarkerTime = :ts WHERE id = :id AND (readMarkerTime IS NULL OR readMarkerTime < :ts)")
    suspend fun advanceReadMarker(id: Long, ts: Long)

    // Delete a buffer and all of its content. messages (and their messages_fts rows via Room's
    // FTS sync triggers) cascade off the buffers->messages FK ON DELETE CASCADE. members and
    // reactions have no FK to buffers, so they are cleared explicitly here in one transaction to
    // avoid orphaned rows (plans/04 cascade note; verified in DeleteBufferDaoTest).
    @Query("DELETE FROM members WHERE bufferId = :id")
    suspend fun deleteMembersForBuffer(id: Long)

    @Query("DELETE FROM reactions WHERE bufferId = :id")
    suspend fun deleteReactionsForBuffer(id: Long)

    @Query("DELETE FROM buffers WHERE id = :id")
    suspend fun deleteBufferRow(id: Long)

    @Transaction
    suspend fun deleteBuffer(id: Long) {
        deleteMembersForBuffer(id)
        deleteReactionsForBuffer(id)
        deleteBufferRow(id) // cascades to messages + messages_fts
    }
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

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND msgid = :msgid LIMIT 1")
    suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?

    /**
     * Newest local self row for [bufferId] matching [text], to collapse an un-labeled echo into a
     * pending/confirmed-local row (plans/03 echo heuristic). A still-pending row (pendingLabel set)
     * is a local send awaiting THIS echo, so it matches regardless of the [lo]..[hi] window — its
     * serverTime is a device timestamp and cannot be compared to the server's echo time under clock
     * skew (would otherwise duplicate the self-send). Confirmed rows must fall inside the window so
     * an old identical self message isn't matched. Pending rows rank first. A suspend @Query runs
     * off the main thread and is transaction-safe (live onChat + HistoryBatch withTransaction).
     */
    @Query(
        """SELECT * FROM messages WHERE bufferId = :bufferId AND isSelf = 1 AND text = :text
          AND (pendingLabel IS NOT NULL OR serverTime BETWEEN :lo AND :hi)
          ORDER BY (pendingLabel IS NOT NULL) DESC, serverTime DESC, id DESC LIMIT 1"""
    )
    suspend fun findSelfEchoCandidate(bufferId: Long, text: String, lo: Long, hi: Long): MessageEntity?

    // Delete a single row by primary key. Used to drop a failed local-echo row on retry/delete so
    // the resend does not leave a permanent duplicate "failed" bubble (plans/15 #10).
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 0-based reverse-list index: strict complement of pagingSource ORDER BY serverTime DESC, id DESC. */
    @Query(
        """SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId
          AND (serverTime > :serverTime OR (serverTime = :serverTime AND id > :id))"""
    )
    suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int

    // FTS4 external-content search over (text, sender). :query is already sanitized (each token
    // quoted + prefixed with *) by SearchRepository. Chat kinds only; optional buffer scope.
    @Query(
        """
        SELECT m.*, b.displayName AS bufferDisplayName, n.name AS networkName
        FROM messages m
        JOIN messages_fts f ON m.id = f.rowid
        JOIN buffers b ON b.id = m.bufferId
        JOIN networks n ON n.id = b.networkId
        WHERE f.messages_fts MATCH :query
          AND (:bufferId IS NULL OR m.bufferId = :bufferId)
          AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
        ORDER BY m.serverTime DESC LIMIT 200
        """
    )
    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>>  // @Query over messages_fts MATCH
}

data class SearchHit(@Embedded val message: MessageEntity, val bufferDisplayName: String, val networkName: String)

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    fun observe(bufferId: Long): Flow<List<MemberEntity>>

    @Query("DELETE FROM members WHERE bufferId = :bufferId")
    suspend fun clear(bufferId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<MemberEntity>)

    // Atomic member snapshot swap (NAMES replay): clear then bulk-insert in one transaction.
    @Transaction
    suspend fun replaceAll(bufferId: Long, members: List<MemberEntity>) {
        clear(bufferId)
        insertAll(members)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MemberEntity)

    @Query("DELETE FROM members WHERE bufferId = :bufferId AND nick = :nick")
    suspend fun remove(bufferId: Long, nick: String)
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE bufferId = :bufferId AND targetMsgid IN (:msgids)")
    fun observeFor(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>>

    // Buffer-scoped observe with no per-msgid IN(...) list. Scrolling back accumulates >999 loaded
    // msgids, which would overflow SQLite's bind-variable limit in observeFor and crash; scoping by
    // bufferId keeps one stable query and the repository filters to the visible window in memory
    // (plans/15 #5). A buffer's reaction table is small relative to its message history.
    @Query("SELECT * FROM reactions WHERE bufferId = :bufferId")
    fun observeForBuffer(bufferId: Long): Flow<List<ReactionEntity>>

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
