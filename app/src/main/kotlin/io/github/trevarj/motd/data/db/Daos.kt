package io.github.trevarj.motd.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

// Room is the authoritative boundary for fixed persistence queries. Prefer typed entity or
// projection methods here; keep raw SQL at callers only when predicates are genuinely dynamic.

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

    @Query("UPDATE networks SET host = :host, port = :port, nick = :nick WHERE id = :id")
    suspend fun updateBouncerConnection(id: Long, host: String, port: Int, nick: String)

    @Query("SELECT * FROM networks WHERE parentId = :rootId")
    suspend fun childrenOf(rootId: Long): List<NetworkEntity>

    // A bouncer root and its local child mirrors are not linked by a SQLite FK: children inherit
    // the root transport at runtime, but deleting the root must still remove every local mirror
    // and its chat-list buffers in one transaction.
    @Query("SELECT id FROM networks WHERE id = :id OR parentId = :id")
    suspend fun localTreeIds(id: Long): List<Long>

    @Query("DELETE FROM members WHERE bufferId IN (SELECT id FROM buffers WHERE networkId IN (:networkIds))")
    suspend fun deleteMembersForNetworks(networkIds: List<Long>)

    @Query("DELETE FROM reactions WHERE bufferId IN (SELECT id FROM buffers WHERE networkId IN (:networkIds))")
    suspend fun deleteReactionsForNetworks(networkIds: List<Long>)

    @Query("DELETE FROM users WHERE networkId IN (:networkIds)")
    suspend fun deleteUsersForNetworks(networkIds: List<Long>)

    @Query("DELETE FROM networks WHERE id IN (:networkIds)")
    suspend fun deleteNetworkRows(networkIds: List<Long>)

    /** Delete one direct/child row, or a bouncer root together with all of its local mirrors. */
    @Transaction
    suspend fun deleteLocalTree(id: Long): List<Long> {
        val networkIds = localTreeIds(id)
        if (networkIds.isEmpty()) return emptyList()
        // buffers/messages cascade off networks/buffers; these tables intentionally have no FKs.
        deleteMembersForNetworks(networkIds)
        deleteReactionsForNetworks(networkIds)
        deleteUsersForNetworks(networkIds)
        deleteNetworkRows(networkIds)
        return networkIds
    }

    // Snapshot of all rows for the app-level duplicate check in NetworkRepositoryImpl.addNetwork.
    // A one-shot read (not the observed Flow) so dedup is a simple suspend call; the networks
    // table is tiny (a handful of rows) so a full scan is cheap and avoids a per-identity index
    // that would need a schema bump (DB is v1, no migrations).
    @Query("SELECT * FROM networks")
    suspend fun allNow(): List<NetworkEntity>
}

@Dao
interface NetworkIdentityDao {
    @Query("SELECT * FROM network_identity ORDER BY networkId")
    fun observeAll(): Flow<List<NetworkIdentityEntity>>

    @Query("SELECT * FROM network_identity WHERE networkId = :networkId")
    fun observe(networkId: Long): Flow<NetworkIdentityEntity?>

    @Query("SELECT * FROM network_identity WHERE networkId = :networkId")
    suspend fun byNetwork(networkId: Long): NetworkIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: NetworkIdentityEntity)

    @Query("UPDATE network_identity SET selfNick = :selfNick WHERE networkId = :networkId")
    suspend fun updateSelfNick(networkId: Long, selfNick: String): Int

    /** Registration normally creates the row; retain a durable fallback for an orphan self-NICK. */
    @Transaction
    suspend fun setSelfNick(networkId: Long, selfNick: String) {
        if (updateSelfNick(networkId, selfNick) == 0) {
            upsert(NetworkIdentityEntity(networkId = networkId, selfNick = selfNick))
        }
    }
}

@Dao
interface BufferDao {
    // Chat-list projection: each non-SERVER buffer joins one newest preview-eligible message by
    // identity. JOIN/PART/QUIT are timeline-only events and never become previews or activity.
    // Unread/mention counts remain chat kinds only; self messages never count as unread.
    // Sort: pinned first, then latest preview activity DESC (nulls last).
    @Transaction
    @Query(
        """
        SELECT
            b.id AS bufferId,
            b.networkId AS networkId,
            n.name AS networkName,
            ni.caseMapping AS caseMapping,
            ni.chanTypes AS chanTypes,
            b.displayName AS displayName,
            b.type AS type,
            b.pinned AS pinned,
            b.muted AS muted,
            lm.text AS lastMessageText,
            lm.sender AS lastMessageSender,
            lm.serverTime AS lastMessageTime,
            (SELECT COUNT(*) FROM messages m WHERE m.bufferId = b.id
                AND (
                    m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), COALESCE(b.localUnreadFloorTime, 0))
                    OR (
                        m.serverTime = b.localReadAnchorTime
                        AND COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime
                        AND m.id > COALESCE(b.localReadAnchorEventId, 0)
                    )
                )
                AND m.isSelf = 0
                AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')) AS unreadCount,
            (SELECT COUNT(*) FROM messages m WHERE m.bufferId = b.id
                AND (
                    m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), COALESCE(b.localUnreadFloorTime, 0))
                    OR (
                        m.serverTime = b.localReadAnchorTime
                        AND COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime
                        AND m.id > COALESCE(b.localReadAnchorEventId, 0)
                    )
                )
                AND m.isSelf = 0
                AND m.hasMention = 1
                AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')) AS mentionCount
        FROM buffers b
        JOIN networks n ON n.id = b.networkId
        LEFT JOIN network_identity ni ON ni.networkId = b.networkId
        LEFT JOIN messages lm ON lm.id = (
            SELECT m.id FROM messages m
            WHERE m.bufferId = b.id AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'NETSPLIT', 'NETJOIN')
            ORDER BY m.serverTime DESC, m.id DESC
            LIMIT 1
        )
        WHERE b.type != 'SERVER' AND b.dismissed = 0
          AND b.pendingCloseAt IS NULL AND b.redirectToRoomId IS NULL
        ORDER BY b.pinned DESC,
                 (lastMessageTime IS NULL) ASC,
                 lastMessageTime DESC,
                 b.id DESC
        """
    )
    fun observeChatList(): Flow<List<ChatListRow>>

    @Query(
        """SELECT canonical.* FROM buffers requested
           JOIN buffers canonical ON canonical.id = COALESCE(requested.redirectToRoomId, requested.id)
           WHERE requested.id = :id""",
    )
    fun observe(id: Long): Flow<BufferEntity?>

    // Point read for read-modify-write toggles (pin/mute); not part of the frozen surface.
    @Query(
        """SELECT canonical.* FROM buffers requested
           JOIN buffers canonical ON canonical.id = COALESCE(requested.redirectToRoomId, requested.id)
           WHERE requested.id = :id""",
    )
    suspend fun observeById(id: Long): BufferEntity?

