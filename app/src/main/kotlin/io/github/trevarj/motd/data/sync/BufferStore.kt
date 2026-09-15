package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.data.db.AppStateEntity
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.service.NotificationRoomMergeListener
import javax.inject.Inject
import javax.inject.Singleton

/** Atomic buffer creation shared by event persistence and user-driven buffer creation. */
@Singleton
class BufferStore
    @Inject
    internal constructor(
        private val db: MotdDatabase,
        private val notifier: MessageNotifier = MessageNotifier.Noop,
        private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
        private val chatFolders: ChatFolderRepository = ChatFolderRepository(db),
        private val notificationRoomMergeListener: NotificationRoomMergeListener = NotificationRoomMergeListener.Noop,
    ) {
        suspend fun getOrCreate(
            networkId: Long,
            normalizedName: String,
            displayName: String,
            type: BufferType,
            initiallyDismissed: Boolean = false,
        ): BufferEntity =
            db.withTransaction {
                val dao = db.bufferDao()
                val aliasDao = db.roomAliasDao()
                // soju's console is not a conversation: a caller may ask for a QUERY and still get
                // the console. Role-scoped, or a real user holding that nick elsewhere would lose
                // their DM room.
                val effectiveType =
                    if (db.networkDao().isBouncerConsole(networkId, normalizedName)) {
                        BufferType.SERVER
                    } else {
                        type
                    }
                val namespace =
                    when (effectiveType) {
                        BufferType.CHANNEL -> RoomAliasNamespace.CHANNEL
                        BufferType.QUERY -> RoomAliasNamespace.PROVISIONAL_NICK
                        BufferType.SERVER -> RoomAliasNamespace.LEGACY_NAME
                    }
                if (effectiveType == BufferType.QUERY) {
                    resolveQueryRoom(networkId, normalizedName, account = null)?.let {
                        return@withTransaction it
                    }
                } else {
                    aliasDao.byValue(networkId, namespace, normalizedName)?.let { alias ->
                        dao.observeById(alias.roomId)?.takeIf { it.type == effectiveType }?.let {
                            return@withTransaction it
                        }
                    }
                }
                val nameCollision = dao.byName(networkId, normalizedName)
                if (nameCollision?.type == effectiveType) return@withTransaction nameCollision
                if (effectiveType == BufferType.CHANNEL && nameCollision?.type == BufferType.QUERY) {
                    val hasAccountIdentity =
                        aliasDao
                            .forRoom(nameCollision.id)
                            .any { it.namespace == RoomAliasNamespace.ACCOUNT }
                    if (!hasAccountIdentity) {
                        val promoted =
                            nameCollision.copy(
                                displayName = displayName,
                                type = BufferType.CHANNEL,
                                readMarkerTime = if (nameCollision.dismissed) null else nameCollision.readMarkerTime,
                                localReadAnchorTime = if (nameCollision.dismissed) null else nameCollision.localReadAnchorTime,
                                localReadAnchorEventId = if (nameCollision.dismissed) null else nameCollision.localReadAnchorEventId,
                                localUnreadFloorTime = if (nameCollision.dismissed) null else nameCollision.localUnreadFloorTime,
                                oldestFetchedTime = if (nameCollision.dismissed) null else nameCollision.oldestFetchedTime,
                                historyComplete = if (nameCollision.dismissed) false else nameCollision.historyComplete,
                                dismissed = false,
                                historyDiscardedThroughMsgid = null,
                                historyDiscardedThroughTime = null,
                            )
                        dao.update(promoted)
                        dao.deleteDiscardedMessageIds(promoted.id)
                        if (nameCollision.dismissed) {
                            db.historyCursorDao().delete(promoted.id)
                            db.historyGapDao().deleteForRoom(promoted.id)
                        }
                        aliasDao.deleteQueryAliases(promoted.id)
                        aliasDao.insertIgnore(
                            RoomAliasEntity(
                                networkId = networkId,
                                namespace = RoomAliasNamespace.CHANNEL,
                                value = normalizedName,
                                roomId = promoted.id,
                                verified = true,
                            ),
                        )
                        chatFolders.claimPending(promoted.id, normalizedChannel = normalizedName)
                        return@withTransaction promoted
                    }
                }
                val storedName =
                    if (nameCollision == null) {
                        normalizedName
                    } else {
                        "$normalizedName\u0000${effectiveType.name.lowercase()}"
                    }
                val candidate =
                    BufferEntity(
                        networkId = networkId,
                        name = storedName,
                        displayName = displayName,
                        type = effectiveType,
                        dismissed = initiallyDismissed,
                    )
                val insertedId = dao.insertIgnore(candidate)
                val room =
                    if (insertedId > 0L) {
                        candidate.copy(id = insertedId)
                    } else {
                        checkNotNull(dao.byName(networkId, storedName)) {
                            "buffer insert conflict did not leave a row for $networkId/$storedName"
                        }
                    }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = namespace,
                        value = normalizedName,
                        roomId = room.id,
                        verified = effectiveType == BufferType.CHANNEL,
                    ),
                )
                if (effectiveType == BufferType.CHANNEL) {
                    chatFolders.claimPending(room.id, normalizedChannel = normalizedName)
                }
                room
            }

        /**
         * Bind verified query identity. An account collision proves that two provisional nick rooms are
         * the same conversation; the oldest local id wins and the losing id remains a redirect.
         */
        suspend fun bindQueryIdentity(
            roomId: RoomId,
            networkId: Long,
            normalizedNick: String,
            displayNick: String,
            account: String?,
        ): BufferEntity =
            db.withTransaction {
                val requestedRoom = checkNotNull(db.bufferDao().observeById(roomId))
                var room = requestedRoom
                val aliasDao = db.roomAliasDao()
                if (account == null) {
                    aliasDao
                        .byValue(
                            networkId,
                            RoomAliasNamespace.VERIFIED_NICK,
                            normalizedNick,
                        )?.let { active ->
                            room = checkNotNull(db.bufferDao().observeById(active.roomId))
                        }
                }
                var roomAccounts =
                    aliasDao
                        .forRoom(room.id)
                        .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                        .map { it.value }
                        .toSet()
                if (account != null) {
                    val known = aliasDao.byValue(networkId, RoomAliasNamespace.ACCOUNT, account)
                    room =
                        when {
                            known != null && known.roomId != requestedRoom.id && roomAccounts.isEmpty() -> {
                                mergeRooms(known.roomId, requestedRoom.id)
                            }

                            known != null -> {
                                checkNotNull(db.bufferDao().observeById(known.roomId))
                            }

                            roomAccounts.any { it != account } -> {
                                val disambiguated =
                                    BufferEntity(
                                        networkId = networkId,
                                        name = "$normalizedNick\u0000account:$account",
                                        displayName = displayNick,
                                        type = BufferType.QUERY,
                                    )
                                disambiguated.copy(id = db.bufferDao().insert(disambiguated))
                            }

                            else -> {
                                room
                            }
                        }
                    roomAccounts =
                        aliasDao
                            .forRoom(room.id)
                            .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                            .map { it.value }
                            .toSet()
                }
                val namespace =
                    if (account != null) {
                        RoomAliasNamespace.ACCOUNT
                    } else {
                        RoomAliasNamespace.PROVISIONAL_NICK
                    }
                val value = account ?: normalizedNick
                val existing = aliasDao.byValue(networkId, namespace, value)
                if (existing != null && existing.roomId != room.id && account != null) {
                    room =
                        if (roomAccounts.isEmpty()) {
                            mergeRooms(existing.roomId, room.id)
                        } else {
                            checkNotNull(db.bufferDao().observeById(existing.roomId))
                        }
                }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = namespace,
                        value = value,
                        roomId = room.id,
                        verified = account != null,
                    ),
                )
                if (account != null) {
                    aliasDao.insertIgnore(
                        RoomAliasEntity(
                            networkId = networkId,
                            namespace = RoomAliasNamespace.VERIFIED_NICK,
                            value = normalizedNick,
                            roomId = room.id,
                            verified = true,
                        ),
                    )
                    aliasDao.moveVerifiedNick(networkId, normalizedNick, room.id)
                    if (room.displayName != displayNick) {
                        room = room.copy(displayName = displayNick)
                        db.bufferDao().update(room)
                    }
                }
                chatFolders.claimPending(room.id, account = account, normalizedNick = normalizedNick)
                checkNotNull(db.bufferDao().observeById(room.id))
            }

        /** Resolve a PM by strong account, then the active verified nick, then provisional nick. */
        suspend fun resolveQueryRoom(
            networkId: Long,
            normalizedNick: String,
            account: String?,
        ): BufferEntity? {
            val aliases = db.roomAliasDao()
            val alias =
                account?.let {
                    aliases.byValue(networkId, RoomAliasNamespace.ACCOUNT, it)
                } ?: aliases.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNick)
                    ?: aliases.byValue(networkId, RoomAliasNamespace.PROVISIONAL_NICK, normalizedNick)
            return alias
                ?.let { db.bufferDao().observeById(it.roomId) }
                ?.takeIf { it.type == BufferType.QUERY }
        }

        /** Resolve a channel through its durable alias, then a compatible legacy room key. */
        suspend fun resolveChannelRoom(
            networkId: Long,
            normalizedName: String,
        ): BufferEntity? {
            val aliased =
                db
                    .roomAliasDao()
                    .byValue(networkId, RoomAliasNamespace.CHANNEL, normalizedName)
                    ?.let { db.bufferDao().observeById(it.roomId) }
                    ?.takeIf { it.type == BufferType.CHANNEL }
            return aliased ?: db
                .bufferDao()
                .byName(networkId, normalizedName)
                ?.takeIf { it.type == BufferType.CHANNEL }
        }

        suspend fun mergeRooms(
            firstId: RoomId,
            secondId: RoomId,
        ): BufferEntity {
            val nestedTransaction = db.inTransaction()
            val merged =
                db.withTransaction {
                    val bufferDao = db.bufferDao()
                    val aliasDao = db.roomAliasDao()
                    val first = checkNotNull(bufferDao.observeById(firstId))
                    val second = checkNotNull(bufferDao.observeById(secondId))
                    if (first.id == second.id) return@withTransaction first
                    val winner = if (first.id < second.id) first else second
                    val loser = if (winner.id == first.id) second else first
                    val rooms = listOf(winner, loser)
                    val visibleRooms = rooms.filterNot { it.dismissed }
                    val readStateRooms = visibleRooms.ifEmpty { rooms }
                    val localReadAnchor =
                        readStateRooms
                            .mapNotNull { room ->
                                room.localReadAnchorTime?.let {
                                    val eventId = room.localReadAnchorEventId ?: 0L
                                    val order = db.messageDao().byCanonicalId(eventId)?.timelineOrder ?: eventId
                                    TimelineAnchor(it, eventId, order)
                                }
                            }.minOrNull()
                    val discardedBoundary =
                        rooms.maxWithOrNull(
                            compareBy<BufferEntity> { it.historyDiscardedThroughTime ?: Long.MIN_VALUE }
                                .thenBy { it.historyDiscardedThroughMsgid != null },
                        )
                    val result =
                        winner.copy(
                            displayName = if (second.displayName.isNotBlank()) second.displayName else winner.displayName,
                            topic = winner.topic ?: loser.topic,
                            topicSetBy = winner.topicSetBy ?: loser.topicSetBy,
                            joined = winner.joined || loser.joined,
                            membershipCycle = maxOf(winner.membershipCycle, loser.membershipCycle),
                            pinned = winner.pinned || loser.pinned,
                            muted = winner.muted || loser.muted,
                            // The canonical winner owns the current archive decision; an older redirect must
                            // never unexpectedly hide a conversation the user has already revived.
                            archived = winner.archived,
                            readMarkerTime = readStateRooms.mapNotNull { it.readMarkerTime }.maxOrNull(),
                            localReadAnchorTime = localReadAnchor?.serverTime,
                            localReadAnchorEventId = localReadAnchor?.eventId,
                            localUnreadFloorTime = readStateRooms.mapNotNull { it.localUnreadFloorTime }.maxOrNull(),
                            oldestFetchedTime = minNullable(winner.oldestFetchedTime, loser.oldestFetchedTime),
                            historyComplete = winner.historyComplete && loser.historyComplete,
                            dismissed = winner.dismissed && loser.dismissed,
                            historyDiscardedThroughMsgid = discardedBoundary?.historyDiscardedThroughMsgid,
                            historyDiscardedThroughTime = discardedBoundary?.historyDiscardedThroughTime,
                            layoutDensityOverride = winner.layoutDensityOverride ?: loser.layoutDensityOverride,
                            presenceModeOverride = winner.presenceModeOverride ?: loser.presenceModeOverride,
                            avatarOverrideModel = winner.avatarOverrideModel ?: loser.avatarOverrideModel,
                            folderId = winner.folderId ?: loser.folderId,
                        )
                    val mergedHistoryGaps = historyGapsForRoomMerge(winner.id, loser.id)
                    bufferDao.update(result)
                    aliasDao.repoint(loser.id, winner.id)
                    canonicalTimeline.moveEventsToRoom(winner.networkId, loser.id, winner.id)
                    bufferDao.refreshMonitorActivity(winner.id)
                    val canonicalMergedHistoryGaps =
                        canonicalizeMergedHistoryGaps(
                            winner.id,
                            mergedHistoryGaps,
                        )
                    aliasDao.copyMembers(loser.id, winner.id)
                    aliasDao.deleteMembers(loser.id)
                    aliasDao.copyReactions(loser.id, winner.id)
                    aliasDao.deleteReactions(loser.id)
                    mergeComposerDrafts(winner.id, loser.id)
                    mergeHistoryCursors(winner.id, loser.id, result.historyComplete)
                    replaceMergedHistoryGaps(winner.id, loser.id, canonicalMergedHistoryGaps)
                    bufferDao.copyDiscardedMessageIds(loser.id, winner.id)
                    bufferDao.deleteDiscardedMessageIds(loser.id)
                    aliasDao.repointRedirects(loser.id, winner.id)
                    aliasDao.markRedirect(loser.id, winner.id)
                    db.appStateDao().insert(
                        AppStateEntity(roomMergePresentationKey(winner.id, loser.id)),
                    )
                    result
                }
            if (!nestedTransaction) drainCommittedRoomMerges()
            return merged
        }

        /** Preserve local islands supplied by the opposite room instead of hiding them in a stale gap. */
        private suspend fun historyGapsForRoomMerge(
            winnerId: RoomId,
            loserId: RoomId,
        ): List<HistoryGapEntity> {
            val dao = db.historyGapDao()
            val winnerRows = db.messageDao().historyRowsForMerge(winnerId)
            val loserRows = db.messageDao().historyRowsForMerge(loserId)
            val winnerGaps = dao.forRoom(winnerId)
            val loserGaps = dao.forRoom(loserId)
            return winnerGaps.flatMap { splitHistoryGap(it, loserRows, loserGaps) } +
                loserGaps.flatMap { splitHistoryGap(it, winnerRows, winnerGaps) }
        }

        private fun splitHistoryGap(
            gap: HistoryGapEntity,
            oppositeRows: List<MessageEntity>,
            oppositeGaps: List<HistoryGapEntity>,
        ): List<HistoryGapEntity> {
            val older = gapBoundary(gap, older = true)
            val newer = gapBoundary(gap, older = false)
            val inside =
                oppositeRows.filter { row ->
                    val anchor = TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
                    anchor > older && anchor < newer
                }
            if (inside.isEmpty()) return listOf(gap)
            val islands = mutableListOf<MutableList<MessageEntity>>()
            inside.forEach { row ->
                val current = islands.lastOrNull()
                val separated =
                    current?.lastOrNull()?.let { previous ->
                        val previousAnchor = TimelineAnchor(previous.serverTime, previous.id, previous.timelineOrder)
                        val rowAnchor = TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
                        oppositeGaps.any { opposite ->
                            gapBoundary(opposite, older = true) < rowAnchor &&
                                gapBoundary(opposite, older = false) > previousAnchor
                        }
                    } ?: true
                if (separated) islands += mutableListOf(row) else current += row
            }
            val segments = mutableListOf<Pair<HistoryMergeBoundary, HistoryMergeBoundary>>()
            var cursor =
                HistoryMergeBoundary(
                    gap.olderMsgid,
                    gap.olderServerTime,
                    gap.olderEventId,
                    gap.olderTimelineOrder,
                )
            islands.forEach { island ->
                val first = island.first().toHistoryMergeBoundary()
                val last = island.last().toHistoryMergeBoundary()
                segments += cursor to first
                cursor = last
            }
            segments += cursor to
                HistoryMergeBoundary(
                    gap.newerMsgid,
                    gap.newerServerTime,
                    gap.newerEventId,
                    gap.newerTimelineOrder,
                )
            return segments.map { (left, right) ->
                gap.copy(
                    id = 0,
                    olderMsgid = left.msgid,
                    olderServerTime = left.serverTime,
                    olderEventId = left.eventId,
                    olderTimelineOrder = left.timelineOrder,
                    newerMsgid = right.msgid,
                    newerServerTime = right.serverTime,
                    newerEventId = right.eventId,
                    newerTimelineOrder = right.timelineOrder,
                )
            }
        }

        private fun MessageEntity.toHistoryMergeBoundary() =
            HistoryMergeBoundary(
                msgid,
                serverTime,
                id,
                timelineOrder,
            )

        private data class HistoryMergeBoundary(
            val msgid: String?,
            val serverTime: Long,
            val eventId: Long?,
            val timelineOrder: Long?,
        )

        /** Resolve pre-move gap snapshots through any event coalescence performed during the move. */
        private suspend fun canonicalizeMergedHistoryGaps(
            winnerId: RoomId,
            gaps: List<HistoryGapEntity>,
        ): List<HistoryGapEntity> =
            gaps.mapNotNull { gap ->
                val older =
                    canonicalHistoryMergeBoundary(
                        winnerId,
                        gap.olderMsgid,
                        gap.olderServerTime,
                        gap.olderEventId,
                        gap.olderTimelineOrder,
                    )
                val newer =
                    canonicalHistoryMergeBoundary(
                        winnerId,
                        gap.newerMsgid,
                        gap.newerServerTime,
                        gap.newerEventId,
                        gap.newerTimelineOrder,
                    )
                val olderAnchor =
                    TimelineAnchor(
                        older.serverTime,
                        older.eventId ?: Long.MIN_VALUE,
                        older.timelineOrder ?: older.eventId ?: Long.MIN_VALUE,
                    )
                val newerAnchor =
                    TimelineAnchor(
                        newer.serverTime,
                        newer.eventId ?: Long.MAX_VALUE,
                        newer.timelineOrder ?: newer.eventId ?: Long.MAX_VALUE,
                    )
                if (olderAnchor > newerAnchor || (olderAnchor == newerAnchor && gap.recoverable)) {
                    null
                } else {
                    gap.copy(
                        olderMsgid = older.msgid,
                        olderServerTime = older.serverTime,
                        olderEventId = older.eventId,
                        olderTimelineOrder = older.timelineOrder,
                        newerMsgid = newer.msgid,
                        newerServerTime = newer.serverTime,
                        newerEventId = newer.eventId,
                        newerTimelineOrder = newer.timelineOrder,
                    )
                }
            }

        private suspend fun canonicalHistoryMergeBoundary(
            winnerId: RoomId,
            msgid: String?,
            serverTime: Long,
            eventId: Long?,
            timelineOrder: Long?,
        ): HistoryMergeBoundary {
            val canonical =
                msgid?.let { db.messageDao().byMsgid(winnerId, it) }
                    ?: eventId?.let { db.messageDao().byCanonicalId(it) }?.takeIf { it.bufferId == winnerId }
            return canonical?.let { row ->
                HistoryMergeBoundary(row.msgid ?: msgid, row.serverTime, row.id, row.timelineOrder)
            } ?: HistoryMergeBoundary(msgid, serverTime, eventId, timelineOrder)
        }

        private fun gapBoundary(
            gap: HistoryGapEntity,
            older: Boolean,
        ): TimelineAnchor {
            val time = if (older) gap.olderServerTime else gap.newerServerTime
            val eventId = if (older) gap.olderEventId else gap.newerEventId
            val order = if (older) gap.olderTimelineOrder else gap.newerTimelineOrder
            val fallback = if (older) Long.MIN_VALUE else Long.MAX_VALUE
            return TimelineAnchor(time, eventId ?: fallback, order ?: eventId ?: fallback)
        }

        /** Coalesce only provably overlapping intervals after both rooms' local islands are retained. */
        private suspend fun replaceMergedHistoryGaps(
            winnerId: RoomId,
            loserId: RoomId,
            gaps: List<HistoryGapEntity>,
        ) {
            val dao = db.historyGapDao()
            val merged = mutableListOf<HistoryGapEntity>()
            gaps
                .sortedWith(compareBy<HistoryGapEntity> { it.olderServerTime }.thenBy { it.newerServerTime })
                .forEach { gap ->
                    val previous = merged.lastOrNull()
                    // Opaque msgids have no sortable order. Merely touching at one millisecond cannot
                    // prove two intervals overlap, especially when an entire server page shares time.
                    if (
                        previous == null ||
                        gap.recoverable != previous.recoverable ||
                        gap.olderServerTime >= previous.newerServerTime
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    } else if (
                        gap.olderServerTime == previous.olderServerTime &&
                        !sameOlderHistoryBoundary(previous, gap)
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    } else if (gap.newerServerTime > previous.newerServerTime) {
                        merged[merged.lastIndex] =
                            previous.copy(
                                newerMsgid = gap.newerMsgid,
                                newerServerTime = gap.newerServerTime,
                                newerEventId = gap.newerEventId,
                                newerTimelineOrder = gap.newerTimelineOrder,
                            )
                    } else if (
                        gap.newerServerTime == previous.newerServerTime &&
                        !sameNewerHistoryBoundary(previous, gap)
                    ) {
                        merged += gap.copy(id = 0, roomId = winnerId)
                    }
                }
            dao.deleteForRoom(winnerId)
            dao.deleteForRoom(loserId)
            merged.forEach { dao.insert(it.copy(id = 0)) }
        }

        private fun sameNewerHistoryBoundary(
            left: HistoryGapEntity,
            right: HistoryGapEntity,
        ): Boolean =
            when {
                left.newerMsgid != null && right.newerMsgid != null -> {
                    left.newerMsgid == right.newerMsgid
                }

                left.newerEventId != null && right.newerEventId != null -> {
                    left.newerEventId == right.newerEventId &&
                        left.newerTimelineOrder == right.newerTimelineOrder
                }

                else -> {
                    false
                }
            }

        private fun sameOlderHistoryBoundary(
            left: HistoryGapEntity,
            right: HistoryGapEntity,
        ): Boolean =
            when {
                left.olderMsgid != null && right.olderMsgid != null -> {
                    left.olderMsgid == right.olderMsgid
                }

                left.olderEventId != null && right.olderEventId != null -> {
                    left.olderEventId == right.olderEventId &&
                        left.olderTimelineOrder == right.olderTimelineOrder
                }

                else -> {
                    false
                }
            }

        /** Apply an IRCv3 channel rename while retiring the old channel alias. */
        suspend fun renameChannel(
            networkId: Long,
            oldNormalizedName: String,
            newNormalizedName: String,
            newDisplayName: String,
        ): BufferEntity? {
            val nestedTransaction = db.inTransaction()
            val renamed =
                db.withTransaction {
                    val old = resolveChannelRoom(networkId, oldNormalizedName) ?: return@withTransaction null
                    val oldMembers = db.memberDao().allNow(old.id)
                    val destination = resolveChannelRoom(networkId, newNormalizedName)
                    if (destination != null && destination.id != old.id && old.id < destination.id) {
                        db.bufferDao().renameRoomKey(
                            destination.id,
                            "$newNormalizedName\u0000redirect:${destination.id}",
                        )
                    }
                    val canonical =
                        if (destination != null && destination.id != old.id) {
                            mergeRooms(old.id, destination.id)
                        } else {
                            old
                        }
                    val canonicalId = db.bufferDao().canonicalId(canonical.id) ?: canonical.id
                    if (canonicalId != old.id) {
                        db.memberDao().replaceAll(
                            canonicalId,
                            oldMembers.map { MemberEntity(canonicalId, it.nick, it.prefixes) },
                        )
                    }
                    val current = checkNotNull(db.bufferDao().observeById(canonicalId))
                    val updated =
                        current.copy(
                            name =
                                if (current.name == oldNormalizedName || destination?.id == null) {
                                    newNormalizedName
                                } else {
                                    current.name
                                },
                            displayName = newDisplayName,
                            topic = old.topic ?: current.topic,
                            topicSetBy = old.topicSetBy ?: current.topicSetBy,
                            joined = old.joined || current.joined,
                            membershipCycle = maxOf(old.membershipCycle, current.membershipCycle),
                        )
                    if (updated.name != current.name || updated.displayName != current.displayName ||
                        updated.topic != current.topic || updated.joined != current.joined ||
                        updated.membershipCycle != current.membershipCycle
                    ) {
                        db.bufferDao().update(updated)
                    }
                    val aliases = db.roomAliasDao()
                    aliases.deleteAlias(networkId, RoomAliasNamespace.CHANNEL, oldNormalizedName)
                    aliases.insertIgnore(
                        RoomAliasEntity(
                            networkId = networkId,
                            namespace = RoomAliasNamespace.CHANNEL,
                            value = newNormalizedName,
                            roomId = updated.id,
                            verified = true,
                        ),
                    )
                    updated
                }
            if (!nestedTransaction) drainCommittedRoomMerges()
            return renamed
        }

        /** Newest draft version wins a room merge; reply ids are opaque local references, not FKs. */
        private suspend fun mergeComposerDrafts(
            winnerId: RoomId,
            loserId: RoomId,
        ) {
            val drafts = db.composerDraftDao()
            val winner = drafts.byRoom(winnerId)
            val loser = drafts.byRoom(loserId)
            val selected =
                listOfNotNull(winner, loser).maxWithOrNull(
                    compareBy<io.github.trevarj.motd.data.db.ComposerDraftEntity> { it.updatedAt }
                        .thenBy { it.roomId },
                )
            if (selected != null && selected.roomId != winnerId) {
                drafts.upsert(selected.copy(roomId = winnerId))
            }
            drafts.delete(loserId)
        }

        /** Present only durable room merges; nested callers drain after their outer transaction commits. */
        suspend fun drainCommittedRoomMerges() {
            val state = db.appStateDao()
            state.keysLike("$ROOM_MERGE_PRESENTATION_PREFIX%").forEach { key ->
                val (winnerId, loserId) =
                    parseRoomMergePresentationKey(key) ?: run {
                        state.delete(key)
                        return@forEach
                    }
                notificationRoomMergeListener.onRoomsMerged(winnerId, loserId)
                notifier.onRoomsMerged(winnerId, loserId)
                state.delete(key)
            }
        }

        /** A server NICK event is strong evidence that the old and new provisional aliases are one PM. */
        suspend fun bindNickChange(
            networkId: Long,
            normalizedOldNick: String,
            normalizedNewNick: String,
            displayNewNick: String,
        ): BufferEntity? =
            db.withTransaction {
                val aliasDao = db.roomAliasDao()
                val oldAlias =
                    aliasDao.byValue(
                        networkId,
                        RoomAliasNamespace.PROVISIONAL_NICK,
                        normalizedOldNick,
                    ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedOldNick)
                        ?: return@withTransaction null
                val newAlias =
                    aliasDao.byValue(
                        networkId,
                        RoomAliasNamespace.PROVISIONAL_NICK,
                        normalizedNewNick,
                    ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNewNick)
                val oldAccounts = accountAliases(oldAlias.roomId)
                val newAccounts = newAlias?.let { accountAliases(it.roomId) }.orEmpty()
                val conflictingAccounts =
                    oldAccounts.isNotEmpty() &&
                        newAccounts.isNotEmpty() && oldAccounts.intersect(newAccounts).isEmpty()
                var room =
                    if (
                        newAlias != null && newAlias.roomId != oldAlias.roomId && !conflictingAccounts
                    ) {
                        mergeRooms(oldAlias.roomId, newAlias.roomId)
                    } else {
                        checkNotNull(db.bufferDao().observeById(oldAlias.roomId))
                    }
                aliasDao.insertIgnore(
                    RoomAliasEntity(
                        networkId = networkId,
                        namespace = RoomAliasNamespace.VERIFIED_NICK,
                        value = normalizedNewNick,
                        roomId = room.id,
                        verified = true,
                    ),
                )
                aliasDao.moveVerifiedNick(networkId, normalizedNewNick, room.id)
                if (room.displayName != displayNewNick) {
                    room = room.copy(displayName = displayNewNick)
                    db.bufferDao().update(room)
                }
                room
            }

        private suspend fun accountAliases(roomId: RoomId): Set<String> =
            db
                .roomAliasDao()
                .forRoom(roomId)
                .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                .mapTo(mutableSetOf()) { it.value }

        private suspend fun mergeHistoryCursors(
            winnerId: RoomId,
            loserId: RoomId,
            historyComplete: Boolean,
        ) {
            val cursors = db.historyCursorDao()
            val winner = cursors.byRoom(winnerId)
            val loser = cursors.byRoom(loserId)
            if (winner != null || loser != null) {
                val newest =
                    listOfNotNull(winner, loser).maxByOrNull {
                        it.newestServerTime ?: Long.MIN_VALUE
                    }
                val oldest =
                    listOfNotNull(winner, loser).minByOrNull {
                        it.oldestServerTime ?: Long.MAX_VALUE
                    }
                cursors.upsert(
                    HistoryCursorEntity(
                        roomId = winnerId,
                        newestMsgid = newest?.newestMsgid,
                        newestServerTime = newest?.newestServerTime,
                        oldestMsgid = oldest?.oldestMsgid,
                        oldestServerTime = oldest?.oldestServerTime,
                        historyComplete = historyComplete,
                    ),
                )
            }
            cursors.deleteRoom(loserId)
        }

        private fun maxNullable(
            first: Long?,
            second: Long?,
        ): Long? = listOfNotNull(first, second).maxOrNull()

        private fun minNullable(
            first: Long?,
            second: Long?,
        ): Long? = listOfNotNull(first, second).minOrNull()
    }

internal const val ROOM_MERGE_PRESENTATION_PREFIX = "room_merge_notification:"

internal fun roomMergePresentationKey(
    winnerId: RoomId,
    loserId: RoomId,
): String = "$ROOM_MERGE_PRESENTATION_PREFIX$winnerId:$loserId"

internal fun parseRoomMergePresentationKey(key: String): Pair<RoomId, RoomId>? {
    val ids = key.removePrefix(ROOM_MERGE_PRESENTATION_PREFIX).split(':')
    if (!key.startsWith(ROOM_MERGE_PRESENTATION_PREFIX) || ids.size != 2) return null
    return (ids[0].toLongOrNull() ?: return null) to (ids[1].toLongOrNull() ?: return null)
}
