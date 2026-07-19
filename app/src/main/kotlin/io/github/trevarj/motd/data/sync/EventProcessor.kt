package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.bouncer.redactBouncerServReply
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.replyReference
import io.github.trevarj.motd.irc.proto.unreactionValue
import io.github.trevarj.motd.service.IrcEventSink
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * The sole IRC→Room writer (plans/04 mapping table). Implements [IrcEventSink]: every per-network
 * collector, the catch-up path, the RemoteMediator, the pending-send insert, and the push path
 * funnel through [process] or [persistHistoryPage]. Never writes state from anywhere else.
 *
 * Per-network mutable helpers (self nick and immutable ISUPPORT identity rules) are kept in a small
 * [NetworkState] cache keyed by network id and rebuilt on Registered / NickChanged.
 */
@Singleton
class EventProcessor @Inject constructor(
    private val db: MotdDatabase,
    private val typing: TypingTrackerImpl,
    private val notifier: MessageNotifier,
    private val chatSoundPlayer: ChatSoundPlayer = ChatSoundPlayer.Noop,
    private val bufferStore: BufferStore = BufferStore(db),
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
) : IrcEventSink {

    private val networkDao get() = db.networkDao()
    private val bufferDao get() = db.bufferDao()
    private val messageDao get() = db.messageDao()
    private val memberDao get() = db.memberDao()
    private val reactionDao get() = db.reactionDao()
    private val userDao get() = db.userDao()
    private val sequencer = NetworkEventSequencer()

    private val states = ConcurrentHashMap<Long, NetworkState>()
    private val rosterSnapshots = ConcurrentHashMap<RosterKey, MutableList<RosterDelta>>()
    private val connectionGenerations = ConcurrentHashMap<Long, Long>()
    private val activeHistoryMultiplicities =
        ConcurrentHashMap<Long, Map<CanonicalBatchKey, CanonicalBatchMultiplicity>>()
    private val activeHistoryOccurrences =
        ConcurrentHashMap<Long, MutableMap<CanonicalBatchKey, Int>>()
    private val activeHistoryChatRoutes =
        ConcurrentHashMap<Long, ArrayDeque<ChatRoute>>()
    private val activeProtocolPageCursorWrites = ConcurrentHashMap.newKeySet<Long>()

    private data class RosterKey(val networkId: Long, val bufferId: Long)

    private data class CanonicalBatchKey(
        val roomId: Long,
        val kind: MessageKind,
        val normalizedActor: String,
        val text: String,
        val serverTime: Long,
    )

    private data class CanonicalSemanticBatchKey(
        val roomId: Long,
        val kind: MessageKind,
        val normalizedActor: String,
        val text: String,
    )

    private data class CanonicalBatchMultiplicity(
        val semantic: Int,
        val exact: Int,
    )

    private data class ChatRoute(
        val bufferId: RoomId,
        val bufferName: String,
        val type: BufferType,
        val storedText: String,
        val serverNotice: Boolean,
        val sourceIsSelf: Boolean,
    )

    private data class ReactionRoute(
        val bufferName: String,
        val type: BufferType,
    )

    private sealed interface RosterDelta {
        data class Upsert(val nick: String) : RosterDelta
        data class Remove(val nick: String) : RosterDelta
        data class Rename(val from: String, val to: String) : RosterDelta
        data class DeferredQuit(val event: IrcEvent.Quit) : RosterDelta
        data class DeferredNick(val event: IrcEvent.NickChanged) : RosterDelta
        data class Prefix(val nick: String, val prefix: Char, val adding: Boolean) : RosterDelta
    }

    private data class DeferredRosterPresentation(
        val ctx: MessageContext,
        val kind: MessageKind,
        val sender: String,
        val text: String,
    )

    private data class RosterReplay(
        val members: List<MemberEntity>,
        val presentations: List<DeferredRosterPresentation>,
    )

    /** Per-network state for self-nick tracking and the server's exact identity rules. */
    private class NetworkState(
        @Volatile var selfNick: String,
        val identityRules: IrcIdentityRules,
        @Volatile var prefixModes: Map<Char, Char> = emptyMap(),
        @Volatile var chanModes: List<Set<Char>> = emptyList(),
    ) {
        fun setNick(nick: String) {
            selfNick = nick
        }

        fun normalize(name: String): String = identityRules.normalize(name)

        fun isChannel(target: String): Boolean = identityRules.isChannel(target)

        fun actorKey(nick: String, account: String?): String = identityRules.actorKey(nick, account)

        fun containsSelfMention(text: String): Boolean = identityRules.containsMention(text, selfNick)
    }

    private suspend fun stateFor(networkId: Long): NetworkState =
        states.getOrPut(networkId) {
            val n = networkDao.byId(networkId)
            NetworkState(selfNick = n?.nick ?: "", identityRules = IrcIdentityRules())
        }

    /** Test/setup seam; production registration enters through [process]. */
    internal suspend fun onRegistered(networkId: Long, nick: String, isupport: Map<String, String>) {
        sequencer.withNetwork(networkId) { applyRegistered(networkId, nick, isupport) }
    }

    private suspend fun applyRegistered(networkId: Long, nick: String, isupport: Map<String, String>) {
        connectionGenerations[networkId] = db.connectionGenerationDao().next(networkId)
        val identityRules = IrcIdentityRules.from(
            rawCaseMapping = isupport["CASEMAPPING"],
            advertisedChanTypes = isupport["CHANTYPES"],
        )
        states[networkId] = NetworkState(
            selfNick = nick,
            identityRules = identityRules,
            prefixModes = parsePrefixModes(isupport["PREFIX"]),
            chanModes = isupport["CHANMODES"]?.split(',')?.map(String::toSet).orEmpty(),
        )
        identityRules.caseMapping.diagnostic?.let { diagnostic ->
            diagnostics.record("irc_protocol", "unsupported_casemapping") {
                mapOf("network_id" to networkId, "diagnostic" to diagnostic)
            }
        }
    }

    override suspend fun process(networkId: Long, event: IrcEvent) {
        sequencer.withNetwork(networkId) {
            processEvent(networkId, event, EventOrigin.LIVE)
            bufferStore.drainCommittedRoomMerges()
        }
    }

    override suspend fun processPush(networkId: Long, event: IrcEvent) {
        sequencer.withNetwork(networkId) {
            processEvent(networkId, event, EventOrigin.PUSH)
            bufferStore.drainCommittedRoomMerges()
        }
    }

    /** Persist one event according to its provenance and, for history, its enclosing target. */
    private suspend fun processEvent(
        networkId: Long,
        event: IrcEvent,
        origin: EventOrigin,
        historyTarget: String? = null,
    ) {
        diagnostics.record("event_processor", "event_received") {
            mapOf(
                "network_id" to networkId,
                "origin" to origin.name,
                "type" to event::class.simpleName,
            )
        }
        if (!origin.accepts(event)) {
            diagnostics.record("event_processor", "event_ignored") {
                mapOf("network_id" to networkId, "origin" to origin.name, "type" to event::class.simpleName)
            }
            return
        }
        when (event) {
            is IrcEvent.Registered -> if (origin.mutatesSessionState) {
                applyRegistered(networkId, event.nick, event.isupport)
            }
            is IrcEvent.ChatMessage -> onChat(networkId, event, origin, historyTarget)
            is IrcEvent.TagMessage -> onTag(networkId, event, origin, historyTarget)
            is IrcEvent.HistoryBatch -> onHistoryBatch(networkId, event)
            is IrcEvent.NetworkBatch -> onNetworkBatch(networkId, event, origin, historyTarget)
            is IrcEvent.Joined -> if (origin == EventOrigin.LIVE) onJoined(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalJoined(networkId, event)
            is IrcEvent.Parted -> if (origin == EventOrigin.LIVE) onParted(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalParted(networkId, event)
            is IrcEvent.Quit -> if (origin == EventOrigin.LIVE) onQuit(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalQuit(networkId, event, historyTarget)
            is IrcEvent.Kicked -> if (origin == EventOrigin.LIVE) onKicked(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalKicked(networkId, event)
            is IrcEvent.NickChanged -> if (origin == EventOrigin.LIVE) onNickChanged(networkId, event) else if (origin == EventOrigin.HISTORY) onHistoricalNickChanged(networkId, event, historyTarget)
            is IrcEvent.NamesStarted -> if (origin.mutatesSessionState) onNamesStarted(networkId, event)
            is IrcEvent.Names -> if (origin.mutatesSessionState) onNames(networkId, event)
            is IrcEvent.TopicSnapshot -> if (origin.mutatesSessionState) onTopicSnapshot(networkId, event)
            is IrcEvent.TopicChanged -> when (origin) {
                EventOrigin.LIVE -> onTopicChanged(networkId, event)
                EventOrigin.HISTORY -> onHistoricalTopicChanged(networkId, event)
                EventOrigin.PUSH -> Unit
            }
            is IrcEvent.ModeChanged -> when (origin) {
                EventOrigin.LIVE -> onModeChanged(networkId, event)
                EventOrigin.HISTORY -> onHistoricalModeChanged(networkId, event)
                EventOrigin.PUSH -> Unit
            }
            is IrcEvent.AwayChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(away = event.awayMessage != null) }
            is IrcEvent.AccountChanged -> if (origin.mutatesSessionState) onAccountChanged(networkId, event)
            is IrcEvent.HostChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(hostmask = "${event.newUser}@${event.newHost}") }
            is IrcEvent.RealnameChanged -> if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(realname = event.realname) }
            is IrcEvent.WhoxRow -> if (origin.mutatesSessionState) onWhoxRow(networkId, event)
            is IrcEvent.WhoxComplete -> Unit
            is IrcEvent.MonitorOnline -> if (origin.mutatesSessionState) onMonitorOnline(networkId, event)
            is IrcEvent.MonitorOffline,
            is IrcEvent.MonitorList,
            is IrcEvent.MonitorListEnd,
            -> Unit
            is IrcEvent.MonitorLimitExceeded -> if (origin.mutatesSessionState) onMonitorLimitExceeded(networkId, event)
            is IrcEvent.Invited -> onInvited(networkId, event, origin)
            is IrcEvent.ReadMarker -> if (origin.mutatesSessionState) onReadMarker(networkId, event)
            is IrcEvent.BouncerNetworkState -> if (origin.mutatesSessionState) onBouncerNetworkState(networkId, event)
            is IrcEvent.Disconnected -> if (origin.mutatesSessionState) onDisconnected(networkId, event)
            is IrcEvent.ServerError -> if (origin.mutatesSessionState) onServerError(networkId, event)
            is IrcEvent.Raw -> onRaw(networkId, event, origin, historyTarget)
            is IrcEvent.CapsChanged,
            -> Unit // not persisted
        }
    }

    // -- chat / tags ---------------------------------------------------------

    private suspend fun onChat(
        networkId: Long,
        e: IrcEvent.ChatMessage,
        origin: EventOrigin,
        historyTarget: String?,
    ) {
        val st = stateFor(networkId)
        val route = if (origin == EventOrigin.HISTORY) {
            activeHistoryChatRoutes[networkId]?.removeFirstOrNull()
                ?: resolveChatRoute(networkId, e, st, historyTarget)
        } else {
            resolveChatRoute(networkId, e, st, historyTarget = null)
        }
        if (route.serverNotice) {
            insertSystem(
                route.bufferId,
                e.ctx,
                MessageKind.NOTICE,
                e.source.nick,
                route.storedText,
                origin = origin,
            )
            return
        }
        val bufferId = route.bufferId
        val bufferName = route.bufferName
        val type = route.type
        val storedText = route.storedText
        val sourceIsSelf = route.sourceIsSelf
        val isDm = type == BufferType.QUERY
        val isBouncerServQuery = isDm && bufferName.equals("BouncerServ", ignoreCase = true)
        val isRootServiceReply = isBouncerServQuery && !sourceIsSelf &&
            e.kind == IrcEvent.ChatKind.PRIVMSG && networkDao.byId(networkId)?.role == NetworkRole.BOUNCER_ROOT

        val replyReference = e.replyToMsgid
        val replyMentionsSelf = if (!sourceIsSelf && replyReference != null) {
            messageDao.byMsgid(bufferId, replyReference)?.let { parent ->
                parent.isSelf || st.normalize(parent.sender) == st.normalize(st.selfNick)
            } == true
        } else {
            false
        }
        val hasMention = !sourceIsSelf && !isRootServiceReply &&
            (replyMentionsSelf || st.containsSelfMention(storedText))
        val identitySender = st.normalize(e.source.nick)

        traceMessageDecision("message_classified", networkId, bufferId, e, origin) {
            mapOf(
                "buffer_type" to type.name,
                "mention" to hasMention,
                "root_service" to isRootServiceReply,
            )
        }

        val row = MessageEntity(
            bufferId = bufferId,
            msgid = e.ctx.msgid,
            serverTime = e.ctx.serverTime,
            sender = e.source.nick,
            normalizedActor = identitySender,
            senderAccount = e.ctx.account,
            kind = kindOf(e.kind),
            text = storedText,
            isSelf = sourceIsSelf,
            hasMention = hasMention,
            replyToMsgid = e.replyToMsgid,
            dedupKey = SemanticIdentity.keyFor(e.ctx, identitySender, storedText),
            serverTimeAuthoritative = e.ctx.serverTimeSource == ServerTimeSource.TAG,
        )

        run {
            val batchKey = CanonicalBatchKey(
                bufferId,
                row.kind,
                identitySender,
                storedText,
                row.serverTime,
            )
            val multiplicity = activeHistoryMultiplicities[networkId]?.get(batchKey)
            val result = canonicalTimeline.ingest(
                TimelineObservation(
                    networkId = networkId,
                    event = row,
                    origin = origin.toObservationOrigin(),
                    connectionGeneration = connectionGenerations[networkId],
                    label = e.ctx.label,
                    batchId = e.ctx.batchId,
                    timeProvenance = e.ctx.serverTimeSource.toTimeProvenance(),
                    batchSemanticMultiplicity = multiplicity?.semantic ?: 1,
                    batchExactMultiplicity = multiplicity?.exact ?: 1,
                    batchExactOrdinal = nextHistoryExactOrdinal(networkId, batchKey),
                    persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                ),
            )
            val canonical = result.event
            traceMessageWrite(
                when (result) {
                    is IngestResult.Inserted -> "canonical_insert"
                    is IngestResult.Enriched -> "canonical_enrich"
                    is IngestResult.Merged -> "canonical_merge"
                    is IngestResult.Ignored -> "canonical_ignore"
                },
                canonical,
                origin == EventOrigin.HISTORY,
            )
            if (isRootServiceReply && origin == EventOrigin.LIVE) {
                bufferDao.advanceReadMarker(canonical.bufferId, canonical.serverTime)
                return
            }
            if (origin == EventOrigin.LIVE && !sourceIsSelf && canonicalTimeline.claimSound(canonical.id)) {
                try {
                    chatSoundPlayer.onIncoming(canonical.bufferId, type, e.copy(text = canonical.text))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    diagnostics.record("chat_sound", "incoming_failed") {
                        mapOf(
                            "network_id" to networkId,
                            "buffer_id" to canonical.bufferId,
                            "event_id" to canonical.id,
                            "error" to error::class.simpleName,
                        )
                    }
                }
            }
            if (origin.notifies &&
                !sourceIsSelf &&
                type != BufferType.SERVER &&
                (type == BufferType.QUERY || hasMention)
            ) {
                presentNotification(canonical.id) {
                    maybeNotify(
                        networkId,
                        canonical.bufferId,
                        type,
                        canonical.hasMention,
                        canonical.id,
                        e.copy(
                            ctx = e.ctx.copy(
                                msgid = canonical.msgid,
                                serverTime = canonical.serverTime,
                                account = canonical.senderAccount,
                            ),
                            text = canonical.text,
                            isSelf = sourceIsSelf,
                            replyToMsgid = canonical.replyToMsgid,
                        ),
                    )
                }
            }
            return
        }

    }

    private suspend fun onTag(
        networkId: Long,
        e: IrcEvent.TagMessage,
        origin: EventOrigin,
        historyTarget: String?,
    ) {
        val st = stateFor(networkId)
        val route = resolveReactionRoute(e.source.nick, e.target, historyTarget, st)
        // typing: routed to tracker, never persisted.
        if (origin == EventOrigin.LIVE) e.typing?.let { typingState ->
            val bufferId = ensureBuffer(networkId, route.bufferName, route.type, st)
            typing.onTyping(bufferId, e.source.nick, typingState)
        }
        // React rows are emoji-specific; an account-tag echo also removes the optimistic nick key.
        val emoji = e.reactEmoji
        val targetMsgid = e.reactTargetMsgid
        if (emoji != null && targetMsgid != null) {
            val bufferId = existingReactionRoomId(networkId, route, st) ?: return
            // Keep an orphan temporarily when the target's echo/history row is still in flight.
            // Reaction queries are scoped to visible msgids, so it becomes visible atomically when
            // the parent arrives and remains inert if the reference never resolves.
            val account = e.ctx.account ?: if (origin == EventOrigin.LIVE) {
                userDao.byNick(networkId, st.normalize(e.source.nick))?.account
            } else {
                null
            }
            val actorKey = st.actorKey(e.source.nick, account)
            val nickKey = st.actorKey(e.source.nick, account = null)
            deleteLegacyReactionAliases(bufferId, targetMsgid, e.source.nick, emoji)
            if (actorKey != nickKey) {
                reactionDao.delete(bufferId, targetMsgid, nickKey, emoji)
            }
            reactionDao.upsert(
                ReactionEntity(
                    bufferId = bufferId,
                    targetMsgid = targetMsgid,
                    actorKey = actorKey,
                    sender = e.source.nick,
                    emoji = emoji,
                    serverTime = e.ctx.serverTime,
                    targetEventId = db.canonicalTimelineDao().eventByAlias(
                        networkId,
                        EventAliasNamespace.MSGID,
                        targetMsgid.toByteArray(Charsets.UTF_8),
                    )?.id,
                ),
            )
        }
    }

    private suspend fun onHistoryBatch(networkId: Long, batch: IrcEvent.HistoryBatch) {
        // All events for one target are applied in a single Room transaction (idempotent by
        // dedupKey). They are historical replay, never live arrivals: persist them without posting
        // notifications even when a previously-missing row is a DM or mention.
        diagnostics.record("history", "batch_started") {
            mapOf(
                "network_id" to networkId,
                "target_fp" to diagnostics.fingerprint(batch.target),
                "events" to batch.events.size,
            )
        }
        try {
            db.withTransaction {
                activeHistoryChatRoutes[networkId] = ArrayDeque()
                activeHistoryMultiplicities[networkId] = canonicalBatchMultiplicities(networkId, batch)
                activeHistoryOccurrences[networkId] = mutableMapOf()
                for (ev in batch.events) processEvent(networkId, ev, EventOrigin.HISTORY, batch.target)
            }
        } finally {
            activeHistoryMultiplicities.remove(networkId)
            activeHistoryOccurrences.remove(networkId)
            activeHistoryChatRoutes.remove(networkId)
        }
        diagnostics.record("history", "batch_finished") {
            mapOf(
                "network_id" to networkId,
                "target_fp" to diagnostics.fingerprint(batch.target),
                "events" to batch.events.size,
            )
        }
    }

    /**
     * Persist one completed CHATHISTORY page and its protocol boundary in the same writer-owned
     * transaction. Context events remain ingestible but cannot become the next page cursor.
     */
    override suspend fun persistHistoryPage(
        networkId: Long,
        request: ChatHistoryRequest,
        response: ChatHistoryResponse.Messages,
    ): RoomId = sequencer.withNetwork(networkId) {
        require(request.subcommand != ChatHistoryRequest.Subcommand.TARGETS) {
            "TARGETS is not a message page"
        }
        val roomId = db.withTransaction {
            val initialRoomId = historicalTargetBuffer(networkId, request.target)
                ?: error("missing history target ${request.target}")
            val initialCanonicalId = bufferDao.canonicalId(initialRoomId) ?: initialRoomId
            val before = db.historyCursorDao().byRoom(initialCanonicalId)

            if (response.events.isNotEmpty()) {
                activeProtocolPageCursorWrites += networkId
                try {
                    processEvent(
                        networkId,
                        IrcEvent.HistoryBatch(request.target, response.events),
                        EventOrigin.LIVE,
                    )
                } finally {
                    activeProtocolPageCursorWrites -= networkId
                }
            }

            val canonicalRoomId = bufferDao.canonicalId(initialCanonicalId) ?: initialCanonicalId
            val after = db.historyCursorDao().byRoom(canonicalRoomId)
            val base = after ?: before
            val baseOldest = base?.let {
                ChatHistoryReference(
                    it.oldestMsgid,
                    it.oldestServerTime,
                )
            }?.takeIf { it.msgid != null || it.serverTime != null }
            val baseNewest = base?.let {
                ChatHistoryReference(
                    it.newestMsgid,
                    it.newestServerTime,
                )
            }?.takeIf { it.msgid != null || it.serverTime != null }
            // Union page metadata with the post-ingest cursor. The latter may now belong to a
            // lower-id room winner and can contain extents that predate this request target.
            val oldest = olderBoundary(baseOldest, response.oldest)
            val newest = newerBoundary(baseNewest, response.newest)
            val provesStart = request.subcommand == ChatHistoryRequest.Subcommand.BEFORE ||
                (request.subcommand == ChatHistoryRequest.Subcommand.LATEST &&
                    request.bound1 == null && request.bound2 == null)
            val complete = provesStart &&
                (response.endOfHistory || response.primaryMessageCount == 0)
            db.historyCursorDao().upsert(
                HistoryCursorEntity(
                    roomId = canonicalRoomId,
                    newestMsgid = newest?.msgid,
                    newestServerTime = newest?.serverTime,
                    oldestMsgid = oldest?.msgid,
                    oldestServerTime = oldest?.serverTime,
                    historyComplete = complete || base?.historyComplete == true,
                ),
            )
            bufferDao.setOldestFetchedTime(canonicalRoomId, oldest?.serverTime)
            if (complete) bufferDao.markHistoryComplete(canonicalRoomId)
            canonicalRoomId
        }
        bufferStore.drainCommittedRoomMerges()
        roomId
    }

    private suspend fun canonicalBatchMultiplicities(
        networkId: Long,
        batch: IrcEvent.HistoryBatch,
    ): Map<CanonicalBatchKey, CanonicalBatchMultiplicity> {
        val st = stateFor(networkId)
        val keys = batch.events.mapNotNull { historyBatchKey(networkId, batch.target, it, st) }
            .map { key ->
                key.copy(roomId = bufferDao.canonicalId(key.roomId) ?: key.roomId)
            }
        activeHistoryChatRoutes[networkId]?.let { routes ->
            activeHistoryChatRoutes[networkId] = ArrayDeque(
                routes.map { route ->
                    route.copy(bufferId = bufferDao.canonicalId(route.bufferId) ?: route.bufferId)
                },
            )
        }
        val exactCounts = keys.groupingBy { it }.eachCount()
        val semanticCounts = keys.groupingBy {
            CanonicalSemanticBatchKey(it.roomId, it.kind, it.normalizedActor, it.text)
        }.eachCount()
        return exactCounts.mapValues { (key, exact) ->
            CanonicalBatchMultiplicity(
                semantic = semanticCounts.getValue(
                    CanonicalSemanticBatchKey(
                        key.roomId,
                        key.kind,
                        key.normalizedActor,
                        key.text,
                    ),
                ),
                exact = exact,
            )
        }
    }

    private suspend fun historyBatchKey(
        networkId: Long,
        target: String,
        event: IrcEvent,
        st: NetworkState,
    ): CanonicalBatchKey? {
        suspend fun channelRoom(name: String) = ensureBuffer(networkId, name, BufferType.CHANNEL, st)
        fun key(roomId: Long, kind: MessageKind, actor: String, text: String, time: Long) =
            CanonicalBatchKey(roomId, kind, st.normalize(actor), text, time)
        return when (event) {
            is IrcEvent.ChatMessage -> {
                val route = resolveChatRoute(networkId, event, st, target)
                activeHistoryChatRoutes[networkId]?.addLast(route)
                key(
                    route.bufferId,
                    kindOf(event.kind),
                    event.source.nick,
                    route.storedText,
                    event.ctx.serverTime,
                )
            }
            is IrcEvent.Joined -> key(
                channelRoom(event.channel), MessageKind.JOIN, event.nick,
                "${event.nick} joined", event.ctx.serverTime,
            )
            is IrcEvent.Parted -> key(
                channelRoom(event.channel), MessageKind.PART, event.nick,
                "${event.nick} left" + (event.reason?.let { " ($it)" } ?: ""),
                event.ctx.serverTime,
            )
            is IrcEvent.Quit -> historicalTargetBuffer(networkId, target)?.let {
                key(
                    it, MessageKind.QUIT, event.nick,
                    "${event.nick} quit" + (event.reason?.let { reason -> " ($reason)" } ?: ""),
                    event.ctx.serverTime,
                )
            }
            is IrcEvent.Kicked -> key(
                channelRoom(event.channel), MessageKind.KICK, event.by,
                "${event.nick} was kicked by ${event.by}" +
                    (event.reason?.let { " ($it)" } ?: ""),
                event.ctx.serverTime,
            )
            is IrcEvent.NickChanged -> historicalTargetBuffer(networkId, target)?.let {
                key(
                    it, MessageKind.NICK, event.from,
                    "${event.from} is now known as ${event.to}", event.ctx.serverTime,
                )
            }
            is IrcEvent.TopicChanged -> key(
                channelRoom(event.channel), MessageKind.TOPIC, event.setBy ?: "",
                "topic: ${event.topic}", event.ctx.serverTime,
            )
            is IrcEvent.ModeChanged -> if (isChannel(event.target, st)) {
                key(
                    channelRoom(event.target), MessageKind.MODE, "",
                    "mode ${event.modes} ${event.args.joinToString(" ")}".trim(),
                    event.ctx.serverTime,
                )
            } else {
                null
            }
            is IrcEvent.Invited -> {
                val selfInvite = st.normalize(event.nick) == st.normalize(st.selfNick)
                val validChannel = isChannel(event.channel, st)
                val existingChannel = if (validChannel) {
                    bufferDao.byName(networkId, st.normalize(event.channel))
                } else {
                    null
                }
                val roomId = when {
                    selfInvite && validChannel -> channelRoom(event.channel)
                    !selfInvite && existingChannel != null -> existingChannel.id
                    else -> ensureServerBuffer(networkId, st)
                }
                key(
                    roomId,
                    MessageKind.INVITE,
                    event.by,
                    InvitePayloadV1(event.by, event.nick, event.channel).encode(),
                    event.ctx.serverTime,
                )
            }
            else -> null
        }
    }

    private fun nextHistoryExactOrdinal(networkId: Long, key: CanonicalBatchKey): Int? {
        val multiplicity = activeHistoryMultiplicities[networkId]?.get(key) ?: return null
        if (multiplicity.exact <= 1) return null
        val occurrences = activeHistoryOccurrences[networkId] ?: return null
        val ordinal = occurrences.getOrDefault(key, 0)
        occurrences[key] = ordinal + 1
        return ordinal
    }


    private suspend fun onNetworkBatch(
        networkId: Long,
        batch: IrcEvent.NetworkBatch,
        origin: EventOrigin,
        historyTarget: String?,
    ) {
        if (batch.events.isEmpty()) return
        if (batch.kind == IrcEvent.NetworkBatchKind.NETSPLIT && batch.events.any { it !is IrcEvent.Quit }) return
        if (batch.kind == IrcEvent.NetworkBatchKind.NETJOIN && batch.events.any { it !is IrcEvent.Joined }) return
        if (origin == EventOrigin.HISTORY) {
            val target = batch.target ?: historyTarget ?: return
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, target, BufferType.CHANNEL, st)
            val children = batch.events.map { child ->
                when (child) {
                    is IrcEvent.Quit -> child.nick to child.ctx
                    is IrcEvent.Joined -> child.nick to child.ctx
                    else -> error("validated network batch child")
                }
            }
            insertNetworkBatch(bufferId, batch, children, st)
            return
        }
        if (origin != EventOrigin.LIVE) return
        val st = stateFor(networkId)
        val affected = LinkedHashMap<Long, MutableList<Pair<String, MessageContext>>>()
        db.withTransaction {
            when (batch.kind) {
                IrcEvent.NetworkBatchKind.NETSPLIT -> batch.events.forEach { child ->
                    val quit = child as IrcEvent.Quit
                    val targetBufferId = batch.target?.let { target ->
                        bufferDao.byName(networkId, st.normalize(target))?.id
                    }
                    (buffersOfNick(networkId, quit.nick) + listOfNotNull(targetBufferId)).distinct().forEach { bufferId ->
                        memberDao.remove(bufferId, quit.nick)
                        journal(networkId, bufferId, RosterDelta.Remove(quit.nick))
                        affected.getOrPut(bufferId) { mutableListOf() } += quit.nick to quit.ctx
                    }
                }
                IrcEvent.NetworkBatchKind.NETJOIN -> batch.events.forEach { child ->
                    val join = child as IrcEvent.Joined
                    val buffer = bufferDao.byName(networkId, st.normalize(join.channel))
                        ?: return@forEach
                    memberDao.upsert(MemberEntity(buffer.id, join.nick))
                    journal(networkId, buffer.id, RosterDelta.Upsert(join.nick))
                    upsertUser(networkId, join.nick) {
                        it.copy(
                            account = join.account ?: it.account,
                            realname = join.realname ?: it.realname,
                        )
                    }
                    affected.getOrPut(buffer.id) { mutableListOf() } += join.nick to join.ctx
                }
            }
            affected.forEach { (bufferId, children) ->
                insertNetworkBatch(bufferId, batch, children, st)
            }
        }
    }

    private suspend fun insertNetworkBatch(
        bufferId: Long,
        batch: IrcEvent.NetworkBatch,
        children: List<Pair<String, MessageContext>>,
        st: NetworkState,
    ) {
        if (children.isEmpty()) return
        val buffer = bufferDao.observeById(bufferId) ?: return
        val nicks = children.map { it.first }
        val msgids = children.map { it.second.msgid }
        val identities = children.map { (nick, ctx) ->
            ctx.msgid ?: "${st.normalize(nick)}@${ctx.serverTime}"
        }
        val pair = listOf(batch.serverA.lowercase(), batch.serverB.lowercase()).sorted().joinToString("|")
        val kind = if (batch.kind == IrcEvent.NetworkBatchKind.NETSPLIT) {
            MessageKind.NETSPLIT
        } else {
            MessageKind.NETJOIN
        }
        val diagnosticKey = "network:${kind.name.lowercase()}:$pair:${buffer.name}:" +
            SemanticIdentity.keyFor(null, 0, pair, identities.joinToString("|"))
        val eventKey = if (msgids.all { it != null }) diagnosticKey else null
        val verb = if (kind == MessageKind.NETSPLIT) "split" else "rejoined"
        val text = "${nicks.size} ${if (nicks.size == 1) "user" else "users"} $verb " +
            "(${batch.serverA} ↔ ${batch.serverB})"
        val row = MessageEntity(
            bufferId = bufferId,
            serverTime = children.maxOf { it.second.serverTime },
            sender = "",
            normalizedActor = "",
            kind = kind,
            text = text,
            dedupKey = diagnosticKey,
            eventKey = eventKey,
            eventPayload = NetworkBatchPayloadV1(batch.serverA, batch.serverB, nicks).encode(),
            serverTimeAuthoritative = children.all {
                it.second.serverTimeSource == ServerTimeSource.TAG
            },
        )
        val fromHistory = !children.any { it.second.batchId == null }
        val result = canonicalTimeline.ingest(
            TimelineObservation(
                networkId = buffer.networkId,
                event = row,
                origin = if (fromHistory) ObservationOrigin.HISTORY else ObservationOrigin.LIVE,
                connectionGeneration = connectionGenerations[buffer.networkId],
                batchId = children.firstNotNullOfOrNull { it.second.batchId },
                timeProvenance = if (row.serverTimeAuthoritative) {
                    TimeProvenance.SERVER_TAG
                } else {
                    TimeProvenance.LOCAL_CLOCK
                },
                persistHistoryCursor = buffer.networkId !in activeProtocolPageCursorWrites,
            ),
        )
        traceMessageWrite("canonical_network_batch", result.event, fromHistory)
    }

    // -- invitations --------------------------------------------------------

    private suspend fun onInvited(networkId: Long, e: IrcEvent.Invited, origin: EventOrigin) {
        val st = stateFor(networkId)
        val selfInvite = st.normalize(e.nick) == st.normalize(st.selfNick)
        val validChannel = isChannel(e.channel, st)
        val existingChannel = if (validChannel) {
            bufferDao.byName(networkId, st.normalize(e.channel))
        } else {
            null
        }
        val bufferId = when {
            selfInvite && validChannel -> ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            !selfInvite && existingChannel != null -> existingChannel.id
            else -> ensureServerBuffer(networkId, st)
        }
        val historical = origin == EventOrigin.HISTORY || e.ctx.batchId != null
        val actionable = selfInvite && validChannel && !historical
        val state = when {
            historical -> InviteState.HISTORICAL
            actionable -> InviteState.PENDING
            else -> InviteState.HISTORICAL
        }
        val payload = InvitePayloadV1(e.by, e.nick, e.channel)
        val eventKey = e.ctx.msgid?.let { "invite:msgid:$it" }
        val text = when {
            selfInvite && validChannel -> "${e.by.ifBlank { "Someone" }} invited you to ${e.channel}"
            validChannel -> "${e.by.ifBlank { "Someone" }} invited ${e.nick} to ${e.channel}"
            else -> "Received an invalid invitation for ${e.channel.ifBlank { "an unknown channel" }}"
        }
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = e.ctx.msgid,
            serverTime = e.ctx.serverTime,
            sender = e.by,
            normalizedActor = st.normalize(e.by),
            kind = MessageKind.INVITE,
            text = text,
            dedupKey = eventKey ?: SemanticIdentity.keyFor(
                null,
                e.ctx.serverTime,
                "${st.normalize(e.by)}|${st.normalize(e.nick)}|${st.normalize(e.channel)}",
                "INVITE",
            ),
            eventKey = eventKey,
            eventPayload = payload.encode(),
            inviteState = state,
            serverTimeAuthoritative = e.ctx.serverTimeSource == ServerTimeSource.TAG,
        )
        val multiplicity = activeHistoryMultiplicities[networkId]?.get(
            CanonicalBatchKey(
                bufferId,
                row.kind,
                row.normalizedActor,
                payload.encode(),
                row.serverTime,
            ),
        )
        val batchKey = CanonicalBatchKey(
            bufferId,
            row.kind,
            row.normalizedActor,
            payload.encode(),
            row.serverTime,
        )
        val result = canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = row,
                origin = origin.toObservationOrigin(),
                connectionGeneration = connectionGenerations[networkId],
                label = e.ctx.label,
                batchId = e.ctx.batchId,
                timeProvenance = e.ctx.serverTimeSource.toTimeProvenance(),
                batchSemanticMultiplicity = multiplicity?.semantic ?: 1,
                batchExactMultiplicity = multiplicity?.exact ?: 1,
                batchExactOrdinal = nextHistoryExactOrdinal(networkId, batchKey),
                persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
            ),
        )
        traceMessageWrite("canonical_invite", result.event, historical)
        if (actionable) {
            presentNotification(result.event.id) {
                notifier.onInvitation(networkId, result.event.bufferId, result.event.id)
            }
        }
    }

    // -- membership ----------------------------------------------------------

    private suspend fun onJoined(networkId: Long, e: IrcEvent.Joined) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        if (e.isSelf) markJoined(bufferId, true)
        memberDao.upsert(MemberEntity(bufferId, e.nick))
        if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Upsert(e.nick))
        upsertUser(networkId, e.nick) { it.copy(account = e.account ?: it.account, realname = e.realname ?: it.realname) }
        if (e.isSelf) {
            if (e.ctx.batchId == null) {
                val resolvedInviteIds = messageDao.actionableInviteIds(bufferId)
                if (resolvedInviteIds.isNotEmpty()) {
                    messageDao.markInvitesJoined(bufferId)
                    resolvedInviteIds.forEach { notifier.onInvitationResolved(it) }
                }
            }
            val cycle = db.bufferDao().observeById(bufferId)?.membershipCycle ?: 0
            insertSystem(
                bufferId,
                e.ctx,
                MessageKind.JOIN,
                e.nick,
                "${e.nick} joined",
                dedupKey = "selfjoin:$bufferId:$cycle",
                isSelf = true,
            )
        } else {
            insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined", isSelf = e.isSelf)
        }
    }

    private suspend fun onHistoricalJoined(networkId: Long, e: IrcEvent.Joined) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        // History uses msgid/exact identity. Attaching the current live membership-cycle alias to
        // an old replay could otherwise coalesce two genuine JOIN cycles.
        insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined", isSelf = e.isSelf)
    }

    private suspend fun onParted(networkId: Long, e: IrcEvent.Parted) {
        val st = stateFor(networkId)
        val buffer = bufferDao.byName(networkId, st.normalize(e.channel)) ?: return
        if (e.isSelf && buffer.pendingCloseAt != null) {
            // A self-PART is the direct/ZNC server acknowledgement for a queued close. Only now is
            // it safe to cascade-delete local history; the row stayed hidden while awaiting this.
            bufferDao.deleteBuffer(buffer.id)
            return
        }
        val bufferId = buffer.id
        db.withTransaction {
            memberDao.remove(bufferId, e.nick)
            if (e.isSelf) {
                rosterSnapshots.remove(RosterKey(networkId, bufferId))
                memberDao.clear(bufferId)
                markJoined(bufferId, false)
            } else if (e.ctx.batchId == null) {
                journal(networkId, bufferId, RosterDelta.Remove(e.nick))
            }
            insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
            if (e.isSelf) bufferDao.advanceMembershipCycle(bufferId)
        }
    }

    private suspend fun onHistoricalParted(networkId: Long, e: IrcEvent.Parted) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
    }

    private suspend fun onQuit(networkId: Long, e: IrcEvent.Quit) {
        // Fan out to every buffer the nick was a member of.
        val buffers = buffersOfNick(networkId, e.nick)
        for (bufferId in buffers) {
            memberDao.remove(bufferId, e.nick)
            if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Remove(e.nick))
            insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
        }
        if (e.ctx.batchId == null) {
            journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredQuit(e))
        }
    }

    private suspend fun onHistoricalQuit(networkId: Long, e: IrcEvent.Quit, target: String?) {
        val bufferId = historicalTargetBuffer(networkId, target) ?: return
        insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
    }

    private suspend fun onKicked(networkId: Long, e: IrcEvent.Kicked) {
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.channel))?.id ?: return
        db.withTransaction {
            memberDao.remove(bufferId, e.nick)
            if (e.isSelf) {
                rosterSnapshots.remove(RosterKey(networkId, bufferId))
                memberDao.clear(bufferId)
                markJoined(bufferId, false)
            } else if (e.ctx.batchId == null) {
                journal(networkId, bufferId, RosterDelta.Remove(e.nick))
            }
            insertSystem(bufferId, e.ctx, MessageKind.KICK, e.by, "${e.nick} was kicked by ${e.by}" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
            if (e.isSelf) bufferDao.advanceMembershipCycle(bufferId)
        }
    }

    private suspend fun onHistoricalKicked(networkId: Long, e: IrcEvent.Kicked) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.KICK, e.by, "${e.nick} was kicked by ${e.by}" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
    }

    private suspend fun onNickChanged(networkId: Long, e: IrcEvent.NickChanged) {
        val st = stateFor(networkId)
        if (e.isSelf) st.setNick(e.to)
        bufferStore.bindNickChange(
            networkId = networkId,
            normalizedOldNick = st.normalize(e.from),
            normalizedNewNick = st.normalize(e.to),
            displayNewNick = e.to,
        )
        // Rename member rows across every buffer that had the old nick.
        val buffers = buffersOfNick(networkId, e.from)
        for (bufferId in buffers) {
            memberDao.remove(bufferId, e.from)
            memberDao.upsert(MemberEntity(bufferId, e.to))
            if (e.ctx.batchId == null) {
                journal(networkId, bufferId, RosterDelta.Rename(e.from, e.to))
            }
            insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
        }
        if (e.ctx.batchId == null) {
            journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredNick(e))
        }
    }

    private suspend fun onAccountChanged(networkId: Long, event: IrcEvent.AccountChanged) {
        val st = stateFor(networkId)
        upsertUser(networkId, event.nick) { it.copy(account = event.account) }
        val account = event.account ?: return
        val room = bufferStore.resolveQueryRoom(
            networkId,
            st.normalize(event.nick),
            account = null,
        ) ?: return
        if (room.type == BufferType.QUERY) {
            bufferStore.bindQueryIdentity(
                roomId = room.id,
                networkId = networkId,
                normalizedNick = st.normalize(event.nick),
                displayNick = event.nick,
                account = account,
            )
        }
    }

    private suspend fun onHistoricalNickChanged(networkId: Long, e: IrcEvent.NickChanged, target: String?) {
        val bufferId = historicalTargetBuffer(networkId, target) ?: return
        insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
    }

    private suspend fun onNamesStarted(networkId: Long, e: IrcEvent.NamesStarted) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        rosterSnapshots.putIfAbsent(RosterKey(networkId, bufferId), mutableListOf())
    }

    private suspend fun onNames(networkId: Long, e: IrcEvent.Names) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        val deltas = rosterSnapshots.remove(RosterKey(networkId, bufferId)).orEmpty()
        val replay = replayRosterDeltas(
            bufferId,
            e.members.map { MemberEntity(bufferId, it.nick, it.prefixes) },
            deltas,
            st,
        )
        db.withTransaction {
            memberDao.replaceAll(bufferId, replay.members)
            e.members.forEach { member ->
                val username = member.username
                val host = member.host
                if (username != null && host != null) {
                    upsertUser(networkId, member.nick) {
                        it.copy(username = username, hostmask = "$username@$host")
                    }
                }
            }
            replay.presentations.forEach { presentation ->
                insertSystem(
                    bufferId,
                    presentation.ctx,
                    presentation.kind,
                    presentation.sender,
                    presentation.text,
                )
            }
        }
    }

    private suspend fun onWhoxRow(networkId: Long, row: IrcEvent.WhoxRow) {
        upsertUser(networkId, row.nick) { existing ->
            val hostmask = if (row.username != null && row.host != null) {
                "${row.username}@${row.host}"
            } else {
                existing.hostmask
            }
            existing.copy(
                username = row.username ?: existing.username,
                hostmask = hostmask,
                account = row.account,
                away = row.flags?.let { 'G' in it } ?: existing.away,
                realname = row.realname?.takeIf(String::isNotBlank) ?: existing.realname,
            )
        }
    }

    private suspend fun onMonitorOnline(networkId: Long, event: IrcEvent.MonitorOnline) {
        event.identities.forEach { identity ->
            val username = identity.user
            val host = identity.host
            if (username != null && host != null) {
                upsertUser(networkId, identity.nick) {
                    it.copy(username = username, hostmask = "$username@$host")
                }
            }
        }
    }

    private suspend fun onMonitorLimitExceeded(networkId: Long, event: IrcEvent.MonitorLimitExceeded) {
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        val targets = event.targets.joinToString(",")
        val text = buildString {
            append("MONITOR limit exceeded")
            event.limit?.let { append(" (").append(it).append(')') }
            if (targets.isNotEmpty()) append(": ").append(targets)
            if (event.text.isNotBlank()) append(" — ").append(event.text)
        }
        insertSystem(bufferId, serverCtx(), MessageKind.ERROR, "", text)
    }

    private suspend fun onTopicChanged(networkId: Long, e: IrcEvent.TopicChanged) {
        val st = stateFor(networkId)
        val buffer = bufferDao.byName(networkId, st.normalize(e.channel))
            ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
        bufferDao.setTopic(buffer.id, e.topic, e.setBy)
        insertSystem(buffer.id, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
    }

    /** Persist the 331/332 topic state received during JOIN without adding a fake topic change. */
    private suspend fun onTopicSnapshot(networkId: Long, e: IrcEvent.TopicSnapshot) {
        val st = stateFor(networkId)
        val buffer = bufferDao.byName(networkId, st.normalize(e.channel))
            ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
        bufferDao.setTopic(buffer.id, e.topic, setBy = null)
    }

    private suspend fun onHistoricalTopicChanged(networkId: Long, e: IrcEvent.TopicChanged) {
        val st = stateFor(networkId)
        val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
    }

    private suspend fun onModeChanged(networkId: Long, e: IrcEvent.ModeChanged) {
        val st = stateFor(networkId)
        if (!isChannel(e.target, st)) return
        val bufferId = bufferDao.byName(networkId, st.normalize(e.target))?.id ?: return
        applyPrefixModes(networkId, bufferId, e, st)
        insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
    }

    private suspend fun onHistoricalModeChanged(networkId: Long, e: IrcEvent.ModeChanged) {
        val st = stateFor(networkId)
        if (!isChannel(e.target, st)) return
        val bufferId = ensureBuffer(networkId, e.target, BufferType.CHANNEL, st)
        insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
    }

    private fun journal(networkId: Long, bufferId: Long, delta: RosterDelta) {
        rosterSnapshots[RosterKey(networkId, bufferId)]?.add(delta)
    }

    private fun journalAcrossActiveSnapshots(
        networkId: Long,
        alreadyPresented: Set<Long>,
        delta: RosterDelta,
    ) {
        rosterSnapshots.forEach { (key, journal) ->
            if (key.networkId == networkId && key.bufferId !in alreadyPresented) journal.add(delta)
        }
    }

    suspend fun cancelRosterSnapshot(networkId: Long, bufferId: Long) {
        sequencer.withNetwork(networkId) {
            rosterSnapshots.remove(RosterKey(networkId, bufferId))
        }
    }

    private fun replayRosterDeltas(
        bufferId: Long,
        snapshot: List<MemberEntity>,
        deltas: List<RosterDelta>,
        st: NetworkState,
    ): RosterReplay {
        val members = LinkedHashMap<String, MemberEntity>()
        val presentations = mutableListOf<DeferredRosterPresentation>()
        snapshot.forEach { members[st.normalize(it.nick)] = it }
        deltas.forEach { delta ->
            when (delta) {
                is RosterDelta.Upsert -> members.putIfAbsent(
                    st.normalize(delta.nick),
                    MemberEntity(bufferId, delta.nick),
                )
                is RosterDelta.Remove -> members.remove(st.normalize(delta.nick))
                is RosterDelta.Rename -> {
                    val old = members.remove(st.normalize(delta.from))
                    if (old != null) members[st.normalize(delta.to)] = old.copy(nick = delta.to)
                }
                is RosterDelta.DeferredQuit -> {
                    val event = delta.event
                    if (members.remove(st.normalize(event.nick)) != null) {
                        presentations += DeferredRosterPresentation(
                            event.ctx,
                            MessageKind.QUIT,
                            event.nick,
                            "${event.nick} quit" + (event.reason?.let { " ($it)" } ?: ""),
                        )
                    }
                }
                is RosterDelta.DeferredNick -> {
                    val event = delta.event
                    val old = members.remove(st.normalize(event.from))
                    if (old != null) {
                        members[st.normalize(event.to)] = old.copy(nick = event.to)
                        presentations += DeferredRosterPresentation(
                            event.ctx,
                            MessageKind.NICK,
                            event.from,
                            "${event.from} is now known as ${event.to}",
                        )
                    }
                }
                is RosterDelta.Prefix -> {
                    val key = st.normalize(delta.nick)
                    val member = members[key] ?: return@forEach
                    members[key] = member.copy(
                        prefixes = updatePrefixes(member.prefixes, delta.prefix, delta.adding, st),
                    )
                }
            }
        }
        return RosterReplay(members.values.toList(), presentations)
    }

    private suspend fun applyPrefixModes(
        networkId: Long,
        bufferId: Long,
        event: IrcEvent.ModeChanged,
        st: NetworkState,
    ) {
        var adding = true
        var argIndex = 0
        for (mode in event.modes) {
            when (mode) {
                '+' -> adding = true
                '-' -> adding = false
                else -> {
                    val prefix = st.prefixModes[mode]
                    val consumesArg = prefix != null || modeConsumesArgument(mode, adding, st.chanModes)
                    val argument = if (consumesArg) event.args.getOrNull(argIndex++) else null
                    if (prefix != null && argument != null) {
                        val member = memberDao.allNow(bufferId).firstOrNull {
                            st.normalize(it.nick) == st.normalize(argument)
                        }
                        if (member != null) {
                            memberDao.upsert(
                                member.copy(prefixes = updatePrefixes(member.prefixes, prefix, adding, st)),
                            )
                        }
                        if (event.ctx.batchId == null) {
                            journal(networkId, bufferId, RosterDelta.Prefix(argument, prefix, adding))
                        }
                    }
                }
            }
        }
    }

    private fun updatePrefixes(current: String, prefix: Char, adding: Boolean, st: NetworkState): String {
        val updated = if (adding) current.toSet() + prefix else current.toSet() - prefix
        val order = st.prefixModes.values.toList()
        return updated.sortedBy { order.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .joinToString("")
    }

    private fun modeConsumesArgument(mode: Char, adding: Boolean, chanModes: List<Set<Char>>): Boolean =
        mode in chanModes.getOrNull(0).orEmpty() ||
            mode in chanModes.getOrNull(1).orEmpty() ||
            (adding && mode in chanModes.getOrNull(2).orEmpty())

    // -- sync ---------------------------------------------------------------

    private suspend fun onReadMarker(networkId: Long, e: IrcEvent.ReadMarker) {
        val ts = e.timestamp ?: return
        val st = stateFor(networkId)
        val bufferId = bufferDao.byName(networkId, st.normalize(e.target))?.id ?: return
        bufferDao.advanceReadMarker(bufferId, ts)
        notifier.onRead(bufferId, ts)
        AutoFollowTrace.record("wire_markread_in", bufferId) { "marker=$ts" }
    }

    private suspend fun onBouncerNetworkState(networkId: Long, e: IrcEvent.BouncerNetworkState) {
        val root = networkDao.byId(networkId) ?: return
        // Only the bouncer ROOT connection materializes child networks. A bound child is scoped to
        // a single upstream network, but its soju connection still receives BOUNCER NETWORK
        // notifications; handling them here would spawn duplicate children parented to the child
        // itself, which cannot resolve a valid root to bind through and fail SASL 904 (#40).
        if (root.role != NetworkRole.BOUNCER_ROOT) return
        val existing = networkDao.childrenOf(root.id).firstOrNull { it.bouncerNetId == e.netId }
        // "*" attrs (empty map) signals deletion of the child network.
        if (e.attrs.isEmpty() && existing != null) {
            networkDao.deleteLocalTree(existing.id)
            return
        }
        if (e.attrs.isEmpty()) return
        val name = e.attrs["name"] ?: e.netId
        val host = e.attrs["host"] ?: root.host
        val port = e.attrs["port"]?.toIntOrNull() ?: root.port
        val nick = e.attrs["nickname"] ?: root.nick
        if (existing == null) {
            networkDao.insert(
                root.copy(
                    id = 0,
                    name = name,
                    role = NetworkRole.BOUNCER_CHILD,
                    parentId = root.id,
                    bouncerNetId = e.netId,
                    host = host,
                    port = port,
                    nick = nick,
                ),
            )
        } else {
            // Preserve the row's current name on update: it may be a user-set alias, and the
            // bouncer name is only authoritative when the child is first created above. Soju may
            // send a partial NETWORK notification; absent attrs mean "unchanged", not "use the
            // root defaults". Replacing child host/port/nick with root values changes its
            // connection fingerprint and restarts an otherwise healthy bound actor.
            networkDao.updateBouncerConnection(
                existing.id,
                e.attrs["host"] ?: existing.host,
                e.attrs["port"]?.toIntOrNull() ?: existing.port,
                e.attrs["nickname"] ?: existing.nick,
            )
        }
    }

    // -- server buffer (plans/16 §5.6) --------------------------------------

    /** ServerError → SERVER buffer, kind ERROR. The event carries no ctx, so use the wall clock. */
    private suspend fun onServerError(networkId: Long, e: IrcEvent.ServerError) {
        val st = stateFor(networkId)
        if (e.code in PART_ALREADY_CLOSED_NUMERICS) {
            val channel = e.params.firstOrNull { isChannel(it, st) }
            val buffer = channel?.let { bufferDao.byName(networkId, st.normalize(it)) }
            if (buffer?.pendingCloseAt != null) {
                // 403/442 confirms the server has no membership to leave. Treat that as the same
                // terminal acknowledgement as our echoed PART.
                bufferDao.deleteBuffer(buffer.id)
                return
            }
        }
        if (e.code in JOIN_ERROR_NUMERICS) {
            val channel = e.params.firstOrNull { isChannel(it, st) }
            val inviteBufferId = channel?.let { bufferDao.byName(networkId, st.normalize(it))?.id }
            if (inviteBufferId != null) {
                messageDao.failJoiningInvites(inviteBufferId, e.text.ifBlank { e.code })
            }
        }
        val bufferId = ensureServerBuffer(networkId, st)
        val text = "${e.code} ${e.text}".trim()
        insertSystem(bufferId, serverCtx(), MessageKind.ERROR, "", text)
    }

    /** Whitelisted informational numerics → SERVER buffer, kind SERVER_INFO (our nick dropped). */
    private suspend fun onRaw(
        networkId: Long,
        e: IrcEvent.Raw,
        origin: EventOrigin,
        historyTarget: String?,
    ) {
        if (removeReaction(networkId, e.message, origin, historyTarget)) return
        if (origin != EventOrigin.LIVE) return
        if (e.message.command !in SERVER_INFO_NUMERICS) return
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        // params[0] is our nick for these numerics; drop it and join the rest as the info line.
        val text = e.message.params.drop(1).joinToString(" ").trim()
        insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
    }

    /** Consume Raw `draft/unreact` at the sole reaction-persistence boundary. */
    private suspend fun removeReaction(
        networkId: Long,
        message: io.github.trevarj.motd.irc.proto.IrcMessage,
        origin: EventOrigin,
        historyTarget: String?,
    ): Boolean {
        if (message.command != "TAGMSG") return false
        val emoji = message.unreactionValue() ?: return false
        val targetMsgid = message.replyReference() ?: return true
        val source = message.source?.nick ?: return true
        val target = message.params.firstOrNull() ?: return true
        val st = stateFor(networkId)
        val route = resolveReactionRoute(source, target, historyTarget, st)
        val bufferId = existingReactionRoomId(networkId, route, st) ?: return true
        val account = message.tags["account"] ?: if (origin == EventOrigin.LIVE) {
            userDao.byNick(networkId, st.normalize(source))?.account
        } else {
            null
        }
        val actorKey = st.actorKey(source, account)
        val nickKey = st.actorKey(source, account = null)
        deleteLegacyReactionAliases(bufferId, targetMsgid, source, emoji)
        reactionDao.delete(bufferId, targetMsgid, actorKey, emoji)
        if (actorKey != nickKey) reactionDao.delete(bufferId, targetMsgid, nickKey, emoji)
        return true
    }

    /** Disconnected marker → SERVER buffer for cheap in-history reconnect visibility. */
    private suspend fun onDisconnected(networkId: Long, e: IrcEvent.Disconnected) {
        rosterSnapshots.keys.removeAll { it.networkId == networkId }
        messageDao.failJoiningInvitesForNetwork(networkId, e.reason ?: "disconnected")
        val st = stateFor(networkId)
        val bufferId = ensureServerBuffer(networkId, st)
        val text = "disconnected" + (e.reason?.let { ": $it" } ?: "")
        insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
    }

    /** A ctx for server-buffer rows: no msgid/label, server time = now (the events carry none). */
    private fun serverCtx(): MessageContext =
        MessageContext(
            msgid = null,
            serverTime = System.currentTimeMillis(),
            account = null,
            batchId = null,
            label = null,
            serverTimeSource = ServerTimeSource.LOCAL,
        )

    /** Find-or-create the per-network SERVER buffer (name "*"); mirrors ConnectionManager's. */
    private suspend fun ensureServerBuffer(networkId: Long, st: NetworkState): Long {
        bufferDao.byName(networkId, "*")?.let { return it.id }
        val displayName = networkDao.byId(networkId)?.name ?: "Server"
        return bufferStore.getOrCreate(networkId, "*", displayName, BufferType.SERVER).id
    }

    // -- pending-send insert path (delegated by ConnectionManagerImpl.sendMessage) --

    /** Recreate a TARGETS-discovered room even when its newest history page is empty. */
    suspend fun ensureHistoryRoom(networkId: Long, target: String): RoomId =
        sequencer.withNetwork(networkId) {
            val state = stateFor(networkId)
            val type = if (isChannel(target, state)) BufferType.CHANNEL else BufferType.QUERY
            bufferStore.getOrCreate(
                networkId = networkId,
                normalizedName = state.normalize(target),
                displayName = target,
                type = type,
            ).id
        }

    /**
     * Insert a locally-sent pending row so the "sole writer" rule holds. Returns the row id.
     * The echo (labeled ChatMessage) later updates it in place; a 30s timeout marks it failed.
     */
    suspend fun insertPending(bufferId: Long, label: String, sender: String, text: String, replyToMsgid: String?, kind: MessageKind): Long {
        val canonicalBuffer = requireNotNull(bufferDao.observeById(bufferId)) { "missing buffer $bufferId" }
        val networkId = canonicalBuffer.networkId
        return sequencer.withNetwork(networkId) {
            val now = System.currentTimeMillis()
            val row = MessageEntity(
                bufferId = canonicalBuffer.id,
                msgid = null,
                serverTime = now,
                sender = sender,
                normalizedActor = stateFor(networkId).normalize(sender),
                kind = kind,
                text = text,
                isSelf = true,
                hasMention = false,
                replyToMsgid = replyToMsgid,
                pendingLabel = label,
                dedupKey = SemanticIdentity.pendingKey(label),
                serverTimeAuthoritative = false,
            )
            val result = canonicalTimeline.ingest(
                TimelineObservation(
                    networkId = networkId,
                    event = row,
                    origin = ObservationOrigin.LOCAL_SEND,
                    connectionGeneration = connectionGenerations[networkId],
                    label = label,
                    batchId = null,
                    timeProvenance = TimeProvenance.LOCAL_CLOCK,
                    persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                ),
            )
            traceMessageWrite("canonical_pending_insert", result.event, fromHistory = false)
            result.event.id
        }
    }

    /** Mark a pending row failed if it is still pending after the echo timeout. */
    suspend fun failIfStillPending(bufferId: Long, label: String) {
        val canonicalBuffer = bufferDao.observeById(bufferId) ?: return
        val networkId = canonicalBuffer.networkId
        sequencer.withNetwork(networkId) {
            if (messageDao.failIfStillPending(canonicalBuffer.id, label) > 0) {
                messageDao.byPendingLabel(canonicalBuffer.id, label)?.let { failed ->
                    traceMessageWrite("room_pending_failed", failed, fromHistory = false)
                }
            }
        }
    }

    /** A successful write on a server without echo-message is final local confirmation. */
    suspend fun confirmIfStillPending(bufferId: Long, label: String) {
        val canonicalBuffer = bufferDao.observeById(bufferId) ?: return
        sequencer.withNetwork(canonicalBuffer.networkId) {
            messageDao.confirmIfStillPending(canonicalBuffer.id, label)
        }
    }

    suspend fun evictNetwork(networkId: Long) {
        sequencer.withNetwork(networkId) {
            states.remove(networkId)
            rosterSnapshots.keys.removeAll { it.networkId == networkId }
            activeHistoryMultiplicities.remove(networkId)
            activeHistoryOccurrences.remove(networkId)
            activeHistoryChatRoutes.remove(networkId)
            activeProtocolPageCursorWrites.remove(networkId)
            connectionGenerations.remove(networkId)
        }
        sequencer.evict(networkId)
    }

    suspend fun shutdown() {
        sequencer.clear()
        states.clear()
        rosterSnapshots.clear()
        activeHistoryChatRoutes.clear()
        activeProtocolPageCursorWrites.clear()
    }

    internal fun sequencerSize(): Int = sequencer.size()

    // -- helpers ------------------------------------------------------------

    private suspend fun deleteLegacyReactionAliases(
        bufferId: RoomId,
        targetMsgid: String,
        sender: String,
        emoji: String,
    ) {
        val baseActorKey = "nick:${IrcCaseMapping.Rfc1459.normalize(sender)}"
        val legacyPrefix = "$baseActorKey\u0000legacy:"
        reactionDao.deleteActorAliases(
            bufferId,
            targetMsgid,
            baseActorKey,
            legacyPrefix,
            "$legacyPrefix\uFFFF",
            emoji,
        )
    }

    private fun isChannel(target: String, st: NetworkState): Boolean =
        st.isChannel(target)

    /** Route TAGMSG mutations through the enclosing query batch across historical nick changes. */
    private fun resolveReactionRoute(
        source: String,
        target: String,
        historyTarget: String?,
        st: NetworkState,
    ): ReactionRoute {
        val isDm = !isChannel(target, st)
        val historyPeer = historyTarget?.takeIf { isDm && !isChannel(it, st) }
        val sourceIsSelf = if (historyPeer != null) {
            st.normalize(target) == st.normalize(historyPeer)
        } else {
            st.normalize(source) == st.normalize(st.selfNick)
        }
        return ReactionRoute(
            bufferName = if (isDm) historyPeer ?: if (sourceIsSelf) target else source else target,
            type = if (isDm) BufferType.QUERY else BufferType.CHANNEL,
        )
    }

    private suspend fun existingReactionRoomId(
        networkId: Long,
        route: ReactionRoute,
        st: NetworkState,
    ): RoomId? = if (route.type == BufferType.QUERY) {
        bufferStore.resolveQueryRoom(networkId, st.normalize(route.bufferName), account = null)?.id
    } else {
        bufferDao.byName(networkId, st.normalize(route.bufferName))?.id
    }

    /**
     * True when a NOTICE source looks like a server, not a user (Confirmed decision #5): an empty
     * source, or one containing '.' (a hostname). RFC nicks cannot contain '.', so NickServ/ChanServ
     * stay user queries while `*.libera.chat` routes to the SERVER buffer.
     */
    private fun isServerSource(nick: String): Boolean = nick.isEmpty() || '.' in nick

    /** Resolve and bind the exact room/text representation used by both history preflight and ingestion. */
    private suspend fun resolveChatRoute(
        networkId: Long,
        event: IrcEvent.ChatMessage,
        st: NetworkState,
        historyTarget: String?,
    ): ChatRoute {
        val isDm = !isChannel(event.target, st)
        // Server-sourced NOTICEs never create query rooms.
        if (isDm && event.kind == IrcEvent.ChatKind.NOTICE && isServerSource(event.source.nick)) {
            return ChatRoute(
                bufferId = ensureServerBuffer(networkId, st),
                bufferName = "*",
                type = BufferType.SERVER,
                storedText = event.text,
                serverNotice = true,
                sourceIsSelf = false,
            )
        }
        val historyPeer = historyTarget?.takeIf { isDm && !isChannel(it, st) }
        // In a query CHATHISTORY batch the wire target disambiguates direction across nick
        // changes: outgoing rows target the peer; incoming rows target any historical self nick.
        val sourceIsSelf = event.isSelf || (
            historyPeer != null && st.normalize(event.target) == st.normalize(historyPeer)
        )
        val bufferName = if (isDm) {
            historyPeer ?: if (sourceIsSelf) event.target else event.source.nick
        } else {
            event.target
        }
        val type = if (isDm) BufferType.QUERY else BufferType.CHANNEL
        var bufferId = if (type == BufferType.QUERY) {
            val normalizedNick = st.normalize(bufferName)
            bufferStore.resolveQueryRoom(networkId, normalizedNick, account = null)?.id
                ?: bufferStore.resolveQueryRoom(
                    networkId,
                    normalizedNick,
                    event.ctx.account.takeUnless { sourceIsSelf },
                )?.id
                ?: ensureBuffer(networkId, bufferName, type, st)
        } else {
            ensureBuffer(networkId, bufferName, type, st)
        }
        if (type == BufferType.QUERY) {
            bufferId = bufferStore.bindQueryIdentity(
                roomId = bufferId,
                networkId = networkId,
                normalizedNick = st.normalize(bufferName),
                displayNick = bufferName,
                account = event.ctx.account.takeUnless { sourceIsSelf },
            ).id
        }
        val storedText = if (isDm && bufferName.equals("BouncerServ", ignoreCase = true)) {
            if (sourceIsSelf) redactBouncerServCommand(event.text) else redactBouncerServReply(event.text)
        } else {
            event.text
        }
        return ChatRoute(
            bufferId,
            bufferName,
            type,
            storedText,
            serverNotice = false,
            sourceIsSelf = sourceIsSelf,
        )
    }

    private suspend fun ensureBuffer(networkId: Long, name: String, type: BufferType, st: NetworkState): Long =
        ensureBufferEntity(networkId, name, type, st).id

    private suspend fun ensureBufferEntity(networkId: Long, name: String, type: BufferType, st: NetworkState): BufferEntity {
        val norm = st.normalize(name)
        if (type == BufferType.QUERY) {
            bufferStore.resolveQueryRoom(networkId, norm, account = null)?.let { return it }
            return bufferStore.getOrCreate(networkId, norm, name, type)
        }
        bufferDao.byName(networkId, norm)?.let { return it }
        return bufferStore.getOrCreate(networkId, norm, name, type)
    }

    private suspend fun historicalTargetBuffer(networkId: Long, target: String?): Long? {
        if (target == null) return null
        val st = stateFor(networkId)
        val type = if (isChannel(target, st)) BufferType.CHANNEL else BufferType.QUERY
        return ensureBuffer(networkId, target, type, st)
    }

    private suspend fun markJoined(bufferId: Long, joined: Boolean) {
        val b = bufferDao.observeById(bufferId) ?: return
        if (b.joined != joined) bufferDao.setJoined(bufferId, joined)
    }

    private suspend fun insertSystem(
        bufferId: Long,
        ctx: MessageContext,
        kind: MessageKind,
        sender: String,
        text: String,
        // Override for idempotent system rows (e.g. self-join) that must collapse across replays
        // regardless of serverTime. Falls back to msgid ?: sha1(serverTime|sender|text).
        dedupKey: String? = null,
        isSelf: Boolean = false,
        origin: EventOrigin = if (ctx.batchId == null) EventOrigin.LIVE else EventOrigin.HISTORY,
    ) {
        val networkId = bufferDao.observeById(bufferId)?.networkId ?: return
        val normalizedSender = stateFor(networkId).normalize(sender)
        val row = MessageEntity(
            bufferId = bufferId,
            msgid = ctx.msgid,
            serverTime = ctx.serverTime,
            sender = sender,
            normalizedActor = normalizedSender,
            kind = kind,
            text = text,
            isSelf = isSelf,
            dedupKey = dedupKey ?: SemanticIdentity.keyFor(ctx.msgid, ctx.serverTime, sender, text),
            eventKey = dedupKey,
            serverTimeAuthoritative = ctx.serverTimeSource == ServerTimeSource.TAG,
        )
        val batchKey = CanonicalBatchKey(bufferId, row.kind, normalizedSender, text, row.serverTime)
        val multiplicity = activeHistoryMultiplicities[networkId]?.get(batchKey)
        val result = canonicalTimeline.ingest(
            TimelineObservation(
                networkId = networkId,
                event = row,
                origin = origin.toObservationOrigin(),
                connectionGeneration = connectionGenerations[networkId],
                label = ctx.label,
                batchId = ctx.batchId,
                timeProvenance = ctx.serverTimeSource.toTimeProvenance(),
                batchSemanticMultiplicity = multiplicity?.semantic ?: 1,
                batchExactMultiplicity = multiplicity?.exact ?: 1,
                batchExactOrdinal = nextHistoryExactOrdinal(networkId, batchKey),
                persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
            ),
        )
        traceMessageWrite("canonical_system_${result::class.simpleName}", result.event, ctx.batchId != null)
    }

    private fun traceMessageWrite(event: String, row: MessageEntity, fromHistory: Boolean) {
        AutoFollowTrace.record(event, row.bufferId) {
            "row=${row.id} kind=${row.kind.name} self=${row.isSelf} history=$fromHistory " +
                "server_time=${row.serverTime} pending=${row.pendingLabel != null} failed=${row.failed}"
        }
        diagnostics.record("room", event) {
            mapOf(
                "buffer_id" to row.bufferId,
                "row_id" to row.id,
                "msgid_fp" to diagnostics.fingerprint(row.msgid),
                "dedup_fp" to diagnostics.fingerprint(row.dedupKey),
                "sender_fp" to diagnostics.fingerprint(row.sender),
                "body_fp" to diagnostics.fingerprint(row.text),
                "kind" to row.kind.name,
                "self" to row.isSelf,
                "history" to fromHistory,
                "server_time" to row.serverTime,
                "pending" to (row.pendingLabel != null),
                "failed" to row.failed,
            )
        }
    }

    private fun traceMessageDecision(
        event: String,
        networkId: Long,
        bufferId: Long,
        message: IrcEvent.ChatMessage,
        origin: EventOrigin,
        extra: () -> Map<String, Any?> = { emptyMap() },
    ) {
        diagnostics.record("messages", event) {
            mapOf(
                "network_id" to networkId,
                "buffer_id" to bufferId,
                "origin" to origin.name,
                "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                "sender_fp" to diagnostics.fingerprint(message.source.nick),
                "body_fp" to diagnostics.fingerprint(message.text),
                "kind" to message.kind.name,
                "self" to message.isSelf,
                "server_time" to message.ctx.serverTime,
                "server_time_source" to message.ctx.serverTimeSource.name,
                "batch" to (message.ctx.batchId != null),
            ) + extra()
        }
    }


    /** Buffer ids where [nick] is currently a member on [networkId] (for quit/nick fan-out). */
    private suspend fun buffersOfNick(networkId: Long, nick: String): List<Long> =
        memberDao.bufferIdsForNick(networkId, nick)

    private suspend fun upsertUser(networkId: Long, nick: String, mutate: (UserEntity) -> UserEntity) {
        val normalized = stateFor(networkId).normalize(nick)
        val existing = userDao.byNick(networkId, normalized)
            ?: UserEntity(networkId = networkId, nick = normalized)
        userDao.upsert(mutate(existing))
    }

    private suspend fun maybeNotify(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        eventId: TimelineEventId,
        e: IrcEvent.ChatMessage,
    ) {
        if (e.isSelf) return
        // Never raise a notification for a SERVER buffer: a MOTD line containing the user's nick
        // must not fire a mention (plans/16 §5.6.5).
        if (type == BufferType.SERVER) return
        if (type != BufferType.QUERY && !hasMention) return
        notifier.onCanonicalIncoming(networkId, bufferId, type, hasMention, eventId, e)
    }

    /**
     * Atomically serialize notification presentation, but only mark it durable after the notifier
     * returns. Startup releases interrupted claims and rebuilds the notification from Room.
     */
    private suspend fun presentNotification(eventId: TimelineEventId, present: suspend () -> Unit) {
        if (!canonicalTimeline.claimNotification(eventId)) return
        try {
            present()
            canonicalTimeline.completeNotification(eventId)
        } catch (cancelled: CancellationException) {
            canonicalTimeline.releaseNotification(eventId)
            throw cancelled
        } catch (error: Exception) {
            canonicalTimeline.releaseNotification(eventId)
            diagnostics.record("notifications", "presentation_failed") {
                mapOf("event_id" to eventId, "error" to error::class.simpleName)
            }
        }
    }

    private fun kindOf(k: IrcEvent.ChatKind): MessageKind = when (k) {
        IrcEvent.ChatKind.PRIVMSG -> MessageKind.PRIVMSG
        IrcEvent.ChatKind.NOTICE -> MessageKind.NOTICE
        IrcEvent.ChatKind.ACTION -> MessageKind.ACTION
    }


    private fun EventOrigin.toObservationOrigin(): ObservationOrigin = when (this) {
        EventOrigin.LIVE -> ObservationOrigin.LIVE
        EventOrigin.HISTORY -> ObservationOrigin.HISTORY
        EventOrigin.PUSH -> ObservationOrigin.PUSH
    }

    private fun ServerTimeSource.toTimeProvenance(): TimeProvenance = when (this) {
        ServerTimeSource.TAG -> TimeProvenance.SERVER_TAG
        ServerTimeSource.LOCAL -> TimeProvenance.LOCAL_CLOCK
    }

    private companion object {
        /**
         * Informational numerics persisted to the SERVER buffer as SERVER_INFO (plans/16 §5.6.3):
         * welcome (001..004), lusers (251..255, 265, 266), MOTD (375, 372, 376), away toggled
         * (305, 306), RPL_AWAY (301), and the WHOIS set (311, 312, 317, 318, 319, 330, 338) as a
         * fallback surface when labeled-response is missing. LIST numerics (321/322/323) are
         * deliberately excluded so a browse never floods the buffer.
         */
        val SERVER_INFO_NUMERICS: Set<String> = setOf(
            "001", "002", "003", "004",
            "251", "252", "253", "254", "255", "265", "266",
            "375", "372", "376",
            "305", "306", "301",
            "311", "312", "317", "318", "319", "330", "338",
        )

        val JOIN_ERROR_NUMERICS: Set<String> = setOf(
            "403", "405", "471", "473", "474", "475", "476",
        )
        val PART_ALREADY_CLOSED_NUMERICS: Set<String> = setOf("403", "442")
    }
}

private fun parsePrefixModes(value: String?): Map<Char, Char> {
    val raw = value ?: return emptyMap()
    val close = raw.indexOf(')')
    if (!raw.startsWith('(') || close <= 1) return emptyMap()
    val modes = raw.substring(1, close)
    val prefixes = raw.substring(close + 1)
    if (modes.length != prefixes.length) return emptyMap()
    return modes.indices.associate { modes[it] to prefixes[it] }
}

private fun olderBoundary(
    existing: ChatHistoryReference?,
    candidate: ChatHistoryReference?,
): ChatHistoryReference? {
    existing ?: return candidate
    candidate ?: return existing
    val existingTime = existing.serverTime ?: return existing
    val candidateTime = candidate.serverTime ?: return existing
    return if (candidateTime < existingTime) candidate else existing
}

private fun newerBoundary(
    existing: ChatHistoryReference?,
    candidate: ChatHistoryReference?,
): ChatHistoryReference? {
    existing ?: return candidate
    candidate ?: return existing
    val existingTime = existing.serverTime ?: return existing
    val candidateTime = candidate.serverTime ?: return existing
    return if (candidateTime > existingTime) candidate else existing
}

/**
 * Notification hook the [EventProcessor] fires for a persisted incoming ChatMessage that already
 * passed the (DM || hasMention) filter. The concrete impl (MotdNotifications, WP5) applies the
 * remaining suppression rules (muted buffer, foregrounded buffer) and posts MessagingStyle.
 */
interface MessageNotifier {
    // suspend so implementations read Room / DataStore with plain suspend calls (which dispatch
    // off the main thread). The events collector runs on Dispatchers.Main, so a blocking read here
    // (runBlocking { suspend Room query }) deadlocks/crashes the main thread — same class of bug as
    // the findSelfEchoCandidate fix. Callers are already in suspend context.
    suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage)

    /** Canonical-id-aware notification hook. Legacy/test implementations inherit the old hook. */
    suspend fun onCanonicalIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        eventId: TimelineEventId,
        message: IrcEvent.ChatMessage,
    ) = onIncoming(networkId, bufferId, type, hasMention, message)

    /** A local or synchronized marker advanced through [upToTime]. */
    suspend fun onRead(bufferId: Long, upToTime: Long) = Unit

    /** Retire presentation state keyed by a losing room id after canonical room coalescing. */
    suspend fun onRoomsMerged(winnerId: RoomId, loserId: RoomId) = Unit

    /** A newly persisted, live, actionable invitation. */
    suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) = Unit

    /** Cancel notification state after Join/Dismiss resolves an invitation. */
    suspend fun onInvitationResolved(messageId: Long) = Unit

    /** No-op notifier for tests / headless contexts. */
    object Noop : MessageNotifier {
        override suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) = Unit
    }
}