    @Query("SELECT * FROM buffers WHERE id = :id")
    suspend fun rawById(id: RoomId): BufferEntity?

    @Query("SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = :id")
    suspend fun canonicalId(id: RoomId): RoomId?

    @Query(
        """SELECT canonical.* FROM buffers requested
           JOIN buffers canonical ON canonical.id = COALESCE(requested.redirectToRoomId, requested.id)
           WHERE requested.networkId = :nid AND requested.name = :normName""",
    )
    suspend fun byName(nid: Long, normName: String): BufferEntity?

    @Query("SELECT id FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND pendingCloseAt IS NULL")
    suspend fun channelIds(networkId: Long): List<Long>

    @Query("SELECT displayName FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND joined = 1 AND pendingCloseAt IS NULL ORDER BY id")
    suspend fun joinedChannelNames(networkId: Long): List<String>

    @Query(
        """SELECT id, CASE WHEN type = 'SERVER' THEN name ELSE displayName END AS name
           FROM buffers WHERE networkId = :networkId AND type != 'SERVER'
             AND pendingCloseAt IS NULL AND redirectToRoomId IS NULL ORDER BY id""",
    )
    suspend fun openTargets(networkId: Long): List<BufferTargetRow>

    @Query(
        """SELECT id FROM buffers WHERE networkId = :networkId AND pendingCloseAt IS NULL
           AND (name = :target COLLATE NOCASE OR displayName = :target COLLATE NOCASE) LIMIT 1""",
    )
    suspend fun idForTarget(networkId: Long, target: String): Long?

    @Query("SELECT * FROM buffers WHERE pendingCloseAt IS NOT NULL AND type = 'CHANNEL' ORDER BY pendingCloseAt, id")
    suspend fun pendingChannelCloses(): List<BufferEntity>

    /** Mark a CHANNEL for an asynchronous server-side close, preserving its first attempt time. */
    @Query(
        "UPDATE buffers SET pendingCloseAt = :timestamp " +
            "WHERE id = :id AND type = 'CHANNEL' AND pendingCloseAt IS NULL",
    )
    suspend fun markPendingClose(id: Long, timestamp: Long): Int

    @Query(
        """SELECT b.id AS bufferId,
                   CASE WHEN b.type = 'SERVER' THEN b.name ELSE b.displayName END AS target,
                  CASE
                    WHEN b.readMarkerTime IS NULL THEN candidate.serverTime
                    WHEN candidate.serverTime IS NULL THEN b.readMarkerTime
                    ELSE MAX(b.readMarkerTime, candidate.serverTime)
                  END AS timestamp,
                  candidate.id AS eventId
           FROM buffers b
           LEFT JOIN messages candidate ON candidate.id = (
               SELECT m.id FROM messages m
               WHERE m.bufferId = b.id AND m.serverTimeAuthoritative = 1
                 AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                 AND b.localReadAnchorTime IS NOT NULL AND (
                     m.serverTime < b.localReadAnchorTime OR
                     (m.serverTime = b.localReadAnchorTime AND
                      m.id <= COALESCE(b.localReadAnchorEventId, 0))
                 )
               ORDER BY m.serverTime DESC, m.id DESC LIMIT 1
           )
           WHERE b.networkId = :networkId AND b.type != 'SERVER'
             AND b.pendingCloseAt IS NULL AND b.redirectToRoomId IS NULL ORDER BY b.id""",
    )
    suspend fun storedReadMarkers(networkId: Long): List<BufferReadMarkerRow>

