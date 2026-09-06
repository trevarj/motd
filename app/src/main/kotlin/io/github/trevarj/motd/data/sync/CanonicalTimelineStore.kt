package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.EventAliasEntity
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.EventObservationEntity
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventEntity
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One delivery-path representation of a canonical timeline event. */
data class TimelineObservation(
    val networkId: Long,
    val event: TimelineEventEntity,
    val origin: ObservationOrigin,
    val connectionGeneration: Long?,
    val label: String? = event.pendingLabel,
    val batchId: String?,
    val timeProvenance: TimeProvenance,
    val semanticPayload: String = event.eventPayload ?: event.ircFormattedText ?: event.text,
    /** Reconciliation is conservative when a history batch contains genuine repeated payloads. */
    val batchSemanticMultiplicity: Int = 1,
    /** Exact aliases remain safe when repeated payloads have distinct authoritative timestamps. */
    val batchExactMultiplicity: Int = 1,
    /** Stable zero-based occurrence among identical exact representations in one ordered batch. */
    val batchExactOrdinal: Int? = null,
    /** Protocol page writers persist exact primary boundaries after ingesting all context events. */
    val persistHistoryCursor: Boolean = true,
    /** The observation has enough protocol context to replace, rather than only promote, direction. */
    val selfAttributionAuthoritative: Boolean = false,
)

sealed interface IngestResult {
    val event: TimelineEventEntity

    data class Inserted(
        override val event: TimelineEventEntity,
    ) : IngestResult

    data class Merged(
        override val event: TimelineEventEntity,
    ) : IngestResult

    data class Enriched(
        override val event: TimelineEventEntity,
    ) : IngestResult

    data class Ignored(
        override val event: TimelineEventEntity,
        val reason: String,
    ) : IngestResult
}

/** One owner per process so startup recovery only releases claims left by an earlier process. */
internal object NotificationClaimSession {
    val owner: String = UUID.randomUUID().toString()
}

/**
 * The single transactional identity boundary for timeline writes. Durable identity is represented
 * by aliases, never by a mutable key on the display row. All delivery paths attach observations to
 * one stable local id, with network-scoped case-sensitive msgids taking precedence over every
 * weaker reconciliation signal.
 */
