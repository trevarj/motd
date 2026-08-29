package io.github.trevarj.motd.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.SkipQueryVerification
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import kotlinx.coroutines.flow.Flow

// Room is the authoritative boundary for fixed persistence queries. Prefer typed entity or
// projection methods here; keep raw SQL at callers only when predicates are genuinely dynamic.

@Dao
interface NetworkDao {
    // `ordering` is the user's manual drawer order (see NetworkDao.applyOrder). `id` is only a
    // tiebreak for rows that have not been ranked yet — a merge import can reuse a position — so the
    // list is never at the mercy of SQLite's arbitrary resolution of an all-ties sort.
    @Query("SELECT * FROM networks ORDER BY ordering, id")
    fun observeAll(): Flow<List<NetworkEntity>>

    @Query("SELECT * FROM networks WHERE autoConnect = 1")
    suspend fun connectable(): List<NetworkEntity>

    @Query("SELECT * FROM networks WHERE id = :id")
    suspend fun byId(id: Long): NetworkEntity?

    @Insert
    suspend fun insert(n: NetworkEntity): Long

    @Update
    suspend fun update(n: NetworkEntity)

    @Query("SELECT COALESCE(MAX(ordering), -1) FROM networks")
    suspend fun maxOrdering(): Int

    @Query("SELECT id FROM networks ORDER BY ordering, id")
    suspend fun idsInOrder(): List<Long>

    @Query("UPDATE networks SET serverIconUrl = :url WHERE id = :id")
    suspend fun setServerIconUrl(
        id: Long,
        url: String?,
    )

    @Query("UPDATE networks SET ordering = :ordering WHERE id = :id")
    suspend fun setOrdering(
        id: Long,
        ordering: Int,
    )

    /**
     * Insert [n] at the end of the manual order. New networks must land somewhere deterministic;
     * keeping the entity default (0) would drop every newly added network at the *top* of the
     * drawer once real positions exist.
     */
    @Transaction
    suspend fun insertLast(n: NetworkEntity): Long = insert(n.copy(ordering = maxOrdering() + 1))

    /**
     * Renumber the whole table so [orderedIds] becomes the stored order, in one transaction so a
     * reorder is all-or-nothing. Unknown ids are ignored and rows missing from [orderedIds] keep
     * their relative order after the listed ones — the drawer omits a child whose parent row is
     * gone, and an invisible row must not be able to renumber the visible ones.
     */
    @Transaction
    suspend fun applyOrder(orderedIds: List<Long>) {
        val known = idsInOrder()
        val requested = orderedIds.filterTo(LinkedHashSet(), known::contains)
        val full = requested + known.filterNot(requested::contains)
        full.forEachIndexed { index, id -> setOrdering(id, index) }
    }

    @Query("UPDATE networks SET host = :host, port = :port, nick = :nick WHERE id = :id")
    suspend fun updateBouncerConnection(
        id: Long,
        host: String,
        port: Int,
        nick: String,
    )

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
    suspend fun updateSelfNick(
        networkId: Long,
        selfNick: String,
    ): Int

    /** Registration normally creates the row; retain a durable fallback for an orphan self-NICK. */
    @Transaction
    suspend fun setSelfNick(
        networkId: Long,
        selfNick: String,
    ) {
        if (updateSelfNick(networkId, selfNick) == 0) {
            upsert(NetworkIdentityEntity(networkId = networkId, selfNick = selfNick))
        }
    }
}

@Dao
interface NetworkIgnoreDao {
    @Query("SELECT * FROM network_ignores WHERE networkId = :networkId ORDER BY enabled DESC, createdAt DESC, id DESC")
    fun observeForNetwork(networkId: Long): Flow<List<NetworkIgnoreEntity>>

    @Query("SELECT * FROM network_ignores WHERE networkId = :networkId AND enabled = 1 ORDER BY id")
    suspend fun enabledForNetwork(networkId: Long): List<NetworkIgnoreEntity>