    @Insert
    suspend fun insert(b: BufferEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(b: BufferEntity): Long

    @Update
    suspend fun update(b: BufferEntity)

    @Query("UPDATE buffers SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE buffers SET muted = :muted WHERE id = :id")
    suspend fun writeMuted(id: Long, muted: Boolean)

    @Query(
        """UPDATE buffers SET
               localReadAnchorTime = CASE WHEN dismissed = 1 THEN NULL ELSE localReadAnchorTime END,
               localReadAnchorEventId = CASE WHEN dismissed = 1 THEN NULL ELSE localReadAnchorEventId END,
               localUnreadFloorTime = CASE WHEN dismissed = 1 THEN NULL ELSE localUnreadFloorTime END,
               dismissed = 0
           WHERE id = :id AND type = 'QUERY'""",
    )
    suspend fun reviveQuery(id: RoomId): Int

    @Query(
        """INSERT OR IGNORE INTO discarded_message_ids(roomId, msgid)
           SELECT :id, msgid FROM messages WHERE bufferId = :id AND msgid IS NOT NULL""",
    )
    suspend fun rememberDiscardedMessageIds(id: RoomId)

    @Query(
        """SELECT EXISTS(
               SELECT 1 FROM discarded_message_ids WHERE roomId = :id AND msgid = :msgid
           )""",
    )
    suspend fun isDiscardedMessageId(id: RoomId, msgid: String): Boolean

    @Query(
        """INSERT OR IGNORE INTO discarded_message_ids(roomId, msgid)
           SELECT :toId, msgid FROM discarded_message_ids WHERE roomId = :fromId""",
    )
    suspend fun copyDiscardedMessageIds(fromId: RoomId, toId: RoomId)

    @Query("DELETE FROM discarded_message_ids WHERE roomId = :id")
    suspend fun deleteDiscardedMessageIds(id: RoomId)

    @Query(
        """SELECT MAX(serverTime) FROM messages WHERE bufferId = :id
           AND isSelf = 0 AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')""",
    )
    suspend fun latestIncomingChatTime(id: Long): Long?

    @Query(
        """UPDATE buffers SET localUnreadFloorTime = :timestamp
           WHERE id = :id AND (
               localUnreadFloorTime IS NULL OR localUnreadFloorTime < :timestamp
           )""",
    )
    suspend fun advanceLocalUnreadFloor(id: Long, timestamp: Long)

    @Transaction
    suspend fun setMuted(id: Long, muted: Boolean) {
        if (!muted) {
            latestIncomingChatTime(id)?.let { advanceLocalUnreadFloor(id, it) }
        }
        writeMuted(id, muted)
    }

    @Query("UPDATE buffers SET topic = :topic, topicSetBy = :setBy WHERE id = :id")
    suspend fun setTopic(id: Long, topic: String, setBy: String?)

    @Query("UPDATE buffers SET joined = :joined WHERE id = :id")
    suspend fun setJoined(id: Long, joined: Boolean)

    @Query("UPDATE buffers SET membershipCycle = membershipCycle + 1 WHERE id = :id")
    suspend fun advanceMembershipCycle(id: RoomId)

    @Query("UPDATE buffers SET historyComplete = 1 WHERE id = :id")
    suspend fun markHistoryComplete(id: Long)

    @Query("UPDATE buffers SET oldestFetchedTime = :oldestFetchedTime WHERE id = :id")
    suspend fun setOldestFetchedTime(id: Long, oldestFetchedTime: Long?)

    @Query("UPDATE buffers SET readMarkerTime = :ts WHERE id = :id AND (readMarkerTime IS NULL OR readMarkerTime < :ts)")
    suspend fun advanceReadMarker(id: Long, ts: Long)

    @Query(
        """UPDATE buffers SET localReadAnchorTime = :serverTime, localReadAnchorEventId = :eventId
           WHERE id = :id AND (
               localReadAnchorTime IS NULL OR localReadAnchorTime < :serverTime OR
               (localReadAnchorTime = :serverTime AND COALESCE(localReadAnchorEventId, 0) < :eventId)
           )""",
    )
    suspend fun advanceLocalReadAnchor(id: RoomId, serverTime: Long, eventId: TimelineEventId)

    @Query(
        """UPDATE buffers SET localReadAnchorTime = :serverTime
           WHERE localReadAnchorEventId = :eventId""",
    )
    suspend fun retimeLocalReadAnchor(eventId: TimelineEventId, serverTime: Long)

    @Query(
        """UPDATE buffers SET localReadAnchorTime = :serverTime, localReadAnchorEventId = :winnerId
           WHERE localReadAnchorEventId = :loserId OR localReadAnchorEventId = :winnerId""",
    )
    suspend fun repointLocalReadAnchors(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
        serverTime: Long,
    )

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

    @Query("DELETE FROM buffers WHERE redirectToRoomId = :id")
    suspend fun deleteRedirectsTo(id: Long)

    @Query("DELETE FROM messages WHERE bufferId = :id")
    suspend fun deleteMessagesForBuffer(id: RoomId)

    @Query("DELETE FROM composer_drafts WHERE roomId = :id")
    suspend fun deleteDraftForBuffer(id: RoomId)

    @Query("SELECT * FROM history_cursors WHERE roomId = :id")
    suspend fun historyCursorForBuffer(id: RoomId): HistoryCursorEntity?

    @Query(
        """SELECT m.msgid,
                  CASE WHEN m.serverTimeAuthoritative = 1 THEN m.serverTime ELSE (
                      SELECT MAX(a.serverTime) FROM messages a
                      WHERE a.bufferId = :id AND a.serverTimeAuthoritative = 1
                  ) END AS serverTime
           FROM messages m
           WHERE m.bufferId = :id
             AND (m.msgid IS NOT NULL OR m.serverTimeAuthoritative = 1)
           ORDER BY m.serverTime DESC, m.id DESC LIMIT 1""",
    )
    suspend fun latestBoundaryForBuffer(id: RoomId): MessageBoundaryRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryCursor(cursor: HistoryCursorEntity)

    @Transaction
    suspend fun deleteBuffer(id: Long) {
        val room = rawById(id) ?: return
        if (room.type == BufferType.QUERY) {
            dismissQuery(room)
            return
        }
        deleteMembersForBuffer(id)
        deleteReactionsForBuffer(id)
        deleteRedirectsTo(id)
        deleteBufferRow(id) // cascades to messages + messages_fts
    }

    /** Purge local query content while retaining enough identity and protocol state to detect DMs. */
    @Transaction
    suspend fun dismissQuery(room: BufferEntity) {
        val cursor = historyCursorForBuffer(room.id)
        val latest = latestBoundaryForBuffer(room.id)
        val candidates = listOf(
            room.historyDiscardedThroughMsgid to room.historyDiscardedThroughTime,
            cursor?.newestMsgid to cursor?.newestServerTime,
            latest?.msgid to latest?.serverTime,
            null to room.readMarkerTime,
        ).filter { (msgid, time) -> msgid != null || time != null }
        val floorTime = candidates.mapNotNull { it.second }.maxOrNull()
        val floorMsgid = candidates.asSequence()
            .filter { it.first != null }
            .maxByOrNull { it.second ?: Long.MIN_VALUE }
            ?.first

        update(
            room.copy(
                pinned = false,
                muted = false,
                localReadAnchorTime = floorTime,
                localReadAnchorEventId = null,
                oldestFetchedTime = null,
                historyComplete = false,
                dismissed = true,
                historyDiscardedThroughMsgid = floorMsgid,
                historyDiscardedThroughTime = floorTime,
            ),
        )
        deleteMembersForBuffer(room.id)
        deleteReactionsForBuffer(room.id)
        deleteDraftForBuffer(room.id)
        rememberDiscardedMessageIds(room.id)
        deleteMessagesForBuffer(room.id)
        upsertHistoryCursor(
            HistoryCursorEntity(
                roomId = room.id,
                newestMsgid = floorMsgid,
                newestServerTime = floorTime,
            ),
        )
    }
}

/** Projection for the chat list screen. */
data class ChatListRow(
    val bufferId: Long, val networkId: Long, val networkName: String,
    val displayName: String, val type: BufferType,
    val pinned: Boolean, val muted: Boolean,
    val lastMessageText: String?, val lastMessageSender: String?, val lastMessageTime: Long?,
    val unreadCount: Int, val mentionCount: Int,
    val caseMapping: String? = null,
    val chanTypes: String? = null,
)

data class BufferTargetRow(val id: Long, val name: String)

data class BufferReadMarkerRow(
    val bufferId: Long,
    val target: String,
    val timestamp: Long?,
    val eventId: TimelineEventId? = null,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, id DESC")
    fun pagingSource(bufferId: Long): PagingSource<Int, MessageEntity>

    /** Dynamic visibility predicates must run inside Room so placeholder counts match page rows. */
    @RawQuery(observedEntities = [MessageEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MessageEntity>

    @RawQuery
    suspend fun rawMessage(query: SupportSQLiteQuery): MessageEntity?

    @RawQuery
    suspend fun rawCount(query: SupportSQLiteQuery): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(msgs: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): MessageEntity?

    @Query(
        """SELECT * FROM messages WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :id),
               :id
           ) LIMIT 1""",
    )
    suspend fun byCanonicalId(id: Long): MessageEntity?

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :bufferId AND id != :excludeEventId
              AND isSelf = 0 AND failed = 0 AND (
                  serverTime > :afterTime OR (serverTime = :afterTime AND id > :afterEventId)
              )
             AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
             AND (:queryRoom = 1 OR hasMention = 1)
           ORDER BY serverTime DESC, id DESC
           LIMIT :limit""",
    )
    suspend fun recentNotifiable(
        bufferId: Long,
        afterTime: Long,
        afterEventId: TimelineEventId,
        queryRoom: Boolean,
        excludeEventId: Long,
        limit: Int,
    ): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND eventKey = :eventKey LIMIT 1")
    suspend fun byEventKey(bufferId: Long, eventKey: String): MessageEntity?

    @Query(
        """UPDATE messages SET inviteState = :toState
           WHERE id = :id AND inviteState = :fromState""",
    )
    suspend fun compareAndSetInviteState(id: Long, fromState: InviteState, toState: InviteState): Int

    @Query("UPDATE messages SET inviteState = :state WHERE id = :id")
    suspend fun setInviteState(id: Long, state: InviteState)

    @Query(
        """UPDATE messages SET inviteState = 'DISMISSED'
           WHERE id = :id AND inviteState IN ('PENDING', 'JOINING', 'FAILED')""",
    )
    suspend fun dismissInvite(id: Long): Int

    @Query(
        """SELECT id FROM messages WHERE bufferId = :bufferId AND kind = 'INVITE'
           AND inviteState IN ('PENDING', 'JOINING', 'FAILED')""",
    )
    suspend fun actionableInviteIds(bufferId: Long): List<Long>

    @Query(
        """UPDATE messages SET inviteState = 'JOINED'
           WHERE bufferId = :bufferId AND kind = 'INVITE'
           AND inviteState IN ('PENDING', 'JOINING', 'FAILED')""",
    )
    suspend fun markInvitesJoined(bufferId: Long): Int

    @Query(
        """UPDATE messages SET inviteState = 'FAILED', text = text || ' — Join failed: ' || :reason
           WHERE id = :id AND kind = 'INVITE' AND inviteState = 'JOINING'""",
    )
    suspend fun failInvite(id: Long, reason: String): Int

    @Query(
        """UPDATE messages SET inviteState = 'FAILED', text = text || ' — Join failed: ' || :reason
           WHERE bufferId = :bufferId AND kind = 'INVITE' AND inviteState = 'JOINING'""",
    )
    suspend fun failJoiningInvites(bufferId: Long, reason: String): Int

    @Query(
        """UPDATE messages SET inviteState = 'FAILED', text = text || ' — Join failed: ' || :reason
           WHERE kind = 'INVITE' AND inviteState = 'JOINING'
           AND bufferId IN (SELECT id FROM buffers WHERE networkId = :networkId)""",
    )
    suspend fun failJoiningInvitesForNetwork(networkId: Long, reason: String): Int

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND pendingLabel = :label")
    suspend fun byPendingLabel(bufferId: Long, label: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:eventIds) ORDER BY id")
    suspend fun byIds(eventIds: List<TimelineEventId>): List<MessageEntity>

    @Query(
        """UPDATE messages SET failed = 1
           WHERE bufferId = :bufferId AND pendingLabel = :label AND msgid IS NULL""",
    )
    suspend fun failIfStillPending(bufferId: Long, label: String): Int

    @Query(
        """UPDATE messages SET failed = 1
           WHERE id IN (:eventIds) AND pendingLabel IS NOT NULL AND msgid IS NULL""",
    )
    suspend fun failPending(eventIds: List<TimelineEventId>): Int

    @Query(
        """UPDATE messages SET pendingLabel = :label, failed = 0
           WHERE id = :eventId AND isSelf = 1 AND failed = 1 AND msgid IS NULL""",
    )
    suspend fun beginRetry(eventId: TimelineEventId, label: String): Int

    @Query(
        """SELECT DISTINCT b.networkId FROM messages m
           JOIN buffers b ON b.id = m.bufferId
           WHERE m.pendingLabel IS NOT NULL AND m.msgid IS NULL AND m.failed = 0
           ORDER BY b.networkId""",
    )
    suspend fun pendingNetworkIds(): List<Long>

    @Query(
        """UPDATE messages SET failed = 1
           WHERE pendingLabel IS NOT NULL AND msgid IS NULL AND failed = 0
             AND bufferId IN (SELECT id FROM buffers WHERE networkId = :networkId)""",
    )
    suspend fun recoverInterruptedPending(networkId: Long): Int

    @Query(
        """UPDATE messages SET pendingLabel = NULL, failed = 0
           WHERE bufferId = :bufferId AND pendingLabel = :label AND msgid IS NULL""",
    )
    suspend fun confirmIfStillPending(bufferId: Long, label: String): Int

    @Update
    suspend fun update(m: MessageEntity)

    @Query("SELECT MAX(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun newestTime(bufferId: Long): Long?

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, id DESC LIMIT 1")
    suspend fun newestMessage(bufferId: RoomId): MessageEntity?

    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun oldestTime(bufferId: Long): Long?

    @Query(
        """SELECT m.msgid,
                  CASE WHEN m.serverTimeAuthoritative = 1 THEN m.serverTime ELSE (
                      SELECT MAX(a.serverTime) FROM messages a
                      WHERE a.bufferId = :bufferId AND a.serverTimeAuthoritative = 1
                  ) END AS serverTime
           FROM messages m
           WHERE m.bufferId = :bufferId
             AND (m.msgid IS NOT NULL OR m.serverTimeAuthoritative = 1)
           ORDER BY m.serverTime DESC, m.id DESC LIMIT 1""",
    )
    suspend fun latestBoundary(bufferId: Long): MessageBoundaryRow?

    @Query(
        """SELECT m.msgid,
                  CASE WHEN m.serverTimeAuthoritative = 1 THEN m.serverTime ELSE (
                      SELECT MIN(a.serverTime) FROM messages a
                      WHERE a.bufferId = :bufferId AND a.serverTimeAuthoritative = 1
                  ) END AS serverTime
           FROM messages m
           WHERE m.bufferId = :bufferId
             AND (m.msgid IS NOT NULL OR m.serverTimeAuthoritative = 1)
           ORDER BY m.serverTime ASC, m.id ASC LIMIT 1""",
    )
    suspend fun oldestBoundary(bufferId: Long): MessageBoundaryRow?

    @Query("SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId")
    suspend fun countForBuffer(bufferId: Long): Int

    @Query(
        """SELECT COUNT(*) FROM messages m
           JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId""",
    )
    suspend fun countForNetwork(networkId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE bufferId = :bufferId AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION'))")
    suspend fun hasStoredChat(bufferId: Long): Boolean

    @Query(
        """SELECT b.id AS bufferId,
                   CASE WHEN b.type = 'SERVER' THEN b.name ELSE b.displayName END AS target,
                  m.serverTime AS timestamp, m.id AS eventId
           FROM buffers b JOIN messages m ON m.id = (
               SELECT newest.id FROM messages newest
               WHERE newest.bufferId = b.id AND newest.isSelf = 0
                 AND newest.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
               ORDER BY newest.serverTime DESC, newest.id DESC LIMIT 1
           )
           WHERE b.id IN (:bufferIds) AND b.type != 'SERVER'""",
    )
    suspend fun latestIncomingMarkers(bufferIds: List<Long>): List<BufferReadMarkerRow>

    @Query(
        """SELECT id AS eventId, serverTime AS timestamp
           FROM messages WHERE bufferId = :bufferId AND serverTimeAuthoritative = 1
             AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
             AND (serverTime < :serverTime OR (serverTime = :serverTime AND id <= :eventId))
           ORDER BY serverTime DESC, id DESC LIMIT 1""",
    )
    suspend fun authoritativeChatAtOrBefore(
        bufferId: RoomId,
        serverTime: Long,
        eventId: TimelineEventId,
    ): TimelineBoundaryRow?

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND msgid = :msgid LIMIT 1")
    suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND dedupKey = :dedupKey LIMIT 1")
    suspend fun byDedupKey(bufferId: Long, dedupKey: String): MessageEntity?

    /**
     * At most two durable incoming rows matching a msgid-less live representation. Account and
     * reply tags are deliberately excluded: bouncers may add or strip those optional tags between
     * live and history delivery. Returning two lets EventProcessor reject an ambiguous match
     * instead of merging legitimately repeated text.
     */
    @Query(
        """SELECT * FROM messages WHERE bufferId = :bufferId AND isSelf = 0 AND msgid IS NOT NULL
          AND sender = :sender AND kind = :kind AND text = :text
          AND serverTime BETWEEN :lo AND :hi
          ORDER BY serverTime DESC, id DESC LIMIT 2""",
    )
    suspend fun findDurableIncomingCandidates(
        bufferId: Long,
        sender: String,
        kind: MessageKind,
        text: String,
        lo: Long,
        hi: Long,
    ): List<MessageEntity>

    /** Casefold fallback for servers that change nick casing between live and history delivery. */
    @Query(
        """SELECT * FROM messages WHERE bufferId = :bufferId AND isSelf = 0 AND msgid IS NOT NULL
          AND kind = :kind AND text = :text
          AND serverTime BETWEEN :lo AND :hi
          ORDER BY serverTime DESC, id DESC LIMIT 8""",
    )
    suspend fun findDurableIncomingCandidatesByText(
        bufferId: Long,
        kind: MessageKind,
        text: String,
        lo: Long,
        hi: Long,
    ): List<MessageEntity>

    /** Observe a reply target so a late echo promotion or history insert updates its preview. */
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND msgid = :msgid LIMIT 1")
    fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?>

    /**
     * Observe a single local row's server msgid by primary key. Emits null while the row is still
     * pending (own optimistic send not yet echoed) and the durable msgid once the echo promotes it
     * in place. Drives the deferred-reaction queue: a react tapped on a still-pending own message
     * waits on this flow until its msgid lands, then sends the TAGMSG (plans/15 reactions).
     */
    @Query("SELECT msgid FROM messages WHERE id = :id LIMIT 1")
    fun observeMsgid(id: Long): Flow<String?>

    @Query(
        """SELECT msgid FROM messages WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :id),
               :id
           ) LIMIT 1""",
    )
    fun observeCanonicalMsgid(id: Long): Flow<String?>

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

    /**
     * Newest self row in [bufferId] matching [text] that still lacks a durable server msgid, used to
     * reconcile a msgid-BEARING self arrival (a CHATHISTORY replay, or a delayed echo) onto the local
     * row it belongs to when the plain time-window heuristic missed it. A msgid-less self row is one
     * whose confirming echo never carried a draft/msgid, so it is definitively still awaiting its
     * durable identity; matching by (isSelf, text) regardless of time is therefore safe — a genuinely
     * distinct second self-send would already carry its OWN msgid (its own echo confirmed it) and so
     * would not be returned here. This is the last-resort collapse before a fresh insert (goguma-style
     * msgid promotion). Suspend @Query → runs off the main thread and is transaction-safe.
     */
    @Query(
        """SELECT * FROM messages WHERE bufferId = :bufferId AND isSelf = 1 AND text = :text
          AND msgid IS NULL ORDER BY serverTime DESC, id DESC LIMIT 1"""
    )
    suspend fun findSelfMsgidlessCandidate(bufferId: Long, text: String): MessageEntity?

    @Query(
        """UPDATE buffers SET
               localReadAnchorTime = (SELECT serverTime FROM messages
                   WHERE bufferId = buffers.id AND id != :id AND
                     (serverTime < :serverTime OR (serverTime = :serverTime AND id < :id))
                   ORDER BY serverTime DESC, id DESC LIMIT 1),
               localReadAnchorEventId = (SELECT id FROM messages
                   WHERE bufferId = buffers.id AND id != :id AND
                     (serverTime < :serverTime OR (serverTime = :serverTime AND id < :id))
                   ORDER BY serverTime DESC, id DESC LIMIT 1)
           WHERE localReadAnchorEventId = :id""",
    )
    suspend fun fallbackReadAnchorBeforeDelete(id: TimelineEventId, serverTime: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun deleteWithAnchorFallback(id: TimelineEventId) {
        val event = byCanonicalId(id) ?: return
        fallbackReadAnchorBeforeDelete(event.id, event.serverTime)
        deleteById(event.id)
    }

    /** 0-based reverse-list index: strict complement of pagingSource ORDER BY serverTime DESC, id DESC. */
    @Query(
        """SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId
          AND (serverTime > :serverTime OR (serverTime = :serverTime AND id > :id))"""
    )
    suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int

    /**
     * Server time of the OLDEST message from someone else (isSelf = 0) newer than [after], or null
     * if none. Anchors the "new messages" divider + unread badge to real incoming messages: your own
     * sent messages must never trip the unread UI, since you have obviously read what you just sent.
     */
    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId AND isSelf = 0 AND serverTime > :after")
    suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long?

    // FTS4 external-content search over (text, sender). :query is already sanitized (each token
    // quoted + prefixed with *) by SearchRepository. Chat kinds only; optional buffer scope.
    @Query(
        """
        SELECT m.*, b.displayName AS bufferDisplayName, n.name AS networkName,
               ni.caseMapping AS caseMapping, ni.chanTypes AS chanTypes
        FROM messages m
        JOIN messages_fts f ON m.id = f.rowid
        JOIN buffers b ON b.id = m.bufferId
        JOIN networks n ON n.id = b.networkId
        LEFT JOIN network_identity ni ON ni.networkId = b.networkId
        WHERE f.messages_fts MATCH :query
          AND (:bufferId IS NULL OR m.bufferId = COALESCE(
              (SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = :bufferId),
              :bufferId
          ))
          AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
        ORDER BY m.serverTime DESC LIMIT 200
        """
    )
    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>>  // @Query over messages_fts MATCH

    @Query(
        """SELECT m.sender, m.text, m.serverTime, m.isSelf
           FROM messages m JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId AND lower(b.name) = 'bouncerserv'
           ORDER BY m.serverTime DESC, m.id DESC LIMIT 100""",
    )
    fun observeBouncerTranscript(networkId: Long): Flow<List<BouncerTranscriptRow>>
}

data class SearchHit(
    @Embedded val message: MessageEntity,
    val bufferDisplayName: String,
    val networkName: String,
    val caseMapping: String? = null,
    val chanTypes: String? = null,
)

data class MessageBoundaryRow(val msgid: String?, val serverTime: Long?)

data class TimelineBoundaryRow(val eventId: TimelineEventId, val timestamp: Long)

data class BouncerTranscriptRow(
    val sender: String,
    val text: String,
    val serverTime: Long,
    val isSelf: Boolean,
)

@Dao
interface ComposerDraftDao {
    @Query("SELECT * FROM composer_drafts WHERE roomId = :roomId")
    fun observe(roomId: RoomId): Flow<ComposerDraftEntity?>

    @Query("SELECT * FROM composer_drafts WHERE roomId = :roomId")
    suspend fun byRoom(roomId: RoomId): ComposerDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: ComposerDraftEntity)

    @Query("DELETE FROM composer_drafts WHERE roomId = :roomId")
    suspend fun delete(roomId: RoomId): Int

    @Query(
        """DELETE FROM composer_drafts WHERE roomId = :roomId AND text = :text
           AND updatedAt = :updatedAt AND (
               replyToEventId = :replyToEventId OR
               (replyToEventId IS NULL AND :replyToEventId IS NULL)
           )""",
    )
    suspend fun deleteIfUnchanged(
        roomId: RoomId,
        text: String,
        replyToEventId: TimelineEventId?,
        updatedAt: Long,
    ): Int

    @Query(
        """UPDATE composer_drafts SET replyToEventId = :winnerId
           WHERE replyToEventId = :loserId""",
    )
    suspend fun repointReplies(loserId: TimelineEventId, winnerId: TimelineEventId)
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    fun observe(bufferId: Long): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    suspend fun allNow(bufferId: Long): List<MemberEntity>

    @Query(
        """SELECT m.bufferId FROM members m
           JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId AND m.nick = :nick""",
    )
    suspend fun bufferIdsForNick(networkId: Long, nick: String): List<Long>

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

    @Query(
        """SELECT * FROM reactions
           WHERE bufferId = :bufferId AND targetMsgid = :targetMsgid
             AND actorKey IN (:actorKeys) AND emoji = :emoji
           ORDER BY id LIMIT 1""",
    )
    suspend fun find(
        bufferId: Long,
        targetMsgid: String,
        actorKeys: List<String>,
        emoji: String,
    ): ReactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ReactionEntity)

    @Query(
        """DELETE FROM reactions
           WHERE bufferId = :bufferId AND targetMsgid = :targetMsgid
             AND actorKey = :actorKey AND emoji = :emoji""",
    )
    suspend fun delete(
        bufferId: Long,
        targetMsgid: String,
        actorKey: String,
        emoji: String,
    ): Int

    @Query(
        """DELETE FROM reactions
           WHERE bufferId = :bufferId AND targetMsgid = :targetMsgid AND emoji = :emoji
              AND (
                  (:deleteBaseActor = 1 AND actorKey = :baseActorKey) OR
                  (actorKey >= :legacyPrefix AND actorKey < :legacyUpperBound)
              )""",
    )
    suspend fun deleteActorAliases(
        bufferId: Long,
        targetMsgid: String,
        baseActorKey: String,
        deleteBaseActor: Boolean,
        legacyPrefix: String,
        legacyUpperBound: String,
        emoji: String,
    ): Int
}

@Dao
interface UserDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(u: UserEntity)

    @Query("SELECT * FROM users WHERE networkId = :nid AND nick = :nick")
    suspend fun byNick(nid: Long, nick: String): UserEntity?

    @Query("SELECT * FROM users WHERE networkId = :nid AND nick = :nick")
    fun observeByNick(nid: Long, nick: String): Flow<UserEntity?>

    @Query("DELETE FROM users WHERE networkId = :nid AND nick = :nick")
    suspend fun delete(nid: Long, nick: String)

    /** Carry the cached identity attached to a nick while retaining any richer destination fields. */
    @Transaction
    suspend fun rekey(nid: Long, from: String, to: String) {
        if (from == to) return
        val source = byNick(nid, from) ?: return
        val destination = byNick(nid, to)
        upsert(
            source.copy(
                nick = to,
                username = source.username ?: destination?.username,
                account = source.account ?: destination?.account,
                hostmask = source.hostmask ?: destination?.hostmask,
                realname = source.realname ?: destination?.realname,
            ),
        )
        delete(nid, from)
    }
}

@Dao
interface RoomAliasDao {
    @Query(
        """SELECT * FROM room_aliases
           WHERE networkId = :networkId AND namespace = :namespace AND value = :value""",
    )
    suspend fun byValue(
        networkId: Long,
        namespace: RoomAliasNamespace,
        value: String,
    ): RoomAliasEntity?

    @Query("SELECT * FROM room_aliases WHERE roomId = :roomId")
    suspend fun forRoom(roomId: RoomId): List<RoomAliasEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(alias: RoomAliasEntity): Long

    @Query(
        """DELETE FROM room_aliases WHERE roomId = :roomId
           AND namespace IN ('VERIFIED_NICK', 'PROVISIONAL_NICK')""",
    )
    suspend fun deleteQueryAliases(roomId: RoomId)

    @Query("UPDATE room_aliases SET roomId = :winnerId WHERE roomId = :loserId")
    suspend fun repoint(loserId: RoomId, winnerId: RoomId)

    @Query("UPDATE messages SET bufferId = :winnerId WHERE bufferId = :loserId")
    suspend fun moveEvents(loserId: RoomId, winnerId: RoomId)

    @Query(
        """INSERT OR IGNORE INTO members(bufferId, nick, prefixes)
           SELECT :winnerId, nick, prefixes FROM members WHERE bufferId = :loserId""",
    )
    suspend fun copyMembers(loserId: RoomId, winnerId: RoomId)

    @Query("DELETE FROM members WHERE bufferId = :loserId")
    suspend fun deleteMembers(loserId: RoomId)

    @Query(
        """INSERT OR IGNORE INTO reactions(
               bufferId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
           )
           SELECT :winnerId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
           FROM reactions WHERE bufferId = :loserId""",
    )
    suspend fun copyReactions(loserId: RoomId, winnerId: RoomId)

    @Query("DELETE FROM reactions WHERE bufferId = :loserId")
    suspend fun deleteReactions(loserId: RoomId)

    @Query("UPDATE buffers SET redirectToRoomId = :winnerId WHERE redirectToRoomId = :loserId")
    suspend fun repointRedirects(loserId: RoomId, winnerId: RoomId)

    @Query("UPDATE buffers SET redirectToRoomId = :winnerId WHERE id = :loserId")
    suspend fun markRedirect(loserId: RoomId, winnerId: RoomId)

    @Query(
        """UPDATE room_aliases SET roomId = :roomId, verified = 1
           WHERE networkId = :networkId AND namespace = 'VERIFIED_NICK' AND value = :value""",
    )
    suspend fun moveVerifiedNick(
        networkId: Long,
        value: String,
        roomId: RoomId,
    ): Int
}

@Dao
interface CanonicalTimelineDao {
    @Query(
        """SELECT m.* FROM event_aliases a
           JOIN messages m ON m.id = a.timelineEventId
           WHERE a.networkId = :networkId AND a.namespace = :namespace AND a.value = :value""",
    )
    suspend fun eventByAlias(
        networkId: Long,
        namespace: EventAliasNamespace,
        value: ByteArray,
    ): TimelineEventEntity?

    @Query(
        """SELECT a.* FROM event_aliases a
           WHERE a.networkId = :networkId AND a.namespace = :namespace AND a.value = :value""",
    )
    suspend fun aliasByValue(
        networkId: Long,
        namespace: EventAliasNamespace,
        value: ByteArray,
    ): EventAliasEntity?

    @Query("SELECT * FROM event_aliases WHERE timelineEventId = :eventId")
    suspend fun aliasesFor(eventId: TimelineEventId): List<EventAliasEntity>

    @Insert
    suspend fun insertEvent(event: TimelineEventEntity): TimelineEventId

    @Update
    suspend fun updateEvent(event: TimelineEventEntity)

    @Query("SELECT * FROM messages WHERE id = :eventId")
    suspend fun eventById(eventId: TimelineEventId): TimelineEventEntity?

    @Query("SELECT * FROM messages WHERE bufferId = :roomId ORDER BY id")
    suspend fun eventsForRoom(roomId: RoomId): List<TimelineEventEntity>

    @Query(
        """SELECT batchExactOrdinal FROM event_observations
           WHERE timelineEventId = :eventId AND batchExactOrdinal IS NOT NULL
           ORDER BY id LIMIT 1""",
    )
    suspend fun batchExactOrdinal(eventId: TimelineEventId): Int?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAliasIgnore(alias: EventAliasEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEventRedirect(redirect: EventRedirectEntity)

    @Query("UPDATE event_redirects SET canonicalEventId = :winnerId WHERE canonicalEventId = :loserId")
    suspend fun repointEventRedirects(loserId: TimelineEventId, winnerId: TimelineEventId)

    @Query(
        "SELECT COALESCE((SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId), :eventId)",
    )
    suspend fun canonicalEventId(eventId: TimelineEventId): TimelineEventId

    @Query("SELECT losingEventId FROM event_redirects WHERE canonicalEventId = :eventId")
    suspend fun losingEventIds(eventId: TimelineEventId): List<TimelineEventId>

    @Query(
        """DELETE FROM event_aliases
           WHERE networkId = :networkId AND namespace = :namespace AND value = :value
             AND timelineEventId = :eventId""",
    )
    suspend fun deleteOwnedAlias(
        networkId: Long,
        namespace: EventAliasNamespace,
        value: ByteArray,
        eventId: TimelineEventId,
    )

    @Insert
    suspend fun insertObservation(observation: EventObservationEntity): Long

    @Query("SELECT COALESCE(MAX(receiveOrder), 0) + 1 FROM event_observations WHERE networkId = :networkId")
    suspend fun nextReceiveOrder(networkId: Long): Long

    @Query(
        """SELECT m.* FROM messages m
           WHERE m.bufferId = :roomId
             AND m.kind = :kind
             AND m.normalizedActor = :sender
             AND m.text = :text
             AND m.msgid IS NULL
             AND m.serverTimeAuthoritative = 0
             AND EXISTS (
                 SELECT 1 FROM event_observations o
                 WHERE o.timelineEventId = m.id
                   AND o.origin IN ('LIVE', 'PUSH')
                   AND o.timeProvenance = 'LOCAL_CLOCK'
             )
           ORDER BY m.id
           LIMIT 2""",
    )
    suspend fun provisionalCandidates(
        roomId: RoomId,
        kind: MessageKind,
        sender: String,
        text: String,
    ): List<TimelineEventEntity>

    @Query(
        """SELECT m.* FROM messages m
           WHERE m.bufferId = :roomId
             AND m.kind = :kind
             AND m.normalizedActor = :sender
             AND m.text = :text
             AND m.msgid IS NULL
             AND m.isSelf = 1
             AND (
                 (m.serverTimeAuthoritative = 1 AND m.serverTime BETWEEN :lo AND :hi)
                 OR EXISTS (
                     SELECT 1 FROM event_observations o
                     WHERE o.timelineEventId = m.id
                       AND o.timeProvenance = 'LOCAL_CLOCK'
                 )
             )
           ORDER BY m.id
           LIMIT 2""",
    )
    suspend fun selfIdentityFreeCandidates(
        roomId: RoomId,
        kind: MessageKind,
        sender: String,
        text: String,
        lo: Long,
        hi: Long,
    ): List<TimelineEventEntity>

    @Query(
        """SELECT m.* FROM messages m
           WHERE m.bufferId = :roomId AND m.kind = 'JOIN' AND m.isSelf = 1
             AND m.eventKey IS NULL
             AND m.normalizedActor = :sender AND m.text = :text
             AND m.serverTime > COALESCE(
                 (SELECT MAX(d.serverTime) FROM messages d
                  WHERE d.bufferId = :roomId AND d.isSelf = 1 AND d.kind IN ('PART', 'KICK')),
                 -9223372036854775808
             )
           ORDER BY m.id
           LIMIT 2""",
    )
    suspend fun selfJoinCycleCandidates(
        roomId: RoomId,
        sender: String,
        text: String,
    ): List<TimelineEventEntity>

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :roomId AND kind = :kind AND isSelf = 1
             AND normalizedActor = :sender AND text = :text
             AND msgid IS NULL AND pendingLabel IS NOT NULL
           ORDER BY failed, id
           LIMIT 2""",
    )
    suspend fun orderedSelfCandidates(
        roomId: RoomId,
        kind: MessageKind,
        sender: String,
        text: String,
    ): List<TimelineEventEntity>

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :roomId AND kind = :kind
             AND normalizedActor = :sender AND text = :text
             AND msgid IS NOT NULL AND serverTime BETWEEN :lowerTime AND :upperTime
           ORDER BY id
           LIMIT 2""",
    )
    suspend fun durableDeliveryCandidates(
        roomId: RoomId,
        kind: MessageKind,
        sender: String,
        text: String,
        lowerTime: Long,
        upperTime: Long,
    ): List<TimelineEventEntity>

    @Query("UPDATE event_aliases SET timelineEventId = :winnerId WHERE timelineEventId = :loserId")
    suspend fun repointAliases(loserId: TimelineEventId, winnerId: TimelineEventId)

    @Query("UPDATE event_observations SET timelineEventId = :winnerId WHERE timelineEventId = :loserId")
    suspend fun repointObservations(loserId: TimelineEventId, winnerId: TimelineEventId)

    @Query("UPDATE messages SET replyToEventId = :winnerId WHERE replyToEventId = :loserId")
    suspend fun repointReplies(loserId: TimelineEventId, winnerId: TimelineEventId)

    @Query("UPDATE reactions SET targetEventId = :winnerId WHERE targetEventId = :loserId")
    suspend fun repointReactions(loserId: TimelineEventId, winnerId: TimelineEventId)

    @Query("DELETE FROM messages WHERE id = :eventId")
    suspend fun deleteEvent(eventId: TimelineEventId)

    @Query(
        """UPDATE messages SET replyToEventId = :parentId
           WHERE bufferId = :bufferId AND replyToMsgid = :msgid AND replyToEventId IS NULL""",
    )
    suspend fun resolveReplies(bufferId: RoomId, msgid: String, parentId: TimelineEventId)

    @Query(
        """UPDATE OR REPLACE reactions SET bufferId = :bufferId, targetEventId = :parentId
           WHERE targetMsgid = :msgid
             AND (targetEventId IS NULL OR (targetEventId = :parentId AND bufferId != :bufferId))
             AND bufferId IN (
                 SELECT candidate.id FROM buffers candidate
                 JOIN buffers parent ON parent.id = :bufferId
                 WHERE candidate.networkId = parent.networkId
             )""",
    )
    suspend fun resolveReactions(bufferId: RoomId, msgid: String, parentId: TimelineEventId)

    @Query("UPDATE messages SET soundHandled = 1 WHERE id = :eventId AND soundHandled = 0")
    suspend fun claimSound(eventId: TimelineEventId): Int

    @Query(
        """UPDATE messages SET notificationClaimed = 1, notificationClaimOwner = :owner
           WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId),
               :eventId
           ) AND notificationHandled = 0 AND notificationClaimed = 0""",
    )
    suspend fun claimNotification(eventId: TimelineEventId, owner: String): Int

    @Query(
        """UPDATE messages SET notificationHandled = 1, notificationClaimed = 0,
               notificationClaimOwner = NULL
           WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId),
               :eventId
           )""",
    )
    suspend fun completeNotification(eventId: TimelineEventId)

    @Query(
        """UPDATE messages SET notificationClaimed = 0, notificationClaimOwner = NULL
           WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId),
               :eventId
           ) AND notificationHandled = 0""",
    )
    suspend fun releaseNotification(eventId: TimelineEventId)

    @Query(
        """UPDATE messages SET notificationClaimed = 0, notificationClaimOwner = NULL
           WHERE notificationHandled = 0 AND notificationClaimed = 1
             AND (notificationClaimOwner IS NULL OR notificationClaimOwner != :currentOwner)""",
    )
    suspend fun releaseInterruptedNotificationClaims(currentOwner: String)

    @Query(
        """SELECT m.* FROM messages m
           JOIN buffers b ON b.id = m.bufferId
           WHERE m.notificationHandled = 0 AND m.notificationClaimed = 0
             AND m.isSelf = 0 AND m.failed = 0
             AND EXISTS (
                 SELECT 1 FROM event_observations o
                 WHERE o.timelineEventId = m.id AND o.origin IN ('LIVE', 'PUSH')
             )
             AND (
                 (m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                    AND b.type != 'SERVER' AND (b.type = 'QUERY' OR m.hasMention = 1))
                 OR (m.kind = 'INVITE' AND m.inviteState IN ('PENDING', 'FAILED'))
             )
           ORDER BY m.serverTime, m.id
           LIMIT :limit""",
    )
    suspend fun pendingNotifications(limit: Int): List<TimelineEventEntity>
}

@Dao
interface HistoryCursorDao {
    @Query("SELECT * FROM history_cursors WHERE roomId = :roomId")
    suspend fun byRoom(roomId: RoomId): HistoryCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: HistoryCursorEntity)

    @Query("DELETE FROM history_cursors WHERE roomId = :roomId")
    suspend fun delete(roomId: RoomId)

    @Query(
        """INSERT OR REPLACE INTO history_cursors(
               roomId, newestMsgid, newestServerTime, oldestMsgid, oldestServerTime, historyComplete
           ) VALUES (
               :roomId,
               (SELECT newestMsgid FROM history_cursors WHERE roomId = :roomId),
               (SELECT newestServerTime FROM history_cursors WHERE roomId = :roomId),
               (SELECT oldestMsgid FROM history_cursors WHERE roomId = :roomId),
               (SELECT oldestServerTime FROM history_cursors WHERE roomId = :roomId),
               1
           )""",
    )
    suspend fun markComplete(roomId: RoomId)

    @Query(
        """SELECT lastSuccessfulSync FROM network_history_cursors
           WHERE networkId = :networkId AND serverDerived = 1""",
    )
    suspend fun networkLastSuccessfulSync(networkId: Long): Long?

    @Query(
        "INSERT OR REPLACE INTO network_history_cursors(networkId, lastSuccessfulSync, serverDerived) " +
            "VALUES (:networkId, :timestamp, 1)",
    )
    suspend fun setNetworkLastSuccessfulSync(networkId: Long, timestamp: Long)

    @Query("DELETE FROM network_history_cursors WHERE networkId = :networkId")
    suspend fun clearNetwork(networkId: Long)

    @Query("DELETE FROM history_cursors WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: RoomId)
}

@Dao
interface ConnectionGenerationDao {
    @Query("SELECT generation FROM connection_generations WHERE networkId = :networkId")
    suspend fun current(networkId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConnectionGenerationEntity)

    @Transaction
    suspend fun next(networkId: Long): Long {
        val next = (current(networkId) ?: 0L) + 1L
        upsert(ConnectionGenerationEntity(networkId, next))
        return next
    }
}

@Dao
interface AppStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(state: AppStateEntity): Long

    @Query("SELECT COUNT(*) FROM app_state WHERE `key` = :key")
    suspend fun contains(key: String): Int

    @Query("SELECT `key` FROM app_state WHERE `key` LIKE :pattern ORDER BY `key`")
    suspend fun keysLike(pattern: String): List<String>

    @Query("DELETE FROM app_state WHERE `key` = :key")
    suspend fun delete(key: String)
}
