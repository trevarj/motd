package io.github.trevarj.motd.data.visibility

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class VisibleMessageAnchor(
    val id: Long,
    val msgid: String?,
    val serverTime: Long,
    val timelineOrder: Long = id,
)

data class MessageContextRow(
    val eventId: Long,
    val serverTime: Long,
    val sender: String,
    val kind: MessageKind,
    val text: String,
    val isSelf: Boolean,
    val replyToEventId: Long?,
)

enum class MessageContextCoverage { COMPLETE, PARTIAL, TRUNCATED }

enum class MessageContextOmission {
    HISTORY_GAP,
    ROW_LIMIT,
    DEPTH_LIMIT,
    UNRESOLVED_EVENT,
    UNRESOLVED_PARENT,
    CYCLE,
}

sealed interface MessageContextResult {
    val coverage: MessageContextCoverage
    val omissions: Set<MessageContextOmission>

    data class Available(
        val rows: List<MessageContextRow>,
        override val coverage: MessageContextCoverage,
        override val omissions: Set<MessageContextOmission>,
        /** Canonical root selected from visible thread rows; null for catch-up contexts. */
        val rootEventId: Long? = null,
    ) : MessageContextResult

    data class Empty(
        override val coverage: MessageContextCoverage,
        override val omissions: Set<MessageContextOmission>,
    ) : MessageContextResult
}

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

        /**
         * Newest bounded conversation context strictly after the frozen tuple [after].
         *
         * Preview visibility intentionally drops fools in both modes while keeping self-authored
         * rows. The descending keyset read keeps the newest [limit], then rows are frozen in
         * chronological order for prompt construction. [knownIncomplete] preserves the frozen entry
         * snapshot's missing-history signal even if the live gap table changes before context capture.
         */
        suspend fun catchUpContext(
            bufferId: Long,
            after: TimelineAnchor,
            spec: MessageVisibilitySpec,
            limit: Int = DEFAULT_CONTEXT_LIMIT,
            knownIncomplete: Boolean = false,
        ): MessageContextResult {
            require(limit > 0) { "limit must be positive" }
            return db.withTransaction {
                val context = visibilityContext(bufferId)
                val queried =
                    db.messageDao().rawMessages(
                        catchUpContextQuery(
                            context.roomId,
                            after,
                            spec,
                            context.identityRules,
                            limit,
                        ),
                    )
                val omissions =
                    linkedSetOf<MessageContextOmission>().apply {
                        if (knownIncomplete) add(MessageContextOmission.HISTORY_GAP)
                    }
                if (queried.size > limit) omissions += MessageContextOmission.ROW_LIMIT
                val rows = queried.take(limit).asReversed().map { it.toContextRow() }
                db.messageDao().newestMessage(context.roomId)?.toTimelineAnchor()?.let { newest ->
                    if (hasHistoryGap(context.roomId, after, newest)) {
                        omissions += MessageContextOmission.HISTORY_GAP
                    }
                }
                contextResult(rows, omissions)
            }
        }

        /**
         * Bounded local thread graph containing the canonical root, siblings, and descendants.
         *
         * Parent point reads are capped by [maxDepth]. Descendants use the reply index through one
         * capped recursive query; invisible nodes are still traversed so a visible grandchild of a
         * hidden row is not lost. A visible root without replies is retained as available context.
         */
        suspend fun threadContext(
            bufferId: Long,
            eventId: Long,
            spec: MessageVisibilitySpec,
            limit: Int = DEFAULT_CONTEXT_LIMIT,
            maxDepth: Int = DEFAULT_THREAD_MAX_DEPTH,
        ): MessageContextResult {
            require(limit > 0) { "limit must be positive" }
            require(maxDepth >= 0) { "maxDepth must not be negative" }
            return db.withTransaction {
                val context = visibilityContext(bufferId)
                val selected = db.messageDao().byCanonicalId(eventId)
                if (selected == null || selected.bufferId != context.roomId) {
                    return@withTransaction contextResult(
                        emptyList(),
                        setOf(MessageContextOmission.UNRESOLVED_EVENT),
                    )
                }

                val omissions = linkedSetOf<MessageContextOmission>()
                val root = threadRoot(selected, context.roomId, maxDepth, omissions)
                val traversed =
                    db.messageDao().rawThreadRows(
                        threadContextQuery(
                            context.roomId,
                            root.id,
                            spec,
                            context.identityRules,
                            limit,
                            maxDepth,
                        ),
                    )
                if (traversed.any { it.traversalCycle }) omissions += MessageContextOmission.CYCLE
                if (traversed.any { it.traversalDepth > maxDepth }) {
                    omissions += MessageContextOmission.DEPTH_LIMIT
                }
                val eligible =
                    traversed
                        .asSequence()
                        .filter { !it.traversalCycle && it.traversalDepth <= maxDepth }
                        .distinctBy { it.message.id }
                        .toList()
                if (eligible.size > limit) omissions += MessageContextOmission.ROW_LIMIT
                val retained = eligible.take(limit)
                val visible = retained.filter { it.traversalVisible }
                val visibleRootEventId =
                    visible
                        .minWithOrNull(
                            compareBy(
                                { it.traversalDepth },
                                { it.message.serverTime },
                                { it.message.timelineOrder },
                                { it.message.id },
                            ),
                        )?.message
                        ?.id
                val rows =
                    visible
                        .sortedWith(
                            compareBy(
                                { it.message.serverTime },
                                { it.message.timelineOrder },
                                { it.message.id },
                            ),
                        ).map { it.message.toContextRow() }

                val newest = db.messageDao().newestMessage(context.roomId)?.toTimelineAnchor()
                if (newest != null) {
                    val rootAnchor = root.toTimelineAnchor()
                    if (hasHistoryGap(context.roomId, minOf(rootAnchor, newest), maxOf(rootAnchor, newest))) {
                        omissions += MessageContextOmission.HISTORY_GAP
                    }
                }
                contextResult(rows, omissions, rootEventId = visibleRootEventId)
            }
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
                compareByDescending<ChatListRow> { it.pinned }
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
            return SimpleSQLiteQuery(
                "WITH selected AS (" +
                    "SELECT b.*, COALESCE(anchor.timelineOrder, COALESCE(b.localReadAnchorEventId, 0)) " +
                    "AS localReadAnchorOrder FROM buffers b " +
                    "LEFT JOIN messages anchor ON anchor.id = b.localReadAnchorEventId " +
                    "WHERE b.id IN ($selectedIds)) " +
                    "SELECT b.id AS bufferId, preview.text AS lastMessageText, " +
                    "preview.sender AS lastMessageSender, preview.serverTime AS lastMessageTime, " +
                    cappedChatListCountSql(visibility.visibleUnread(), mentionsOnly = false) + " AS unreadCount, " +
                    cappedChatListCountSql(visibility.visibleUnread(), mentionsOnly = true) + " AS mentionCount " +
                    "FROM selected b LEFT JOIN messages preview ON preview.id = (" +
                    "SELECT m.id FROM messages m WHERE m.bufferId = b.id AND ${visibility.preview()} " +
                    "ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC LIMIT 1)",
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

        private suspend fun threadRoot(
            selected: MessageEntity,
            roomId: Long,
            maxDepth: Int,
            omissions: MutableSet<MessageContextOmission>,
        ): MessageEntity {
            var current = selected
            val seen = mutableSetOf(selected.id)
            var depth = 0
            while (true) {
                val storedParentId = current.replyToEventId
                if (storedParentId == null) {
                    if (!current.replyToMsgid.isNullOrBlank()) {
                        omissions += MessageContextOmission.UNRESOLVED_PARENT
                    }
                    break
                }
                if (depth == maxDepth) {
                    omissions += MessageContextOmission.DEPTH_LIMIT
                    break
                }
                val parent = db.messageDao().byCanonicalId(storedParentId)
                if (parent == null || parent.bufferId != roomId) {
                    omissions += MessageContextOmission.UNRESOLVED_PARENT
                    break
                }
                if (!seen.add(parent.id)) {
                    omissions += MessageContextOmission.CYCLE
                    break
                }
                current = parent
                depth++
            }
            return current
        }

        private fun catchUpContextQuery(
            roomId: Long,
            after: TimelineAnchor,
            spec: MessageVisibilitySpec,
            identityRules: IrcIdentityRules,
            limit: Int,
        ): SimpleSQLiteQuery {
            val visibility = MessageVisibilitySql(spec, identityRules)
            return SimpleSQLiteQuery(
                """
                SELECT m.* FROM messages m
                WHERE m.bufferId = ?
                  AND (m.serverTime > ? OR (m.serverTime = ? AND (
                    m.timelineOrder > ? OR
                    (m.timelineOrder = ? AND m.id > ?)
                  )))
                  AND m.kind IN ($CONVERSATION_KIND_SQL)
                  AND ${visibility.preview()}
                ORDER BY m.serverTime DESC, m.timelineOrder DESC, m.id DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(
                    roomId,
                    after.serverTime,
                    after.serverTime,
                    after.timelineOrder,
                    after.timelineOrder,
                    after.eventId,
                    limit.toLong() + 1,
                ),
            )
        }

        private fun threadContextQuery(
            roomId: Long,
            rootId: Long,
            spec: MessageVisibilitySpec,
            identityRules: IrcIdentityRules,
            limit: Int,
            maxDepth: Int,
        ): SimpleSQLiteQuery {
            val visibility = MessageVisibilitySql(spec, identityRules)
            return SimpleSQLiteQuery(
                """
                WITH RECURSIVE thread(
                    id, depth, path, cycle, server_time, timeline_order
                ) AS (
                  SELECT root.id, 0, ',' || root.id || ',', 0,
                         root.serverTime, root.timelineOrder
                  FROM messages root
                  WHERE root.id = ? AND root.bufferId = ?
                  UNION ALL
                  SELECT child.id, thread.depth + 1,
                         thread.path || child.id || ',',
                         instr(thread.path, ',' || child.id || ',') > 0,
                         child.serverTime, child.timelineOrder
                  FROM thread
                  JOIN messages child ON child.replyToEventId = thread.id
                  WHERE child.bufferId = ?
                    AND thread.cycle = 0
                    AND thread.depth <= ?
                  ORDER BY 5 DESC, 6 DESC, 1 DESC
                  LIMIT ?
                )
                SELECT m.*,
                       thread.depth AS traversalDepth,
                       thread.cycle AS traversalCycle,
                       CASE WHEN m.kind IN ($CONVERSATION_KIND_SQL)
                                AND ${visibility.preview("m")}
                            THEN 1 ELSE 0 END AS traversalVisible
                FROM thread
                JOIN messages m ON m.id = thread.id
                ORDER BY CASE WHEN thread.depth = 0 THEN 0 ELSE 1 END,
                         m.serverTime DESC, m.timelineOrder DESC, m.id DESC
                """.trimIndent(),
                arrayOf<Any>(
                    rootId,
                    roomId,
                    roomId,
                    maxDepth,
                    limit.toLong() + 1,
                ),
            )
        }

        private suspend fun hasHistoryGap(
            roomId: Long,
            lower: TimelineAnchor,
            upper: TimelineAnchor,
        ): Boolean =
            upper > lower &&
                db.historyGapDao().hasIntersecting(
                    roomId = roomId,
                    lowerTime = lower.serverTime,
                    lowerOrder = lower.timelineOrder,
                    lowerEventId = lower.eventId,
                    upperTime = upper.serverTime,
                    upperOrder = upper.timelineOrder,
                    upperEventId = upper.eventId,
                )

        private fun contextResult(
            rows: List<MessageContextRow>,
            omissions: Set<MessageContextOmission>,
            rootEventId: Long? = null,
        ): MessageContextResult {
            val frozenOmissions = omissions.toSet()
            val coverage =
                when {
                    frozenOmissions.isEmpty() -> MessageContextCoverage.COMPLETE
                    MessageContextOmission.ROW_LIMIT in frozenOmissions -> MessageContextCoverage.TRUNCATED
                    else -> MessageContextCoverage.PARTIAL
                }
            return if (rows.isEmpty()) {
                MessageContextResult.Empty(coverage, frozenOmissions)
            } else {
                MessageContextResult.Available(rows.toList(), coverage, frozenOmissions, rootEventId)
            }
        }

        private fun MessageEntity.toTimelineAnchor() = TimelineAnchor(serverTime, id, timelineOrder)

        private fun MessageEntity.toContextRow() =
            MessageContextRow(
                eventId = id,
                serverTime = serverTime,
                sender = sender,
                kind = kind,
                text = text,
                isSelf = isSelf,
                replyToEventId = replyToEventId,
            )

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

        private companion object {
            const val DEFAULT_CONTEXT_LIMIT = 100
            const val DEFAULT_THREAD_MAX_DEPTH = 32
            val CONVERSATION_KIND_SQL = CONVERSATION_KINDS.joinToString(",") { "'${it.name}'" }
        }

        private data class VisibilityContext(
            val roomId: Long,
            val identityRules: IrcIdentityRules,
        )
    }