    @Query("SELECT networkId FROM network_ignores WHERE id = :id")
    suspend fun networkIdFor(id: Long): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ignore: NetworkIgnoreEntity): Long

    @Query("UPDATE network_ignores SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ): Int

    @Query("DELETE FROM network_ignores WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
interface ChatFolderDao {
    @Query("SELECT * FROM chat_folders ORDER BY ordering, id")
    fun observeFolders(): Flow<List<ChatFolderEntity>>

    @Query("SELECT * FROM chat_folders ORDER BY ordering, id")
    suspend fun allFolders(): List<ChatFolderEntity>

    @Query("SELECT * FROM chat_folders WHERE id = :id")
    suspend fun folder(id: Long): ChatFolderEntity?

    @Query("SELECT * FROM chat_folders WHERE normalizedName = :normalizedName")
    suspend fun folderByName(normalizedName: String): ChatFolderEntity?

    @Insert
    suspend fun insertFolder(folder: ChatFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: ChatFolderEntity)

    @Query("DELETE FROM chat_folders WHERE id = :id")
    suspend fun deleteFolder(id: Long): Int

    @Query("UPDATE chat_folders SET ordering = :ordering WHERE id = :id")
    suspend fun setOrdering(
        id: Long,
        ordering: Int,
    )

    @Query("UPDATE chat_folders SET expanded = :expanded WHERE id = :id")
    suspend fun setExpanded(
        id: Long,
        expanded: Boolean,
    ): Int

    @Query("UPDATE buffers SET folderId = :folderId WHERE id = COALESCE((SELECT redirectToRoomId FROM buffers WHERE id = :bufferId), :bufferId) AND type IN ('CHANNEL', 'QUERY')")
    suspend fun assign(
        bufferId: Long,
        folderId: Long?,
    ): Int

    @Query("SELECT * FROM buffers WHERE id = COALESCE((SELECT redirectToRoomId FROM buffers WHERE id = :bufferId), :bufferId)")
    suspend fun canonicalRoom(bufferId: Long): RoomEntity?

    @Query("SELECT * FROM buffers WHERE folderId IS NOT NULL AND type IN ('CHANNEL', 'QUERY') AND redirectToRoomId IS NULL")
    suspend fun assignedRooms(): List<RoomEntity>

    @Query("SELECT COUNT(*) FROM buffers WHERE folderId = :folderId AND type IN ('CHANNEL', 'QUERY') AND redirectToRoomId IS NULL")
    suspend fun assignedCount(folderId: Long): Int

    @Query("DELETE FROM ignored_auto_group_patterns")
    suspend fun clearIgnored()

    @Query("DELETE FROM ignored_auto_group_patterns WHERE networkId = :networkId AND normalizedPrefix = :normalizedPrefix")
    suspend fun deleteIgnored(
        networkId: Long,
        normalizedPrefix: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIgnored(pattern: IgnoredAutoGroupPatternEntity)

    @Query("SELECT * FROM ignored_auto_group_patterns ORDER BY networkId, normalizedPrefix")
    fun observeIgnored(): Flow<List<IgnoredAutoGroupPatternEntity>>

    @Query("SELECT * FROM ignored_auto_group_patterns ORDER BY networkId, normalizedPrefix")
    suspend fun allIgnored(): List<IgnoredAutoGroupPatternEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPending(assignment: PendingFolderAssignmentEntity)

    @Query("SELECT * FROM pending_folder_assignments ORDER BY networkId, chatType, identityKind, identityValue")
    suspend fun allPending(): List<PendingFolderAssignmentEntity>

    @Query("SELECT * FROM pending_folder_assignments WHERE networkId = :networkId AND chatType = :chatType AND identityKind = :identityKind AND identityValue = :identityValue")
    suspend fun pending(
        networkId: Long,
        chatType: BufferType,
        identityKind: FolderIdentityKind,
        identityValue: String,
    ): PendingFolderAssignmentEntity?

    @Query("DELETE FROM pending_folder_assignments WHERE networkId = :networkId AND chatType = :chatType AND identityKind = :identityKind AND identityValue = :identityValue")
    suspend fun deletePending(
        networkId: Long,
        chatType: BufferType,
        identityKind: FolderIdentityKind,
        identityValue: String,
    ): Int

    @Query("DELETE FROM pending_folder_assignments")
    suspend fun clearPending()

    @Query("UPDATE buffers SET folderId = NULL")
    suspend fun clearAssignments()

    @Query("DELETE FROM chat_folders")
    suspend fun clearFolders()
}

@Dao
interface BufferDao {
    // Chat-list projection: each non-SERVER buffer joins one newest preview-eligible message by
    // identity. Presence events are timeline-only and never become previews or activity.
    // Unread/mention counts remain chat kinds only; self messages never count as unread.
    // Counts are capped at 1000 (the badge renders 999+) so a buffer holding a huge unread
    // backlog cannot turn every invalidation into a full-buffer scan.
    // Sort: pinned first, then latest KNOWN activity DESC (nulls last), where "known" is the newer
    // of the local preview row and what CHATHISTORY TARGETS last advertised. Discovery therefore
    // re-sorts the list on its first response, before any per-room page has been fetched.
    // `advertisedUnread` is the count-less companion of that: the server says this room moved past
    // both the read floor and everything held locally, but the rows are not here yet. It carries no
    // number because discovery reports a timestamp, not a count, and it extinguishes itself when
    // the fetched page or a mark-read catches the room up. Compared at SECOND granularity, exactly
    // like `reachedAdvertisedTolerance` and the wave planner: stored server-time tags can carry
    // second precision while TARGETS advertises milliseconds, and a strict comparison here would
    // light the cue on rooms the pass has already proven converged. What no comparison can settle —
    // an advertisement replay never serves — is retired at the source by
    // `EventProcessor.clampAdvertisedActivity`.
    @Transaction
    @Query(
        """
        WITH visible_buffers AS (
            SELECT base.*,
                   COALESCE(anchor.timelineOrder, COALESCE(base.localReadAnchorEventId, 0))
                       AS localReadAnchorOrder
            FROM buffers base
            LEFT JOIN messages anchor ON anchor.id = base.localReadAnchorEventId
            WHERE base.type != 'SERVER' AND base.dismissed = 0
              AND base.pendingCloseAt IS NULL AND base.redirectToRoomId IS NULL
        )
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
            b.archived AS archived,
            b.avatarOverrideModel AS avatarOverrideModel,
            b.folderId AS folderId,
            lm.text AS lastMessageText,
            lm.sender AS lastMessageSender,
            lm.serverTime AS lastMessageTime,
            (SELECT COUNT(*) FROM (SELECT 1 FROM messages m WHERE m.bufferId = b.id
                AND (
                    m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), COALESCE(b.localUnreadFloorTime, 0))
                    OR (
                        m.serverTime = b.localReadAnchorTime
                        AND COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime
                        AND (
                            m.timelineOrder > b.localReadAnchorOrder
                            OR (m.timelineOrder = b.localReadAnchorOrder
                                AND m.id > COALESCE(b.localReadAnchorEventId, 0))
                        )
                    )
                )
                AND m.isSelf = 0
                AND (
                    m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                    OR (m.kind = 'DCC_TRANSFER' AND m.eventPayload IS NOT NULL)
                ) LIMIT 1000)) AS unreadCount,
            (SELECT COUNT(*) FROM (SELECT 1 FROM messages m WHERE m.bufferId = b.id
                AND (
                    m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), COALESCE(b.localUnreadFloorTime, 0))
                    OR (
                        m.serverTime = b.localReadAnchorTime
                        AND COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime
                        AND (
                            m.timelineOrder > b.localReadAnchorOrder
                            OR (m.timelineOrder = b.localReadAnchorOrder
                                AND m.id > COALESCE(b.localReadAnchorEventId, 0))
                        )
                    )
                )
                AND m.isSelf = 0
                AND m.hasMention = 1
                AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION') LIMIT 1000)) AS mentionCount,
            EXISTS(SELECT 1 FROM history_gaps g WHERE g.roomId = b.id
                AND (g.newerServerTime > MAX(COALESCE(b.localReadAnchorTime, 0),
                    COALESCE(b.localUnreadFloorTime, 0)) OR
                    (g.newerServerTime = MAX(COALESCE(b.localReadAnchorTime, 0),
                        COALESCE(b.localUnreadFloorTime, 0)) AND
                     COALESCE(b.localUnreadFloorTime, -9223372036854775808) <
                         COALESCE(b.localReadAnchorTime, 0) AND
                     (g.recoverable = 0 AND g.olderServerTime = g.newerServerTime OR
                      g.newerEventId IS NULL OR g.newerTimelineOrder IS NULL OR
                      g.newerTimelineOrder > b.localReadAnchorOrder OR
                      (g.newerTimelineOrder = b.localReadAnchorOrder AND
                       g.newerEventId > COALESCE(b.localReadAnchorEventId, 0)))))) OR
            (b.historyComplete = 0 AND b.oldestFetchedTime >
                MAX(COALESCE(b.localReadAnchorTime, 0),
                    COALESCE(b.localUnreadFloorTime, 0))) AS unreadCountIncomplete,
            EXISTS(SELECT 1 FROM history_gaps g WHERE g.roomId = b.id
                AND (g.newerServerTime > MAX(COALESCE(b.localReadAnchorTime, 0),
                    COALESCE(b.localUnreadFloorTime, 0)) OR
                    (g.newerServerTime = MAX(COALESCE(b.localReadAnchorTime, 0),
                        COALESCE(b.localUnreadFloorTime, 0)) AND
                     COALESCE(b.localUnreadFloorTime, -9223372036854775808) <
                         COALESCE(b.localReadAnchorTime, 0) AND
                     (g.recoverable = 0 AND g.olderServerTime = g.newerServerTime OR
                      g.newerEventId IS NULL OR g.newerTimelineOrder IS NULL OR
                      g.newerTimelineOrder > b.localReadAnchorOrder OR
                      (g.newerTimelineOrder = b.localReadAnchorOrder AND
                       g.newerEventId > COALESCE(b.localReadAnchorEventId, 0)))))) OR
            (b.historyComplete = 0 AND b.oldestFetchedTime >
                MAX(COALESCE(b.localReadAnchorTime, 0),
                    COALESCE(b.localUnreadFloorTime, 0))) AS mentionCountIncomplete,
            (b.advertisedLatestTime IS NOT NULL
                AND b.advertisedLatestTime / 1000 > MAX(COALESCE(b.localReadAnchorTime, 0),
                    COALESCE(b.localUnreadFloorTime, 0)) / 1000
                AND b.advertisedLatestTime / 1000 > COALESCE(lm.serverTime, 0) / 1000)
                AS advertisedUnread
        FROM visible_buffers b
        JOIN networks n ON n.id = b.networkId
        LEFT JOIN network_identity ni ON ni.networkId = b.networkId
        LEFT JOIN messages lm ON lm.id = (
            SELECT m.id FROM messages m
            WHERE m.bufferId = b.id
              AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'AWAY', 'BACK', 'NETSPLIT', 'NETJOIN')
            ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC
            LIMIT 1
        )
        ORDER BY b.pinned DESC,
                 (COALESCE(lm.serverTime, 0) = 0) ASC,
                 COALESCE(lm.serverTime, 0) DESC,
                 b.id DESC
        """,
    )
    fun observeChatList(): Flow<List<ChatListRow>>

    // QUERY-only materialization keeps channel traffic from invalidating this always-on projection.
    @Query(
        """SELECT b.networkId AS networkId,
                  COALESCE(b.wireTarget, b.displayName) AS displayName,
                  b.pinned AS pinned,
                  b.monitorActivityTime AS lastMessageTime
           FROM buffers b
           WHERE b.type = 'QUERY' AND b.dismissed = 0
             AND b.pendingCloseAt IS NULL AND b.redirectToRoomId IS NULL""",
    )
    fun observeMonitorQueryRows(): Flow<List<MonitorQueryRow>>

    /** Recompute one QUERY's MONITOR rank after canonical insert/update/delete or room merge. */
    @Query(
        """UPDATE buffers SET monitorActivityTime = (
               SELECT m.serverTime FROM messages m
               WHERE m.bufferId = buffers.id
                 AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'AWAY', 'BACK', 'NETSPLIT', 'NETJOIN')
               ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1
           ) WHERE id = :bufferId AND type = 'QUERY'""",
    )
    suspend fun refreshMonitorActivity(bufferId: Long): Int

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
    suspend fun byName(
        nid: Long,
        normName: String,
    ): BufferEntity?

    @Query(
        """SELECT * FROM buffers WHERE networkId = :networkId
           AND (wireTarget = :target COLLATE NOCASE OR displayName = :target COLLATE NOCASE)
           AND redirectToRoomId IS NULL LIMIT 1""",
    )
    suspend fun byWireTarget(
        networkId: Long,
        target: String,
    ): BufferEntity?

    @Query("UPDATE buffers SET displayName = :displayName, wireTarget = COALESCE(wireTarget, :wireTarget) WHERE id = :roomId")
    suspend fun updateSidecarDisplayName(
        roomId: RoomId,
        wireTarget: String,
        displayName: String,
    ): Int

    @Query("UPDATE buffers SET sidecarSecurity = :security WHERE id = :roomId")
    suspend fun updateSidecarSecurity(
        roomId: RoomId,
        security: io.github.trevarj.motd.sidecar.SidecarSecurityState?,
    ): Int

    @Query("SELECT id FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND pendingCloseAt IS NULL")
    suspend fun channelIds(networkId: Long): List<Long>

    @Query("SELECT COALESCE(wireTarget, displayName) FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND joined = 1 AND pendingCloseAt IS NULL ORDER BY id")
    suspend fun joinedChannelNames(networkId: Long): List<String>

    /** Authoritative self-JOIN persistence for channel-browser buttons, retaining server spelling. */
    @Query(
        """SELECT COALESCE(wireTarget, displayName) FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND joined = 1
           AND pendingCloseAt IS NULL AND redirectToRoomId IS NULL ORDER BY id""",
    )
    fun observeJoinedChannelNames(networkId: Long): Flow<List<String>>

    /** Lightweight picker rows for outgoing IRC INVITE actions. */
    @Query(
        """SELECT id AS bufferId, networkId, displayName, avatarOverrideModel
           FROM buffers WHERE networkId = :networkId AND type = 'CHANNEL' AND joined = 1
             AND pendingCloseAt IS NULL AND redirectToRoomId IS NULL
           ORDER BY pinned DESC, displayName COLLATE NOCASE, id""",
    )
    fun observeJoinedChannels(networkId: Long): Flow<List<JoinedChannelRow>>

    /**
     * History-resync targets. The soju console is the one SERVER row soju answers CHATHISTORY for, so
     * it is admitted here — role-scoped, since elsewhere that nick is an ordinary user's query — and
     * carries its server spelling like any other conversation.
     */
    @Query(
        """SELECT id,
                  CASE WHEN type = 'SERVER' AND lower(name) != 'bouncerserv' THEN name ELSE COALESCE(wireTarget, displayName) END AS name,
                  pinned
           FROM buffers WHERE networkId = :networkId
             AND (type != 'SERVER' OR (lower(name) = 'bouncerserv' AND EXISTS (
                   SELECT 1 FROM networks n WHERE n.id = buffers.networkId AND n.role = 'BOUNCER_ROOT')))
             AND pendingCloseAt IS NULL AND redirectToRoomId IS NULL ORDER BY id""",
    )
    suspend fun openTargets(networkId: Long): List<BufferTargetRow>

    @Query(
        """SELECT id AS bufferId, displayName AS displayName, type AS type, muted AS muted
           FROM buffers WHERE networkId = :networkId AND type IN ('CHANNEL', 'QUERY')
             AND dismissed = 0 AND pendingCloseAt IS NULL AND redirectToRoomId IS NULL
           ORDER BY muted DESC, displayName COLLATE NOCASE""",
    )
    fun observeNetworkBufferTools(networkId: Long): Flow<List<NetworkBufferToolRow>>

    @Query(
        """SELECT id FROM buffers WHERE networkId = :networkId AND pendingCloseAt IS NULL
           AND (name = :target COLLATE NOCASE OR displayName = :target COLLATE NOCASE OR wireTarget = :target COLLATE NOCASE) LIMIT 1""",
    )
    suspend fun idForTarget(
        networkId: Long,
        target: String,
    ): Long?

    @Query("SELECT * FROM buffers WHERE pendingCloseAt IS NOT NULL AND type = 'CHANNEL' ORDER BY pendingCloseAt, id")
    suspend fun pendingChannelCloses(): List<BufferEntity>

    /** Mark a CHANNEL for an asynchronous server-side close, preserving its first attempt time. */
    @Query(
        "UPDATE buffers SET pendingCloseAt = :timestamp, folderId = NULL " +
            "WHERE id = :id AND type = 'CHANNEL' AND pendingCloseAt IS NULL",
    )
    suspend fun markPendingClose(
        id: Long,
        timestamp: Long,
    ): Int

    @Query(
        """SELECT b.id AS bufferId,
                   CASE WHEN b.type = 'SERVER' THEN b.name ELSE COALESCE(b.wireTarget, b.displayName) END AS target,
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
                      (m.timelineOrder < COALESCE((SELECT timelineOrder FROM messages
                           WHERE id = b.localReadAnchorEventId), COALESCE(b.localReadAnchorEventId, 0))
                       OR (m.timelineOrder = COALESCE((SELECT timelineOrder FROM messages
                           WHERE id = b.localReadAnchorEventId), COALESCE(b.localReadAnchorEventId, 0))
                           AND m.id <= COALESCE(b.localReadAnchorEventId, 0))))
                 )
               ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1
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
    suspend fun setPinned(
        id: Long,
        pinned: Boolean,
    )

    @Query("UPDATE buffers SET muted = :muted WHERE id = :id")
    suspend fun writeMuted(
        id: Long,
        muted: Boolean,
    )

    /** Archive through a stale redirect shell to the current canonical conversation. */
    @Query(
        """UPDATE buffers SET archived = :archived
           WHERE id = (SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = :requestedId)
             AND type IN ('CHANNEL', 'QUERY')""",
    )
    suspend fun setArchived(
        requestedId: RoomId,
        archived: Boolean,
    ): Int

    /** A peer's live chat revives an unmuted conversation only. */
    @Query("UPDATE buffers SET archived = 0 WHERE id = :id AND muted = 0")
    suspend fun unarchiveIfUnmuted(id: RoomId): Int

    /**
     * Genuinely new peer activity delivered off the live socket (history catch-up, replay, push)
     * also revives an unmuted conversation, but only when the inserted row is unread and newer
     * than every other stored chat row — backfill, gap fills, and duplicate transports must
     * retain the user's choice.
     */
    @Query(
        """UPDATE buffers SET archived = 0
           WHERE id = :id AND archived = 1 AND muted = 0
             AND :serverTime > MAX(COALESCE(localReadAnchorTime, 0), COALESCE(localUnreadFloorTime, 0))
             AND NOT EXISTS(
                 SELECT 1 FROM messages m
                 WHERE m.bufferId = :id AND m.id != :eventId
                   AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                   AND (m.serverTime > :serverTime
                        OR (m.serverTime = :serverTime AND m.timelineOrder > :timelineOrder)
                        OR (m.serverTime = :serverTime AND m.timelineOrder = :timelineOrder
                            AND m.id > :eventId)))""",
    )
    suspend fun unarchiveIfUnmutedForNewPeerActivity(
        id: RoomId,
        eventId: Long,
        serverTime: Long,
        timelineOrder: Long,
    ): Int

    /** Write via a stale redirect shell to its current canonical conversation. */
    @Query(
        """UPDATE buffers SET layoutDensityOverride = :layout
           WHERE id = (
               SELECT COALESCE(redirectToRoomId, id)
               FROM buffers
               WHERE id = :requestedId
           )""",
    )
    suspend fun setLayoutDensityOverride(
        requestedId: RoomId,
        layout: LayoutDensity?,
    ): Int

    /** Write via a stale redirect shell to its current canonical conversation. */
    @Query(
        """UPDATE buffers SET presenceModeOverride = :mode
           WHERE id = (
               SELECT COALESCE(redirectToRoomId, id)
               FROM buffers
               WHERE id = :requestedId
           )""",
    )
    suspend fun setPresenceModeOverride(
        requestedId: RoomId,
        mode: PresenceMode?,
    ): Int

    /** Write through durable redirects; SERVER rows reject conversation avatar overrides. */
    @Query(
        """UPDATE buffers SET avatarOverrideModel = :model
           WHERE id = (
               SELECT COALESCE(redirectToRoomId, id) FROM buffers WHERE id = :requestedId
           ) AND type IN ('CHANNEL', 'QUERY')""",
    )
    suspend fun setAvatarOverride(
        requestedId: RoomId,
        model: String?,
    ): Int

    @Query("SELECT avatarOverrideModel FROM buffers WHERE avatarOverrideModel LIKE 'file://%'")
    suspend fun localAvatarModels(): List<String>

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
    suspend fun isDiscardedMessageId(
        id: RoomId,
        msgid: String,
    ): Boolean

    @Query(
        """INSERT OR IGNORE INTO discarded_message_ids(roomId, msgid)
           SELECT :toId, msgid FROM discarded_message_ids WHERE roomId = :fromId""",
    )
    suspend fun copyDiscardedMessageIds(
        fromId: RoomId,
        toId: RoomId,
    )

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
    suspend fun advanceLocalUnreadFloor(
        id: Long,
        timestamp: Long,
    )

    /**
     * Backlog imported into a room with no prior durable content starts read: seed the floor once,
     * losing to any existing local anchor, floor, or server-derived marker already applied.
     */
    @Query(
        """UPDATE buffers SET localUnreadFloorTime = :floorTime
           WHERE id = :id AND localUnreadFloorTime IS NULL
             AND localReadAnchorTime IS NULL AND localReadAnchorEventId IS NULL""",
    )
    suspend fun seedHistoryUnreadFloor(
        id: RoomId,
        floorTime: Long,
    )

    /** Put the local floor back where [setMuted] found it; the undo half of unmute suppression. */
    @Query("UPDATE buffers SET localUnreadFloorTime = :timestamp WHERE id = :id")
    suspend fun restoreLocalUnreadFloor(
        id: Long,
        timestamp: Long?,
    )

    /**
     * Unmuting drops the backlog that piled up while muted: the local floor jumps to the newest
     * incoming chat so the badge does not dump messages the user muted away. That is deliberate but
     * lossy, so it returns a [MuteBacklogSuppression] whenever the advance actually hid unread
     * activity — callers surface it and route the undo through [restoreLocalUnreadFloor]. Null means
     * nothing visible changed (muting, or nothing unread above the effective floor).
     */
    @Transaction
    suspend fun setMuted(
        id: Long,
        muted: Boolean,
    ): MuteBacklogSuppression? {
        var suppression: MuteBacklogSuppression? = null
        if (!muted) {
            val before = rawById(id)
            val latest = latestIncomingChatTime(id)
            if (latest != null) {
                // Unread is counted above MAX(read anchor, floor), so only chat newer than that
                // effective floor is being hidden; a no-op advance must stay silent.
                val effectiveFloor =
                    maxOf(
                        before?.localReadAnchorTime ?: 0L,
                        before?.localUnreadFloorTime ?: 0L,
                    )
                if (latest > effectiveFloor) {
                    suppression = MuteBacklogSuppression(id, before?.localUnreadFloorTime)
                }
                advanceLocalUnreadFloor(id, latest)
            }
        }
        writeMuted(id, muted)
        return suppression
    }

    @Query("UPDATE buffers SET topic = :topic, topicSetBy = :setBy WHERE id = :id")
    suspend fun setTopic(
        id: Long,
        topic: String,
        setBy: String?,
    )

    @Query("UPDATE buffers SET joined = :joined WHERE id = :id")
    suspend fun setJoined(
        id: Long,
        joined: Boolean,
    )

    @Query(
        """UPDATE buffers SET name = :normalizedName, displayName = :displayName
           WHERE id = :id AND type = 'CHANNEL'""",
    )
    suspend fun renameChannel(
        id: RoomId,
        normalizedName: String,
        displayName: String,
    ): Int

    @Query("UPDATE buffers SET name = :normalizedName WHERE id = :id")
    suspend fun renameRoomKey(
        id: RoomId,
        normalizedName: String,
    ): Int

    @Query("UPDATE buffers SET membershipCycle = membershipCycle + 1 WHERE id = :id")
    suspend fun advanceMembershipCycle(id: RoomId)

    @Query("UPDATE buffers SET historyComplete = 1 WHERE id = :id")
    suspend fun markHistoryComplete(id: Long)

    @Query("UPDATE buffers SET oldestFetchedTime = :oldestFetchedTime WHERE id = :id")
    suspend fun setOldestFetchedTime(
        id: Long,
        oldestFetchedTime: Long?,
    )

    @Query("UPDATE buffers SET readMarkerTime = :ts WHERE id = :id AND (readMarkerTime IS NULL OR readMarkerTime < :ts)")
    suspend fun advanceReadMarker(
        id: Long,
        ts: Long,
    )

    /**
     * Forward-only high-water mark of what CHATHISTORY TARGETS advertised for this room.
     *
     * Guarded in SQL rather than by a read-modify-write: discovery pages from several passes (a
     * reconnect catch-up and the paced backfill) can report the same room out of order, and the
     * older report must not walk the sort key or the advertised-unread cue backwards.
     */
    @Query(
        """UPDATE buffers SET advertisedLatestTime = :ts
           WHERE id = :id AND (advertisedLatestTime IS NULL OR advertisedLatestTime < :ts)""",
    )
    suspend fun advanceAdvertisedLatest(
        id: RoomId,
        ts: Long,
    )

    /**
     * Clamp the advertised high-water mark down onto this room's newest VISIBLE row (null when it
     * has none) — the one downward move the column allows, and only for a caller holding a server
     * response that proves the room can never reach the advertisement (see
     * [io.github.trevarj.motd.data.sync.EventProcessor.clampAdvertisedActivity]).
     *
     * The subquery is `observeChatList`'s preview row, kind filter included, because the advertised
     * cue is defined against exactly that row: TARGETS timestamps the newest SERVER event, which is
     * routinely a JOIN or an event ingestion filters away, and a value stranded above the newest
     * previewable row is an unread dot for something the list can never show.
     *
     * [provenLatest] is what the caller's response actually settled, and the column is left alone
     * above it: a stored value from a newer discovery than this one has not been disproved by
     * anything, and lowering it would be the backwards walk [advanceAdvertisedLatest] exists to
     * prevent.
     *
     * The second-granularity guard is the cue's own comparison, so a room that is already converged
     * within stored-timestamp precision is not written at all — and, since Room invalidates on rows
     * actually updated, does not re-run the chat list for every settled target of every pass.
     */
    @Query(
        """UPDATE buffers SET advertisedLatestTime = (
               SELECT m.serverTime FROM messages m
               WHERE m.bufferId = :id
                 AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'AWAY', 'BACK', 'NETSPLIT', 'NETJOIN')
               ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC
               LIMIT 1)
           WHERE id = :id AND advertisedLatestTime IS NOT NULL
             AND advertisedLatestTime <= :provenLatest
             AND advertisedLatestTime / 1000 > COALESCE((
               SELECT m.serverTime FROM messages m
               WHERE m.bufferId = :id
                 AND m.kind NOT IN ('JOIN', 'PART', 'QUIT', 'AWAY', 'BACK', 'NETSPLIT', 'NETJOIN')
               ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC
               LIMIT 1), 0) / 1000""",
    )
    suspend fun clampAdvertisedLatestToVisible(
        id: RoomId,
        provenLatest: Long,
    )

    @Query(
        """UPDATE buffers SET localReadAnchorTime = :serverTime, localReadAnchorEventId = :eventId
           WHERE id = :id AND (
               localReadAnchorTime IS NULL OR localReadAnchorTime < :serverTime OR
               (localReadAnchorTime = :serverTime AND (
                   COALESCE((SELECT timelineOrder FROM messages WHERE id = localReadAnchorEventId),
                       COALESCE(localReadAnchorEventId, 0)) <
                       COALESCE((SELECT timelineOrder FROM messages WHERE id = :eventId), :eventId)
                   OR (
                       COALESCE((SELECT timelineOrder FROM messages WHERE id = localReadAnchorEventId),
                           COALESCE(localReadAnchorEventId, 0)) =
                           COALESCE((SELECT timelineOrder FROM messages WHERE id = :eventId), :eventId)
                       AND COALESCE(localReadAnchorEventId, 0) < :eventId
                   )
               ))
           )""",
    )
    suspend fun advanceLocalReadAnchor(
        id: RoomId,
        serverTime: Long,
        eventId: TimelineEventId,
    )

    @Query(
        """UPDATE buffers SET localReadAnchorTime = :serverTime
           WHERE localReadAnchorEventId = :eventId""",
    )
    suspend fun retimeLocalReadAnchor(
        eventId: TimelineEventId,
        serverTime: Long,
    )

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
    // avoid orphaned rows.
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
           ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1""",
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
        val candidates =
            listOf(
                room.historyDiscardedThroughMsgid to room.historyDiscardedThroughTime,
                cursor?.newestMsgid to cursor?.newestServerTime,
                latest?.msgid to latest?.serverTime,
                null to room.readMarkerTime,
            ).filter { (msgid, time) -> msgid != null || time != null }
        val floorTime = candidates.mapNotNull { it.second }.maxOrNull()
        val floorMsgid =
            candidates
                .asSequence()
                .filter { it.first != null }
                .maxByOrNull { it.second ?: Long.MIN_VALUE }
                ?.first

        update(
            room.copy(
                pinned = false,
                muted = false,
                folderId = null,
                localReadAnchorTime = floorTime,
                localReadAnchorEventId = null,
                oldestFetchedTime = null,
                historyComplete = false,
                dismissed = true,
                historyDiscardedThroughMsgid = floorMsgid,
                historyDiscardedThroughTime = floorTime,
                monitorActivityTime = null,
            ),
        )
        deleteMembersForBuffer(room.id)
        deleteReactionsForBuffer(room.id)
        deleteDraftForBuffer(room.id)
        rememberDiscardedMessageIds(room.id)
        deleteMessagesForBuffer(room.id)
        deleteHistoryGapsForBuffer(room.id)
        upsertHistoryCursor(
            HistoryCursorEntity(
                roomId = room.id,
                newestMsgid = floorMsgid,
                newestServerTime = floorTime,
            ),
        )
    }

    @Query("DELETE FROM history_gaps WHERE roomId = :bufferId")
    suspend fun deleteHistoryGapsForBuffer(bufferId: RoomId)
}

/** Minimal joined-channel projection for outgoing IRC invitations. */
data class JoinedChannelRow(
    val bufferId: Long,
    val networkId: Long,
    val displayName: String,
    val avatarOverrideModel: String? = null,
)

/** Projection for the chat list screen. */
data class ChatListRow(
    val bufferId: Long,
    val networkId: Long,
    val networkName: String,
    val displayName: String,
    val type: BufferType,
    val pinned: Boolean,
    val muted: Boolean,
    val lastMessageText: String?,
    val lastMessageSender: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int,
    val mentionCount: Int,
    val caseMapping: String? = null,
    val chanTypes: String? = null,
    val archived: Boolean = false,
    val avatarOverrideModel: String? = null,
    val folderId: Long? = null,
    val unreadCountIncomplete: Boolean = false,
    val mentionCountIncomplete: Boolean = false,
    /**
     * CHATHISTORY TARGETS advertises activity newer than both the read floor and every row this
     * device holds: there is something unread here whose rows have not arrived yet. Count-less by
     * construction — discovery reports a timestamp, never a number. A badge only: the list sorts on
     * stored rows alone, because an advertisement can describe an event this device will never show
     * (a JOIN, a filtered event) and promoting on it bounces the row back down once the fetch
     * proves that.
     */
    val advertisedUnread: Boolean = false,
)

/** Batched fool-aware replacement for chat-list preview and badge fields. */
data class ChatListVisibilityRow(
    val bufferId: Long,
    val lastMessageText: String?,
    val lastMessageSender: String?,
    val lastMessageTime: Long?,
    val unreadCount: Int,
    val mentionCount: Int,
)

/** Minimal query-buffer projection for MONITOR target selection. */
data class MonitorQueryRow(
    val networkId: Long,
    val displayName: String,
    val pinned: Boolean,
    val lastMessageTime: Long?,
)

/** Canonical invitation projection for the chat-list inbox. Payload decoding stays above Room. */
data class InvitationEventRow(
    val messageId: Long,
    val bufferId: Long,
    val networkId: Long,
    val networkName: String,
    val text: String,
    val eventPayload: String,
    val inviteState: InviteState,
    val serverTime: Long,
)

/** A history pass's open-buffer input; [pinned] is an ordering signal, not a filter. */
data class BufferTargetRow(
    val id: Long,
    val name: String,
    val pinned: Boolean = false,
)

data class NetworkBufferToolRow(
    val bufferId: Long,
    val displayName: String,
    val type: BufferType,
    val muted: Boolean,
)

data class BufferReadMarkerRow(
    val bufferId: Long,
    val target: String,
    val timestamp: Long?,
    val eventId: TimelineEventId? = null,
)

/** Per-nick last-spoke time in a channel (PRIVMSG/NOTICE/ACTION, isSelf=0). Projection, not an entity. */
data class LastSpokeRow(
    val nick: String,
    val lastSpokeAt: Long,
)

/**
 * A backlog an unmute hid by advancing the local mute floor (see [BufferDao.setMuted]), reported so
 * the UI can say it happened and offer an undo. [previousFloorTime] is the floor to put back; null
 * means the buffer had no floor before the unmute.
 */
data class MuteBacklogSuppression(
    val bufferId: Long,
    val previousFloorTime: Long?,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, timelineOrder DESC, id DESC")
    fun pagingSource(bufferId: Long): PagingSource<Int, MessageEntity>

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime, timelineOrder, id")
    suspend fun historyRowsForMerge(bufferId: Long): List<MessageEntity>

    /** Dynamic visibility predicates must run inside Room so placeholder counts match page rows. */
    @RawQuery(observedEntities = [MessageEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MessageEntity>

    /**
     * One keyset page of the global feed, same projection and joins as [search].
     * `GlobalFeedPagingSource` owns paging and observes the joined tables itself, so this stays a
     * plain read: no COUNT, no OFFSET.
     */
    @RawQuery
    suspend fun globalFeedPage(query: SupportSQLiteQuery): List<SearchHit>

    @RawQuery
    suspend fun rawMessage(query: SupportSQLiteQuery): MessageEntity?

    /**
     * As [rawMessage], but re-run whenever the row set changes. Seam placement needs the newest
     * row a visibility predicate ADMITS, which moves on any insert rather than only on the gap
     * table's own writes.
     */
    @RawQuery(observedEntities = [MessageEntity::class])
    fun observeRawMessage(query: SupportSQLiteQuery): Flow<MessageEntity?>

    @RawQuery
    suspend fun rawCount(query: SupportSQLiteQuery): Int

    @RawQuery
    suspend fun rawChatListVisibility(query: SupportSQLiteQuery): List<ChatListVisibilityRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllRaw(msgs: List<MessageEntity>): List<Long>

    @Query("UPDATE messages SET timelineOrder = :eventId WHERE id = :eventId AND timelineOrder = 0")
    suspend fun initializeTimelineOrder(eventId: TimelineEventId)

    /** Keep direct/import insertion on the same stable tie-order invariant as canonical ingestion. */
    @Transaction
    suspend fun insertAll(msgs: List<MessageEntity>): List<Long> =
        insertAllRaw(msgs).also { ids ->
            ids.filter { it > 0 }.forEach { initializeTimelineOrder(it) }
        }

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
        """SELECT m.id AS messageId, m.bufferId AS bufferId, b.networkId AS networkId,
                  n.name AS networkName, m.text AS text, m.eventPayload AS eventPayload,
                  m.inviteState AS inviteState, m.serverTime AS serverTime
           FROM messages m
           JOIN buffers b ON b.id = m.bufferId
           JOIN networks n ON n.id = b.networkId
           LEFT JOIN event_redirects redirect ON redirect.losingEventId = m.id
           WHERE m.kind = 'INVITE' AND m.eventPayload IS NOT NULL
             AND m.inviteState IN ('PENDING', 'JOINING', 'FAILED', 'JOINED', 'DISMISSED')
             AND redirect.losingEventId IS NULL
             AND b.pendingCloseAt IS NULL AND b.redirectToRoomId IS NULL
           ORDER BY CASE m.inviteState
                      WHEN 'PENDING' THEN 0 WHEN 'JOINING' THEN 0 WHEN 'FAILED' THEN 0 ELSE 1
                    END,
                    m.serverTime DESC, m.timelineOrder DESC, m.id DESC""",
    )
    fun observeInvitations(): Flow<List<InvitationEventRow>>

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :bufferId AND id != :excludeEventId
              AND isSelf = 0 AND failed = 0 AND (
                  serverTime > :afterTime OR (serverTime = :afterTime AND (
                      timelineOrder > COALESCE((SELECT timelineOrder FROM messages WHERE id = :afterEventId), :afterEventId)
                      OR (timelineOrder = COALESCE((SELECT timelineOrder FROM messages WHERE id = :afterEventId), :afterEventId)
                          AND id > :afterEventId)
                  ))
              )
             AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
             AND (:queryRoom = 1 OR hasMention = 1)
           ORDER BY serverTime DESC, timelineOrder DESC, id DESC
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
    suspend fun byEventKey(
        bufferId: Long,
        eventKey: String,
    ): MessageEntity?

    @Query(
        """UPDATE messages SET inviteState = :toState
           WHERE id = :id AND inviteState = :fromState""",
    )
    suspend fun compareAndSetInviteState(
        id: Long,
        fromState: InviteState,
        toState: InviteState,
    ): Int

    @Query("UPDATE messages SET inviteState = :state WHERE id = :id")
    suspend fun setInviteState(
        id: Long,
        state: InviteState,
    )

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
    suspend fun failInvite(
        id: Long,
        reason: String,
    ): Int

    @Query(
        """UPDATE messages SET inviteState = 'FAILED', text = text || ' — Join failed: ' || :reason
           WHERE bufferId = :bufferId AND kind = 'INVITE' AND inviteState = 'JOINING'""",
    )
    suspend fun failJoiningInvites(
        bufferId: Long,
        reason: String,
    ): Int

    @Query(
        """UPDATE messages SET inviteState = 'FAILED', text = text || ' — Join failed: ' || :reason
           WHERE kind = 'INVITE' AND inviteState = 'JOINING'
           AND bufferId IN (SELECT id FROM buffers WHERE networkId = :networkId)""",
    )
    suspend fun failJoiningInvitesForNetwork(
        networkId: Long,
        reason: String,
    ): Int

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND pendingLabel = :label")
    suspend fun byPendingLabel(
        bufferId: Long,
        label: String,
    ): MessageEntity?

    @Query(
        """SELECT m.* FROM messages m
           JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId
             AND m.pendingLabel = :label
             AND m.msgid IS NULL
             AND m.failed = 0
           LIMIT 1""",
    )
    suspend fun pendingByNetworkLabel(
        networkId: Long,
        label: String,
    ): MessageEntity?

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :bufferId AND pendingLabel IS NOT NULL AND msgid IS NULL AND failed = 0
           ORDER BY id DESC LIMIT 1""",
    )
    suspend fun latestPendingRow(bufferId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:eventIds) ORDER BY id")
    suspend fun byIds(eventIds: List<TimelineEventId>): List<MessageEntity>

    @Query(
        """UPDATE messages SET failed = 1
           WHERE bufferId = :bufferId AND pendingLabel = :label AND msgid IS NULL""",
    )
    suspend fun failIfStillPending(
        bufferId: Long,
        label: String,
    ): Int

    // Mark the most recent un-echoed self-send in this buffer failed. Used when the server
    // rejects a send with a "not in channel" / "cannot send" numeric (403/404/442) that
    // carries no label to correlate with a specific pending row.
    @Query(
        """UPDATE messages SET failed = 1
           WHERE id = (
             SELECT id FROM messages
             WHERE bufferId = :bufferId AND pendingLabel IS NOT NULL AND msgid IS NULL AND failed = 0
             ORDER BY id DESC LIMIT 1
           )""",
    )
    suspend fun failLatestPending(bufferId: Long): Int

    @Query(
        """UPDATE messages SET failed = 1
           WHERE id IN (:eventIds) AND pendingLabel IS NOT NULL AND msgid IS NULL""",
    )
    suspend fun failPending(eventIds: List<TimelineEventId>): Int

    @Query(
        """UPDATE messages SET pendingLabel = :label, failed = 0
           WHERE id = :eventId AND isSelf = 1 AND failed = 1 AND msgid IS NULL""",
    )
    suspend fun beginRetry(
        eventId: TimelineEventId,
        label: String,
    ): Int

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
    suspend fun confirmIfStillPending(
        bufferId: Long,
        label: String,
    ): Int

    @Update
    suspend fun update(m: MessageEntity)

    @Query("SELECT MAX(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun newestTime(bufferId: Long): Long?

    @Query(
        """SELECT normalizedActor AS nick, MAX(serverTime) AS lastSpokeAt
           FROM messages
           WHERE bufferId = :bufferId AND isSelf = 0
             AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
           GROUP BY normalizedActor""",
    )
    fun observeLastSpoke(bufferId: Long): Flow<List<LastSpokeRow>>

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 1")
    suspend fun newestMessage(bufferId: RoomId): MessageEntity?

    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId")
    suspend fun oldestTime(bufferId: Long): Long?

    @Query(
        """SELECT m.msgid,
                  CASE WHEN m.serverTimeAuthoritative = 1 THEN m.serverTime ELSE NULL END AS serverTime
           FROM messages m
           WHERE m.bufferId = :bufferId
             AND (m.msgid IS NOT NULL OR m.serverTimeAuthoritative = 1)
           ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1""",
    )
    suspend fun latestBoundary(bufferId: Long): MessageBoundaryRow?

    @Query(
        """SELECT m.msgid,
                  CASE WHEN m.serverTimeAuthoritative = 1 THEN m.serverTime ELSE NULL END AS serverTime
           FROM messages m
           WHERE m.bufferId = :bufferId
             AND (m.msgid IS NOT NULL OR m.serverTimeAuthoritative = 1)
           ORDER BY m.serverTime ASC, m.timelineOrder ASC, m.id ASC LIMIT 1""",
    )
    suspend fun oldestBoundary(bufferId: Long): MessageBoundaryRow?

    @Query("SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId")
    suspend fun countForBuffer(bufferId: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE bufferId = :bufferId AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION'))")
    suspend fun hasStoredChat(bufferId: Long): Boolean

    @Query(
        """SELECT b.id AS bufferId,
                   CASE WHEN b.type = 'SERVER' THEN b.name ELSE COALESCE(b.wireTarget, b.displayName) END AS target,
                  m.serverTime AS timestamp, m.id AS eventId
           FROM buffers b JOIN messages m ON m.id = (
               SELECT newest.id FROM messages newest
               WHERE newest.bufferId = b.id AND newest.isSelf = 0
                 AND newest.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
               ORDER BY newest.serverTime DESC, newest.timelineOrder DESC, newest.id DESC LIMIT 1
           )
           WHERE b.id IN (:bufferIds) AND b.type != 'SERVER'""",
    )
    suspend fun latestIncomingMarkers(bufferIds: List<Long>): List<BufferReadMarkerRow>

    @Query(
        """SELECT id AS eventId, serverTime AS timestamp
           FROM messages WHERE bufferId = :bufferId AND serverTimeAuthoritative = 1
             AND kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
             AND (serverTime < :serverTime OR (serverTime = :serverTime AND (
                 timelineOrder < COALESCE((SELECT timelineOrder FROM messages WHERE id = :eventId), :eventId)
                 OR (timelineOrder = COALESCE((SELECT timelineOrder FROM messages WHERE id = :eventId), :eventId)
                     AND id <= :eventId)
             )))
           ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 1""",
    )
    suspend fun authoritativeChatAtOrBefore(
        bufferId: RoomId,
        serverTime: Long,
        eventId: TimelineEventId,
    ): TimelineBoundaryRow?

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND msgid = :msgid LIMIT 1")
    suspend fun byMsgid(
        bufferId: Long,
        msgid: String,
    ): MessageEntity?

    @Query("SELECT * FROM messages WHERE bufferId = :bufferId AND dedupKey = :dedupKey LIMIT 1")
    suspend fun byDedupKey(
        bufferId: Long,
        dedupKey: String,
    ): MessageEntity?

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
          ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 2""",
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
          ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 8""",
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
    fun observeByMsgid(
        bufferId: Long,
        msgid: String,
    ): Flow<MessageEntity?>

    /**
     * Observe a single local row's server msgid by primary key. Emits null while the row is still
     * pending (own optimistic send not yet echoed) and the durable msgid once the echo promotes it
     * in place. Drives the deferred-reaction queue: a react tapped on a still-pending own message
     * waits on this flow until its msgid lands, then sends the TAGMSG.
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
     * pending/confirmed-local row (echo heuristic). A still-pending row (pendingLabel set)
     * is a local send awaiting THIS echo, so it matches regardless of the [lo]..[hi] window — its
     * serverTime is a device timestamp and cannot be compared to the server's echo time under clock
     * skew (would otherwise duplicate the self-send). Confirmed rows must fall inside the window so
     * an old identical self message isn't matched. Pending rows rank first. A suspend @Query runs
     * off the main thread and is transaction-safe (live onChat + HistoryBatch withTransaction).
     */
    @Query(
        """SELECT * FROM messages WHERE bufferId = :bufferId AND isSelf = 1 AND text = :text
          AND (pendingLabel IS NOT NULL OR serverTime BETWEEN :lo AND :hi)
          ORDER BY (pendingLabel IS NOT NULL) DESC, serverTime DESC, timelineOrder DESC, id DESC LIMIT 1""",
    )
    suspend fun findSelfEchoCandidate(
        bufferId: Long,
        text: String,
        lo: Long,
        hi: Long,
    ): MessageEntity?

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
          AND msgid IS NULL ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 1""",
    )
    suspend fun findSelfMsgidlessCandidate(
        bufferId: Long,
        text: String,
    ): MessageEntity?

    @Query(
        """UPDATE buffers SET
               localReadAnchorTime = (SELECT serverTime FROM messages
                   WHERE bufferId = buffers.id AND id != :id AND
                     (serverTime < :serverTime OR (serverTime = :serverTime AND (
                         timelineOrder < COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
                         OR (timelineOrder = COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
                             AND id < :id)
                     )))
                   ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 1),
               localReadAnchorEventId = (SELECT id FROM messages
                   WHERE bufferId = buffers.id AND id != :id AND
                     (serverTime < :serverTime OR (serverTime = :serverTime AND (
                         timelineOrder < COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
                         OR (timelineOrder = COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
                             AND id < :id)
                     )))
                   ORDER BY serverTime DESC, timelineOrder DESC, id DESC LIMIT 1)
           WHERE localReadAnchorEventId = :id""",
    )
    suspend fun fallbackReadAnchorBeforeDelete(
        id: TimelineEventId,
        serverTime: Long,
    )

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun deleteWithAnchorFallback(id: TimelineEventId) {
        val event = byCanonicalId(id) ?: return
        fallbackReadAnchorBeforeDelete(event.id, event.serverTime)
        deleteById(event.id)
    }

    /** 0-based reverse-list index: strict complement of pagingSource ORDER BY serverTime DESC, timelineOrder DESC, id DESC. */
    @Query(
        """SELECT COUNT(*) FROM messages WHERE bufferId = :bufferId
          AND (serverTime > :serverTime OR (serverTime = :serverTime AND (
              timelineOrder > COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
              OR (timelineOrder = COALESCE((SELECT timelineOrder FROM messages WHERE id = :id), :id)
                  AND id > :id)
          )))""",
    )
    suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
    ): Int

    /**
     * Server time of the OLDEST message from someone else (isSelf = 0) newer than [after], or null
     * if none. Anchors the "new messages" divider + unread badge to real incoming messages: your own
     * sent messages must never trip the unread UI, since you have obviously read what you just sent.
     */
    @Query("SELECT MIN(serverTime) FROM messages WHERE bufferId = :bufferId AND isSelf = 0 AND serverTime > :after")
    suspend fun firstUnreadOtherTime(
        bufferId: Long,
        after: Long,
    ): Long?

    // FTS4 external-content search over (text, sender). :query is already sanitized (each token
    // quoted + prefixed with *) by SearchRepository. Chat kinds only; optional buffer scope.
    @Query(
        """
        SELECT m.*, b.displayName AS bufferDisplayName, n.name AS networkName,
               b.type AS bufferType, b.networkId AS networkId, b.avatarOverrideModel AS avatarOverrideModel,
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
        """,
    )
    fun search(query: String, bufferId: Long?): Flow<List<SearchHit>> // @Query over messages_fts MATCH

    @Query(
        """SELECT m.sender, m.text, m.serverTime, m.isSelf
           FROM messages m JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId AND lower(b.name) = 'bouncerserv'
           ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 100""",
    )
    fun observeBouncerTranscript(networkId: Long): Flow<List<BouncerTranscriptRow>>
}

/**
 * One message plus its conversation tag and the identity columns needed to apply
 * [io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy] to a row from an unknown network.
 */
data class SearchHit(
    @Embedded val message: MessageEntity,
    val bufferDisplayName: String,
    val networkName: String,
    val bufferType: BufferType,
    val networkId: Long,
    val avatarOverrideModel: String? = null,
    val caseMapping: String? = null,
    val chanTypes: String? = null,
)

data class MessageBoundaryRow(
    val msgid: String?,
    val serverTime: Long?,
)

data class TimelineBoundaryRow(
    val eventId: TimelineEventId,
    val timestamp: Long,
)

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
    suspend fun repointReplies(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )
}

