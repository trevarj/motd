package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.EventAliasEntity
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.EventObservationEntity
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.InviteState
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
    val semanticPayload: String = event.eventPayload ?: event.text,
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

    data class Inserted(override val event: TimelineEventEntity) : IngestResult
    data class Merged(override val event: TimelineEventEntity) : IngestResult
    data class Enriched(override val event: TimelineEventEntity) : IngestResult
    data class Ignored(override val event: TimelineEventEntity, val reason: String) : IngestResult
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
class CanonicalTimelineStore @Inject constructor(
    private val db: MotdDatabase,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) {
    private val dao get() = db.canonicalTimelineDao()

    suspend fun ingest(observation: TimelineObservation): IngestResult = db.withTransaction {
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
                        batchSemanticMultiplicity = maxOf(
                            observation.batchSemanticMultiplicity,
                            semanticMultiplicities.getValue(batchSemanticKey(observation)),
                        ),
                        batchExactMultiplicity = maxOf(
                            observation.batchExactMultiplicity,
                            exactMultiplicities.getValue(exactKey),
                        ),
                        batchExactOrdinal = observation.batchExactOrdinal ?: ordinal,
                    ),
                )
            }
        }

    /** Attach a new durable attempt label/observation to the same failed canonical event. */
    suspend fun beginRetry(
        networkId: Long,
        eventId: TimelineEventId,
        label: String,
        connectionGeneration: Long?,
    ): TimelineEventEntity? = db.withTransaction {
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
                semanticFingerprint = digest(event.kind.name, event.normalizedActor, event.text),
                batchExactOrdinal = null,
                observedAt = System.currentTimeMillis(),
            ),
        )
        event.copy(pendingLabel = label, failed = false)
    }

    suspend fun claimSound(eventId: TimelineEventId): Boolean =
        dao.claimSound(eventId) == 1

    suspend fun claimNotification(eventId: TimelineEventId): Boolean =
        dao.claimNotification(eventId, NotificationClaimSession.owner) == 1

    suspend fun completeNotification(eventId: TimelineEventId) =
        dao.completeNotification(eventId)

    suspend fun releaseNotification(eventId: TimelineEventId) =
        dao.releaseNotification(eventId)

    /** Move canonical rows and rekey only the room-scoped aliases they already owned. */
    suspend fun moveEventsToRoom(networkId: Long, fromRoomId: RoomId, toRoomId: RoomId) =
        db.withTransaction {
            dao.eventsForRoom(fromRoomId).forEach { original ->
                val payload = original.eventPayload ?: original.text
                val ordinal = dao.batchExactOrdinal(original.id)
                val moved = original.copy(bufferId = toRoomId)
                val aliasMoves = buildList {
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
        val incoming = observation.event.copy(
            id = 0,
            serverTimeAuthoritative = observation.timeProvenance == TimeProvenance.SERVER_TAG,
        )
        val observedAt = System.currentTimeMillis()
        val receiveOrder = dao.nextReceiveOrder(observation.networkId)
        val aliases = aliasesFor(observation, incoming)
        val msgidCandidate = aliases.firstOrNull { it.first == EventAliasNamespace.MSGID }
            ?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
        val labelCandidate = aliases.firstOrNull { it.first == EventAliasNamespace.LABEL }
            ?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
        val batchPositionCandidate = aliases.firstOrNull {
            it.first == EventAliasNamespace.BATCH_POSITION
        }?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
        val fingerprintCandidate = aliases.firstOrNull {
            it.first == EventAliasNamespace.EXACT_FINGERPRINT
        }?.let { (namespace, value) -> dao.eventByAlias(observation.networkId, namespace, value) }
        val typedCandidate = aliases.firstOrNull { it.first == EventAliasNamespace.TYPED_EVENT }
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

        var candidate = msgidCandidate
            ?: compatible(labelCandidate, incoming)
            ?: compatible(batchPositionCandidate, incoming)
            ?: compatible(fingerprintCandidate, incoming)
            ?: compatible(typedCandidate, incoming)

        val provisionalCandidate = if (
            observation.timeProvenance == TimeProvenance.SERVER_TAG &&
            observation.batchSemanticMultiplicity == 1 &&
            (observation.origin == ObservationOrigin.HISTORY || incoming.msgid != null)
        ) {
            dao.provisionalCandidates(
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
            candidate = dao.selfIdentityFreeCandidates(
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
            candidate = dao.selfJoinCycleCandidates(
                incoming.bufferId,
                incoming.normalizedActor,
                incoming.text,
            ).singleOrNull()
        }

        if (candidate == null &&
            incoming.msgid == null &&
            observation.origin == ObservationOrigin.LIVE
        ) {
            candidate = dao.durableDeliveryCandidates(
                incoming.bufferId,
                incoming.kind,
                incoming.normalizedActor,
                incoming.text,
                incoming.serverTime - DELIVERY_RECONCILIATION_WINDOW_MS,
                incoming.serverTime + DELIVERY_RECONCILIATION_WINDOW_MS,
            ).singleOrNull()
        }

        if (candidate == null && incoming.isSelf && observation.origin != ObservationOrigin.LOCAL_SEND) {
            candidate = dao.orderedSelfCandidates(
                incoming.bufferId,
                incoming.kind,
                incoming.normalizedActor,
                incoming.text,
            ).firstOrNull()?.takeIf { compatible(it, incoming) != null }
        }

        val initial: TimelineEventEntity
        val inserted: Boolean
        if (candidate == null) {
            val eventId = dao.insertEvent(incoming)
            initial = incoming.copy(id = eventId)
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

        val enriched = enrich(
            canonical,
            incoming,
            observation.timeProvenance,
            observation.selfAttributionAuthoritative,
        )
        if (enriched != canonical) {
            dao.updateEvent(enriched)
            db.bufferDao().retimeLocalReadAnchor(enriched.id, enriched.serverTime)
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
                batchExactOrdinal = observation.batchExactOrdinal
                    ?: if (observation.timeProvenance == TimeProvenance.SERVER_TAG) 0 else null,
                observedAt = observedAt,
            ),
        )

        if (observation.persistHistoryCursor) updateHistoryCursor(observation, canonical)

        incoming.msgid?.let { msgid ->
            dao.resolveReplies(canonical.bufferId, msgid, canonical.id)
            dao.resolveReactions(canonical.bufferId, msgid, canonical.id)
        }
        val replyId = incoming.replyToMsgid?.let { replyMsgid ->
            dao.eventByAlias(
                observation.networkId,
                EventAliasNamespace.MSGID,
                bytes(replyMsgid),
            )?.id
        }
        if (replyId != null && canonical.replyToEventId == null) {
            canonical = canonical.copy(replyToEventId = replyId)
            dao.updateEvent(canonical)
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
        if (observation.origin != ObservationOrigin.HISTORY) return
        val cursorDao = db.historyCursorDao()
        val current = cursorDao.byRoom(event.bufferId)
        val oldest = current?.oldestServerTime
        val newest = current?.newestServerTime
        cursorDao.upsert(
            HistoryCursorEntity(
                roomId = event.bufferId,
                newestMsgid = if (newest == null || event.serverTime >= newest) {
                    event.msgid ?: current?.newestMsgid
                } else {
                    current.newestMsgid
                },
                newestServerTime = maxOf(newest ?: event.serverTime, event.serverTime),
                oldestMsgid = if (oldest == null || event.serverTime <= oldest) {
                    event.msgid ?: current?.oldestMsgid
                } else {
                    current.oldestMsgid
                },
                oldestServerTime = minOf(oldest ?: event.serverTime, event.serverTime),
                historyComplete = current?.historyComplete == true,
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
        db.bufferDao().repointLocalReadAnchors(loser.id, winner.id, merged.serverTime)
        dao.repointAliases(loser.id, winner.id)
        dao.repointObservations(loser.id, winner.id)
        dao.repointReplies(loser.id, winner.id)
        db.composerDraftDao().repointReplies(loser.id, winner.id)
        dao.repointReactions(loser.id, winner.id)
        dao.repointEventRedirects(loser.id, winner.id)
        dao.upsertEventRedirect(EventRedirectEntity(loser.id, winner.id))
        dao.deleteEvent(loser.id)
        return merged
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
            serverTime = if (authoritative && !existing.serverTimeAuthoritative) {
                incoming.serverTime
            } else {
                existing.serverTime
            },
            sender = if (authoritative && incoming.sender.isNotEmpty()) incoming.sender else existing.sender,
            normalizedActor = if (authoritative) incoming.normalizedActor else existing.normalizedActor,
            senderAccount = existing.senderAccount ?: incoming.senderAccount,
            text = when {
                existing.pendingLabel != null -> incoming.text
                authoritative && !existing.serverTimeAuthoritative -> incoming.text
                else -> existing.text
            },
            isSelf = if (selfAttributionAuthoritative) {
                incoming.isSelf
            } else {
                existing.isSelf || incoming.isSelf
            },
            hasMention = existing.hasMention || incoming.hasMention,
            replyToMsgid = existing.replyToMsgid ?: incoming.replyToMsgid,
            replyToEventId = existing.replyToEventId ?: incoming.replyToEventId,
            pendingLabel = if (incoming.pendingLabel == null) null else existing.pendingLabel,
            failed = if (incoming.pendingLabel == null) false else existing.failed,
            eventKey = existing.eventKey ?: incoming.eventKey,
            eventPayload = existing.eventPayload ?: incoming.eventPayload,
            inviteState = mergeInviteState(existing.inviteState, incoming.inviteState),
            serverTimeAuthoritative = existing.serverTimeAuthoritative || authoritative,
            notificationHandled = existing.notificationHandled || incoming.notificationHandled,
            notificationClaimed = existing.notificationClaimed || incoming.notificationClaimed,
            notificationClaimOwner = existing.notificationClaimOwner ?: incoming.notificationClaimOwner,
            soundHandled = existing.soundHandled || incoming.soundHandled,
        )
    }

    /** Historical is only provenance; actionable and terminal local states must win every replay order. */
    private fun mergeInviteState(existing: InviteState?, incoming: InviteState?): InviteState? =
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
    ): List<Pair<EventAliasNamespace, ByteArray>> = buildList {
        event.msgid?.let { add(EventAliasNamespace.MSGID to bytes(it)) }
        observation.label?.let { add(EventAliasNamespace.LABEL to labelBytes(it)) }
        if (observation.timeProvenance == TimeProvenance.SERVER_TAG) {
            val ordinal = observation.batchExactOrdinal ?: 0
            if (ordinal == 0) {
                add(
                    EventAliasNamespace.EXACT_FINGERPRINT to
                        exactFingerprint(event, observation.semanticPayload),
                )
            }
            add(
                EventAliasNamespace.BATCH_POSITION to batchPositionFingerprint(
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

    private fun exactFingerprint(event: TimelineEventEntity, semanticPayload: String): ByteArray = digest(
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
    ): ByteArray = digest(
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
    ): ByteArray = digest(
        event.kind.name,
        event.normalizedActor,
        observation.semanticPayload,
    )

    private fun batchFingerprintKey(observation: TimelineObservation): List<Any?> = listOf(
        observation.networkId,
        observation.event.bufferId,
        observation.event.kind,
        observation.event.normalizedActor,
        observation.semanticPayload,
        observation.event.serverTime,
    )

    private fun batchSemanticKey(observation: TimelineObservation): List<Any?> = listOf(
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

    private fun provenanceOf(event: TimelineEventEntity): TimeProvenance =
        if (event.serverTimeAuthoritative) TimeProvenance.SERVER_TAG else TimeProvenance.LOCAL_CLOCK

    private fun TimelineEventEntity.hasContentConflict(other: TimelineEventEntity): Boolean =
        kind != other.kind || normalizedActor != other.normalizedActor || text != other.text

    private companion object {
        const val DELIVERY_RECONCILIATION_WINDOW_MS = 2_000L
        const val PROVISIONAL_RECONCILIATION_WINDOW_MS = 30_000L
    }
}
