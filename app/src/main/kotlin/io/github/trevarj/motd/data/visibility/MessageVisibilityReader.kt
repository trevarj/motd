package io.github.trevarj.motd.data.visibility

import androidx.room.InvalidationTracker
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class VisibleMessageAnchor(
    val id: Long,
    val msgid: String?,
    val serverTime: Long,
)

/** Policy-backed targeted reads sharing the Room paging predicate. */
@Singleton
class MessageVisibilityReader @Inject constructor(
    private val db: MotdDatabase,
) {
    fun observeLatestRawAnchor(bufferId: Long): Flow<TimelineAnchor?> = callbackFlow {
        val observer = object : InvalidationTracker.Observer("messages", "buffers") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        db.invalidationTracker.addObserver(observer)
        trySend(Unit)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }.map { latestRawAnchor(bufferId) }.distinctUntilChanged()

    /** Emits only when event-id coalescence may require a live viewport re-anchor. */
    fun observeEventRedirects(): Flow<Unit> = callbackFlow {
        val observer = object : InvalidationTracker.Observer("event_redirects") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(Unit)
            }
        }
        db.invalidationTracker.addObserver(observer)
        awaitClose { db.invalidationTracker.removeObserver(observer) }
    }

    suspend fun latestRawAnchor(bufferId: Long): TimelineAnchor? =
        db.messageDao().newestMessage(canonicalRoomId(bufferId))?.let {
            TimelineAnchor(it.serverTime, it.id)
        }

    suspend fun countTimelineNewer(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): Int {
        val context = visibilityContext(bufferId)
        return db.messageDao().rawCount(
            countTimelineNewerQuery(context.roomId, serverTime, id, spec, context.identityRules),
        )
    }

    suspend fun countVisibleUnreadInTimelinePrefix(
        bufferId: Long,
        beforeIndex: Int,
        after: TimelineAnchor,
        maxCount: Int,
        spec: MessageVisibilitySpec,
    ): Int {
        if (beforeIndex <= 0 || maxCount <= 0) return 0
        val context = visibilityContext(bufferId)
        return db.messageDao().rawCount(
            countVisibleUnreadInTimelinePrefixQuery(
                context.roomId,
                beforeIndex,
                after,
                maxCount,
                spec,
                context.identityRules,
            ),
        )
    }

    suspend fun firstVisibleUnreadAnchor(
        bufferId: Long,
        after: TimelineAnchor,
        spec: MessageVisibilitySpec,
    ): TimelineAnchor? {
        val context = visibilityContext(bufferId)
        return db.messageDao().rawMessage(
            firstVisibleUnreadQuery(context.roomId, after, spec, context.identityRules),
        )
            ?.let { TimelineAnchor(it.serverTime, it.id) }
    }

    suspend fun resolveSavedAnchor(
        bufferId: Long,
        msgid: String?,
        serverTime: Long,
        id: Long,
        spec: MessageVisibilitySpec,
    ): VisibleMessageAnchor? {
        val context = visibilityContext(bufferId)
        val visibility = MessageVisibilitySql(spec, context.identityRules)
        val canonicalEventId = resolveCanonicalEventId(id)
        val exact = queryMessage(
            where = when {
                msgid != null -> "m.msgid = ?"
                canonicalEventId != id -> "m.id = ?"
                else -> "m.serverTime = ? AND m.id = ?"
            },
            args = when {
                msgid != null -> listOf(msgid)
                canonicalEventId != id -> listOf(canonicalEventId)
                else -> listOf(serverTime, id)
            },
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime DESC, m.id DESC",
        )
        if (exact != null) return exact.toAnchor()

        // Prefer the first meaningful row at or behind the old viewport, then the nearest newer
        // row. This avoids surprising forward jumps while history is being read.
        val older = queryMessage(
            where = "m.serverTime < ? OR (m.serverTime = ? AND m.id < ?)",
            args = listOf(serverTime, serverTime, id),
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime DESC, m.id DESC",
        )
        if (older != null) return older.toAnchor()
        return queryMessage(
            where = "m.serverTime > ? OR (m.serverTime = ? AND m.id > ?)",
            args = listOf(serverTime, serverTime, id),
            bufferId = context.roomId,
            visibility = visibility.anchor(),
            order = "m.serverTime ASC, m.id ASC",
        )?.toAnchor()
    }

    /** Newest row that can define effective bottom; ignored raw tails remain separately observed. */
    suspend fun latestEffectiveAnchor(
        bufferId: Long,
        spec: MessageVisibilitySpec,
    ): VisibleMessageAnchor? {
        val context = visibilityContext(bufferId)
        return queryMessage(
            where = "1",
            args = emptyList(),
            bufferId = context.roomId,
            visibility = MessageVisibilitySql(spec, context.identityRules).anchor(),
            order = "m.serverTime DESC, m.id DESC",
        )?.toAnchor()
    }

    private suspend fun canonicalRoomId(bufferId: Long): Long =
        db.bufferDao().canonicalId(bufferId) ?: bufferId

    private suspend fun visibilityContext(bufferId: Long): VisibilityContext {
        val room = db.bufferDao().observeById(bufferId)
            ?: return VisibilityContext(bufferId, IrcIdentityRules())
        val identityRules = db.networkIdentityDao().byNetwork(room.networkId)?.identityRules
            ?: IrcIdentityRules()
        return VisibilityContext(room.id, identityRules)
    }

    suspend fun resolveCanonicalEventId(eventId: Long): Long =
        db.canonicalTimelineDao().canonicalEventId(eventId)

    /** Replace fool-authored chat-list state, then re-sort by the resulting meaningful activity. */
    suspend fun resolveChatList(
        rows: List<ChatListRow>,
        spec: MessageVisibilitySpec,
    ): List<ChatListRow> {
        if (spec.fools.isEmpty()) return rows
        val resolved = rows.map { row -> resolveChatListRow(row, spec) }
        return resolved.sortedWith(
            compareByDescending<ChatListRow> { it.pinned }
                .thenBy { it.lastMessageTime == null }
                .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                .thenByDescending { it.bufferId },
        )
    }

    private suspend fun resolveChatListRow(
        row: ChatListRow,
        spec: MessageVisibilitySpec,
    ): ChatListRow {
        val visibility = MessageVisibilitySql(
            spec,
            IrcIdentityRules.from(row.caseMapping, row.chanTypes),
        )
        val preview = queryMessage(
            where = "1",
            args = emptyList(),
            bufferId = row.bufferId,
            visibility = visibility.preview(),
            order = "m.serverTime DESC, m.id DESC",
        )
        val unreadCount = chatListCount(row.bufferId, visibility.visibleUnread(), mentionsOnly = false)
        val mentionCount = chatListCount(row.bufferId, visibility.visibleUnread(), mentionsOnly = true)
        return row.copy(
            lastMessageText = preview?.text,
            lastMessageSender = preview?.sender,
            lastMessageTime = preview?.serverTime,
            unreadCount = unreadCount,
            mentionCount = mentionCount,
        )
    }

    private suspend fun queryMessage(
        where: String,
        args: List<Any>,
        bufferId: Long,
        visibility: String,
        order: String,
    ): MessageEntity? = db.messageDao().rawMessage(
        SimpleSQLiteQuery(
            "SELECT m.* FROM messages m WHERE m.bufferId = ? AND ($where) " +
                "AND $visibility ORDER BY $order LIMIT 1",
            (listOf(bufferId) + args).toTypedArray(),
        ),
    )

    private suspend fun chatListCount(
        bufferId: Long,
        visibility: String,
        mentionsOnly: Boolean,
    ): Int = db.messageDao().rawCount(
        SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM buffers b JOIN messages m ON m.bufferId = b.id " +
                "WHERE b.id = ? AND (" +
                "m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), " +
                "COALESCE(b.localUnreadFloorTime, 0)) OR (" +
                "m.serverTime = b.localReadAnchorTime AND " +
                "COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime " +
                "AND m.id > COALESCE(b.localReadAnchorEventId, 0))) " +
                "AND $visibility" + if (mentionsOnly) " AND m.hasMention = 1" else "",
            arrayOf(bufferId),
        ),
    )

    private fun MessageEntity.toAnchor() = VisibleMessageAnchor(id, msgid, serverTime)

    private data class VisibilityContext(
        val roomId: Long,
        val identityRules: IrcIdentityRules,
    )
}