@Dao
interface MemberDao {
    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    fun observe(bufferId: Long): Flow<List<MemberEntity>>

    // Nick-only projection. Mention coloring observes the roster eagerly on every chat open, so it
    // must not materialize whole member rows for a busy channel.
    @Query("SELECT nick FROM members WHERE bufferId = :bufferId")
    fun observeNicks(bufferId: Long): Flow<List<String>>

    @Query("SELECT * FROM members WHERE bufferId = :bufferId")
    suspend fun allNow(bufferId: Long): List<MemberEntity>

    @Query(
        """SELECT m.bufferId FROM members m
           JOIN buffers b ON b.id = m.bufferId
           WHERE b.networkId = :networkId AND m.nick = :nick""",
    )
    suspend fun bufferIdsForNick(
        networkId: Long,
        nick: String,
    ): List<Long>

    @Query("DELETE FROM members WHERE bufferId = :bufferId")
    suspend fun clear(bufferId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(members: List<MemberEntity>)

    // Atomic member snapshot swap (NAMES replay): clear then bulk-insert in one transaction.
    @Transaction
    suspend fun replaceAll(
        bufferId: Long,
        members: List<MemberEntity>,
    ) {
        clear(bufferId)
        insertAll(members)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MemberEntity)

    @Query(
        """UPDATE members SET isBot = :isBot WHERE nick = :nick AND bufferId IN (
           SELECT id FROM buffers WHERE networkId = :networkId
        )""",
    )
    suspend fun setBot(
        networkId: Long,
        nick: String,
        isBot: Boolean,
    )

    @Query("DELETE FROM members WHERE bufferId = :bufferId AND nick = :nick")
    suspend fun remove(
        bufferId: Long,
        nick: String,
    )
}

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE bufferId = :bufferId AND targetMsgid IN (:msgids)")
    fun observeFor(
        bufferId: Long,
        msgids: List<String>,
    ): Flow<List<ReactionEntity>>