@Singleton
class CanonicalTimelineStore
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    ) {
        private val dao get() = db.canonicalTimelineDao()

        suspend fun ingest(observation: TimelineObservation): IngestResult =
            db.withTransaction {
                ingestInTransaction(observation)
            }

        suspend fun ingestBatch(observations: List<TimelineObservation>): List<IngestResult> =
            db.withTransaction {
                val semanticMultiplicities = observations.groupingBy(::batchSemanticKey).eachCount()
                val exactMultiplicities = observations.groupingBy(::batchFingerprintKey).eachCount()
                val seenExact = mutableMapOf<List<Any?>, Int>()
                observations.map { observation ->
                    val exactKey = batchFingerprintKey(observation)
                    val ordinal = seenExact.getOrDefault(exactKey, 0)
                    seenExact[exactKey] = ordinal + 1
                    ingestInTransaction(
                        observation.copy(
                            batchSemanticMultiplicity =
                                maxOf(
                                    observation.batchSemanticMultiplicity,
                                    semanticMultiplicities.getValue(batchSemanticKey(observation)),
                                ),
                            batchExactMultiplicity =
                                maxOf(
                                    observation.batchExactMultiplicity,
                                    exactMultiplicities.getValue(exactKey),
                                ),
                            batchExactOrdinal = observation.batchExactOrdinal ?: ordinal,
                        ),
                    )
                }
            }

        /**
         * Reconcile completed server playback with provisional local tie ordering. Server time remains
         * the primary key; this only orders events sharing the same timestamp (including the stable
         * unknown-history sentinel). A later contradictory confirmed batch is diagnosed and cannot
         * make the UI oscillate.
         */
        suspend fun reconcilePlaybackOrder(
            orderedEventIds: List<TimelineEventId>,
            insertedEventIds: Set<TimelineEventId>,
            prependUnanchored: Boolean,
        ) = db.withTransaction {
            val canonicalIds =
                buildList {
                    for (eventId in orderedEventIds) add(dao.canonicalEventId(eventId))
                }.distinct()
            val canonicalInsertedIds =
                buildSet {
                    for (eventId in insertedEventIds) add(dao.canonicalEventId(eventId))
                }
            val incomingEvents = canonicalIds.mapNotNull { id -> dao.eventById(id) }
            incomingEvents.groupBy { it.bufferId to it.serverTime }.forEach { (key, incoming) ->
                val existing = dao.eventsAtTime(key.first, key.second)
                if (existing.size <= 1) {
                    incoming.forEachIndexed { index, event ->
                        dao.updateTimelineOrder(event.id, index.toLong(), confirmed = true)
                        db.historyGapDao().repointEventBoundary(
                            event.id,
                            event.id,
                            event.serverTime,
                            index.toLong(),
                        )
                    }
                    return@forEach
                }

                val incomingIds = incoming.map(TimelineEventEntity::id).distinct()
                val incomingSet = incomingIds.toSet()
                val existingBefore = existing.filterNot { it.id in canonicalInsertedIds }
                val existingById = existingBefore.associateBy(TimelineEventEntity::id)
                val common = incomingIds.filter(existingById::containsKey)
                val existingCommon = existingBefore.map(TimelineEventEntity::id).filter(incomingSet::contains)
                val confirmedConflict =
                    common != existingCommon &&
                        common.all { id ->
                            existingById.getValue(id).timelineOrderConfirmed
                        }

                val merged = existingBefore.map(TimelineEventEntity::id).toMutableList()
                incomingIds.forEachIndexed { index, id ->
                    if (id in merged) return@forEachIndexed
                    val previous = incomingIds.take(index).lastOrNull(merged::contains)
                    val next = incomingIds.drop(index + 1).firstOrNull(merged::contains)
                    val insertion =
                        when {
                            previous != null -> merged.indexOf(previous) + 1
                            next != null -> merged.indexOf(next)
                            prependUnanchored -> 0
                            else -> merged.size
                        }
                    merged.add(insertion, id)
                }

                if (!confirmedConflict) {
                    val positions = merged.indices.filter { merged[it] in incomingSet }
                    positions.zip(incomingIds).forEach { (position, id) -> merged[position] = id }
                } else {
                    diagnostics.record("history", "playback_order_conflict") {
                        mapOf(
                            "buffer_id" to key.first,
                            "server_time" to key.second,
                            "events" to incomingIds.size,
                        )
                    }
                }

                merged.forEachIndexed { index, id ->
                    dao.updateTimelineOrder(
                        eventId = id,
                        timelineOrder = index.toLong(),
                        confirmed = id in incomingSet,
                    )
                    dao.eventById(id)?.let { event ->
                        db.historyGapDao().repointEventBoundary(
                            id,
                            id,
                            event.serverTime,
                            index.toLong(),
                        )
                    }
                }
            }
        }

        /** Attach a new durable attempt label/observation to the same failed canonical event. */
        suspend fun beginRetry(
            networkId: Long,
            eventId: TimelineEventId,
            label: String,
            connectionGeneration: Long?,
        ): TimelineEventEntity? =
            db.withTransaction {
                val canonicalId = dao.canonicalEventId(eventId)
                val event = dao.eventById(canonicalId) ?: return@withTransaction null
                if (db.messageDao().beginRetry(canonicalId, label) != 1) return@withTransaction null
                dao.insertAliasIgnore(
                    EventAliasEntity(
                        networkId = networkId,
                        namespace = EventAliasNamespace.LABEL,
                        value = bytes(label),
                        timelineEventId = canonicalId,
                    ),
                )
                dao.insertObservation(
                    EventObservationEntity(
                        networkId = networkId,
                        timelineEventId = canonicalId,
                        origin = ObservationOrigin.LOCAL_SEND,
                        connectionGeneration = connectionGeneration,
                        receiveOrder = dao.nextReceiveOrder(networkId),
                        batchId = null,
                        timeProvenance = TimeProvenance.LOCAL_CLOCK,
                        semanticFingerprint =
                            digest(event.kind.name, event.normalizedActor, event.ircFormattedText ?: event.text),
                        batchExactOrdinal = null,
                        observedAt = System.currentTimeMillis(),
                    ),
                )
                event.copy(pendingLabel = label, failed = false)
            }

        suspend fun replanPendingLocalSend(
            networkId: Long,
            eventId: TimelineEventId,
            oldLabel: String,
            events: List<OutgoingEventPlan>,
            connectionGeneration: Long?,
        ): List<TimelineEventEntity>? =
            db.withTransaction {
                if (events.isEmpty()) return@withTransaction null
                val canonicalId = dao.canonicalEventId(eventId)
                val original = dao.eventById(canonicalId) ?: return@withTransaction null
                if (original.pendingLabel != oldLabel || original.msgid != null || original.failed || !original.isSelf) {
                    return@withTransaction null
                }

                dao.deleteOwnedAlias(networkId, EventAliasNamespace.LABEL, bytes(oldLabel), canonicalId)
                val firstPlan = events.first()
                val first =
                    original.copy(
                        kind = firstPlan.kind,
                        text = firstPlan.text,
                        ircFormattedText = firstPlan.ircFormattedText,
                        pendingLabel = firstPlan.label,
                        dedupKey = SemanticIdentity.pendingKey(firstPlan.label),
                        failed = false,
                    )
                dao.updateEvent(first)
                insertLocalSendAttempt(networkId, first, firstPlan.label, connectionGeneration)

                buildList {
                    add(first)
                    events.drop(1).forEach { plan ->
                        val inserted =
                            first.copy(
                                id = 0,
                                kind = plan.kind,
                                text = plan.text,
                                ircFormattedText = plan.ircFormattedText,
                                pendingLabel = plan.label,
                                dedupKey = SemanticIdentity.pendingKey(plan.label),
                                notificationWatched = false,
                                notificationHandled = false,
                                notificationClaimed = false,
                                notificationClaimOwner = null,
                                soundHandled = false,
                            )
                        val insertedId = dao.insertEvent(inserted)
                        val event = inserted.copy(id = insertedId, timelineOrder = insertedId)
                        dao.updateEvent(event)
                        insertLocalSendAttempt(networkId, event, plan.label, connectionGeneration)
                        add(event)
                    }
                }
            }

        suspend fun claimSound(eventId: TimelineEventId): Boolean = dao.claimSound(eventId) == 1

        suspend fun claimNotification(eventId: TimelineEventId): Boolean = dao.claimNotification(eventId, NotificationClaimSession.owner) == 1

        suspend fun completeNotification(eventId: TimelineEventId) = dao.completeNotification(eventId)

        suspend fun releaseNotification(eventId: TimelineEventId) = dao.releaseNotification(eventId)

        /** Move canonical rows and rekey only the room-scoped aliases they already owned. */
        suspend fun moveEventsToRoom(
            networkId: Long,
            fromRoomId: RoomId,
            toRoomId: RoomId,
        ) = db.withTransaction {
            dao.eventsForRoom(fromRoomId).forEach { original ->
                val payload = original.eventPayload ?: original.text
                val ordinal = dao.batchExactOrdinal(original.id)
                val moved = original.copy(bufferId = toRoomId)
                val aliasMoves =
                    buildList {
                        add(
                            Triple(
                                EventAliasNamespace.EXACT_FINGERPRINT,
                                exactFingerprint(original, payload),
                                exactFingerprint(moved, payload),
                            ),
                        )
                        ordinal?.let {
                            add(
                                Triple(
                                    EventAliasNamespace.BATCH_POSITION,
                                    batchPositionFingerprint(original, payload, it),
                                    batchPositionFingerprint(moved, payload, it),
                                ),
                            )
                        }
                    }.filter { (namespace, oldValue, _) ->
                        dao.aliasByValue(networkId, namespace, oldValue)?.timelineEventId == original.id
                    }
                aliasMoves.forEach { (namespace, oldValue, _) ->
                    dao.deleteOwnedAlias(
                        networkId,
                        namespace,
                        oldValue,
                        original.id,
                    )
                }

                dao.updateEvent(moved)
                var currentEventId = moved.id
                aliasMoves.forEach { (namespace, _, newValue) ->
                    val existing = dao.aliasByValue(networkId, namespace, newValue)
                    if (existing == null) {
                        dao.insertAliasIgnore(
                            EventAliasEntity(
                                networkId = networkId,
                                namespace = namespace,
                                value = newValue,
                                timelineEventId = currentEventId,
                            ),
                        )
                    } else if (existing.timelineEventId != currentEventId) {
                        val other = dao.eventById(existing.timelineEventId)
                        val current = dao.eventById(currentEventId)
                        if (other != null && current != null && compatible(other, current) != null) {
                            currentEventId = coalesce(current, other, current).id
                        }
                    }
                }
            }
        }

        private suspend fun ingestInTransaction(observation: TimelineObservation): IngestResult {
            val incoming =
                observation.event.copy(
                    id = 0,
                    serverTimeAuthoritative = observation.timeProvenance == TimeProvenance.SERVER_TAG,
                    timeProvenance = observation.timeProvenance,
                )
            val observedAt = System.currentTimeMillis()
            val receiveOrder = dao.nextReceiveOrder(observation.networkId)
            val aliases = aliasesFor(observation, incoming)
            val msgidCandidate =
                aliases
                    .firstOrNull { it.first == EventAliasNamespace.MSGID }
                    ?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
            val labelCandidate =
                aliases
                    .firstOrNull { it.first == EventAliasNamespace.LABEL }
                    ?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
            val batchPositionCandidate =
                aliases
                    .firstOrNull {
                        it.first == EventAliasNamespace.BATCH_POSITION
                    }?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
            val fingerprintCandidate =
                aliases
                    .firstOrNull {
                        it.first == EventAliasNamespace.EXACT_FINGERPRINT
                    }?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
            val typedCandidate =
                aliases
                    .firstOrNull { it.first == EventAliasNamespace.TYPED_EVENT }
                    ?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }

            if (msgidCandidate != null && msgidCandidate.hasContentConflict(incoming)) {
                diagnostics.record("irc_protocol", "msgid_content_conflict") {
                    mapOf(
                        "network_id" to observation.networkId,
                        "event_id" to msgidCandidate.id,
                        "msgid_fp" to diagnostics.fingerprint(incoming.msgid),
                        "existing_body_fp" to diagnostics.fingerprint(msgidCandidate.text),
                        "incoming_body_fp" to diagnostics.fingerprint(incoming.text),
                        "existing_kind" to msgidCandidate.kind.name,
                        "incoming_kind" to incoming.kind.name,
                    )
                }
            }

            var candidate =
                msgidCandidate
                    ?: compatible(labelCandidate, incoming)
                    ?: compatible(batchPositionCandidate, incoming)
                    ?: compatible(fingerprintCandidate, incoming)
                    ?: compatible(typedCandidate, incoming)

            val provisionalCandidate =
                if (
                    observation.timeProvenance == TimeProvenance.SERVER_TAG &&
                    observation.batchSemanticMultiplicity == 1 &&
                    (observation.origin == ObservationOrigin.HISTORY || incoming.msgid != null)
                ) {
                    dao
                        .provisionalCandidates(
                            incoming.bufferId,
                            incoming.kind,
                            incoming.normalizedActor,
                            incoming.text,
                        ).singleOrNull()
                } else {
                    null
                }
            candidate = candidate ?: provisionalCandidate

            if (candidate == null &&
                incoming.isSelf &&
                incoming.msgid != null &&
                observation.batchSemanticMultiplicity == 1
            ) {
                // A bare echo may already have cleared its pending label before CHATHISTORY supplies
                // the durable msgid. Unique ordered self correlation remains safe; repeated identical
                // sends produce multiple candidates and therefore stay distinct.
                candidate =
                    dao
                        .selfIdentityFreeCandidates(
                            incoming.bufferId,
                            incoming.kind,
                            incoming.normalizedActor,
                            incoming.text,
                            incoming.serverTime - PROVISIONAL_RECONCILIATION_WINDOW_MS,
                            incoming.serverTime + PROVISIONAL_RECONCILIATION_WINDOW_MS,
                        ).singleOrNull()
            }

            if (candidate == null &&
                incoming.eventKey?.startsWith("selfjoin:") == true &&
                observation.origin == ObservationOrigin.LIVE &&
                observation.batchSemanticMultiplicity == 1
            ) {
                candidate =
                    dao
                        .selfJoinCycleCandidates(
                            incoming.bufferId,
                            incoming.normalizedActor,
                            incoming.text,
                        ).singleOrNull()
            }

            if (candidate == null &&
                incoming.msgid == null &&
                observation.origin == ObservationOrigin.LIVE
            ) {
                candidate =
                    dao
                        .durableDeliveryCandidates(
                            incoming.bufferId,
                            incoming.kind,
                            incoming.normalizedActor,
                            incoming.text,
                            incoming.serverTime - DELIVERY_RECONCILIATION_WINDOW_MS,
                            incoming.serverTime + DELIVERY_RECONCILIATION_WINDOW_MS,
                        ).singleOrNull()
            }

            if (candidate == null && incoming.isSelf && observation.origin != ObservationOrigin.LOCAL_SEND) {
                candidate =
                    dao
                        .orderedSelfCandidates(
                            incoming.bufferId,
                            incoming.kind,
                            incoming.normalizedActor,
                            incoming.text,
                        ).firstOrNull()
                        ?.takeIf { compatible(it, incoming) != null }
            }

            val initial: TimelineEventEntity
            val inserted: Boolean
            if (candidate == null) {
                val eventId = dao.nextTimelineEventId()
                initial = incoming.copy(id = eventId, timelineOrder = eventId)
                dao.insertEvent(initial)
                inserted = true
            } else {
                initial = candidate
                inserted = false
            }

            var canonical = initial
            for ((namespace, value) in aliases) {
                val existingAlias = dao.aliasByValue(observation.networkId, namespace, value)
                if (existingAlias != null && existingAlias.timelineEventId != canonical.id) {
                    val other = dao.eventById(existingAlias.timelineEventId) ?: continue
                    if (compatible(other, incoming) == null) continue
                    canonical = coalesce(canonical, other, incoming)
                } else if (existingAlias == null) {
                    dao.insertAliasIgnore(
                        EventAliasEntity(
                            networkId = observation.networkId,
                            namespace = namespace,
                            value = value,
                            timelineEventId = canonical.id,
                        ),
                    )
                }
            }
            if (provisionalCandidate != null && provisionalCandidate.id != canonical.id) {
                canonical = coalesce(canonical, provisionalCandidate, incoming)
            }

            val enriched =
                applySelfSendSortFloor(
                    canonical,
                    enrich(
                        canonical,
                        incoming,
                        observation.timeProvenance,
                        observation.selfAttributionAuthoritative,
                    ),
                )
            if (enriched != canonical) {
                dao.updateEvent(enriched)
                db.bufferDao().retimeLocalReadAnchor(enriched.id, enriched.serverTime)
                db.historyGapDao().repointEventBoundary(
                    enriched.id,
                    enriched.id,
                    enriched.serverTime,
                    enriched.timelineOrder,
                )
                canonical = enriched
            }

            dao.insertObservation(
                EventObservationEntity(
                    networkId = observation.networkId,
                    timelineEventId = canonical.id,
                    origin = observation.origin,
                    connectionGeneration = observation.connectionGeneration,
                    receiveOrder = receiveOrder,
                    batchId = observation.batchId,
                    timeProvenance = observation.timeProvenance,
                    semanticFingerprint = semanticFingerprint(observation, incoming),
                    batchExactOrdinal =
                        observation.batchExactOrdinal
                            ?: if (observation.timeProvenance == TimeProvenance.SERVER_TAG) 0 else null,
                    observedAt = observedAt,
                ),
            )

            if (observation.persistHistoryCursor) updateHistoryCursor(observation, canonical)

            incoming.msgid?.let { msgid ->
                dao.resolveReplies(canonical.bufferId, msgid, canonical.id)
                dao.resolveReactions(canonical.bufferId, msgid, canonical.id)
            }
            val replyId =
                incoming.replyToMsgid?.let { replyMsgid ->
                    dao
                        .eventByAlias(
                            observation.networkId,
                            EventAliasNamespace.MSGID,
                            bytes(replyMsgid),
                        )?.id
                }
            if (replyId != null && canonical.replyToEventId == null) {
                canonical = canonical.copy(replyToEventId = replyId)
                dao.updateEvent(canonical)
            }

            if (canonical.kind !in MONITOR_EXCLUDED_KINDS) {
                db.bufferDao().refreshMonitorActivity(canonical.bufferId)
            }

            return when {
                inserted -> IngestResult.Inserted(canonical)
                canonical != initial -> IngestResult.Enriched(canonical)
                else -> IngestResult.Merged(canonical)
            }
        }

        private suspend fun updateHistoryCursor(
            observation: TimelineObservation,
            event: TimelineEventEntity,
        ) {
            if (
                observation.origin != ObservationOrigin.HISTORY ||
                observation.timeProvenance != TimeProvenance.SERVER_TAG
            ) {
                return
            }
            val cursorDao = db.historyCursorDao()
            val current = cursorDao.byRoom(event.bufferId)
            val oldest = current?.oldestServerTime
            val newest = current?.newestServerTime
            cursorDao.upsert(
                HistoryCursorEntity(
                    roomId = event.bufferId,
                    // Boundary fields are one atomic reference. Never retain a msgid from a
                    // different event when the timestamp endpoint advances.
                    newestMsgid =
                        if (newest == null || event.serverTime >= newest) {
                            event.msgid
                        } else {
                            current.newestMsgid
                        },
                    newestServerTime = maxOf(newest ?: event.serverTime, event.serverTime),
                    oldestMsgid =
                        if (oldest == null || event.serverTime <= oldest) {
                            event.msgid
                        } else {
                            current.oldestMsgid
                        },
                    oldestServerTime = minOf(oldest ?: event.serverTime, event.serverTime),
                    historyComplete = current?.historyComplete == true,
                ),
            )
        }

        private suspend fun insertLocalSendAttempt(
            networkId: Long,
            event: TimelineEventEntity,
            label: String,
            connectionGeneration: Long?,
        ) {
            dao.insertAliasIgnore(
                EventAliasEntity(
                    networkId = networkId,
                    namespace = EventAliasNamespace.LABEL,
                    value = bytes(label),
                    timelineEventId = event.id,
                ),
            )
            dao.insertObservation(
                EventObservationEntity(
                    networkId = networkId,
                    timelineEventId = event.id,
                    origin = ObservationOrigin.LOCAL_SEND,
                    connectionGeneration = connectionGeneration,
                    receiveOrder = dao.nextReceiveOrder(networkId),
                    batchId = null,
                    timeProvenance = TimeProvenance.LOCAL_CLOCK,
                    semanticFingerprint =
                        digest(event.kind.name, event.normalizedActor, event.ircFormattedText ?: event.text),
                    batchExactOrdinal = null,
                    observedAt = System.currentTimeMillis(),
                ),
            )
        }

        private fun compatible(
            existing: TimelineEventEntity?,
            incoming: TimelineEventEntity,
        ): TimelineEventEntity? {
            existing ?: return null
            val existingMsgid = existing.msgid
            val incomingMsgid = incoming.msgid
            return existing.takeUnless {
                existingMsgid != null && incomingMsgid != null && existingMsgid != incomingMsgid
            }
        }

        private suspend fun coalesce(
            first: TimelineEventEntity,
            second: TimelineEventEntity,
            incoming: TimelineEventEntity,
        ): TimelineEventEntity {
            val winner = if (first.id <= second.id) first else second
            val loser = if (winner.id == first.id) second else first
            if (compatible(winner, loser) == null) return first
            val merged = enrich(enrich(winner, loser, provenanceOf(loser)), incoming, provenanceOf(incoming))
            if (merged != winner) dao.updateEvent(merged)
            db.historyGapDao().repointEventBoundary(
                loser.id,
                winner.id,
                merged.serverTime,
                merged.timelineOrder,
            )
            db.historyGapDao().repointEventBoundary(
                winner.id,
                winner.id,
                merged.serverTime,
                merged.timelineOrder,
            )
            db.bufferDao().repointLocalReadAnchors(loser.id, winner.id, merged.serverTime)
            dao.repointAliases(loser.id, winner.id)
            dao.repointObservations(loser.id, winner.id)
            dao.compactObservations(winner.id)
            dao.repointReplies(loser.id, winner.id)
            db.composerDraftDao().repointReplies(loser.id, winner.id)
            dao.repointReactions(loser.id, winner.id)
            dao.repointEventRedirects(loser.id, winner.id)
            dao.upsertEventRedirect(EventRedirectEntity(loser.id, winner.id))
            dao.deleteEvent(loser.id)
            if (loser.kind !in MONITOR_EXCLUDED_KINDS) {
                db.bufferDao().refreshMonitorActivity(loser.bufferId)
            }
            return merged
        }

        /**
         * Hold a just-sent message at the bottom when its bouncer echo carries an older origin-server
         * time. A bouncer that fronts another network (e.g. soju in front of Libera) forwards the origin
         * server's `time` tag, whose clock can trail this device by tens of seconds; promoting the
         * optimistic device-clock row to that past time would otherwise re-sort it into the middle of the
         * buffer. Floor the promoted time at the newest authoritative row that already existed when the
         * send was staged. That floor is a real origin-server timestamp already stored here, so unlike a
         * device-clock clamp it never pushes any serverTime past reality — the history cursor,
         * `CHATHISTORY AFTER` boundary, and read-marker retime all stay honest.
         */
        private suspend fun applySelfSendSortFloor(
            existing: TimelineEventEntity,
            enriched: TimelineEventEntity,
        ): TimelineEventEntity {
            if (
                !existing.isSelf ||
                existing.pendingLabel == null ||
                existing.serverTimeAuthoritative ||
                !enriched.serverTimeAuthoritative
            ) {
                return enriched
            }
            val floor =
                dao.newestAuthoritativeServerTimeBefore(existing.bufferId, existing.id)
                    ?: return enriched
            if (enriched.serverTime >= floor) return enriched
            diagnostics.record("timeline", "self_echo_sort_clamp") {
                mapOf(
                    "buffer_id" to existing.bufferId,
                    "event_id" to existing.id,
                    "echo_delta_ms" to floor - enriched.serverTime,
                )
            }
            return enriched.copy(serverTime = floor)
        }

        private fun enrich(
            existing: TimelineEventEntity,
            incoming: TimelineEventEntity,
            provenance: TimeProvenance,
            selfAttributionAuthoritative: Boolean = false,
        ): TimelineEventEntity {
            val authoritative = provenance == TimeProvenance.SERVER_TAG
            return existing.copy(
                bufferId = existing.bufferId,
                msgid = existing.msgid ?: incoming.msgid,
                serverTime =
                    if (authoritative && !existing.serverTimeAuthoritative) {
                        incoming.serverTime
                    } else {
                        existing.serverTime
                    },
                sender = if (authoritative && incoming.sender.isNotEmpty()) incoming.sender else existing.sender,
                normalizedActor = if (authoritative) incoming.normalizedActor else existing.normalizedActor,
                senderAccount = existing.senderAccount ?: incoming.senderAccount,
                text =
                    when {
                        existing.kind == MessageKind.REDACTED -> existing.text
                        existing.pendingLabel != null -> incoming.text
                        authoritative && !existing.serverTimeAuthoritative -> incoming.text
                        else -> existing.text
                    },
                ircFormattedText =
                    when {
                        existing.kind == MessageKind.REDACTED -> null
                        existing.pendingLabel != null -> incoming.ircFormattedText
                        authoritative && !existing.serverTimeAuthoritative -> incoming.ircFormattedText
                        else -> existing.ircFormattedText
                    },
                isSelf =
                    if (selfAttributionAuthoritative) {
                        incoming.isSelf
                    } else {
                        existing.isSelf || incoming.isSelf
                    },
                isBot = existing.isBot || incoming.isBot,
                hasMention = existing.hasMention || incoming.hasMention,
                replyToMsgid = existing.replyToMsgid ?: incoming.replyToMsgid,
                replyToEventId = existing.replyToEventId ?: incoming.replyToEventId,
                channelContext = existing.channelContext ?: incoming.channelContext,
                pendingLabel = if (incoming.pendingLabel == null) null else existing.pendingLabel,
                failed = if (incoming.pendingLabel == null) false else existing.failed,
                eventKey = existing.eventKey ?: incoming.eventKey,
                eventPayload = existing.eventPayload ?: incoming.eventPayload,
                inviteState = mergeInviteState(existing.inviteState, incoming.inviteState),
                serverTimeAuthoritative = existing.serverTimeAuthoritative || authoritative,
                timeProvenance =
                    if (existing.serverTimeAuthoritative || authoritative) {
                        TimeProvenance.SERVER_TAG
                    } else {
                        existing.timeProvenance
                    },
                notificationWatched = existing.notificationWatched || incoming.notificationWatched,
                notificationHandled = existing.notificationHandled || incoming.notificationHandled,
                notificationClaimed = existing.notificationClaimed || incoming.notificationClaimed,
                notificationClaimOwner = existing.notificationClaimOwner ?: incoming.notificationClaimOwner,
                soundHandled = existing.soundHandled || incoming.soundHandled,
            )
        }

        /** Historical is only provenance; actionable and terminal local states must win every replay order. */
        private fun mergeInviteState(
            existing: InviteState?,
            incoming: InviteState?,
        ): InviteState? =
            when {
                existing == null -> incoming
                incoming == null -> existing
                existing == InviteState.HISTORICAL -> incoming
                incoming == InviteState.HISTORICAL -> existing
                else -> existing
            }

        private fun aliasesFor(
            observation: TimelineObservation,
            event: TimelineEventEntity,
        ): List<Pair<EventAliasNamespace, ByteArray>> =
            buildList {
                event.msgid?.let { add(EventAliasNamespace.MSGID to bytes(it)) }
                observation.label?.let { add(EventAliasNamespace.LABEL to labelBytes(it)) }
                if (
                    observation.timeProvenance == TimeProvenance.SERVER_TAG ||
                    observation.timeProvenance == TimeProvenance.UNKNOWN
                ) {
                    val ordinal = observation.batchExactOrdinal ?: 0
                    if (ordinal == 0) {
                        add(
                            EventAliasNamespace.EXACT_FINGERPRINT to
                                exactFingerprint(event, observation.semanticPayload),
                        )
                    }
                    add(
                        EventAliasNamespace.BATCH_POSITION to
                            batchPositionFingerprint(
                                event,
                                observation.semanticPayload,
                                ordinal,
                            ),
                    )
                }
                if (observation.batchSemanticMultiplicity == 1) {
                    event.eventKey?.let { add(EventAliasNamespace.TYPED_EVENT to bytes(it)) }
                }
            }

        // Caller-owned labels are globally unique opaque values and survive process/connection changes.
        private fun labelBytes(label: String): ByteArray = bytes(label)

        private fun exactFingerprint(
            event: TimelineEventEntity,
            semanticPayload: String,
        ): ByteArray =
            digest(
                event.bufferId.toString(),
                event.kind.name,
                event.normalizedActor,
                semanticPayload,
                event.serverTime.toString(),
            )

        private fun batchPositionFingerprint(
            event: TimelineEventEntity,
            semanticPayload: String,
            ordinal: Int,
        ): ByteArray =
            digest(
                event.bufferId.toString(),
                event.kind.name,
                event.normalizedActor,
                semanticPayload,
                event.serverTime.toString(),
                ordinal.toString(),
            )

        private fun semanticFingerprint(
            observation: TimelineObservation,
            event: TimelineEventEntity,
        ): ByteArray =
            digest(
                event.kind.name,
                event.normalizedActor,
                observation.semanticPayload,
            )

        private fun batchFingerprintKey(observation: TimelineObservation): List<Any?> =
            listOf(
                observation.networkId,
                observation.event.bufferId,
                observation.event.kind,
                observation.event.normalizedActor,
                observation.semanticPayload,
                observation.event.serverTime,
            )

        private fun batchSemanticKey(observation: TimelineObservation): List<Any?> =
            listOf(
                observation.networkId,
                observation.event.bufferId,
                observation.event.kind,
                observation.event.normalizedActor,
                observation.semanticPayload,
            )

        private fun digest(vararg values: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val encoded = bytes(value)
                digest.update((encoded.size ushr 24).toByte())
                digest.update((encoded.size ushr 16).toByte())
                digest.update((encoded.size ushr 8).toByte())
                digest.update(encoded.size.toByte())
                digest.update(encoded)
            }
            return digest.digest()
        }

        private fun bytes(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

        private fun provenanceOf(event: TimelineEventEntity): TimeProvenance = event.timeProvenance

        private fun TimelineEventEntity.hasContentConflict(other: TimelineEventEntity): Boolean = kind != other.kind || normalizedActor != other.normalizedActor || text != other.text

        private companion object {
            val MONITOR_EXCLUDED_KINDS =
                setOf(
                    MessageKind.JOIN,
                    MessageKind.PART,
                    MessageKind.QUIT,
                    MessageKind.AWAY,
                    MessageKind.BACK,
                    MessageKind.NETSPLIT,
                    MessageKind.NETJOIN,
                )
            const val DELIVERY_RECONCILIATION_WINDOW_MS = 2_000L
            const val PROVISIONAL_RECONCILIATION_WINDOW_MS = 30_000L
        }
    }
