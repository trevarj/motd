package io.github.trevarj.motd.data.visibility

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class VisibleMessageAnchor(
    val id: Long,
    val msgid: String?,
    val serverTime: Long,
    val timelineOrder: Long = id,
)

/** Policy-backed targeted reads sharing the Room paging predicate. */
@Singleton
class MessageVisibilityReader
    @Inject
    constructor(
        private val db: MotdDatabase,
    ) {
        suspend fun effectiveLocalReadAnchor(buffer: RoomEntity): TimelineAnchor? {
            val local =
                buffer.localReadAnchorTime?.let { serverTime ->
                    val eventId = buffer.localReadAnchorEventId ?: 0L
                    val canonicalId = resolveCanonicalEventId(eventId)
                    val order = db.canonicalTimelineDao().eventById(canonicalId)?.timelineOrder ?: eventId
                    TimelineAnchor(serverTime, eventId, order)
                }
            val mute =
                buffer.localUnreadFloorTime?.let { serverTime ->
                    TimelineAnchor(serverTime, Long.MAX_VALUE, Long.MAX_VALUE)
                }
            return listOfNotNull(local, mute).maxOrNull()
        }

        fun observeLatestRawAnchor(bufferId: Long): Flow<TimelineAnchor?> =
            db.invalidationTracker
                .createFlow(
                    "messages",
                    "buffers",
                    emitInitialState = true,
                ).map { latestRawAnchor(bufferId) }
                .distinctUntilChanged()

        /** Emits only when event-id coalescence may require a live viewport re-anchor. */
        fun observeEventRedirects(): Flow<Unit> =
            db.invalidationTracker
                .createFlow(
                    "event_redirects",
                    emitInitialState = false,
                ).map { _: Set<String> -> }

        suspend fun latestRawAnchor(bufferId: Long): TimelineAnchor? =
            db.messageDao().newestMessage(canonicalRoomId(bufferId))?.let {
                TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
            }

        suspend fun countTimelineNewer(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            spec: MessageVisibilitySpec,
        ): Int {
            val canonicalId = resolveCanonicalEventId(id)
            val timelineOrder = db.canonicalTimelineDao().eventById(canonicalId)?.timelineOrder ?: id
            return countTimelineNewer(
                bufferId,
                TimelineAnchor(serverTime, id, timelineOrder),
                spec,
            )
        }

        suspend fun countTimelineNewer(
            bufferId: Long,
            anchor: TimelineAnchor,
            spec: MessageVisibilitySpec,
        ): Int {
            val context = visibilityContext(bufferId)
            return db.messageDao().rawCount(
                countTimelineNewerQuery(
                    context.roomId,
                    anchor.serverTime,
                    anchor.eventId,
                    anchor.timelineOrder,
                    spec,
                    context.identityRules,
                ),
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
            return db
                .messageDao()
                .rawMessage(
                    firstVisibleUnreadQuery(context.roomId, after, spec, context.identityRules),
                )?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }

        /**
         * Lazy-list index of the nearest unread nick mention strictly below the viewport (the oldest
         * unread mention within the newest [beforeIndex] visible-timeline rows), or null if none.
         * Mirrors [countVisibleUnreadInTimelinePrefix] so the FAB can resolve a jump target with one
         * cheap DB read instead of scanning paged items during a fling.
         */
        suspend fun nearestUnreadMentionBelowIndex(
            bufferId: Long,
            beforeIndex: Int,
            after: TimelineAnchor,
            spec: MessageVisibilitySpec,
        ): Int? {
            if (beforeIndex <= 0) return null
            val target =
                nearestUnreadMentionBelow(
                    bufferId,
                    beforeIndex,
                    after,
                    spec,
                ) ?: return null
            return countTimelineNewer(bufferId, target.serverTime, target.id, spec)
        }

        suspend fun nearestUnreadMentionBelow(
            bufferId: Long,
            beforeIndex: Int,
            after: TimelineAnchor,
            spec: MessageVisibilitySpec,
        ): VisibleMessageAnchor? {
            if (beforeIndex <= 0) return null
            val context = visibilityContext(bufferId)
            return db
                .messageDao()
                .rawMessage(
                    nearestUnreadMentionInPrefixQuery(
                        context.roomId,
                        beforeIndex,
                        after,
                        spec,
                        context.identityRules,
                    ),
                )?.let { VisibleMessageAnchor(it.id, it.msgid, it.serverTime, it.timelineOrder) }
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
            val savedOrder = db.canonicalTimelineDao().eventById(canonicalEventId)?.timelineOrder ?: id
            val exact =
                queryMessage(
                    where =
                        when {
                            msgid != null -> "m.msgid = ?"
                            canonicalEventId != id -> "m.id = ?"
                            else -> "m.serverTime = ? AND m.id = ?"
                        },
                    args =
                        when {
                            msgid != null -> listOf(msgid)
                            canonicalEventId != id -> listOf(canonicalEventId)
                            else -> listOf(serverTime, id)
                        },
                    bufferId = context.roomId,
                    visibility = visibility.anchor(),
                    order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
                )
            if (exact != null) return exact.toAnchor()

            // Prefer the first meaningful row at or behind the old viewport, then the nearest newer
            // row. This avoids surprising forward jumps while history is being read.
            val older =
                queryMessage(
                    where =
                        "m.serverTime < ? OR (m.serverTime = ? AND " +
                            "(m.timelineOrder < ? OR (m.timelineOrder = ? AND m.id < ?)))",
                    args = listOf(serverTime, serverTime, savedOrder, savedOrder, id),
                    bufferId = context.roomId,
                    visibility = visibility.anchor(),
                    order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
                )
            if (older != null) return older.toAnchor()
            return queryMessage(
                where =
                    "m.serverTime > ? OR (m.serverTime = ? AND " +
                        "(m.timelineOrder > ? OR (m.timelineOrder = ? AND m.id > ?)))",
                args = listOf(serverTime, serverTime, savedOrder, savedOrder, id),
                bufferId = context.roomId,
                visibility = visibility.anchor(),
                order = "m.serverTime ASC, m.timelineOrder ASC, m.id ASC",
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
                order = "m.serverTime DESC, m.timelineOrder DESC, m.id DESC",
            )?.toAnchor()
        }

        private suspend fun canonicalRoomId(bufferId: Long): Long = db.bufferDao().canonicalId(bufferId) ?: bufferId

        private suspend fun visibilityContext(bufferId: Long): VisibilityContext {
            val room =
                db.bufferDao().observeById(bufferId)
                    ?: return VisibilityContext(bufferId, IrcIdentityRules())
            val identityRules =
                db.networkIdentityDao().byNetwork(room.networkId)?.identityRules
                    ?: IrcIdentityRules()
            return VisibilityContext(room.id, identityRules)
        }

        suspend fun resolveCanonicalEventId(eventId: Long): Long = db.canonicalTimelineDao().canonicalEventId(eventId)

        /** Entry already resolved the canonical room; reuse list policy without reading other rooms. */
        suspend fun chatListRow(
            roomId: Long,
            spec: MessageVisibilitySpec,
        ): ChatListRow? =
            // A plain read: a Flow would register an observer, and both that and the Flow query's
            // transaction can need the writer, which a history sync holds for seconds.
            resolveChatList(withContext(Dispatchers.IO) { db.bufferDao().chatListNow(roomId) }, spec).singleOrNull()

        /** Replace fool-authored chat-list state, then re-sort by the resulting meaningful activity. */
        suspend fun resolveChatList(
            rows: List<ChatListRow>,
            spec: MessageVisibilitySpec,
        ): List<ChatListRow> {
            if ((spec.fools.isEmpty() && spec.showRedactedMessages) || rows.isEmpty()) return rows
            val replacements =
                rows
                    .groupBy { row -> IrcIdentityRules.from(row.caseMapping, row.chanTypes) }
                    .values
                    .flatMap { group ->
                        val first = group.first()
                        db.messageDao().rawChatListVisibility(
                            chatListVisibilityQuery(
                                group,
                                spec,
                                IrcIdentityRules.from(first.caseMapping, first.chanTypes),
                            ),
                        )
                    }.associateBy { it.bufferId }
            val resolved =
                rows.map { row ->
                    replacements[row.bufferId]?.let { replacement ->
                        row.copy(
                            lastMessageText = replacement.lastMessageText,
                            lastMessageSender = replacement.lastMessageSender,
                            lastMessageTime = replacement.lastMessageTime,
                            unreadCount = replacement.unreadCount,
                            mentionCount = replacement.mentionCount,
                        )
                    } ?: row
                }
            // Same key the SQL projection orders on (stored rows only, never the advertised high-water
            // mark), so hiding a fool re-sorts the list without changing what "most recent" means.
            return resolved.sortedWith(
                compareBy<ChatListRow> { it.anchorOrder == null }
                    .thenBy { it.anchorOrder }
                    .thenByDescending { it.pinned }
                    .thenBy { it.lastMessageTime == null }
                    .thenByDescending { it.lastMessageTime ?: Long.MIN_VALUE }
                    .thenByDescending { it.bufferId },
            )
        }

        private fun chatListVisibilityQuery(
            rows: List<ChatListRow>,
            spec: MessageVisibilitySpec,
            identityRules: IrcIdentityRules,
        ): SimpleSQLiteQuery {
            val selectedIds = rows.joinToString(",") { it.bufferId.toString() }
            val visibility = MessageVisibilitySql(spec, identityRules)
            // Android routes only statements that start with SELECT to reader connections; a leading WITH
            // would run on the writer's connection and wait out every history write.
            return SimpleSQLiteQuery(
                "SELECT * FROM (WITH selected AS (" +
                    "SELECT b.*, COALESCE(anchor.timelineOrder, COALESCE(b.localReadAnchorEventId, 0)) " +
                    "AS localReadAnchorOrder FROM buffers b " +
                    "LEFT JOIN messages anchor ON anchor.id = b.localReadAnchorEventId " +
                    "WHERE b.id IN ($selectedIds)) " +
                    "SELECT b.id AS bufferId, preview.text AS lastMessageText, " +
                    "preview.sender AS lastMessageSender, preview.serverTime AS lastMessageTime, " +
                    cappedChatListCountSql(visibility.visibleUnread(), mentionsOnly = false) + " AS unreadCount, " +
                    cappedChatListCountSql(visibility.mentionAttention("b.type"), mentionsOnly = true) + " AS mentionCount " +
                    "FROM selected b LEFT JOIN messages preview ON preview.id = (" +
                    "SELECT m.id FROM messages m WHERE m.bufferId = b.id AND ${visibility.preview()} " +
                    "ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1))",
            )
        }

        private fun cappedChatListCountSql(
            visibility: String,
            mentionsOnly: Boolean,
        ): String =
            "(SELECT COUNT(*) FROM (SELECT 1 FROM messages m WHERE m.bufferId = b.id AND (" +
                "m.serverTime > MAX(COALESCE(b.localReadAnchorTime, 0), COALESCE(b.localUnreadFloorTime, 0)) " +
                "OR (m.serverTime = b.localReadAnchorTime AND " +
                "COALESCE(b.localUnreadFloorTime, -9223372036854775808) < b.localReadAnchorTime AND (" +
                "m.timelineOrder > b.localReadAnchorOrder OR " +
                "(m.timelineOrder = b.localReadAnchorOrder AND m.id > COALESCE(b.localReadAnchorEventId, 0))))) " +
                "AND $visibility" + (if (mentionsOnly) " AND m.hasMention = 1" else "") +
                " LIMIT 1000))"

        private suspend fun queryMessage(
            where: String,
            args: List<Any>,
            bufferId: Long,
            visibility: String,
            order: String,
        ): MessageEntity? =
            db.messageDao().rawMessage(
                SimpleSQLiteQuery(
                    "SELECT m.* FROM messages m WHERE m.bufferId = ? AND ($where) " +
                        "AND $visibility ORDER BY $order LIMIT 1",
                    (listOf(bufferId) + args).toTypedArray(),
                ),
            )

        private fun MessageEntity.toAnchor() = VisibleMessageAnchor(id, msgid, serverTime, timelineOrder)

        private data class VisibilityContext(
            val roomId: Long,
            val identityRules: IrcIdentityRules,
        )
    }