    // Buffer-scoped observe with no per-msgid IN(...) list. Scrolling back accumulates >999 loaded
    // msgids, which would overflow SQLite's bind-variable limit in observeFor and crash; scoping by
    // bufferId keeps one stable query and the repository filters to the visible window in memory
    // . A buffer's reaction table is small relative to its message history.
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

    @Query("DELETE FROM reactions WHERE bufferId = :bufferId AND targetMsgid = :targetMsgid")
    suspend fun deleteForTarget(
        bufferId: Long,
        targetMsgid: String,
    )

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
    suspend fun byNick(
        nid: Long,
        nick: String,
    ): UserEntity?

    @Query("SELECT * FROM users WHERE networkId = :nid AND nick = :nick")
    fun observeByNick(
        nid: Long,
        nick: String,
    ): Flow<UserEntity?>

    @Query("UPDATE users SET realname = :displayName WHERE networkId = :nid AND nick = :nick")
    suspend fun updateDisplayName(
        nid: Long,
        nick: String,
        displayName: String,
    ): Int

    @Query("DELETE FROM users WHERE networkId = :nid AND nick = :nick")
    suspend fun delete(
        nid: Long,
        nick: String,
    )

    /** Carry the cached identity attached to a nick while retaining any richer destination fields. */
    @Transaction
    suspend fun rekey(
        nid: Long,
        from: String,
        to: String,
    ) {
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
                isBot = source.isBot || destination?.isBot == true,
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

    @Query("SELECT * FROM room_aliases ORDER BY networkId, roomId, namespace, value")
    suspend fun allNow(): List<RoomAliasEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(alias: RoomAliasEntity): Long

    @Query(
        """DELETE FROM room_aliases WHERE roomId = :roomId
           AND namespace IN ('VERIFIED_NICK', 'PROVISIONAL_NICK')""",
    )
    suspend fun deleteQueryAliases(roomId: RoomId)

    @Query(
        """DELETE FROM room_aliases
           WHERE networkId = :networkId AND namespace = :namespace AND value = :value""",
    )
    suspend fun deleteAlias(
        networkId: Long,
        namespace: RoomAliasNamespace,
        value: String,
    ): Int

    @Query("UPDATE room_aliases SET roomId = :winnerId WHERE roomId = :loserId")
    suspend fun repoint(
        loserId: RoomId,
        winnerId: RoomId,
    )

    @Query("UPDATE messages SET bufferId = :winnerId WHERE bufferId = :loserId")
    suspend fun moveEvents(
        loserId: RoomId,
        winnerId: RoomId,
    )

    @Query(
        """INSERT OR IGNORE INTO members(bufferId, nick, prefixes)
           SELECT :winnerId, nick, prefixes FROM members WHERE bufferId = :loserId""",
    )
    suspend fun copyMembers(
        loserId: RoomId,
        winnerId: RoomId,
    )

    @Query("DELETE FROM members WHERE bufferId = :loserId")
    suspend fun deleteMembers(loserId: RoomId)

    @Query(
        """INSERT OR IGNORE INTO reactions(
               bufferId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
           )
           SELECT :winnerId, targetMsgid, actorKey, sender, emoji, serverTime, targetEventId
           FROM reactions WHERE bufferId = :loserId""",
    )
    suspend fun copyReactions(
        loserId: RoomId,
        winnerId: RoomId,
    )

    @Query("DELETE FROM reactions WHERE bufferId = :loserId")
    suspend fun deleteReactions(loserId: RoomId)

    @Query("UPDATE buffers SET redirectToRoomId = :winnerId WHERE redirectToRoomId = :loserId")
    suspend fun repointRedirects(
        loserId: RoomId,
        winnerId: RoomId,
    )

    @Query("UPDATE buffers SET redirectToRoomId = :winnerId WHERE id = :loserId")
    suspend fun markRedirect(
        loserId: RoomId,
        winnerId: RoomId,
    )

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

    /**
     * The id AUTOINCREMENT will assign to the next messages row, so ingest can insert a row born
     * with timelineOrder = id. The FTS sync triggers run on every UPDATE, so the previous
     * insert-then-update re-tokenized messages_fts twice per message. sqlite_sequence never reuses
     * deleted ids (event_redirects depends on that) and explicit inserts advance it exactly like
     * generated ones; the table itself exists because messages requires a buffers row first.
     */
    @SkipQueryVerification
    @Query("SELECT COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'messages'), 0) + 1")
    suspend fun nextTimelineEventId(): TimelineEventId

    @Update
    suspend fun updateEvent(event: TimelineEventEntity)

    @Query("SELECT * FROM messages WHERE id = :eventId")
    suspend fun eventById(eventId: TimelineEventId): TimelineEventEntity?

    @Query("SELECT * FROM messages WHERE bufferId = :roomId ORDER BY id")
    suspend fun eventsForRoom(roomId: RoomId): List<TimelineEventEntity>

    @Query(
        """SELECT * FROM messages
           WHERE bufferId = :roomId AND serverTime = :serverTime
           ORDER BY timelineOrder, id""",
    )
    suspend fun eventsAtTime(
        roomId: RoomId,
        serverTime: Long,
    ): List<TimelineEventEntity>

    @Query(
        """UPDATE messages
           SET timelineOrder = :timelineOrder,
               timelineOrderConfirmed = CASE
                   WHEN :confirmed THEN 1 ELSE timelineOrderConfirmed
               END
           WHERE id = :eventId
             AND (timelineOrder != :timelineOrder
                  OR (:confirmed = 1 AND timelineOrderConfirmed = 0))""",
    )
    suspend fun updateTimelineOrder(
        eventId: TimelineEventId,
        timelineOrder: Long,
        confirmed: Boolean,
    )

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
    suspend fun repointEventRedirects(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )

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

    @Query(
        """INSERT INTO event_observations(
               networkId, timelineEventId, origin, connectionGeneration, receiveOrder, batchId,
               timeProvenance, semanticFingerprint, batchExactOrdinal, observedAt
           ) SELECT :networkId, :eventId, :origin, :connectionGeneration, :receiveOrder, :batchId,
                    :timeProvenance, :semanticFingerprint, :batchExactOrdinal, :observedAt
           WHERE NOT EXISTS (
               SELECT 1 FROM event_observations
               WHERE timelineEventId = :eventId AND origin = :origin AND timeProvenance = :timeProvenance
           ) OR (:batchExactOrdinal IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM event_observations
               WHERE timelineEventId = :eventId AND batchExactOrdinal IS NOT NULL
           ))""",
    )
    suspend fun insertObservationIfNovel(
        networkId: Long,
        eventId: TimelineEventId,
        origin: ObservationOrigin,
        connectionGeneration: Long?,
        receiveOrder: Long,
        batchId: String?,
        timeProvenance: TimeProvenance,
        semanticFingerprint: ByteArray,
        batchExactOrdinal: Int?,
        observedAt: Long,
    ): Long

    /** Keep one origin/provenance witness plus the first exact batch ordinal for each event. */
    suspend fun insertObservation(observation: EventObservationEntity): Long =
        insertObservationIfNovel(
            observation.networkId,
            observation.timelineEventId,
            observation.origin,
            observation.connectionGeneration,
            observation.receiveOrder,
            observation.batchId,
            observation.timeProvenance,
            observation.semanticFingerprint,
            observation.batchExactOrdinal,
            observation.observedAt,
        )

    @Query("SELECT COALESCE(MAX(receiveOrder), 0) + 1 FROM event_observations WHERE networkId = :networkId")
    suspend fun nextReceiveOrder(networkId: Long): Long

    /**
     * Newest authoritative server time among rows that already existed (lower id) when [beforeEventId]
     * was staged. Used to floor a self send's promoted echo time so a bouncer echo whose origin-server
     * clock trails this device cannot re-sort the just-sent row before content that was on screen at
     * send. The floor is always a real origin-server timestamp already in the timeline, so it never
     * advances any serverTime past reality.
     */
    @Query(
        """SELECT MAX(m.serverTime) FROM messages m
           WHERE m.bufferId = :roomId
             AND m.serverTimeAuthoritative = 1
             AND m.id < :beforeEventId""",
    )
    suspend fun newestAuthoritativeServerTimeBefore(
        roomId: RoomId,
        beforeEventId: TimelineEventId,
    ): Long?

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
    suspend fun repointAliases(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )

    @Query("UPDATE event_observations SET timelineEventId = :winnerId WHERE timelineEventId = :loserId")
    suspend fun repointObservations(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )

    @Query(
        """DELETE FROM event_observations
           WHERE timelineEventId = :eventId
             AND id NOT IN (
                 SELECT MIN(id) FROM event_observations
                 WHERE timelineEventId = :eventId GROUP BY origin, timeProvenance
             )
             AND id != COALESCE((
                 SELECT MIN(id) FROM event_observations
                 WHERE timelineEventId = :eventId AND batchExactOrdinal IS NOT NULL
             ), -1)""",
    )
    suspend fun compactObservations(eventId: TimelineEventId)

    @Query("UPDATE messages SET replyToEventId = :winnerId WHERE replyToEventId = :loserId")
    suspend fun repointReplies(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )

    @Query("UPDATE reactions SET targetEventId = :winnerId WHERE targetEventId = :loserId")
    suspend fun repointReactions(
        loserId: TimelineEventId,
        winnerId: TimelineEventId,
    )

    @Query("DELETE FROM messages WHERE id = :eventId")
    suspend fun deleteEvent(eventId: TimelineEventId)

    @Query(
        """UPDATE messages SET replyToEventId = :parentId
           WHERE bufferId = :bufferId AND replyToMsgid = :msgid AND replyToEventId IS NULL""",
    )
    suspend fun resolveReplies(
        bufferId: RoomId,
        msgid: String,
        parentId: TimelineEventId,
    )

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
    suspend fun resolveReactions(
        bufferId: RoomId,
        msgid: String,
        parentId: TimelineEventId,
    )

    @Query("UPDATE messages SET soundHandled = 1 WHERE id = :eventId AND soundHandled = 0")
    suspend fun claimSound(eventId: TimelineEventId): Int

    @Query(
        """UPDATE messages SET notificationClaimed = 1, notificationClaimOwner = :owner
           WHERE id = COALESCE(
               (SELECT canonicalEventId FROM event_redirects WHERE losingEventId = :eventId),
               :eventId
           ) AND notificationHandled = 0 AND notificationClaimed = 0""",
    )
    suspend fun claimNotification(
        eventId: TimelineEventId,
        owner: String,
    ): Int

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
                 OR (m.kind = 'DCC_TRANSFER' AND m.eventPayload IS NOT NULL)
             )
           ORDER BY m.serverTime, m.timelineOrder, m.id
           LIMIT :limit""",
    )
    suspend fun pendingNotifications(limit: Int): List<TimelineEventEntity>
}

@Dao
interface DccTransferDao {
    @Query("SELECT * FROM dcc_transfers WHERE id = :id")
    suspend fun byId(id: Long): DccTransferEntity?

    @Query("SELECT * FROM dcc_transfers WHERE networkId = :networkId AND offerKey = :offerKey LIMIT 1")
    suspend fun byOfferKey(
        networkId: Long,
        offerKey: String,
    ): DccTransferEntity?

    @Query("SELECT * FROM dcc_transfers WHERE timelineEventId = :timelineEventId LIMIT 1")
    fun observeByTimelineEventId(timelineEventId: TimelineEventId): Flow<DccTransferEntity?>

    @Query("SELECT * FROM dcc_transfers ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<DccTransferEntity>>

    @Query("SELECT * FROM dcc_transfers WHERE networkId = :networkId ORDER BY updatedAt DESC, id DESC")
    fun observeForNetwork(networkId: Long): Flow<List<DccTransferEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(transfer: DccTransferEntity): Long

    @Update
    suspend fun update(transfer: DccTransferEntity)
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
    suspend fun setNetworkLastSuccessfulSync(
        networkId: Long,
        timestamp: Long,
    )

    @Query("DELETE FROM network_history_cursors WHERE networkId = :networkId")
    suspend fun clearNetwork(networkId: Long)

    @Query("DELETE FROM history_cursors WHERE roomId = :roomId")
    suspend fun deleteRoom(roomId: RoomId)
}

@Dao
interface HistoryBackfillCursorDao {
    @Query("SELECT * FROM history_backfill_cursors WHERE networkId = :networkId")
    suspend fun byNetwork(networkId: Long): HistoryBackfillCursorEntity?

    /** First-sync seeding only: an existing cursor (earlier progress) always wins. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seed(cursor: HistoryBackfillCursorEntity)

    /** Monotonic toward epoch; a replayed older page can never move the cursor back up. */
    @Query(
        """UPDATE history_backfill_cursors SET upperBound = :upperBound
           WHERE networkId = :networkId AND upperBound > :upperBound AND complete = 0""",
    )
    suspend fun advance(
        networkId: Long,
        upperBound: Long,
    )

    @Query("UPDATE history_backfill_cursors SET complete = 1 WHERE networkId = :networkId")
    suspend fun markComplete(networkId: Long)
}

@Dao
interface HistoryGapDao {
    @Query("SELECT * FROM history_gaps WHERE roomId = :roomId ORDER BY olderServerTime")
    suspend fun forRoom(roomId: RoomId): List<HistoryGapEntity>

    @Query("SELECT * FROM history_gaps WHERE roomId = :roomId ORDER BY olderServerTime")
    fun observeForRoom(roomId: RoomId): Flow<List<HistoryGapEntity>>

    /** Live count of recorded missing intervals for one canonical room. */
    @Query("SELECT COUNT(*) FROM history_gaps WHERE roomId = :roomId")
    fun observeCount(roomId: RoomId): Flow<Int>

    @Insert
    suspend fun insert(gap: HistoryGapEntity): Long

    @Update
    suspend fun update(gap: HistoryGapEntity)

    @Query("DELETE FROM history_gaps WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history_gaps WHERE roomId = :roomId")
    suspend fun deleteForRoom(roomId: RoomId)

    @Query("UPDATE history_gaps SET roomId = :winnerId WHERE roomId = :loserId")
    suspend fun moveToRoom(
        loserId: RoomId,
        winnerId: RoomId,
    )

    /** Keep exact gap endpoints canonical when an event is coalesced, retimed, or reordered. */
    @Query(
        """UPDATE history_gaps SET
               olderEventId = CASE WHEN olderEventId = :fromEventId THEN :toEventId ELSE olderEventId END,
               olderServerTime = CASE WHEN olderEventId = :fromEventId THEN :serverTime ELSE olderServerTime END,
               olderTimelineOrder = CASE WHEN olderEventId = :fromEventId THEN :timelineOrder ELSE olderTimelineOrder END,
               newerEventId = CASE WHEN newerEventId = :fromEventId THEN :toEventId ELSE newerEventId END,
               newerServerTime = CASE WHEN newerEventId = :fromEventId THEN :serverTime ELSE newerServerTime END,
               newerTimelineOrder = CASE WHEN newerEventId = :fromEventId THEN :timelineOrder ELSE newerTimelineOrder END
           WHERE olderEventId = :fromEventId OR newerEventId = :fromEventId""",
    )
    suspend fun repointEventBoundary(
        fromEventId: TimelineEventId,
        toEventId: TimelineEventId,
        serverTime: Long,
        timelineOrder: Long,
    )
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
