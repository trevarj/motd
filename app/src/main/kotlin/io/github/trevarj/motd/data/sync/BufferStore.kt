package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.AppStateEntity
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomAliasEntity
import io.github.trevarj.motd.data.db.RoomAliasNamespace
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimelineAnchor
import javax.inject.Inject
import javax.inject.Singleton

/** Atomic buffer creation shared by event persistence and user-driven buffer creation. */
@Singleton
class BufferStore @Inject constructor(
    private val db: MotdDatabase,
    private val notifier: MessageNotifier = MessageNotifier.Noop,
    private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
) {
    suspend fun getOrCreate(
        networkId: Long,
        normalizedName: String,
        displayName: String,
        type: BufferType,
    ): BufferEntity = db.withTransaction {
        val dao = db.bufferDao()
        val aliasDao = db.roomAliasDao()
        val namespace = when (type) {
            BufferType.CHANNEL -> RoomAliasNamespace.CHANNEL
            BufferType.QUERY -> RoomAliasNamespace.PROVISIONAL_NICK
            BufferType.SERVER -> RoomAliasNamespace.LEGACY_NAME
        }
        if (type == BufferType.QUERY) {
            resolveQueryRoom(networkId, normalizedName, account = null)?.let {
                return@withTransaction it
            }
        } else {
            aliasDao.byValue(networkId, namespace, normalizedName)?.let { alias ->
                dao.observeById(alias.roomId)?.takeIf { it.type == type }?.let {
                    return@withTransaction it
                }
            }
        }
        val nameCollision = dao.byName(networkId, normalizedName)
        if (nameCollision?.type == type) return@withTransaction nameCollision
        if (type == BufferType.CHANNEL && nameCollision?.type == BufferType.QUERY) {
            val hasAccountIdentity = aliasDao.forRoom(nameCollision.id)
                .any { it.namespace == RoomAliasNamespace.ACCOUNT }
            if (!hasAccountIdentity) {
                val promoted = nameCollision.copy(displayName = displayName, type = BufferType.CHANNEL)
                dao.update(promoted)
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
                return@withTransaction promoted
            }
        }
        val storedName = if (nameCollision == null) {
            normalizedName
        } else {
            "$normalizedName\u0000${type.name.lowercase()}"
        }
        val candidate = BufferEntity(
            networkId = networkId,
            name = storedName,
            displayName = displayName,
            type = type,
        )
        val insertedId = dao.insertIgnore(candidate)
        val room = if (insertedId > 0L) candidate.copy(id = insertedId) else {
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
                verified = type == BufferType.CHANNEL,
            ),
        )
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
    ): BufferEntity = db.withTransaction {
        val requestedRoom = checkNotNull(db.bufferDao().observeById(roomId))
        var room = requestedRoom
        val aliasDao = db.roomAliasDao()
        if (account == null) {
            aliasDao.byValue(
                networkId,
                RoomAliasNamespace.VERIFIED_NICK,
                normalizedNick,
            )?.let { active ->
                room = checkNotNull(db.bufferDao().observeById(active.roomId))
            }
        }
        var roomAccounts = aliasDao.forRoom(room.id)
            .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
            .map { it.value }
            .toSet()
        if (account != null) {
            val known = aliasDao.byValue(networkId, RoomAliasNamespace.ACCOUNT, account)
            room = when {
                known != null && known.roomId != requestedRoom.id && roomAccounts.isEmpty() -> {
                    mergeRooms(known.roomId, requestedRoom.id)
                }
                known != null -> {
                    checkNotNull(db.bufferDao().observeById(known.roomId))
                }
                roomAccounts.any { it != account } -> {
                    val disambiguated = BufferEntity(
                        networkId = networkId,
                        name = "$normalizedNick\u0000account:$account",
                        displayName = displayNick,
                        type = BufferType.QUERY,
                    )
                    disambiguated.copy(id = db.bufferDao().insert(disambiguated))
                }
                else -> room
            }
            roomAccounts = aliasDao.forRoom(room.id)
                .filter { it.namespace == RoomAliasNamespace.ACCOUNT }
                .map { it.value }
                .toSet()
        }
        val namespace = if (account != null) {
            RoomAliasNamespace.ACCOUNT
        } else {
            RoomAliasNamespace.PROVISIONAL_NICK
        }
        val value = account ?: normalizedNick
        val existing = aliasDao.byValue(networkId, namespace, value)
        if (existing != null && existing.roomId != room.id && account != null) {
            room = if (roomAccounts.isEmpty()) {
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
        room
    }

    /** Resolve a PM by strong account, then the active verified nick, then provisional nick. */
    suspend fun resolveQueryRoom(
        networkId: Long,
        normalizedNick: String,
        account: String?,
    ): BufferEntity? {
        val aliases = db.roomAliasDao()
        val alias = account?.let {
            aliases.byValue(networkId, RoomAliasNamespace.ACCOUNT, it)
        } ?: aliases.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNick)
            ?: aliases.byValue(networkId, RoomAliasNamespace.PROVISIONAL_NICK, normalizedNick)
        return alias?.let { db.bufferDao().observeById(it.roomId) }
            ?.takeIf { it.type == BufferType.QUERY }
    }

    /** Resolve a channel through its durable alias, then a compatible legacy room key. */
    suspend fun resolveChannelRoom(networkId: Long, normalizedName: String): BufferEntity? {
        val aliased = db.roomAliasDao()
            .byValue(networkId, RoomAliasNamespace.CHANNEL, normalizedName)
            ?.let { db.bufferDao().observeById(it.roomId) }
            ?.takeIf { it.type == BufferType.CHANNEL }
        return aliased ?: db.bufferDao().byName(networkId, normalizedName)
            ?.takeIf { it.type == BufferType.CHANNEL }
    }

    suspend fun mergeRooms(firstId: RoomId, secondId: RoomId): BufferEntity {
        val nestedTransaction = db.inTransaction()
        val merged = db.withTransaction {
            val bufferDao = db.bufferDao()
            val aliasDao = db.roomAliasDao()
            val first = checkNotNull(bufferDao.observeById(firstId))
            val second = checkNotNull(bufferDao.observeById(secondId))
            if (first.id == second.id) return@withTransaction first
            val winner = if (first.id < second.id) first else second
            val loser = if (winner.id == first.id) second else first
            val localReadAnchor = listOfNotNull(
                winner.localReadAnchorTime?.let {
                    TimelineAnchor(it, winner.localReadAnchorEventId ?: 0L)
                },
                loser.localReadAnchorTime?.let {
                    TimelineAnchor(it, loser.localReadAnchorEventId ?: 0L)
                },
            ).minOrNull()
            val result = winner.copy(
                displayName = if (second.displayName.isNotBlank()) second.displayName else winner.displayName,
                topic = winner.topic ?: loser.topic,
                topicSetBy = winner.topicSetBy ?: loser.topicSetBy,
                joined = winner.joined || loser.joined,
                membershipCycle = maxOf(winner.membershipCycle, loser.membershipCycle),
                pinned = winner.pinned || loser.pinned,
                muted = winner.muted || loser.muted,
                readMarkerTime = maxNullable(winner.readMarkerTime, loser.readMarkerTime),
                localReadAnchorTime = localReadAnchor?.serverTime,
                localReadAnchorEventId = localReadAnchor?.eventId,
                localUnreadFloorTime = maxNullable(
                    winner.localUnreadFloorTime,
                    loser.localUnreadFloorTime,
                ),
                oldestFetchedTime = minNullable(winner.oldestFetchedTime, loser.oldestFetchedTime),
                historyComplete = winner.historyComplete && loser.historyComplete,
            )
            bufferDao.update(result)
            aliasDao.repoint(loser.id, winner.id)
            canonicalTimeline.moveEventsToRoom(winner.networkId, loser.id, winner.id)
            aliasDao.copyMembers(loser.id, winner.id)
            aliasDao.deleteMembers(loser.id)
            aliasDao.copyReactions(loser.id, winner.id)
            aliasDao.deleteReactions(loser.id)
            mergeComposerDrafts(winner.id, loser.id)
            mergeHistoryCursors(winner.id, loser.id, result.historyComplete)
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

    /** Newest draft version wins a room merge; reply ids are opaque local references, not FKs. */
    private suspend fun mergeComposerDrafts(winnerId: RoomId, loserId: RoomId) {
        val drafts = db.composerDraftDao()
        val winner = drafts.byRoom(winnerId)
        val loser = drafts.byRoom(loserId)
        val selected = listOfNotNull(winner, loser).maxWithOrNull(
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
            val (winnerId, loserId) = parseRoomMergePresentationKey(key) ?: run {
                state.delete(key)
                return@forEach
            }
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
    ): BufferEntity? = db.withTransaction {
        val aliasDao = db.roomAliasDao()
        val oldAlias = aliasDao.byValue(
            networkId,
            RoomAliasNamespace.PROVISIONAL_NICK,
            normalizedOldNick,
        ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedOldNick)
            ?: return@withTransaction null
        val newAlias = aliasDao.byValue(
            networkId,
            RoomAliasNamespace.PROVISIONAL_NICK,
            normalizedNewNick,
        ) ?: aliasDao.byValue(networkId, RoomAliasNamespace.VERIFIED_NICK, normalizedNewNick)
        val oldAccounts = accountAliases(oldAlias.roomId)
        val newAccounts = newAlias?.let { accountAliases(it.roomId) }.orEmpty()
        val conflictingAccounts = oldAccounts.isNotEmpty() &&
            newAccounts.isNotEmpty() && oldAccounts.intersect(newAccounts).isEmpty()
        var room = if (
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
        db.roomAliasDao().forRoom(roomId)
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
            val newest = listOfNotNull(winner, loser).maxByOrNull {
                it.newestServerTime ?: Long.MIN_VALUE
            }
            val oldest = listOfNotNull(winner, loser).minByOrNull {
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

    private fun maxNullable(first: Long?, second: Long?): Long? =
        listOfNotNull(first, second).maxOrNull()

    private fun minNullable(first: Long?, second: Long?): Long? =
        listOfNotNull(first, second).minOrNull()
}

internal const val ROOM_MERGE_PRESENTATION_PREFIX = "room_merge_notification:"

internal fun roomMergePresentationKey(winnerId: RoomId, loserId: RoomId): String =
    "$ROOM_MERGE_PRESENTATION_PREFIX$winnerId:$loserId"

internal fun parseRoomMergePresentationKey(key: String): Pair<RoomId, RoomId>? {
    val ids = key.removePrefix(ROOM_MERGE_PRESENTATION_PREFIX).split(':')
    if (!key.startsWith(ROOM_MERGE_PRESENTATION_PREFIX) || ids.size != 2) return null
    return (ids[0].toLongOrNull() ?: return null) to (ids[1].toLongOrNull() ?: return null)
}
