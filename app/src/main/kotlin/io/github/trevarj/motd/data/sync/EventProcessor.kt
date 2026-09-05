package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.bouncer.redactBouncerServReply
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.EventAliasNamespace
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObservationOrigin
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.repo.NetworkIgnoreCache
import io.github.trevarj.motd.data.repo.ignoredBy
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.validChannelContext
import io.github.trevarj.motd.irc.client.whoxFlagsIndicateBot
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.event.messageContextOrNull
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.format.containsIrcFormatting
import io.github.trevarj.motd.irc.format.parseIrcFormatting
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.replyReference
import io.github.trevarj.motd.irc.proto.unreactionValue
import io.github.trevarj.motd.service.ChannelWatch
import io.github.trevarj.motd.service.IrcEventSink
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class OutgoingEventPlan(
    val label: String,
    val text: String,
    val kind: MessageKind,
    val ircFormattedText: String? = null,
)

data class DurableOutgoingEvent(
    val eventId: TimelineEventId,
    val label: String,
)

data class ReplannedOutgoingPlan(
    val bufferId: RoomId,
    val events: List<DurableOutgoingEvent>,
)

internal data class PersistedHistoryPage(
    val roomId: RoomId,
    val inserted: Int,
)

internal const val COMMAND_RESPONSE_PAYLOAD_PREFIX = "command-response:v1:"
private const val COMMAND_RESPONSE_TIMEOUT_MS = 30_000L

/**
 * One CHATHISTORY TARGETS row already resolved to a known room: the server's newest-activity
 * timestamp for it. Resolution belongs to the caller (only it knows the connection's normalization
 * rules); the write belongs to [EventProcessor].
 */
internal data class AdvertisedActivity(
    val roomId: RoomId,
    val latestMessageTime: Long,
)

/** Committed canonical order and freshly inserted row count of one playback batch. */
internal data class PlaybackCommit(
    val order: List<TimelineEventId> = emptyList(),
    val inserted: Int = 0,
)

internal data class HistoryPageWrite(
    val request: ChatHistoryRequest,
    val response: ChatHistoryResponse.Messages,
    val historyGapId: Long? = null,
)

/**
 * The sole IRC→Room writer. Implements [IrcEventSink]: every per-network
 * collector, the catch-up path, the RemoteMediator, the pending-send insert, and the push path
 * funnel through [process] or [persistHistoryPage]. Never writes state from anywhere else.
 *
 * Per-network mutable helpers (self nick and immutable ISUPPORT identity rules) are kept in a small
 * [NetworkState] cache keyed by network id and rebuilt on Registered / NickChanged.
 */
@Singleton
class EventProcessor
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val typing: TypingTrackerImpl,
        private val notifier: MessageNotifier,
        private val chatSoundPlayer: ChatSoundPlayer = ChatSoundPlayer.Noop,
        private val bufferStore: BufferStore = BufferStore(db),
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        private val canonicalTimeline: CanonicalTimelineStore = CanonicalTimelineStore(db),
        private val networkIgnoreCache: NetworkIgnoreCache = NetworkIgnoreCache(db.networkIgnoreDao()),
        private val channelWatch: ChannelWatch = ChannelWatch.Noop,
    ) : IrcEventSink {
        private val networkDao get() = db.networkDao()
        private val networkIdentityDao get() = db.networkIdentityDao()
        private val bufferDao get() = db.bufferDao()
        private val messageDao get() = db.messageDao()
        private val memberDao get() = db.memberDao()
        private val reactionDao get() = db.reactionDao()
        private val userDao get() = db.userDao()
        private val dccTransferDao get() = db.dccTransferDao()
        private val sequencer = NetworkEventSequencer()

        private val states = ConcurrentHashMap<Long, NetworkState>()
        private val rosterSnapshots = ConcurrentHashMap<RosterKey, MutableList<RosterDelta>>()
        private val connectionGenerations = ConcurrentHashMap<Long, Long>()
        private val activeHistoryMultiplicities =
            ConcurrentHashMap<Long, Map<CanonicalBatchKey, CanonicalBatchMultiplicity>>()
        private val activeHistoryOccurrences =
            ConcurrentHashMap<Long, MutableMap<CanonicalBatchKey, Int>>()
        private val activeHistoryCanonicalOrder =
            ConcurrentHashMap<Long, MutableList<TimelineEventId>>()
        private val activeHistoryInsertedIds =
            ConcurrentHashMap<Long, MutableSet<TimelineEventId>>()
        private val activeHistoryChatRoutes =
            ConcurrentHashMap<Long, ArrayDeque<ChatRoute>>()
        private val activeHistoryTargets = ConcurrentHashMap<Long, ActiveHistoryTarget>()
        private val activeProtocolPageCursorWrites = ConcurrentHashMap.newKeySet<Long>()
        private val commandResponsesById = ConcurrentHashMap<String, CommandResponseSession>()
        private val commandResponsesByLabel = ConcurrentHashMap<String, CommandResponseSession>()
        private val latestUnlabeledCommand = ConcurrentHashMap<Long, CommandResponseSession>()

        private data class RosterKey(
            val networkId: Long,
            val bufferId: Long,
        )

        private data class CommandResponseSession(
            val id: String,
            val networkId: Long,
            val bufferId: RoomId,
            val command: String,
            val label: String?,
            val expiresAt: Long,
            var nextOrdinal: Int = 0,
        )

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
            val selfAttributionAuthoritative: Boolean,
            /** soju's console, already role-scoped by the route that resolved it. */
            val bouncerConsole: Boolean = false,
        )

        private data class ActiveHistoryTarget(
            val target: String,
            val roomId: RoomId,
            val type: BufferType,
            val normalizedName: String,
        )

        private data class ReactionRoute(
            val bufferName: String,
            val type: BufferType,
            val sourceIsSelf: Boolean,
            val roomId: RoomId? = null,
        )

        private sealed interface RosterDelta {
            data class Upsert(
                val nick: String,
            ) : RosterDelta

            data class Remove(
                val nick: String,
            ) : RosterDelta

            data class Rename(
                val from: String,
                val to: String,
            ) : RosterDelta

            data class DeferredQuit(
                val event: IrcEvent.Quit,
            ) : RosterDelta

            data class DeferredNick(
                val event: IrcEvent.NickChanged,
            ) : RosterDelta

            data class Prefix(
                val nick: String,
                val prefix: Char,
                val adding: Boolean,
            ) : RosterDelta
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
            @Volatile var botMode: Char? = null,
        ) {
            fun setNick(nick: String) {
                selfNick = nick
            }

            fun isSelfNick(nick: String): Boolean = selfNick.isNotBlank() && normalize(nick) == normalize(selfNick)

            fun normalize(name: String): String = identityRules.normalize(name)

            fun isChannel(target: String): Boolean = identityRules.isChannel(target)

            fun actorKey(
                nick: String,
                account: String?,
            ): String = identityRules.actorKey(nick, account)

            fun containsSelfMention(text: String): Boolean = identityRules.containsMention(text, selfNick)
        }

        private suspend fun stateFor(networkId: Long): NetworkState {
            states[networkId]?.let { return it }
            val network = networkDao.byId(networkId)
            val identity = networkIdentityDao.byNetwork(networkId)
            val identityRules = identity?.identityRules ?: IrcIdentityRules()
            recordIdentityDiagnostic(networkId, identityRules)
            val restored =
                NetworkState(
                    selfNick = identity?.selfNick ?: network?.nick.orEmpty(),
                    identityRules = identityRules,
                )
            return states.putIfAbsent(networkId, restored) ?: restored
        }

        /** Test/setup seam; production registration enters through [process]. */
        internal suspend fun onRegistered(
            networkId: Long,
            nick: String,
            isupport: Map<String, String>,
        ) {
            sequencer.withNetwork(networkId) { applyRegistered(networkId, nick, isupport) }
        }

        private suspend fun applyRegistered(
            networkId: Long,
            nick: String,
            isupport: Map<String, String>,
        ) {
            connectionGenerations[networkId] = db.connectionGenerationDao().next(networkId)
            networkDao.setServerIconUrl(networkId, isupport["draft/ICON"]?.let(::validateAvatarUrl))
            val identity =
                NetworkIdentityEntity(
                    networkId = networkId,
                    caseMapping = isupport["CASEMAPPING"],
                    chanTypes = isupport["CHANTYPES"],
                    selfNick = nick,
                )
            networkIdentityDao.upsert(identity)
            val identityRules = identity.identityRules
            states[networkId] =
                NetworkState(
                    selfNick = nick,
                    identityRules = identityRules,
                    prefixModes = parsePrefixModes(isupport["PREFIX"]),
                    chanModes = isupport["CHANMODES"]?.split(',')?.map(String::toSet).orEmpty(),
                    botMode = isupport["BOT"]?.singleOrNull(),
                )
            recordIdentityDiagnostic(networkId, identityRules)
        }

        private fun recordIdentityDiagnostic(
            networkId: Long,
            identityRules: IrcIdentityRules,
        ) {
            identityRules.caseMapping.diagnostic?.let { diagnostic ->
                diagnostics.record("irc_protocol", "unsupported_casemapping") {
                    mapOf("network_id" to networkId, "diagnostic" to diagnostic)
                }
            }
        }

        /** Register one composer command before its wire write so replies can return to its chat. */
        fun beginCommandResponse(
            networkId: Long,
            bufferId: RoomId,
            command: String,
            label: String?,
            now: Long = System.currentTimeMillis(),
        ): String {
            commandResponsesById.values.removeAll { it.expiresAt <= now }
            commandResponsesByLabel.values.removeAll { it.expiresAt <= now }
            latestUnlabeledCommand.entries.removeAll { it.value.expiresAt <= now }
            val session =
                CommandResponseSession(
                    id = UUID.randomUUID().toString(),
                    networkId = networkId,
                    bufferId = bufferId,
                    command = command.uppercase().filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "COMMAND" },
                    label = label,
                    expiresAt = now + COMMAND_RESPONSE_TIMEOUT_MS,
                )
            commandResponsesById[session.id] = session
            if (label == null) {
                latestUnlabeledCommand.put(networkId, session)?.let(::finishCommandResponse)
            } else {
                commandResponsesByLabel[label] = session
            }
            return session.id
        }

        fun cancelCommandResponse(sessionId: String) {
            commandResponsesById[sessionId]?.let(::finishCommandResponse)
        }

        override suspend fun process(
            networkId: Long,
            event: IrcEvent,
        ) {
            sequencer.withNetwork(networkId) {
                processEvent(networkId, event, EventOrigin.LIVE)
                completeCommandFromState(networkId, event)
                if (event is IrcEvent.Disconnected) clearCommandResponses(networkId)
                bufferStore.drainCommittedRoomMerges()
            }
        }

        override suspend fun processPush(
            networkId: Long,
            event: IrcEvent,
        ) {
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
            expectedHistoryRoomId: RoomId? = null,
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
                is IrcEvent.Registered -> {
                    if (origin.mutatesSessionState) {
                        applyRegistered(networkId, event.nick, event.isupport)
                    }
                }

                is IrcEvent.ChatMessage -> {
                    onChat(networkId, event, origin, historyTarget)
                }

                is IrcEvent.TagMessage -> {
                    onTag(networkId, event, origin, historyTarget)
                }

                is IrcEvent.MessageRedacted -> {
                    onMessageRedacted(networkId, event, historyTarget)
                }

                is IrcEvent.HistoryBatch -> {
                    onHistoryBatch(networkId, event, expectedHistoryRoomId)
                }

                is IrcEvent.PlaybackBatch -> {
                    onPlaybackBatch(networkId, event, expectedHistoryRoomId)
                }

                is IrcEvent.ReplayBatch -> {
                    onReplayBatch(networkId, event)
                }

                is IrcEvent.NetworkBatch -> {
                    onNetworkBatch(networkId, event, origin, historyTarget)
                }

                is IrcEvent.Joined -> {
                    if (origin.mutatesSessionState) {
                        onJoined(networkId, event)
                    } else if (origin.isHistorical) {
                        onHistoricalJoined(networkId, event)
                    }
                }

                is IrcEvent.Parted -> {
                    if (origin.mutatesSessionState) {
                        onParted(networkId, event)
                    } else if (origin.isHistorical) {
                        onHistoricalParted(networkId, event)
                    }
                }

                is IrcEvent.Quit -> {
                    if (origin.mutatesSessionState) {
                        onQuit(networkId, event)
                    } else if (origin.isHistorical) {
                        onHistoricalQuit(networkId, event, historyTarget)
                    }
                }

                is IrcEvent.Kicked -> {
                    if (origin.mutatesSessionState) {
                        onKicked(networkId, event)
                    } else if (origin.isHistorical) {
                        onHistoricalKicked(networkId, event)
                    }
                }

                is IrcEvent.NickChanged -> {
                    if (origin.mutatesSessionState) {
                        onNickChanged(networkId, event)
                    } else if (origin.isHistorical) {
                        onHistoricalNickChanged(networkId, event, historyTarget)
                    }
                }

                is IrcEvent.NamesStarted -> {
                    if (origin.mutatesSessionState) onNamesStarted(networkId, event)
                }

                is IrcEvent.Names -> {
                    if (origin.mutatesSessionState) onNames(networkId, event)
                }

                is IrcEvent.TopicSnapshot -> {
                    if (origin.mutatesSessionState) onTopicSnapshot(networkId, event)
                }

                is IrcEvent.TopicChanged -> {
                    when (origin) {
                        EventOrigin.LIVE -> onTopicChanged(networkId, event)
                        EventOrigin.HISTORY, EventOrigin.REPLAY -> onHistoricalTopicChanged(networkId, event)
                        EventOrigin.PUSH -> Unit
                    }
                }

                is IrcEvent.ChannelRenamed -> {
                    onChannelRenamed(networkId, event, origin)
                }

                is IrcEvent.ModeChanged -> {
                    when (origin) {
                        EventOrigin.LIVE -> onModeChanged(networkId, event)
                        EventOrigin.HISTORY, EventOrigin.REPLAY -> onHistoricalModeChanged(networkId, event)
                        EventOrigin.PUSH -> Unit
                    }
                }

                is IrcEvent.AwayChanged -> {
                    if (origin.mutatesSessionState) {
                        upsertUser(networkId, event.nick) { it.copy(away = event.awayMessage != null) }
                        if (!stateFor(networkId).isSelfNick(event.nick)) onAwayChanged(networkId, event)
                    }
                }

                is IrcEvent.SelfAwayChanged -> {
                    if (origin == EventOrigin.LIVE) onSelfAwayChanged(networkId, event)
                }

                is IrcEvent.AccountChanged -> {
                    if (origin.mutatesSessionState) onAccountChanged(networkId, event)
                }

                is IrcEvent.HostChanged -> {
                    if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(hostmask = "${event.newUser}@${event.newHost}") }
                }

                is IrcEvent.RealnameChanged -> {
                    if (origin.mutatesSessionState) upsertUser(networkId, event.nick) { it.copy(realname = event.realname) }
                }

                is IrcEvent.WhoxRow -> {
                    if (origin.mutatesSessionState) onWhoxRow(networkId, event)
                }

                is IrcEvent.WhoxComplete -> {}

                is IrcEvent.MonitorOnline -> {
                    if (origin.mutatesSessionState) onMonitorOnline(networkId, event)
                }

                is IrcEvent.MonitorOffline,
                is IrcEvent.MonitorList,
                is IrcEvent.MonitorListEnd,
                -> {}

                is IrcEvent.MonitorLimitExceeded -> {
                    if (origin.mutatesSessionState) onMonitorLimitExceeded(networkId, event)
                }

                is IrcEvent.Invited -> {
                    onInvited(networkId, event, origin)
                }

                is IrcEvent.DccSend -> {
                    onDccSend(networkId, event, origin)
                }

                is IrcEvent.DccResume -> {
                    onDccResume(networkId, event, origin)
                }

                is IrcEvent.DccAccept -> {
                    onDccAccept(networkId, event, origin)
                }

                is IrcEvent.UnsupportedDcc -> {
                    onUnsupportedDcc(networkId, event, origin)
                }

                is IrcEvent.ReadMarker -> {
                    if (origin.mutatesSessionState) onReadMarker(networkId, event)
                }

                is IrcEvent.BouncerNetworkState -> {
                    if (origin.mutatesSessionState) onBouncerNetworkState(networkId, event)
                }

                is IrcEvent.Disconnected -> {
                    if (origin.mutatesSessionState) onDisconnected(networkId, event)
                }

                is IrcEvent.StandardReply -> {
                    if (origin != EventOrigin.PUSH) onStandardReply(networkId, event, origin)
                }

                is IrcEvent.MultilineRejected -> {}

                is IrcEvent.ServerError -> {
                    if (origin.mutatesSessionState) onServerError(networkId, event)
                }

                is IrcEvent.Raw -> {
                    onRaw(networkId, event, origin, historyTarget)
                }

                is IrcEvent.CapsChanged,
                -> {} // not persisted
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
            val sourceSelfCandidate = e.isSelf || st.isSelfNick(e.source.nick)
            if (!sourceSelfCandidate &&
                !(e.kind == IrcEvent.ChatKind.NOTICE && isServerSource(e.source.nick)) &&
                ignoredBy(networkIgnoreCache.enabledForNetwork(networkId), e.source, st.identityRules)
            ) {
                diagnostics.record("messages", "message_ignored_by_network_rule") {
                    mapOf(
                        "network_id" to networkId,
                        "origin" to origin.name,
                        "sender_fp" to diagnostics.fingerprint(e.source.nick),
                    )
                }
                return
            }
            val route =
                if (origin.isHistorical) {
                    activeHistoryChatRoutes[networkId]?.removeFirstOrNull()
                        ?: resolveChatRoute(networkId, e, st, historyTarget, origin)
                } else {
                    resolveChatRoute(networkId, e, st, historyTarget = null, origin = origin)
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
            val routedText = route.storedText
            val sourceIsSelf = route.sourceIsSelf
            val isDm = type == BufferType.QUERY
            // soju warns through console NOTICEs ("network X disconnected"). The console is not in the
            // chat list, so a swallowed notice leaves the user no signal at all.
            val consoleNotice = route.bouncerConsole && e.kind == IrcEvent.ChatKind.NOTICE
            // CHATHISTORY and reconnect playback must both honor a forgotten query's discard boundary.
            val usesDiscardBoundary = origin.isHistorical
            if (isDm && usesDiscardBoundary && shouldDiscardHistoricalEvent(bufferId, e)) {
                return
            }
            if (isDm && !usesDiscardBoundary && isExactDiscardedEvent(bufferId, e.ctx)) {
                return
            }
            val formatted =
                if (type == BufferType.SERVER || !containsIrcFormatting(routedText)) {
                    null
                } else {
                    parseIrcFormatting(routedText)
                }
            val storedText = formatted?.visibleText ?: routedText
            val ircFormattedText = routedText.takeIf { formatted != null && it != storedText }

            val replyReference = e.replyToMsgid
            val replyMentionsSelf =
                if (!sourceIsSelf && replyReference != null) {
                    messageDao.byMsgid(bufferId, replyReference)?.let { parent ->
                        parent.isSelf || st.normalize(parent.sender) == st.normalize(st.selfNick)
                    } == true
                } else {
                    false
                }
            val hasMention =
                !sourceIsSelf && type != BufferType.SERVER &&
                    (replyMentionsSelf || st.containsSelfMention(storedText))
            val identitySender = st.normalize(e.source.nick)

            traceMessageDecision("message_classified", networkId, bufferId, e, origin) {
                mapOf(
                    "buffer_type" to type.name,
                    "mention" to hasMention,
                )
            }

            val row =
                MessageEntity(
                    bufferId = bufferId,
                    msgid = e.ctx.msgid,
                    serverTime = e.ctx.serverTime,
                    sender = e.source.nick,
                    normalizedActor = identitySender,
                    senderAccount = e.ctx.account,
                    kind = kindOf(e.kind),
                    text = storedText,
                    ircFormattedText = ircFormattedText,
                    isSelf = sourceIsSelf,
                    isBot = e.isBot,
                    hasMention = hasMention,
                    replyToMsgid = e.replyToMsgid,
                    dedupKey = SemanticIdentity.keyFor(e.ctx, identitySender, ircFormattedText ?: storedText),
                    serverTimeAuthoritative = e.ctx.serverTimeSource == ServerTimeSource.TAG,
                )

            run {
                val batchKey =
                    CanonicalBatchKey(
                        bufferId,
                        row.kind,
                        identitySender,
                        ircFormattedText ?: storedText,
                        row.serverTime,
                    )
                val multiplicity = activeHistoryMultiplicities[networkId]?.get(batchKey)
                val result =
                    db.withTransaction {
                        if (isDm) bufferDao.reviveQuery(bufferId)
                        val ingested =
                            canonicalTimeline.ingest(
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
                                    selfAttributionAuthoritative = route.selfAttributionAuthoritative,
                                ),
                            )
                        // A new peer chat revives an unmuted archive. Live delivery is authoritative on
                        // its own; history, replay, and push revive only when they insert a genuinely new
                        // newest unread row, so backfill, gap fills, and duplicate transports retain the
                        // user's choice. Self echoes and system activity never revive.
                        if (!sourceIsSelf && type != BufferType.SERVER) {
                            if (origin == EventOrigin.LIVE) {
                                bufferDao.unarchiveIfUnmuted(ingested.event.bufferId)
                            } else if (ingested is IngestResult.Inserted) {
                                bufferDao.unarchiveIfUnmutedForNewPeerActivity(
                                    ingested.event.bufferId,
                                    ingested.event.id,
                                    ingested.event.serverTime,
                                    ingested.event.timelineOrder,
                                )
                            }
                        }
                        ingested
                    }
                val canonical = result.event
                recordPlaybackResult(networkId, result)
                traceMessageWrite(
                    when (result) {
                        is IngestResult.Inserted -> "canonical_insert"
                        is IngestResult.Enriched -> "canonical_enrich"
                        is IngestResult.Merged -> "canonical_merge"
                        is IngestResult.Ignored -> "canonical_ignore"
                    },
                    canonical,
                    origin != EventOrigin.LIVE,
                )
                if (origin == EventOrigin.LIVE &&
                    !sourceIsSelf &&
                    type != BufferType.SERVER &&
                    canonicalTimeline.claimSound(canonical.id)
                ) {
                    try {
                        chatSoundPlayer.onCanonicalIncoming(
                            canonical.bufferId,
                            type,
                            e.copy(text = canonical.text),
                            canonical,
                        )
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
                val watchedChat =
                    origin == EventOrigin.LIVE &&
                        channelWatch.isActive(canonical.bufferId) &&
                        (e.kind == IrcEvent.ChatKind.PRIVMSG || e.kind == IrcEvent.ChatKind.ACTION)
                if (origin.notifies &&
                    !sourceIsSelf &&
                    shouldNotifyIncoming(type, hasMention, consoleNotice, watchedChat)
                ) {
                    presentNotification(canonical.id) {
                        maybeNotify(
                            networkId,
                            canonical.bufferId,
                            type,
                            canonical.hasMention,
                            canonical.id,
                            e.copy(
                                ctx =
                                    e.ctx.copy(
                                        msgid = canonical.msgid,
                                        serverTime = canonical.serverTime,
                                        account = canonical.senderAccount,
                                    ),
                                text = canonical.text,
                                isSelf = sourceIsSelf,
                                replyToMsgid = canonical.replyToMsgid,
                            ),
                            consoleNotice = consoleNotice,
                            watchedChat = watchedChat,
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
            val route = resolveReactionRoute(networkId, e.source.nick, e.target, historyTarget, st)
            // Peer typing is routed to the tracker, never persisted.
            if (origin == EventOrigin.LIVE && !route.sourceIsSelf) {
                e.typing?.let { typingState ->
                    val bufferId = ensureBuffer(networkId, route.bufferName, route.type, st)
                    typing.onTyping(bufferId, e.source.nick, typingState)
                }
            }
            // React rows are emoji-specific; an account-tag echo also removes the optimistic nick key.
            val emoji = e.reactEmoji
            val targetMsgid = e.reactTargetMsgid
            if (emoji != null && targetMsgid != null) {
                val account =
                    e.ctx.account ?: if (origin == EventOrigin.LIVE) {
                        userDao.byNick(networkId, st.normalize(e.source.nick))?.account
                    } else {
                        null
                    }
                val targetEvent =
                    db.canonicalTimelineDao().eventByAlias(
                        networkId,
                        EventAliasNamespace.MSGID,
                        targetMsgid.toByteArray(Charsets.UTF_8),
                    )
                val bufferId =
                    targetEvent?.bufferId ?: existingReactionRoomId(
                        networkId,
                        route,
                        st,
                        account,
                    ) ?: return
                // Keep an orphan temporarily when the target's echo/history row is still in flight.
                // Once exact target identity exists, resolution also repairs an ambiguous query route.
                if (targetEvent != null) {
                    db.canonicalTimelineDao().resolveReactions(bufferId, targetMsgid, targetEvent.id)
                }
                val actorKey = st.actorKey(e.source.nick, account)
                val nickKey = st.actorKey(e.source.nick, account = null)
                deleteLegacyReactionAliases(bufferId, targetMsgid, e.source.nick, nickKey, emoji)
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
                        targetEventId = targetEvent?.id,
                    ),
                )
            }
        }

        private suspend fun onMessageRedacted(
            networkId: Long,
            event: IrcEvent.MessageRedacted,
            historyTarget: String?,
        ) {
            val st = stateFor(networkId)
            val route = resolveReactionRoute(networkId, event.source.nick, event.target, historyTarget, st)
            val expectedRoomId = existingReactionRoomId(networkId, route, st, event.ctx.account) ?: return
            val target =
                db.canonicalTimelineDao().eventByAlias(
                    networkId,
                    EventAliasNamespace.MSGID,
                    event.targetMsgid.toByteArray(Charsets.UTF_8),
                ) ?: return
            val canonicalExpectedRoomId = bufferDao.canonicalId(expectedRoomId) ?: expectedRoomId
            if (target.bufferId != canonicalExpectedRoomId || target.kind !in REDACTABLE_MESSAGE_KINDS) return

            val tombstone =
                "Message deleted by ${event.source.nick}" +
                    event.reason
                        ?.takeIf(String::isNotBlank)
                        ?.let { " ($it)" }
                        .orEmpty()
            db.withTransaction {
                messageDao.update(
                    target.copy(
                        kind = MessageKind.REDACTED,
                        text = tombstone,
                        ircFormattedText = null,
                        hasMention = false,
                        pendingLabel = null,
                        failed = false,
                    ),
                )
                reactionDao.deleteForTarget(target.bufferId, event.targetMsgid)
            }
        }

        private suspend fun onHistoryBatch(
            networkId: Long,
            batch: IrcEvent.HistoryBatch,
            expectedRoomId: RoomId? = null,
        ) = onPlaybackEvents(
            networkId = networkId,
            target = batch.target,
            events = batch.events,
            origin = EventOrigin.HISTORY,
            expectedRoomId = expectedRoomId,
            placement = IrcEvent.PlaybackPlacement.AUTOMATIC,
        )

        private suspend fun onPlaybackBatch(
            networkId: Long,
            batch: IrcEvent.PlaybackBatch,
            expectedRoomId: RoomId? = null,
        ) = onPlaybackEvents(
            networkId = networkId,
            target = batch.target,
            events = batch.events,
            origin =
                if (batch.source == IrcEvent.PlaybackSource.ZNC_PLAYBACK) {
                    EventOrigin.REPLAY
                } else {
                    EventOrigin.HISTORY
                },
            expectedRoomId = expectedRoomId,
            placement = batch.placement,
        )

        private suspend fun onReplayBatch(
            networkId: Long,
            batch: IrcEvent.ReplayBatch,
        ) = onPlaybackEvents(
            networkId = networkId,
            target = batch.target,
            events = batch.events,
            origin = EventOrigin.REPLAY,
            placement = IrcEvent.PlaybackPlacement.AUTOMATIC,
        )

        private suspend fun onPlaybackEvents(
            networkId: Long,
            target: String,
            events: List<IrcEvent>,
            origin: EventOrigin,
            expectedRoomId: RoomId? = null,
            placement: IrcEvent.PlaybackPlacement,
        ): PlaybackCommit {
            // All events for one target are applied in a single Room transaction (idempotent by
            // dedupKey). They are historical replay, never live arrivals: persist them without posting
            // notifications even when a previously-missing row is a DM or mention.
            diagnostics.record("history", "batch_started") {
                mapOf(
                    "network_id" to networkId,
                    "target_fp" to diagnostics.fingerprint(target),
                    "events" to events.size,
                    "source" to origin.name,
                )
            }
            val targetRoom =
                expectedRoomId?.let { roomId ->
                    val room =
                        bufferDao.observeById(roomId)
                            ?: error("history target $roomId no longer exists")
                    check(room.networkId == networkId) { "history target $roomId belongs to another network" }
                    room
                } ?: existingRoom(networkId, target, stateFor(networkId))
            targetRoom?.let { room ->
                activeHistoryTargets[networkId] =
                    ActiveHistoryTarget(
                        target,
                        room.id,
                        room.type,
                        room.name,
                    )
            }
            var committedOrder = emptyList<TimelineEventId>()
            var insertedCount = 0
            try {
                db.withTransaction {
                    activeHistoryChatRoutes[networkId] = ArrayDeque()
                    activeHistoryMultiplicities[networkId] =
                        canonicalBatchMultiplicities(
                            networkId,
                            target,
                            events,
                            origin,
                        )
                    val routedRoomIds = linkedSetOf<RoomId>()
                    activeHistoryChatRoutes[networkId].orEmpty().forEach { route ->
                        if (targetRoom == null || route.type == targetRoom.type) {
                            routedRoomIds += bufferDao.canonicalId(route.bufferId) ?: route.bufferId
                        }
                    }
                    val contextAmbiguous = routedRoomIds.size > 1
                    val contextRoomId =
                        routedRoomIds.singleOrNull()
                            ?: targetRoom?.id?.let { bufferDao.canonicalId(it) ?: it }
                    contextRoomId?.let { roomId ->
                        bufferDao.observeById(roomId)?.let { room ->
                            activeHistoryTargets[networkId] =
                                ActiveHistoryTarget(
                                    target,
                                    room.id,
                                    room.type,
                                    room.name,
                                )
                        }
                    }
                    val events =
                        when {
                            contextAmbiguous -> {
                                events.filterIsInstance<IrcEvent.ChatMessage>()
                            }

                            contextRoomId == null -> {
                                events
                            }

                            else -> {
                                events.filterNot { event ->
                                    event !is IrcEvent.ChatMessage &&
                                        shouldDiscardHistoricalEvent(contextRoomId, event)
                                }
                            }
                        }
                    activeHistoryOccurrences[networkId] = mutableMapOf()
                    activeHistoryCanonicalOrder[networkId] = mutableListOf()
                    activeHistoryInsertedIds[networkId] = mutableSetOf()
                    for (ev in events) processEvent(networkId, ev, origin, target)
                    committedOrder = activeHistoryCanonicalOrder[networkId].orEmpty().toList()
                    insertedCount = activeHistoryInsertedIds[networkId].orEmpty().size
                    canonicalTimeline.reconcilePlaybackOrder(
                        orderedEventIds = committedOrder,
                        insertedEventIds = activeHistoryInsertedIds[networkId].orEmpty(),
                        prependUnanchored =
                            placement == IrcEvent.PlaybackPlacement.BEFORE ||
                                placement == IrcEvent.PlaybackPlacement.AUTOMATIC,
                    )
                }
            } finally {
                activeHistoryMultiplicities.remove(networkId)
                activeHistoryOccurrences.remove(networkId)
                activeHistoryCanonicalOrder.remove(networkId)
                activeHistoryInsertedIds.remove(networkId)
                activeHistoryChatRoutes.remove(networkId)
                activeHistoryTargets.remove(networkId)
            }
            diagnostics.record("history", "batch_finished") {
                mapOf(
                    "network_id" to networkId,
                    "target_fp" to diagnostics.fingerprint(target),
                    "events" to events.size,
                    "source" to origin.name,
                )
            }
            return PlaybackCommit(committedOrder, insertedCount)
        }

        /**
         * Keep discarded history out permanently. A different msgid at the exact floor remains
         * potentially new, but an equal-time replay with no msgid cannot be distinguished from the
         * discarded boundary and must stay forgotten.
         */
        private suspend fun shouldDiscardHistoricalEvent(
            roomId: RoomId,
            event: IrcEvent,
        ): Boolean {
            val room =
                bufferDao.observeById(roomId)?.takeIf { it.type == BufferType.QUERY }
                    ?: return false
            val context =
                when (event) {
                    is IrcEvent.ChatMessage -> event.ctx
                    is IrcEvent.TagMessage -> event.ctx
                    is IrcEvent.Joined -> event.ctx
                    is IrcEvent.Parted -> event.ctx
                    is IrcEvent.Quit -> event.ctx
                    is IrcEvent.Kicked -> event.ctx
                    is IrcEvent.NickChanged -> event.ctx
                    is IrcEvent.TopicChanged -> event.ctx
                    is IrcEvent.ChannelRenamed -> event.ctx
                    is IrcEvent.ModeChanged -> event.ctx
                    is IrcEvent.Invited -> event.ctx
                    is IrcEvent.DccSend -> event.ctx
                    is IrcEvent.DccResume -> event.ctx
                    is IrcEvent.DccAccept -> event.ctx
                    is IrcEvent.UnsupportedDcc -> event.ctx
                    is IrcEvent.StandardReply -> event.ctx
                    is IrcEvent.MultilineRejected -> event.ctx
                    else -> null
                } ?: return false
            val msgid = context.msgid
            if (msgid != null && (
                    msgid == room.historyDiscardedThroughMsgid ||
                        bufferDao.isDiscardedMessageId(room.id, msgid)
                )
            ) {
                return true
            }
            val floor = room.historyDiscardedThroughTime ?: return false
            return context.serverTimeSource == ServerTimeSource.TAG && (
                context.serverTime < floor || context.serverTime == floor && msgid == null
            )
        }

        private suspend fun isExactDiscardedEvent(
            roomId: RoomId,
            context: MessageContext,
        ): Boolean {
            val msgid = context.msgid ?: return false
            val room =
                bufferDao.observeById(roomId)?.takeIf { it.type == BufferType.QUERY }
                    ?: return false
            return msgid == room.historyDiscardedThroughMsgid ||
                bufferDao.isDiscardedMessageId(room.id, msgid)
        }

        /**
         * Record what a completed CHATHISTORY TARGETS page advertised as each room's newest activity.
         *
         * This is IRC-derived state, so it is written here and nowhere else. It is not message state:
         * no row is created, nothing is ingested, and the values are a monotone high-water mark of what
         * the server said. That is exactly what lets the chat list badge from DISCOVERY instead of
         * waiting for the per-room pages the pass has not fetched yet. Deliberately a badge and never a
         * sort input: an advertisement can describe an event this device will never show, and ordering
         * on it would bounce the row up and back once the fetch resolves with nothing visible.
         *
         * One transaction for the whole page so the list invalidates once, rather than once per target.
         */
        internal suspend fun recordAdvertisedActivity(
            networkId: Long,
            targets: List<AdvertisedActivity>,
        ) {
            if (targets.isEmpty()) return
            sequencer.withNetwork(networkId) {
                db.withTransaction {
                    targets.forEach { bufferDao.advanceAdvertisedLatest(it.roomId, it.latestMessageTime) }
                }
            }
        }

        /**
         * Retire an advertisement the server itself has disproved, by clamping [roomId]'s advertised
         * high-water mark down onto the newest row the room actually shows.
         *
         * The one exception to the column's forward-only rule, and driven by the same kind of response
         * that writes it: a caller may only call this once a pass has proven the room is as current as
         * CHATHISTORY will ever make it — the advertised instant is local, or replay refuses to return
         * it at all (soju can index an event its replay never serves). Both leave the same residue: a
         * timestamp above every visible row, describing an event the reader will never see. Left
         * standing, it is a permanent count-less unread dot no mark-read can clear (the read anchor
         * lands on the newest LOCAL row, which is below it).
         *
         * [provenLatest] is the advertisement the caller settled, and it bounds the clamp: anything the
         * column holds above it came from a discovery this response says nothing about.
         *
         * Written here because it is the same IRC-derived column [recordAdvertisedActivity] owns.
         */
        internal suspend fun clampAdvertisedActivity(
            networkId: Long,
            roomId: RoomId,
            provenLatest: Long,
        ) {
            sequencer.withNetwork(networkId) {
                bufferDao.clampAdvertisedLatestToVisible(roomId, provenLatest)
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
            expectedRoomId: RoomId?,
        ): RoomId = persistHistoryPageResult(networkId, request, response, expectedRoomId).roomId

        internal suspend fun persistHistoryPageResult(
            networkId: Long,
            request: ChatHistoryRequest,
            response: ChatHistoryResponse.Messages,
            expectedRoomId: RoomId?,
            historyGapId: Long? = null,
        ): PersistedHistoryPage =
            sequencer.withNetwork(networkId) {
                val persisted =
                    db.withTransaction {
                        persistHistoryPageInTransaction(
                            networkId,
                            request,
                            response,
                            expectedRoomId,
                            historyGapId,
                        )
                    }
                bufferStore.drainCommittedRoomMerges()
                persisted
            }

        /** Publish a complete multi-page synchronization as one observable Room invalidation. */
        internal suspend fun persistHistoryPagesResult(
            networkId: Long,
            pages: List<HistoryPageWrite>,
            expectedRoomId: RoomId,
        ): PersistedHistoryPage =
            sequencer.withNetwork(networkId) {
                require(pages.isNotEmpty()) { "history page batch is empty" }
                val persisted =
                    db.withTransaction {
                        var canonicalRoomId = expectedRoomId
                        var inserted = 0
                        pages.forEach { page ->
                            val persistedPage =
                                persistHistoryPageInTransaction(
                                    networkId,
                                    page.request,
                                    page.response,
                                    canonicalRoomId,
                                    page.historyGapId,
                                )
                            canonicalRoomId = persistedPage.roomId
                            inserted += persistedPage.inserted
                        }
                        PersistedHistoryPage(roomId = canonicalRoomId, inserted = inserted)
                    }
                bufferStore.drainCommittedRoomMerges()
                persisted
            }

        private suspend fun persistHistoryPageInTransaction(
            networkId: Long,
            request: ChatHistoryRequest,
            response: ChatHistoryResponse.Messages,
            expectedRoomId: RoomId?,
            historyGapId: Long?,
        ): PersistedHistoryPage {
            require(request.subcommand != ChatHistoryRequest.Subcommand.TARGETS) {
                "TARGETS is not a message page"
            }
            val initialRoomId =
                expectedRoomId?.let { roomId ->
                    val room =
                        bufferDao.observeById(roomId)
                            ?: error("history target $roomId no longer exists")
                    check(room.networkId == networkId) { "history target $roomId belongs to another network" }
                    room.id
                } ?: historicalTargetBuffer(networkId, request.target)
                    ?: error("missing history target ${request.target}")
            val initialCanonicalId = bufferDao.canonicalId(initialRoomId) ?: initialRoomId
            val before = db.historyCursorDao().byRoom(initialCanonicalId)
            val previousNewest =
                before
                    ?.let {
                        ChatHistoryReference(it.newestMsgid, it.newestServerTime)
                    }?.takeIf { it.msgid != null || it.serverTime != null }
                    ?: messageDao.latestBoundary(initialCanonicalId)?.let {
                        ChatHistoryReference(it.msgid, it.serverTime)
                    }
            val previousNewestAnchor =
                previousNewest?.let {
                    resolveStoredBoundary(initialCanonicalId, it, newest = true)
                }
            // The bottom of what this room already holds, taken as the OLDER of the stored cursor and
            // the oldest retained row — the same minimum the APPEND ladder pages BEFORE
            // (`olderPageability`). Preferring the cursor the way [previousNewest] does would put this
            // boundary ABOVE retained rows whenever a reconnect LATEST page seeded a fresh cursor over a
            // store that already held older live rows, and an island gap recorded against it would then
            // span history that is present.
            val previousOldest =
                olderBoundary(
                    before
                        ?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
                        ?.takeIf { it.msgid != null || it.serverTime != null },
                    messageDao.oldestBoundary(initialCanonicalId)?.let {
                        ChatHistoryReference(it.msgid, it.serverTime)
                    },
                )

            val pageCommit =
                if (response.events.isNotEmpty()) {
                    activeProtocolPageCursorWrites += networkId
                    try {
                        onPlaybackBatch(
                            networkId,
                            IrcEvent.PlaybackBatch(
                                source = IrcEvent.PlaybackSource.CHATHISTORY,
                                target = request.target,
                                items =
                                    response.events.mapIndexed { ordinal, event ->
                                        IrcEvent.PlaybackItem.from(event, ordinal)
                                    },
                                placement =
                                    when (request.subcommand) {
                                        ChatHistoryRequest.Subcommand.BEFORE -> IrcEvent.PlaybackPlacement.BEFORE
                                        ChatHistoryRequest.Subcommand.AFTER -> IrcEvent.PlaybackPlacement.AFTER
                                        ChatHistoryRequest.Subcommand.AROUND -> IrcEvent.PlaybackPlacement.AUTOMATIC
                                        ChatHistoryRequest.Subcommand.BETWEEN -> IrcEvent.PlaybackPlacement.AFTER
                                        ChatHistoryRequest.Subcommand.LATEST -> IrcEvent.PlaybackPlacement.LATEST
                                        ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS is not a message page")
                                    },
                            ),
                            expectedRoomId = initialCanonicalId,
                        )
                    } finally {
                        activeProtocolPageCursorWrites -= networkId
                    }
                } else {
                    PlaybackCommit()
                }

            val canonicalRoomId = bufferDao.canonicalId(initialCanonicalId) ?: initialCanonicalId
            val after = db.historyCursorDao().byRoom(canonicalRoomId)
            val base = after ?: before
            val baseOldest =
                base
                    ?.let {
                        ChatHistoryReference(
                            it.oldestMsgid,
                            it.oldestServerTime,
                        )
                    }?.takeIf { it.msgid != null || it.serverTime != null }
            val baseNewest =
                base
                    ?.let {
                        ChatHistoryReference(
                            it.newestMsgid,
                            it.newestServerTime,
                        )
                    }?.takeIf { it.msgid != null || it.serverTime != null }
            // Union page metadata with the post-ingest cursor. The latter may now belong to a
            // lower-id room winner and can contain extents that predate this request target.
            val oldest = olderBoundary(baseOldest, response.oldest)
            val newest = newerBoundary(baseNewest, response.newest)
            // Start-of-history is DURABLE — nothing clears it, and `olderPageability` reads it as "this
            // direction is finished, permanently" — so it may only be claimed by a page that named a row
            // to be older than, or that delivered the history it says is whole.
            //
            //  - BEFORE named the boundary. "Nothing older than this retained row" stays true forever,
            //    whether the server says it with an empty batch or with the end tag.
            //  - An unbounded LATEST names nothing. A batch it actually DELIVERED and tagged terminal is
            //    the whole history and proves the start. An EMPTY one proves only what the server could
            //    serve at that instant: a channel restored a moment ago, a bouncer that has archived
            //    nothing for it yet, a target whose history it does not retain. Branding the room on
            //    that answer left it unable to page for good once the backlog did exist — and on a wiped
            //    store every room takes this path, because every room seeds from empty.
            val complete =
                when (request.subcommand) {
                    ChatHistoryRequest.Subcommand.BEFORE -> {
                        response.endOfHistory || response.primaryMessageCount == 0
                    }

                    ChatHistoryRequest.Subcommand.LATEST -> {
                        request.bound1 == null && request.bound2 == null &&
                            response.endOfHistory && response.primaryMessageCount > 0
                    }

                    else -> {
                        false
                    }
                }
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
            // Resolved AFTER ingest and against the post-merge winner: inserting an older page renumbers
            // timelineOrder for the rows above it, so a snapshot taken before this page would compare
            // unequal to the page's own anchor for what is the SAME event.
            val previousOldestAnchor =
                previousOldest?.let {
                    resolveStoredBoundary(canonicalRoomId, it, newest = false)
                }
            reconcileHistoryGaps(
                roomId = canonicalRoomId,
                request = request,
                response = response,
                previousNewest = previousNewest,
                previousNewestAnchor = previousNewestAnchor,
                previousOldest = previousOldest,
                previousOldestAnchor = previousOldestAnchor,
                pageRows =
                    pageCommit.order
                        .mapNotNull { messageDao.byCanonicalId(it) }
                        .filter { it.bufferId == canonicalRoomId },
                historyGapId = historyGapId,
            )
            bufferDao.setOldestFetchedTime(canonicalRoomId, oldest?.serverTime)
            if (complete) bufferDao.markHistoryComplete(canonicalRoomId)
            // History seeded into a room that held no durable content starts read: backlog predating
            // the app must not badge. A marker or anchor that already exists wins inside the DAO guard.
            if (previousNewest == null) {
                newest?.serverTime?.let { bufferDao.seedHistoryUnreadFloor(canonicalRoomId, it) }
            }
            return PersistedHistoryPage(canonicalRoomId, pageCommit.inserted)
        }

        /**
         * Reconcile one fetched protocol interval with durable missing intervals. Pages may arrive from
         * either side of a gap or from a deep-link request in its middle, so overlap can close, shrink,
         * or split a gap. LATEST creates a new gap only when it proves a newer retained island without
         * reaching the previously known newest boundary, and a non-LATEST page creates one only when it
         * proves an OLDER retained island below the previously known oldest boundary.
         */
        private suspend fun reconcileHistoryGaps(
            roomId: RoomId,
            request: ChatHistoryRequest,
            response: ChatHistoryResponse.Messages,
            previousNewest: ChatHistoryReference?,
            previousNewestAnchor: TimelineAnchor?,
            previousOldest: ChatHistoryReference?,
            previousOldestAnchor: TimelineAnchor?,
            pageRows: List<MessageEntity>,
            historyGapId: Long?,
        ) {
            val roomGaps = db.historyGapDao().forRoom(roomId)
            val directionalGap =
                historyGapId?.let { id -> roomGaps.firstOrNull { it.id == id } }
                    ?: when (request.subcommand) {
                        ChatHistoryRequest.Subcommand.AFTER -> {
                            roomGaps.matchingDirectionalGap(request.bound1) {
                                it.olderMsgid to it.olderServerTime
                            }
                        }

                        ChatHistoryRequest.Subcommand.BEFORE -> {
                            roomGaps.matchingDirectionalGap(request.bound1) {
                                it.newerMsgid to it.newerServerTime
                            }
                        }

                        else -> {
                            null
                        }
                    }
            val terminal = response.endOfHistory || response.primaryMessageCount == 0
            if (response.primaryMessageCount == 0) {
                directionalGap?.let { db.historyGapDao().update(it.copy(recoverable = false)) }
                return
            }

            val pageOldest = response.oldest?.takeIf { it.serverTime != null } ?: return
            val pageNewest = response.newest?.takeIf { it.serverTime != null } ?: return
            val pageOldestTime = checkNotNull(pageOldest.serverTime)
            val pageNewestTime = checkNotNull(pageNewest.serverTime)
            if (pageOldestTime > pageNewestTime) return
            val pageOldestAnchor = resolvePageBoundary(pageOldest, pageRows, oldest = true)
            val pageNewestAnchor = resolvePageBoundary(pageNewest, pageRows, oldest = false)

            val gaps = db.historyGapDao().forRoom(roomId)
            // Whether this page touched any recorded interval. A page that did is already accounted
            // for by the close/shrink/split branches below and must not also record an island gap.
            var overlappedExistingGap = directionalGap != null
            gaps.forEach { gap ->
                val gapOlderAnchor = historyGapAnchor(roomId, gap, older = true)
                val gapNewerAnchor = historyGapAnchor(roomId, gap, older = false)
                if (directionalGap?.id == gap.id) {
                    overlappedExistingGap = true
                    if (request.subcommand == ChatHistoryRequest.Subcommand.AFTER) {
                        val reachedNewerBoundary =
                            pageNewestTime > gap.newerServerTime ||
                                (
                                    pageNewestTime == gap.newerServerTime &&
                                        pageNewest.matchesBoundary(
                                            pageNewestAnchor,
                                            gap.newerMsgid,
                                            gapNewerAnchor,
                                        )
                                )
                        if (terminal && reachedNewerBoundary) {
                            db.historyGapDao().delete(gap.id)
                        } else {
                            // recoverable=false means the SERVER PROVED the remainder empty (a terminal
                            // response that still did not reach the far boundary). A saturated
                            // timestamp-only page is not such proof: the interval stays fetchable and
                            // per-fetch equal-timestamp ambiguity is handled by the loader's
                            // cannotSafelyPageAfter per page, never by poisoning the gap.
                            updateOrDeleteGap(
                                gap.copy(
                                    olderMsgid = pageNewest.msgid,
                                    olderServerTime = pageNewestTime,
                                    olderEventId = pageNewestAnchor?.eventId,
                                    olderTimelineOrder = pageNewestAnchor?.timelineOrder,
                                    recoverable = !terminal,
                                ),
                            )
                        }
                    } else {
                        val reachedOlderBoundary =
                            pageOldestTime < gap.olderServerTime ||
                                (
                                    pageOldestTime == gap.olderServerTime &&
                                        pageOldest.matchesBoundary(
                                            pageOldestAnchor,
                                            gap.olderMsgid,
                                            gapOlderAnchor,
                                        )
                                )
                        if (terminal && reachedOlderBoundary) {
                            db.historyGapDao().delete(gap.id)
                        } else {
                            // recoverable=false means the SERVER PROVED the remainder empty (a terminal
                            // response that still did not reach the far boundary). A saturated
                            // timestamp-only page is not such proof: the interval stays fetchable and
                            // per-fetch equal-timestamp ambiguity is handled by the loader's
                            // cannotSafelyPageBefore per page, never by poisoning the gap.
                            updateOrDeleteGap(
                                gap.copy(
                                    newerMsgid = pageOldest.msgid,
                                    newerServerTime = pageOldestTime,
                                    newerEventId = pageOldestAnchor?.eventId,
                                    newerTimelineOrder = pageOldestAnchor?.timelineOrder,
                                    recoverable = !terminal,
                                ),
                            )
                        }
                    }
                    return@forEach
                }
                if (pageOldestAnchor == null || pageNewestAnchor == null) return@forEach
                val overlaps =
                    (
                        pageNewestTime > gap.olderServerTime &&
                            pageOldestTime < gap.newerServerTime
                    ) || (
                        pageNewestTime == gap.olderServerTime &&
                            pageNewest.matchesBoundary(
                                pageNewestAnchor,
                                gap.olderMsgid,
                                gapOlderAnchor,
                            )
                    ) || (
                        pageOldestTime == gap.newerServerTime &&
                            pageOldest.matchesBoundary(
                                pageOldestAnchor,
                                gap.newerMsgid,
                                gapNewerAnchor,
                            )
                    )
                if (!overlaps) {
                    return@forEach
                }
                overlappedExistingGap = true
                // Timeline order is assigned locally and cannot prove that a distinct equal-time
                // server event crossed an existing protocol boundary. At equal timestamps only the
                // exact persisted boundary proves coverage; otherwise retain the uncovered prefix or
                // suffix and let directional paging resolve it.
                val coversOlder =
                    pageOldestTime < gap.olderServerTime ||
                        (
                            pageOldestTime == gap.olderServerTime &&
                                pageOldest.matchesBoundary(
                                    pageOldestAnchor,
                                    gap.olderMsgid,
                                    gapOlderAnchor,
                                )
                        )
                val coversNewer =
                    pageNewestTime > gap.newerServerTime ||
                        (
                            pageNewestTime == gap.newerServerTime &&
                                pageNewest.matchesBoundary(
                                    pageNewestAnchor,
                                    gap.newerMsgid,
                                    gapNewerAnchor,
                                )
                        )
                when {
                    coversOlder && coversNewer -> {
                        db.historyGapDao().delete(gap.id)
                    }

                    coversOlder -> {
                        updateOrDeleteGap(
                            gap.copy(
                                olderMsgid = pageNewest.msgid,
                                olderServerTime = pageNewestTime,
                                olderEventId = pageNewestAnchor.eventId,
                                olderTimelineOrder = pageNewestAnchor.timelineOrder,
                            ),
                        )
                    }

                    coversNewer -> {
                        updateOrDeleteGap(
                            gap.copy(
                                newerMsgid = pageOldest.msgid,
                                newerServerTime = pageOldestTime,
                                newerEventId = pageOldestAnchor.eventId,
                                newerTimelineOrder = pageOldestAnchor.timelineOrder,
                            ),
                        )
                    }

                    else -> {
                        db.historyGapDao().update(
                            gap.copy(
                                newerMsgid = pageOldest.msgid,
                                newerServerTime = pageOldestTime,
                                newerEventId = pageOldestAnchor.eventId,
                                newerTimelineOrder = pageOldestAnchor.timelineOrder,
                            ),
                        )
                        db.historyGapDao().insert(
                            HistoryGapEntity(
                                roomId = roomId,
                                olderMsgid = pageNewest.msgid,
                                olderServerTime = pageNewestTime,
                                olderEventId = pageNewestAnchor.eventId,
                                olderTimelineOrder = pageNewestAnchor.timelineOrder,
                                newerMsgid = gap.newerMsgid,
                                newerServerTime = gap.newerServerTime,
                                newerEventId = gap.newerEventId,
                                newerTimelineOrder = gap.newerTimelineOrder,
                            ),
                        )
                    }
                }
            }

            // A non-LATEST page that lands strictly below every retained row and touches no recorded
            // gap creates a new OLDEST island. Without a recorded interval between the island's newest
            // edge and the previously-oldest retained boundary the timeline renders false adjacency AND
            // the interval is permanently unfillable: the APPEND ladder pages BEFORE the island's
            // OLDEST row, so it never asks for anything above it. Symmetric to the LATEST catch-up
            // insert below, and born recoverable for the same reasons.
            //
            // Accepted imprecision: a deep BEFORE whose bound is NOT the previously-oldest boundary
            // records `[pageNewest .. previousOldest]`, which includes the server-proven-empty
            // `(pageNewest .. bound]` prefix. The row is recoverable, a fill shrinks it, and no code
            // path issues such a request today — the ungapped ladder always pages BEFORE exactly this
            // boundary, and a gap-directed fill arrives here with a focused `historyGapId`.
            if (
                request.subcommand != ChatHistoryRequest.Subcommand.LATEST &&
                !overlappedExistingGap &&
                previousOldest?.serverTime != null
            ) {
                val prevTime = checkNotNull(previousOldest.serverTime)
                // Equal timestamps only prove a DISTINCT event when the comparison is decidable —
                // msgids on both sides, or a resolvable anchor on both. When neither side can be
                // identified the page's newest row may BE the previously-oldest row, and the gap would
                // be a zero-width row asserting a missing message that cannot exist.
                val decidableAtEqualTime =
                    (pageNewest.msgid != null && previousOldest.msgid != null) ||
                        (pageNewestAnchor != null && previousOldestAnchor != null)
                val strictlyBelow =
                    pageNewestTime < prevTime ||
                        (
                            pageNewestTime == prevTime && decidableAtEqualTime &&
                                !pageNewest.matchesBoundary(
                                    pageNewestAnchor,
                                    previousOldest.msgid,
                                    previousOldestAnchor,
                                )
                        )
                // A BEFORE page is adjacent to its request bound by protocol. When that bound IS the
                // previously-oldest boundary (the ordinary append ladder) the interval is server-proven
                // contiguous, and recording a gap would draw a false seam on every appended page.
                val provenAdjacent =
                    request.subcommand == ChatHistoryRequest.Subcommand.BEFORE &&
                        request.bound1.matches(previousOldest.msgid, prevTime)
                if (strictlyBelow && !provenAdjacent) {
                    db.historyGapDao().insert(
                        HistoryGapEntity(
                            roomId = roomId,
                            olderMsgid = pageNewest.msgid,
                            olderServerTime = pageNewestTime,
                            olderEventId = pageNewestAnchor?.eventId,
                            olderTimelineOrder = pageNewestAnchor?.timelineOrder,
                            newerMsgid = previousOldest.msgid,
                            newerServerTime = prevTime,
                            newerEventId = previousOldestAnchor?.eventId,
                            newerTimelineOrder = previousOldestAnchor?.timelineOrder,
                            // recoverable = false stays reserved for server-proven-empty intervals;
                            // saturation must never poison this gap.
                            recoverable = true,
                        ),
                    )
                }
            }

            // A saturated msgid-less page edge is an AMBIGUOUS CURSOR, not a missing interval, so it is
            // recorded as an observation and never as a durable gap.
            //
            // `history_gaps` rows mean exactly one thing: "messages are missing strictly between these
            // two known boundaries". A row whose older and newer edges name the SAME event asserts
            // nothing — a zero-width interval cannot hold a message — and `recoverable = false` is
            // reserved for intervals the server PROVED empty (the primaryMessageCount == 0 branch
            // above). A saturated timestamp-only page proves neither, so writing that row states two
            // falsehoods at once and both of its consumers act on them: the mediator treats an
            // unrecoverable focused gap as permanently terminal (killing older backfill from page one on
            // every soju MSGREFTYPES=timestamp wire), and `historyWindowBounds` clamps the Recent window
            // at the edge row (hiding whatever backfill did land). This mirrors the directional branches
            // above and the catch-up insert below, which already refuse to let saturation poison a gap.
            //
            // What saturation genuinely means — "additional messages may share this boundary timestamp,
            // so the SAME cursor cannot be paged past safely" — is a per-fetch property and stays where
            // it already lives: HistoryPageLoader's cannotSafelyPageBefore/cannotSafelyPageAfter end
            // that fetch at the ambiguous edge, `historyComplete` is still only set by a terminal
            // response, and the mediator re-issues only after the boundary actually advanced.
            val ambiguousBoundary =
                when (request.subcommand) {
                    ChatHistoryRequest.Subcommand.LATEST,
                    ChatHistoryRequest.Subcommand.BEFORE,
                    -> pageOldest

                    ChatHistoryRequest.Subcommand.AFTER -> pageNewest

                    else -> null
                }?.takeIf { reference ->
                    !terminal && reference.msgid == null &&
                        response.primaryMessageCount >= request.limit &&
                        directionalGap == null &&
                        (request.subcommand != ChatHistoryRequest.Subcommand.LATEST || previousNewest == null)
                }
            if (ambiguousBoundary != null) {
                diagnostics.record("chat_history", "ambiguous_saturated_boundary") {
                    mapOf(
                        "room_id" to roomId,
                        "subcommand" to request.subcommand.name,
                        "boundary_server_time" to ambiguousBoundary.serverTime,
                        "primary_count" to response.primaryMessageCount,
                        "limit" to request.limit,
                    )
                }
            }

            if (
                request.subcommand == ChatHistoryRequest.Subcommand.LATEST &&
                !response.endOfHistory &&
                response.primaryMessageCount > 0
            ) {
                val prior = previousNewest?.takeIf { it.serverTime != null } ?: return
                val priorTime = checkNotNull(prior.serverTime)
                val priorAnchor = previousNewestAnchor
                if (
                    priorTime < pageOldestTime ||
                    (
                        priorTime == pageOldestTime &&
                            !pageOldest.matchesBoundary(
                                pageOldestAnchor,
                                prior.msgid,
                                priorAnchor,
                            )
                    )
                ) {
                    var covered = false
                    if (priorAnchor != null && pageOldestAnchor != null) {
                        for (gap in db.historyGapDao().forRoom(roomId)) {
                            val gapOlderAnchor = historyGapAnchor(roomId, gap, older = true)
                            val gapNewerAnchor = historyGapAnchor(roomId, gap, older = false)
                            val coversPrior =
                                gap.olderServerTime < priorTime ||
                                    (
                                        gap.olderServerTime == priorTime &&
                                            prior.matchesBoundary(
                                                priorAnchor,
                                                gap.olderMsgid,
                                                gapOlderAnchor,
                                            )
                                    )
                            val coversPage =
                                gap.newerServerTime > pageOldestTime ||
                                    (
                                        gap.newerServerTime == pageOldestTime &&
                                            pageOldest.matchesBoundary(
                                                pageOldestAnchor,
                                                gap.newerMsgid,
                                                gapNewerAnchor,
                                            )
                                    )
                            if (coversPrior && coversPage) {
                                covered = true
                                break
                            }
                        }
                    }
                    if (!covered) {
                        // Reconnect catch-up interval between the previous newest boundary and this
                        // LATEST page's oldest row. Always born recoverable: recoverable=false is
                        // reserved for server-proven-empty intervals (primaryMessageCount == 0 above).
                        // On timestamp-only wires (soju advertises MSGREFTYPES=timestamp, stripping
                        // msgid boundary references) a saturated page must NOT poison this gap — the
                        // interval is fully fetchable via BEFORE from its newer edge, and the loader's
                        // cannotSafelyPageBefore already stops each fetch at an ambiguous
                        // equal-timestamp boundary per page.
                        db.historyGapDao().insert(
                            HistoryGapEntity(
                                roomId = roomId,
                                olderMsgid = prior.msgid,
                                olderServerTime = priorTime,
                                olderEventId = priorAnchor?.eventId,
                                olderTimelineOrder = priorAnchor?.timelineOrder,
                                newerMsgid = pageOldest.msgid,
                                newerServerTime = pageOldestTime,
                                newerEventId = pageOldestAnchor?.eventId,
                                newerTimelineOrder = pageOldestAnchor?.timelineOrder,
                                recoverable = true,
                            ),
                        )
                    }
                }
            }
        }

        private suspend fun updateOrDeleteGap(gap: HistoryGapEntity) {
            val olderAnchor = historyGapAnchor(gap.roomId, gap, older = true)
            val newerAnchor = historyGapAnchor(gap.roomId, gap, older = false)
            val sameBoundary =
                when {
                    gap.olderMsgid != null && gap.newerMsgid != null -> {
                        gap.olderMsgid == gap.newerMsgid
                    }

                    else -> {
                        olderAnchor == newerAnchor
                    }
                }
            // Equal server timestamps do not establish ordering between distinct IRC events. Keep
            // that interval until an exact boundary proves it empty.
            val invalid =
                gap.olderServerTime > gap.newerServerTime ||
                    (gap.olderServerTime == gap.newerServerTime && sameBoundary)
            if (invalid) {
                db.historyGapDao().delete(gap.id)
            } else {
                db.historyGapDao().update(gap)
            }
        }

        private suspend fun resolveStoredBoundary(
            roomId: RoomId,
            reference: ChatHistoryReference,
            newest: Boolean,
        ): TimelineAnchor? {
            reference.msgid?.let { msgid ->
                messageDao.byMsgid(roomId, msgid)?.let {
                    return TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
                }
            }
            val row =
                if (newest) {
                    messageDao.newestMessage(roomId)
                } else {
                    val boundary = messageDao.oldestBoundary(roomId) ?: return null
                    boundary.msgid?.let { messageDao.byMsgid(roomId, it) }
                }
            return row
                ?.takeIf { it.serverTime == reference.serverTime }
                ?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }

        private fun resolvePageBoundary(
            reference: ChatHistoryReference,
            pageRows: List<MessageEntity>,
            oldest: Boolean,
        ): TimelineAnchor? {
            val candidates =
                pageRows.filter { row ->
                    when {
                        reference.msgid != null -> row.msgid == reference.msgid
                        reference.serverTime != null -> row.serverTime == reference.serverTime
                        else -> false
                    }
                }
            val row = if (oldest) candidates.firstOrNull() else candidates.lastOrNull()
            return row?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
        }

        private suspend fun historyGapAnchor(
            roomId: RoomId,
            gap: HistoryGapEntity,
            older: Boolean,
        ): TimelineAnchor {
            val msgid = if (older) gap.olderMsgid else gap.newerMsgid
            val serverTime = if (older) gap.olderServerTime else gap.newerServerTime
            val eventId = if (older) gap.olderEventId else gap.newerEventId
            val timelineOrder = if (older) gap.olderTimelineOrder else gap.newerTimelineOrder
            msgid?.let { messageDao.byMsgid(roomId, it) }?.let {
                return TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
            }
            eventId?.let { messageDao.byCanonicalId(it) }?.takeIf { it.bufferId == roomId }?.let {
                return TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
            }
            eventId?.let { return TimelineAnchor(serverTime, it, timelineOrder ?: it) }
            val fallback = if (older) Long.MIN_VALUE else Long.MAX_VALUE
            return TimelineAnchor(serverTime, fallback, fallback)
        }

        private fun String?.matches(
            msgid: String?,
            serverTime: Long,
        ): Boolean =
            when (this) {
                msgid?.let(ChatHistorySelectors::msgid) -> true
                ChatHistorySelectors.timestamp(serverTime) -> true
                else -> false
            }

        private fun List<HistoryGapEntity>.matchingDirectionalGap(
            selector: String?,
            boundary: (HistoryGapEntity) -> Pair<String?, Long>,
        ): HistoryGapEntity? {
            val matches =
                filter { gap ->
                    val (msgid, serverTime) = boundary(gap)
                    selector.matches(msgid, serverTime)
                }
            // Msgids are exact. Timestamp selectors cannot safely choose among opaque equal-time gaps.
            return if (selector?.startsWith("msgid=") == true) matches.firstOrNull() else matches.singleOrNull()
        }

        private fun ChatHistoryReference.matchesBoundary(
            anchor: TimelineAnchor?,
            storedMsgid: String?,
            storedAnchor: TimelineAnchor?,
        ): Boolean =
            when {
                msgid != null && storedMsgid != null -> msgid == storedMsgid
                anchor != null && storedAnchor != null -> anchor == storedAnchor
                else -> false
            }

        private suspend fun canonicalBatchMultiplicities(
            networkId: Long,
            target: String,
            events: List<IrcEvent>,
            origin: EventOrigin,
        ): Map<CanonicalBatchKey, CanonicalBatchMultiplicity> {
            val st = stateFor(networkId)
            val chatEvents = events.filterIsInstance<IrcEvent.ChatMessage>()
            var chatRoutes =
                chatEvents
                    .map { event ->
                        resolveChatRoute(networkId, event, st, target, origin)
                    }.map { route ->
                        route.copy(bufferId = bufferDao.canonicalId(route.bufferId) ?: route.bufferId)
                    }
            val strongQueryRooms =
                chatEvents
                    .zip(chatRoutes)
                    .filter { (event, route) ->
                        route.type == BufferType.QUERY && !route.sourceIsSelf &&
                            event.ctx.account?.takeUnless { it.isEmpty() || it == "*" } != null
                    }.mapTo(linkedSetOf()) { (_, route) -> route.bufferId }
            strongQueryRooms.singleOrNull()?.let { roomId ->
                chatRoutes =
                    chatRoutes.map { route ->
                        if (route.type == BufferType.QUERY) route.copy(bufferId = roomId) else route
                    }
            }
            activeHistoryChatRoutes[networkId] = ArrayDeque(chatRoutes)
            val routedTargetRooms =
                chatRoutes
                    .asSequence()
                    .filter { route -> activeHistoryTargets[networkId]?.type == route.type }
                    .mapTo(linkedSetOf()) { it.bufferId }
            routedTargetRooms.singleOrNull()?.let { roomId ->
                bufferDao.observeById(roomId)?.let { room ->
                    activeHistoryTargets[networkId] =
                        ActiveHistoryTarget(
                            target,
                            room.id,
                            room.type,
                            room.name,
                        )
                }
            }
            val routeIterator = chatRoutes.iterator()
            val keys =
                events
                    .mapNotNull { event ->
                        historyBatchKey(
                            networkId,
                            target,
                            event,
                            st,
                            if (event is IrcEvent.ChatMessage) routeIterator.next() else null,
                        )
                    }.map { key -> key.copy(roomId = bufferDao.canonicalId(key.roomId) ?: key.roomId) }
            val exactCounts = keys.groupingBy { it }.eachCount()
            val semanticCounts =
                keys
                    .groupingBy {
                        CanonicalSemanticBatchKey(it.roomId, it.kind, it.normalizedActor, it.text)
                    }.eachCount()
            return exactCounts.mapValues { (key, exact) ->
                CanonicalBatchMultiplicity(
                    semantic =
                        semanticCounts.getValue(
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
            preflightChatRoute: ChatRoute? = null,
        ): CanonicalBatchKey? {
            suspend fun channelRoom(name: String) = ensureBuffer(networkId, name, BufferType.CHANNEL, st)

            fun key(
                roomId: Long,
                kind: MessageKind,
                actor: String,
                text: String,
                time: Long,
            ) = CanonicalBatchKey(roomId, kind, st.normalize(actor), text, time)
            return when (event) {
                is IrcEvent.ChatMessage -> {
                    val route =
                        preflightChatRoute
                            ?: resolveChatRoute(networkId, event, st, target, EventOrigin.HISTORY)
                    key(
                        route.bufferId,
                        kindOf(event.kind),
                        event.source.nick,
                        route.storedText,
                        event.ctx.serverTime,
                    )
                }

                is IrcEvent.Joined -> {
                    key(
                        channelRoom(event.channel),
                        MessageKind.JOIN,
                        event.nick,
                        "${event.nick} joined",
                        event.ctx.serverTime,
                    )
                }

                is IrcEvent.Parted -> {
                    key(
                        channelRoom(event.channel),
                        MessageKind.PART,
                        event.nick,
                        "${event.nick} left" + (event.reason?.let { " ($it)" } ?: ""),
                        event.ctx.serverTime,
                    )
                }

                is IrcEvent.Quit -> {
                    historicalTargetBuffer(networkId, target)?.let {
                        key(
                            it,
                            MessageKind.QUIT,
                            event.nick,
                            "${event.nick} quit" + (event.reason?.let { reason -> " ($reason)" } ?: ""),
                            event.ctx.serverTime,
                        )
                    }
                }

                is IrcEvent.Kicked -> {
                    key(
                        channelRoom(event.channel),
                        MessageKind.KICK,
                        event.by,
                        "${event.nick} was kicked by ${event.by}" +
                            (event.reason?.let { " ($it)" } ?: ""),
                        event.ctx.serverTime,
                    )
                }

                is IrcEvent.NickChanged -> {
                    historicalTargetBuffer(networkId, target)?.let {
                        key(
                            it,
                            MessageKind.NICK,
                            event.from,
                            "${event.from} is now known as ${event.to}",
                            event.ctx.serverTime,
                        )
                    }
                }

                is IrcEvent.TopicChanged -> {
                    key(
                        channelRoom(event.channel),
                        MessageKind.TOPIC,
                        event.setBy ?: "",
                        "topic: ${event.topic}",
                        event.ctx.serverTime,
                    )
                }

                is IrcEvent.ModeChanged -> {
                    if (isChannel(networkId, event.target, st)) {
                        key(
                            channelRoom(event.target),
                            MessageKind.MODE,
                            "",
                            "mode ${event.modes} ${event.args.joinToString(" ")}".trim(),
                            event.ctx.serverTime,
                        )
                    } else {
                        null
                    }
                }

                is IrcEvent.Invited -> {
                    val selfInvite = st.normalize(event.nick) == st.normalize(st.selfNick)
                    val validChannel = isChannel(networkId, event.channel, st)
                    val existingChannel =
                        if (validChannel) {
                            existingChannelBuffer(networkId, event.channel, st)
                        } else {
                            null
                        }
                    val roomId =
                        when {
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

                else -> {
                    null
                }
            }
        }

        private fun nextHistoryExactOrdinal(
            networkId: Long,
            key: CanonicalBatchKey,
        ): Int? {
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
            if (origin.isHistorical) {
                val target = batch.target ?: historyTarget ?: return
                val st = stateFor(networkId)
                val bufferId = ensureBuffer(networkId, target, BufferType.CHANNEL, st)
                val children =
                    batch.events.map { child ->
                        when (child) {
                            is IrcEvent.Quit -> child.nick to child.ctx
                            is IrcEvent.Joined -> child.nick to child.ctx
                            else -> error("validated network batch child")
                        }
                    }
                insertNetworkBatch(bufferId, batch, children, st)
                return
            }
            if (!origin.mutatesSessionState) return
            val st = stateFor(networkId)
            val affected = LinkedHashMap<Long, MutableList<Pair<String, MessageContext>>>()
            db.withTransaction {
                when (batch.kind) {
                    IrcEvent.NetworkBatchKind.NETSPLIT -> {
                        batch.events.forEach { child ->
                            val quit = child as IrcEvent.Quit
                            val targetBufferId =
                                batch.target?.let { target ->
                                    existingChannelBuffer(networkId, target, st)?.id
                                }
                            (buffersOfNick(networkId, quit.nick) + listOfNotNull(targetBufferId)).distinct().forEach { bufferId ->
                                memberDao.remove(bufferId, quit.nick)
                                journal(networkId, bufferId, RosterDelta.Remove(quit.nick))
                                affected.getOrPut(bufferId) { mutableListOf() } += quit.nick to quit.ctx
                            }
                        }
                    }

                    IrcEvent.NetworkBatchKind.NETJOIN -> {
                        batch.events.forEach { child ->
                            val join = child as IrcEvent.Joined
                            val buffer =
                                existingChannelBuffer(networkId, join.channel, st)
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
            val identities =
                children.map { (nick, ctx) ->
                    ctx.msgid ?: "${st.normalize(nick)}@${ctx.serverTime}"
                }
            val pair = listOf(batch.serverA.lowercase(), batch.serverB.lowercase()).sorted().joinToString("|")
            val kind =
                if (batch.kind == IrcEvent.NetworkBatchKind.NETSPLIT) {
                    MessageKind.NETSPLIT
                } else {
                    MessageKind.NETJOIN
                }
            val diagnosticKey =
                "network:${kind.name.lowercase()}:$pair:${buffer.name}:" +
                    SemanticIdentity.keyFor(null, 0, pair, identities.joinToString("|"))
            val eventKey = if (msgids.all { it != null }) diagnosticKey else null
            val verb = if (kind == MessageKind.NETSPLIT) "split" else "rejoined"
            val text =
                "${nicks.size} ${if (nicks.size == 1) "user" else "users"} $verb " +
                    "(${batch.serverA} ↔ ${batch.serverB})"
            val row =
                MessageEntity(
                    bufferId = bufferId,
                    serverTime = children.maxOf { it.second.serverTime },
                    sender = "",
                    normalizedActor = "",
                    kind = kind,
                    text = text,
                    dedupKey = diagnosticKey,
                    eventKey = eventKey,
                    eventPayload = NetworkBatchPayloadV1(batch.serverA, batch.serverB, nicks).encode(),
                    serverTimeAuthoritative =
                        children.all {
                            it.second.serverTimeSource == ServerTimeSource.TAG
                        },
                )
            val fromHistory = !children.any { it.second.batchId == null }
            val result =
                canonicalTimeline.ingest(
                    TimelineObservation(
                        networkId = buffer.networkId,
                        event = row,
                        origin = if (fromHistory) ObservationOrigin.HISTORY else ObservationOrigin.LIVE,
                        connectionGeneration = connectionGenerations[buffer.networkId],
                        batchId = children.firstNotNullOfOrNull { it.second.batchId },
                        timeProvenance =
                            if (row.serverTimeAuthoritative) {
                                TimeProvenance.SERVER_TAG
                            } else {
                                TimeProvenance.LOCAL_CLOCK
                            },
                        persistHistoryCursor = buffer.networkId !in activeProtocolPageCursorWrites,
                    ),
                )
            recordPlaybackResult(buffer.networkId, result)
            traceMessageWrite("canonical_network_batch", result.event, fromHistory)
        }

        // -- invitations --------------------------------------------------------

        private suspend fun onInvited(
            networkId: Long,
            e: IrcEvent.Invited,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val selfInvite = st.normalize(e.nick) == st.normalize(st.selfNick)
            val validChannel = isChannel(networkId, e.channel, st)
            val existingChannel =
                if (validChannel) {
                    existingChannelBuffer(networkId, e.channel, st)
                } else {
                    null
                }
            val bufferId =
                when {
                    selfInvite && validChannel -> ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
                    !selfInvite && existingChannel != null -> existingChannel.id
                    else -> ensureServerBuffer(networkId, st)
                }
            val historical = origin.isHistorical || e.ctx.batchId != null
            val actionable = selfInvite && validChannel && !historical
            val state =
                when {
                    historical -> InviteState.HISTORICAL
                    actionable -> InviteState.PENDING
                    else -> InviteState.HISTORICAL
                }
            val payload = InvitePayloadV1(e.by, e.nick, e.channel)
            val eventKey = e.ctx.msgid?.let { "invite:msgid:$it" }
            val text =
                when {
                    selfInvite && validChannel -> "${e.by.ifBlank { "Someone" }} invited you to ${e.channel}"
                    validChannel -> "${e.by.ifBlank { "Someone" }} invited ${e.nick} to ${e.channel}"
                    else -> "Received an invalid invitation for ${e.channel.ifBlank { "an unknown channel" }}"
                }
            val row =
                MessageEntity(
                    bufferId = bufferId,
                    msgid = e.ctx.msgid,
                    serverTime = e.ctx.serverTime,
                    sender = e.by,
                    normalizedActor = st.normalize(e.by),
                    kind = MessageKind.INVITE,
                    text = text,
                    dedupKey =
                        eventKey ?: SemanticIdentity.keyFor(
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
            val multiplicity =
                activeHistoryMultiplicities[networkId]?.get(
                    CanonicalBatchKey(
                        bufferId,
                        row.kind,
                        row.normalizedActor,
                        payload.encode(),
                        row.serverTime,
                    ),
                )
            val batchKey =
                CanonicalBatchKey(
                    bufferId,
                    row.kind,
                    row.normalizedActor,
                    payload.encode(),
                    row.serverTime,
                )
            val result =
                canonicalTimeline.ingest(
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
            recordPlaybackResult(networkId, result)
            traceMessageWrite("canonical_invite", result.event, historical)
            if (actionable) {
                presentNotification(result.event.id) {
                    notifier.onInvitation(networkId, result.event.bufferId, result.event.id)
                }
            }
        }

        // -- DCC direct connections ---------------------------------------------

        private suspend fun onDccSend(
            networkId: Long,
            e: IrcEvent.DccSend,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val room = ensureDccPeerQuery(networkId, e.source.nick, e.ctx.account, st)
            val normalizedPeer = st.normalize(e.source.nick)
            val offer = e.offer
            val offerKey = dccFileOfferKey(e, normalizedPeer)
            val historical = origin.isHistorical || e.ctx.batchId != null
            val payload =
                DccFileOfferPayloadV1(
                    protocol = offer.protocol.name,
                    filename = offer.filename,
                    address = offer.endpoint.address,
                    addressKind = offer.endpoint.addressKind.name,
                    port = offer.endpoint.port,
                    sizeBytes = offer.sizeBytes,
                    token = offer.token,
                    offerKey = offerKey,
                )
            val text =
                "DCC file offer: ${sanitizeDccFilename(offer.filename)}" +
                    (offer.sizeBytes?.let { " (${formatBytes(it)})" } ?: "")
            val row =
                MessageEntity(
                    bufferId = room.id,
                    msgid = e.ctx.msgid,
                    serverTime = e.ctx.serverTime,
                    sender = e.source.nick,
                    normalizedActor = normalizedPeer,
                    senderAccount = e.ctx.account,
                    kind = MessageKind.DCC_TRANSFER,
                    text = text,
                    dedupKey = offerKey,
                    eventKey = offerKey,
                    eventPayload = payload.encode(),
                    serverTimeAuthoritative = e.ctx.serverTimeSource == ServerTimeSource.TAG,
                )
            val result =
                canonicalTimeline.ingest(
                    TimelineObservation(
                        networkId = networkId,
                        event = row,
                        origin = origin.toObservationOrigin(),
                        connectionGeneration = connectionGenerations[networkId],
                        label = e.ctx.label,
                        batchId = e.ctx.batchId,
                        timeProvenance = e.ctx.serverTimeSource.toTimeProvenance(),
                        persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                    ),
                )
            recordPlaybackResult(networkId, result)
            traceMessageWrite("canonical_dcc_file_offer", result.event, historical)
            dccTransferDao.insertIgnore(
                DccTransferEntity(
                    networkId = networkId,
                    timelineEventId = result.event.id,
                    offerKey = offerKey,
                    direction = DccDirection.INCOMING,
                    protocol =
                        if (offer.protocol == IrcEvent.DccFileProtocol.SSEND) {
                            DccTransferProtocol.SSEND
                        } else {
                            DccTransferProtocol.SEND
                        },
                    peerNick = e.source.nick,
                    normalizedPeer = normalizedPeer,
                    filename = offer.filename,
                    displayFilename = sanitizeDccFilename(offer.filename),
                    address = offer.endpoint.address,
                    addressKind = offer.endpoint.addressKind.toDbAddressKind(),
                    port = offer.endpoint.port,
                    sizeBytes = offer.sizeBytes,
                    token = offer.token,
                    state = if (historical) DccTransferState.EXPIRED else DccTransferState.OFFERED,
                    createdAt = e.ctx.serverTime,
                    expiresAt = if (historical) e.ctx.serverTime else e.ctx.serverTime + DCC_OFFER_EXPIRY_MS,
                    updatedAt = e.ctx.serverTime,
                ),
            )
            if (origin.notifies && !historical) {
                presentNotification(result.event.id) {
                    notifier.onDccTransferOffer(networkId, result.event.bufferId, result.event.id)
                }
            }
        }

        private suspend fun onDccResume(
            networkId: Long,
            e: IrcEvent.DccResume,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val room = ensureDccPeerQuery(networkId, e.source.nick, e.ctx.account, st)
            val text = "DCC resume requested for ${sanitizeDccFilename(e.request.filename)} at ${formatBytes(e.request.positionBytes)}"
            insertDccControl(room.id, networkId, e.ctx, e.source.nick, st.normalize(e.source.nick), text, origin)
        }

        private suspend fun onDccAccept(
            networkId: Long,
            e: IrcEvent.DccAccept,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val room = ensureDccPeerQuery(networkId, e.source.nick, e.ctx.account, st)
            val text = "DCC resume accepted for ${sanitizeDccFilename(e.accepted.filename)} at ${formatBytes(e.accepted.positionBytes)}"
            insertDccControl(room.id, networkId, e.ctx, e.source.nick, st.normalize(e.source.nick), text, origin)
        }

        private suspend fun onUnsupportedDcc(
            networkId: Long,
            e: IrcEvent.UnsupportedDcc,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val room = ensureDccPeerQuery(networkId, e.source.nick, e.ctx.account, st)
            val text =
                when (e.reason) {
                    IrcEvent.DccUnsupportedReason.UNKNOWN_COMMAND -> "Unsupported DCC ${e.command.orEmpty()} request".trim()
                    IrcEvent.DccUnsupportedReason.MALFORMED -> "Malformed DCC request"
                }
            val payload = UnsupportedDccPayloadV1(e.command, e.reason.name, e.rawPayload)
            val key =
                e.ctx.msgid?.let { "dcc:unsupported:msgid:$it" }
                    ?: "dcc:unsupported:" + SemanticIdentity.keyFor(e.ctx, e.source.nick, e.rawPayload)
            val row =
                MessageEntity(
                    bufferId = room.id,
                    msgid = e.ctx.msgid,
                    serverTime = e.ctx.serverTime,
                    sender = e.source.nick,
                    normalizedActor = st.normalize(e.source.nick),
                    senderAccount = e.ctx.account,
                    kind = MessageKind.DCC_UNSUPPORTED,
                    text = text,
                    dedupKey = key,
                    eventKey = key,
                    eventPayload = payload.encode(),
                    serverTimeAuthoritative = e.ctx.serverTimeSource == ServerTimeSource.TAG,
                )
            val result =
                canonicalTimeline.ingest(
                    TimelineObservation(
                        networkId = networkId,
                        event = row,
                        origin = origin.toObservationOrigin(),
                        connectionGeneration = connectionGenerations[networkId],
                        label = e.ctx.label,
                        batchId = e.ctx.batchId,
                        timeProvenance = e.ctx.serverTimeSource.toTimeProvenance(),
                        persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                    ),
                )
            recordPlaybackResult(networkId, result)
            traceMessageWrite("canonical_dcc_unsupported", result.event, origin.isHistorical || e.ctx.batchId != null)
        }

        private suspend fun insertDccControl(
            roomId: RoomId,
            networkId: Long,
            ctx: MessageContext,
            sender: String,
            normalizedSender: String,
            text: String,
            origin: EventOrigin,
        ) {
            val key =
                ctx.msgid?.let { "dcc:control:msgid:$it" }
                    ?: "dcc:control:" + SemanticIdentity.keyFor(ctx, sender, text)
            val row =
                MessageEntity(
                    bufferId = roomId,
                    msgid = ctx.msgid,
                    serverTime = ctx.serverTime,
                    sender = sender,
                    normalizedActor = normalizedSender,
                    kind = MessageKind.DCC_TRANSFER,
                    text = text,
                    dedupKey = key,
                    eventKey = key,
                    serverTimeAuthoritative = ctx.serverTimeSource == ServerTimeSource.TAG,
                )
            val result =
                canonicalTimeline.ingest(
                    TimelineObservation(
                        networkId = networkId,
                        event = row,
                        origin = origin.toObservationOrigin(),
                        connectionGeneration = connectionGenerations[networkId],
                        label = ctx.label,
                        batchId = ctx.batchId,
                        timeProvenance = ctx.serverTimeSource.toTimeProvenance(),
                        persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                    ),
                )
            recordPlaybackResult(networkId, result)
            traceMessageWrite("canonical_dcc_control", result.event, origin.isHistorical || ctx.batchId != null)
        }

        private suspend fun ensureDccPeerQuery(
            networkId: Long,
            peerNick: String,
            account: String?,
            st: NetworkState,
        ): BufferEntity {
            val normalizedPeer = st.normalize(peerNick)
            val provisional =
                bufferStore.resolveQueryRoom(networkId, normalizedPeer, account = null)
                    ?: bufferStore.resolveQueryRoom(networkId, normalizedPeer, account)
                    ?: ensureBufferEntity(networkId, peerNick, BufferType.QUERY, st)
            return bufferStore.bindQueryIdentity(
                roomId = provisional.id,
                networkId = networkId,
                normalizedNick = normalizedPeer,
                displayNick = peerNick,
                account = account,
            )
        }

        private fun dccFileOfferKey(
            e: IrcEvent.DccSend,
            normalizedPeer: String,
        ): String =
            e.ctx.msgid?.let { "dcc:file:msgid:$it" }
                ?: "dcc:file:" +
                SemanticIdentity.keyFor(
                    null,
                    e.ctx.serverTime,
                    normalizedPeer,
                    listOf(
                        e.offer.protocol.name,
                        e.offer.filename,
                        e.offer.endpoint.address,
                        e.offer.endpoint.port
                            .toString(),
                        e.offer.sizeBytes
                            ?.toString()
                            .orEmpty(),
                        e.offer.token.orEmpty(),
                    ).joinToString("|"),
                )

        private fun IrcEvent.DccAddressKind.toDbAddressKind(): DccAddressKind =
            when (this) {
                IrcEvent.DccAddressKind.IPV4_INTEGER -> DccAddressKind.IPV4_INTEGER
                IrcEvent.DccAddressKind.IPV4_DOTTED -> DccAddressKind.IPV4_DOTTED
                IrcEvent.DccAddressKind.IPV6_LITERAL -> DccAddressKind.IPV6_LITERAL
            }

        private fun sanitizeDccFilename(filename: String): String {
            val clean =
                filename
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
                    .map { ch -> if (ch.isISOControl() || ch == ':' || ch == '"' || ch == '<' || ch == '>') '_' else ch }
                    .joinToString("")
                    .trim()
                    .trim('.')
            return clean.ifBlank { "download" }
        }

        private fun formatBytes(bytes: Long): String =
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
                bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MiB"
                else -> "${bytes / (1024L * 1024L * 1024L)} GiB"
            }

        // -- membership ----------------------------------------------------------

        // One arrival, one commit. The roster upsert, the user record, the invite resolution and the
        // JOIN row are a single logical event, and a Room commit is what releases a PagingSource
        // invalidation — so committing them separately regenerated the open timeline two or three times
        // per JOIN line. A reconnect join storm turns that into visible redraw churn, worst under
        // presence mode ALL where every one of those rows is also a presented row. Live-only: the
        // historical path already runs inside [onPlaybackEvents]' batch transaction.
        private suspend fun onJoined(
            networkId: Long,
            e: IrcEvent.Joined,
        ) {
            var resolvedInviteIds = emptyList<Long>()
            db.withTransaction {
                val st = stateFor(networkId)
                val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
                if (e.isSelf) markJoined(bufferId, true)
                memberDao.upsert(MemberEntity(bufferId, e.nick))
                if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Upsert(e.nick))
                upsertUser(networkId, e.nick) { it.copy(account = e.account ?: it.account, realname = e.realname ?: it.realname) }
                if (e.isSelf) {
                    if (e.ctx.batchId == null) {
                        resolvedInviteIds = messageDao.actionableInviteIds(bufferId)
                        if (resolvedInviteIds.isNotEmpty()) messageDao.markInvitesJoined(bufferId)
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
            // Announced only after the commit: the notifier is an app-level side effect, and firing it
            // from inside the transaction would resolve an invitation a rollback could still restore.
            resolvedInviteIds.forEach { notifier.onInvitationResolved(it) }
        }

        private suspend fun onHistoricalJoined(
            networkId: Long,
            e: IrcEvent.Joined,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            // History uses msgid/exact identity. Attaching the current live membership-cycle alias to
            // an old replay could otherwise coalesce two genuine JOIN cycles.
            insertSystem(bufferId, e.ctx, MessageKind.JOIN, e.nick, "${e.nick} joined", isSelf = e.isSelf)
        }

        private suspend fun onParted(
            networkId: Long,
            e: IrcEvent.Parted,
        ) {
            val st = stateFor(networkId)
            val buffer = existingChannelBuffer(networkId, e.channel, st) ?: return
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

        private suspend fun onHistoricalParted(
            networkId: Long,
            e: IrcEvent.Parted,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            insertSystem(bufferId, e.ctx, MessageKind.PART, e.nick, "${e.nick} left" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
        }

        private suspend fun onQuit(
            networkId: Long,
            e: IrcEvent.Quit,
        ) {
            // Fan out to every buffer the nick was a member of, in ONE transaction. A quit is a single
            // wire event; committing per buffer released one PagingSource invalidation per shared
            // channel, which is the largest per-line multiplier the timeline sees during a storm.
            val buffers = buffersOfNick(networkId, e.nick)
            if (buffers.isNotEmpty()) {
                db.withTransaction {
                    for (bufferId in buffers) {
                        memberDao.remove(bufferId, e.nick)
                        if (e.ctx.batchId == null) journal(networkId, bufferId, RosterDelta.Remove(e.nick))
                        insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
                    }
                }
            }
            if (e.ctx.batchId == null) {
                journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredQuit(e))
            }
        }

        private suspend fun onHistoricalQuit(
            networkId: Long,
            e: IrcEvent.Quit,
            target: String?,
        ) {
            val bufferId = historicalTargetBuffer(networkId, target) ?: return
            insertSystem(bufferId, e.ctx, MessageKind.QUIT, e.nick, "${e.nick} quit" + (e.reason?.let { " ($it)" } ?: ""))
        }

        /** AWAY has no channel target, so fan it out to channels where the nick is present. */
        private suspend fun onAwayChanged(
            networkId: Long,
            e: IrcEvent.AwayChanged,
        ) {
            val buffers = buffersOfNick(networkId, e.nick)
            if (buffers.isEmpty()) return
            val away = e.awayMessage != null
            val kind = if (away) MessageKind.AWAY else MessageKind.BACK
            val reason = e.awayMessage?.takeIf { it.isNotBlank() }
            val text = if (away) "${e.nick} is away" + (reason?.let { " ($it)" } ?: "") else "${e.nick} is back"
            val ctx = serverCtx()
            db.withTransaction {
                for (bufferId in buffers) {
                    insertSystem(bufferId, ctx, kind, e.nick, text)
                }
            }
        }

        private suspend fun onKicked(
            networkId: Long,
            e: IrcEvent.Kicked,
        ) {
            val st = stateFor(networkId)
            val bufferId = existingChannelBuffer(networkId, e.channel, st)?.id ?: return
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

        private suspend fun onHistoricalKicked(
            networkId: Long,
            e: IrcEvent.Kicked,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            insertSystem(bufferId, e.ctx, MessageKind.KICK, e.by, "${e.nick} was kicked by ${e.by}" + (e.reason?.let { " ($it)" } ?: ""), isSelf = e.isSelf)
        }

        private suspend fun onNickChanged(
            networkId: Long,
            e: IrcEvent.NickChanged,
        ) {
            val st = stateFor(networkId)
            val selfChange = e.isSelf || st.isSelfNick(e.from)
            val normalizedOldNick = st.normalize(e.from)
            val normalizedNewNick = st.normalize(e.to)
            db.withTransaction {
                userDao.rekey(networkId, normalizedOldNick, normalizedNewNick)
                if (selfChange) networkIdentityDao.setSelfNick(networkId, e.to)
            }
            if (selfChange) st.setNick(e.to)
            bufferStore.bindNickChange(
                networkId = networkId,
                normalizedOldNick = normalizedOldNick,
                normalizedNewNick = normalizedNewNick,
                displayNewNick = e.to,
            )
            // Rename member rows across every buffer that had the old nick, in ONE transaction: three
            // commits per shared channel was the largest per-line invalidation multiplier left after
            // JOIN and QUIT, and nick churn is a standard component of a reconnect storm.
            val buffers = buffersOfNick(networkId, e.from)
            if (buffers.isNotEmpty()) {
                db.withTransaction {
                    for (bufferId in buffers) {
                        val member = memberDao.allNow(bufferId).firstOrNull { it.nick == e.from }
                        memberDao.remove(bufferId, e.from)
                        memberDao.upsert(member?.copy(nick = e.to) ?: MemberEntity(bufferId, e.to))
                        if (e.ctx.batchId == null) {
                            journal(networkId, bufferId, RosterDelta.Rename(e.from, e.to))
                        }
                        insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
                    }
                }
            }
            if (e.ctx.batchId == null) {
                journalAcrossActiveSnapshots(networkId, buffers.toSet(), RosterDelta.DeferredNick(e))
            }
        }

        private suspend fun onAccountChanged(
            networkId: Long,
            event: IrcEvent.AccountChanged,
        ) {
            val st = stateFor(networkId)
            upsertUser(networkId, event.nick) { it.copy(account = event.account) }
            val account = event.account ?: return
            val room =
                bufferStore.resolveQueryRoom(
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

        private suspend fun onHistoricalNickChanged(
            networkId: Long,
            e: IrcEvent.NickChanged,
            target: String?,
        ) {
            val bufferId = historicalTargetBuffer(networkId, target) ?: return
            insertSystem(bufferId, e.ctx, MessageKind.NICK, e.from, "${e.from} is now known as ${e.to}")
        }

        private suspend fun onNamesStarted(
            networkId: Long,
            e: IrcEvent.NamesStarted,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            rosterSnapshots.putIfAbsent(RosterKey(networkId, bufferId), mutableListOf())
        }

        private suspend fun onNames(
            networkId: Long,
            e: IrcEvent.Names,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            val deltas = rosterSnapshots.remove(RosterKey(networkId, bufferId)).orEmpty()
            val snapshot =
                e.members.map { member ->
                    MemberEntity(
                        bufferId,
                        member.nick,
                        member.prefixes,
                        isBot = userDao.byNick(networkId, st.normalize(member.nick))?.isBot == true,
                    )
                }
            val replay = replayRosterDeltas(bufferId, snapshot, deltas, st)
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

        private suspend fun onWhoxRow(
            networkId: Long,
            row: IrcEvent.WhoxRow,
        ) {
            val botMode = stateFor(networkId).botMode
            upsertUser(networkId, row.nick) { existing ->
                val hostmask =
                    if (row.username != null && row.host != null) {
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
                    isBot = botMode?.let { whoxFlagsIndicateBot(row.flags, it) } ?: existing.isBot,
                )
            }
            botMode?.let { mode ->
                memberDao.setBot(networkId, row.nick, whoxFlagsIndicateBot(row.flags, mode))
            }
        }

        private suspend fun onMonitorOnline(
            networkId: Long,
            event: IrcEvent.MonitorOnline,
        ) {
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

        private suspend fun onMonitorLimitExceeded(
            networkId: Long,
            event: IrcEvent.MonitorLimitExceeded,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureServerBuffer(networkId, st)
            val targets = event.targets.joinToString(",")
            val text =
                buildString {
                    append("MONITOR limit exceeded")
                    event.limit?.let { append(" (").append(it).append(')') }
                    if (targets.isNotEmpty()) append(": ").append(targets)
                    if (event.text.isNotBlank()) append(" — ").append(event.text)
                }
            insertSystem(bufferId, serverCtx(), MessageKind.ERROR, "", text)
        }

        private suspend fun onTopicChanged(
            networkId: Long,
            e: IrcEvent.TopicChanged,
        ) {
            val st = stateFor(networkId)
            val buffer =
                existingChannelBuffer(networkId, e.channel, st)
                    ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
            bufferDao.setTopic(buffer.id, e.topic, e.setBy)
            insertSystem(buffer.id, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
        }

        /** Persist the 331/332 topic state received during JOIN without adding a fake topic change. */
        private suspend fun onTopicSnapshot(
            networkId: Long,
            e: IrcEvent.TopicSnapshot,
        ) {
            val st = stateFor(networkId)
            val buffer =
                existingChannelBuffer(networkId, e.channel, st)
                    ?: ensureBufferEntity(networkId, e.channel, BufferType.CHANNEL, st)
            bufferDao.setTopic(buffer.id, e.topic, setBy = null)
        }

        private suspend fun onHistoricalTopicChanged(
            networkId: Long,
            e: IrcEvent.TopicChanged,
        ) {
            val st = stateFor(networkId)
            val bufferId = ensureBuffer(networkId, e.channel, BufferType.CHANNEL, st)
            insertSystem(bufferId, e.ctx, MessageKind.TOPIC, e.setBy ?: "", "topic: ${e.topic}")
        }

        private suspend fun onChannelRenamed(
            networkId: Long,
            e: IrcEvent.ChannelRenamed,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val text =
                buildString {
                    e.actor?.takeIf(String::isNotBlank)?.let { append(it).append(' ') }
                    append("renamed ").append(e.oldName).append(" to ").append(e.newName)
                    e.reason?.takeIf(String::isNotBlank)?.let { append(" (").append(it).append(')') }
                }
            if (!origin.mutatesSessionState) {
                val bufferId = ensureBuffer(networkId, e.oldName, BufferType.CHANNEL, st)
                insertSystem(bufferId, e.ctx, MessageKind.SERVER_INFO, e.actor.orEmpty(), text, origin = EventOrigin.HISTORY)
                return
            }
            val oldRoomId = existingChannelBuffer(networkId, e.oldName, st)?.id
            val renamed =
                bufferStore.renameChannel(
                    networkId = networkId,
                    oldNormalizedName = st.normalize(e.oldName),
                    newNormalizedName = st.normalize(e.newName),
                    newDisplayName = e.newName,
                ) ?: return
            oldRoomId?.let { rosterSnapshots.remove(RosterKey(networkId, it)) }
            rosterSnapshots.remove(RosterKey(networkId, renamed.id))
            insertSystem(
                renamed.id,
                e.ctx,
                MessageKind.SERVER_INFO,
                e.actor.orEmpty(),
                text,
                origin = if (origin == EventOrigin.REPLAY) EventOrigin.REPLAY else EventOrigin.LIVE,
            )
        }

        private suspend fun onModeChanged(
            networkId: Long,
            e: IrcEvent.ModeChanged,
        ) {
            val st = stateFor(networkId)
            if (!isChannel(networkId, e.target, st)) return
            val bufferId = existingChannelBuffer(networkId, e.target, st)?.id ?: return
            // One commit for the whole line: a multi-argument MODE (a services re-op after a netsplit)
            // otherwise commits once per prefix change plus once for the row it renders as.
            db.withTransaction {
                applyPrefixModes(networkId, bufferId, e, st)
                insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
            }
        }

        private suspend fun onHistoricalModeChanged(
            networkId: Long,
            e: IrcEvent.ModeChanged,
        ) {
            val st = stateFor(networkId)
            if (!isChannel(networkId, e.target, st)) return
            val bufferId = ensureBuffer(networkId, e.target, BufferType.CHANNEL, st)
            insertSystem(bufferId, e.ctx, MessageKind.MODE, "", "mode ${e.modes} ${e.args.joinToString(" ")}".trim())
        }

        private fun journal(
            networkId: Long,
            bufferId: Long,
            delta: RosterDelta,
        ) {
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

        suspend fun cancelRosterSnapshot(
            networkId: Long,
            bufferId: Long,
        ) {
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
                    is RosterDelta.Upsert -> {
                        members.putIfAbsent(
                            st.normalize(delta.nick),
                            MemberEntity(bufferId, delta.nick),
                        )
                    }

                    is RosterDelta.Remove -> {
                        members.remove(st.normalize(delta.nick))
                    }

                    is RosterDelta.Rename -> {
                        val old = members.remove(st.normalize(delta.from))
                        if (old != null) members[st.normalize(delta.to)] = old.copy(nick = delta.to)
                    }

                    is RosterDelta.DeferredQuit -> {
                        val event = delta.event
                        if (members.remove(st.normalize(event.nick)) != null) {
                            presentations +=
                                DeferredRosterPresentation(
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
                            presentations +=
                                DeferredRosterPresentation(
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
                        members[key] =
                            member.copy(
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
                    '+' -> {
                        adding = true
                    }

                    '-' -> {
                        adding = false
                    }

                    else -> {
                        val prefix = st.prefixModes[mode]
                        val consumesArg = prefix != null || modeConsumesArgument(mode, adding, st.chanModes)
                        val argument = if (consumesArg) event.args.getOrNull(argIndex++) else null
                        if (prefix != null && argument != null) {
                            val member =
                                memberDao.allNow(bufferId).firstOrNull {
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

        private fun updatePrefixes(
            current: String,
            prefix: Char,
            adding: Boolean,
            st: NetworkState,
        ): String {
            val updated = if (adding) current.toSet() + prefix else current.toSet() - prefix
            val order = st.prefixModes.values.toList()
            return updated
                .sortedBy { order.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .joinToString("")
        }

        private fun modeConsumesArgument(
            mode: Char,
            adding: Boolean,
            chanModes: List<Set<Char>>,
        ): Boolean =
            mode in chanModes.getOrNull(0).orEmpty() ||
                mode in chanModes.getOrNull(1).orEmpty() ||
                (adding && mode in chanModes.getOrNull(2).orEmpty())

        // -- sync ---------------------------------------------------------------

        private suspend fun onReadMarker(
            networkId: Long,
            e: IrcEvent.ReadMarker,
        ) {
            val ts = e.timestamp ?: return
            val st = stateFor(networkId)
            val bufferId = existingRoom(networkId, e.target, st)?.id ?: return
            val localAnchor =
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(ts, Long.MAX_VALUE)
            // Publish the server marker and its effective local anchor atomically. Entry gating must
            // never observe the wire marker advanced while the unread-query anchor is still stale.
            db.withTransaction {
                bufferDao.advanceReadMarker(bufferId, ts)
                bufferDao.advanceLocalReadAnchor(bufferId, localAnchor.serverTime, localAnchor.eventId)
            }
            notifier.onRead(bufferId, localAnchor)
            AutoFollowTrace.record("wire_markread_in", bufferId) { "marker=$ts" }
        }

        private suspend fun onBouncerNetworkState(
            networkId: Long,
            e: IrcEvent.BouncerNetworkState,
        ) {
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
            if (existing == null) {
                // NETWORK notifications are discovery state, not local import intent. Explicit import
                // paths create BOUNCER_CHILD rows; passive bouncer state only maintains existing rows.
                return
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

        // -- server buffer --------------------------------------

        private fun commandResponse(
            networkId: Long,
            label: String?,
            now: Long = System.currentTimeMillis(),
        ): CommandResponseSession? {
            val session =
                if (label != null) {
                    commandResponsesByLabel[label]
                } else {
                    latestUnlabeledCommand[networkId]
                } ?: return null
            if (session.networkId != networkId || session.expiresAt <= now) {
                finishCommandResponse(session)
                return null
            }
            return session
        }

        private fun finishCommandResponse(session: CommandResponseSession) {
            commandResponsesById.remove(session.id, session)
            session.label?.let { commandResponsesByLabel.remove(it, session) }
            latestUnlabeledCommand.remove(session.networkId, session)
        }

        private fun clearCommandResponses(networkId: Long) {
            commandResponsesById.values.filter { it.networkId == networkId }.forEach(::finishCommandResponse)
        }

        private fun completeCommandFromState(
            networkId: Long,
            event: IrcEvent,
        ) {
            val session = commandResponse(networkId, event.messageContextOrNull()?.label) ?: return
            val complete =
                when (session.command) {
                    "JOIN" -> event is IrcEvent.Joined && event.isSelf
                    "PART" -> event is IrcEvent.Parted && event.isSelf
                    "NICK" -> event is IrcEvent.NickChanged && event.isSelf
                    "TOPIC" -> event is IrcEvent.TopicChanged
                    "MODE" -> event is IrcEvent.ModeChanged
                    "KICK" -> event is IrcEvent.Kicked
                    "INVITE" -> event is IrcEvent.Invited
                    "SETNAME" -> event is IrcEvent.RealnameChanged
                    "PRIVMSG", "NOTICE" -> event is IrcEvent.ChatMessage && event.isSelf
                    else -> false
                }
            if (complete) finishCommandResponse(session)
        }

        private suspend fun insertCommandResponse(
            session: CommandResponseSession,
            ctx: MessageContext,
            kind: MessageKind,
            text: String,
            isSelf: Boolean = false,
        ) {
            val ordinal = session.nextOrdinal++
            insertSystem(
                bufferId = session.bufferId,
                ctx = ctx,
                kind = kind,
                sender = "/${session.command.lowercase()}",
                text = text,
                dedupKey = "command:${session.id}:$ordinal",
                eventPayload = "$COMMAND_RESPONSE_PAYLOAD_PREFIX${session.id}",
                isSelf = isSelf,
                origin = EventOrigin.LIVE,
            )
        }

        private fun commandResponseEnds(
            command: String,
            reply: String,
        ): Boolean =
            when (command) {
                "WHOIS" -> reply == "318"
                "WHO" -> reply == "315"
                "NAMES" -> reply == "366"
                "MOTD" -> reply == "376" || reply == "422"
                "LIST" -> reply == "323"
                "LINKS" -> reply == "365"
                "STATS" -> reply == "219"
                "HELP" -> reply == "706"
                "INFO" -> reply == "374"
                "ADMIN" -> reply == "259"
                "TRACE" -> reply == "262"
                "LUSERS" -> reply == "266"
                "VERSION" -> reply == "351"
                "TIME" -> reply == "391"
                "USERHOST" -> reply == "302"
                "ISON" -> reply == "303"
                "INVITE" -> reply == "341"
                else -> false
            }

        private fun rawCommandResponseText(message: io.github.trevarj.motd.irc.proto.IrcMessage): String {
            val numeric = message.command.length == 3 && message.command.all(Char::isDigit)
            val params = if (numeric) message.params.drop(1) else message.params
            return if (numeric) {
                params.joinToString(" ").trim().ifEmpty { message.command }
            } else {
                (listOf(message.command) + params).joinToString(" ").trim()
            }
        }

        private suspend fun onStandardReply(
            networkId: Long,
            e: IrcEvent.StandardReply,
            origin: EventOrigin,
        ) {
            val st = stateFor(networkId)
            val routed =
                e.context.firstNotNullOfOrNull { target ->
                    when {
                        isChannel(networkId, target, st) -> existingChannelBuffer(networkId, target, st)
                        else -> bufferStore.resolveQueryRoom(networkId, st.normalize(target), account = null)
                    }
                }
            val protocolBufferId = routed?.id ?: ensureServerBuffer(networkId, st)
            val response = if (origin == EventOrigin.LIVE) commandResponse(networkId, e.ctx.label) else null
            if (e.severity == IrcEvent.StandardReplySeverity.FAIL) {
                e.ctx.label?.let { label ->
                    messageDao.failIfStillPending(protocolBufferId, label)
                } ?: if (e.commandName.equals("PRIVMSG", ignoreCase = true) ||
                    e.commandName.equals("NOTICE", ignoreCase = true)
                ) {
                    messageDao.failLatestPending(protocolBufferId)
                } else {
                    Unit
                }
            }
            val prefix =
                when (e.severity) {
                    IrcEvent.StandardReplySeverity.FAIL -> "failed"
                    IrcEvent.StandardReplySeverity.WARN -> "warning"
                    IrcEvent.StandardReplySeverity.NOTE -> "note"
                }
            val command =
                e.commandName
                    .takeUnless { it == "*" }
                    ?.let { "$it " }
                    .orEmpty()
            val text = "$command$prefix (${e.code}): ${e.description}".trim()
            val kind =
                if (e.severity == IrcEvent.StandardReplySeverity.FAIL) {
                    MessageKind.ERROR
                } else {
                    MessageKind.SERVER_INFO
                }
            if (response != null) {
                insertCommandResponse(response, e.ctx, kind, text)
                if (e.severity == IrcEvent.StandardReplySeverity.FAIL) finishCommandResponse(response)
            } else {
                insertSystem(protocolBufferId, e.ctx, kind, "", text, origin = origin)
            }
        }

        /** ServerError mutates target state, but composer feedback returns to its originating chat. */
        private suspend fun onServerError(
            networkId: Long,
            e: IrcEvent.ServerError,
        ) {
            val st = stateFor(networkId)
            val response = commandResponse(networkId, e.ctx?.label)
            val responseContext = e.ctx ?: serverCtx()
            if (e.code in PART_ALREADY_CLOSED_NUMERICS) {
                val channel = e.params.firstOrNull { isChannel(networkId, it, st) }
                val buffer = channel?.let { existingChannelBuffer(networkId, it, st) }
                if (buffer?.pendingCloseAt != null) {
                    // 403/442 confirms the server has no membership to leave. Treat that as the same
                    // terminal acknowledgement as our echoed PART.
                    bufferDao.deleteBuffer(buffer.id)
                    return
                }
            }
            if (e.code in JOIN_ERROR_NUMERICS) {
                val channel = e.params.firstOrNull { isChannel(networkId, it, st) }
                val inviteBufferId = channel?.let { existingChannelBuffer(networkId, it, st)?.id }
                if (inviteBufferId != null) {
                    messageDao.failJoiningInvites(inviteBufferId, e.text.ifBlank { e.code })
                }
            }
            // "Not in channel" / "cannot send" numerics are only useful if surfaced where the user is
            // looking — the channel they tried to talk to. Route these inline into that channel buffer
            // (instead of the SERVER buffer) so a bouncer that never echoed the self-PART still makes
            // the parted state obvious. 403/442 also flip joined=false so the UI banner engages; 404
            // (ERR_CANNOTSENDTOCHAN) may be a mute/ban while still joined, so it only surfaces inline.
            if (e.code in NOT_IN_CHANNEL_NUMERICS || e.code == "404") {
                val channel = e.params.firstOrNull { isChannel(networkId, it, st) }
                val channelBuffer = channel?.let { existingChannelBuffer(networkId, it, st) }
                if (channelBuffer != null) {
                    val bufferId = channelBuffer.id
                    val text = "${e.code} ${e.text}".trim()
                    if (response != null) {
                        insertCommandResponse(response, responseContext, MessageKind.ERROR, text)
                        finishCommandResponse(response)
                    } else {
                        insertSystem(bufferId, responseContext, MessageKind.ERROR, "", text)
                    }
                    // Mark the just-sent message as failed; the server never accepted it. Retry is still
                    // available, and after rejoining it will succeed. Capture the row first — failLatestPending
                    // flips it to failed=0-excluded.
                    val failedRow = messageDao.latestPendingRow(bufferId)
                    if (messageDao.failLatestPending(bufferId) > 0 && failedRow != null) {
                        traceMessageWrite("room_pending_failed", failedRow, fromHistory = false)
                    }
                    if (e.code in NOT_IN_CHANNEL_NUMERICS) markJoined(bufferId, false)
                    return
                }
            }
            val text = "${e.code} ${e.text}".trim()
            if (response != null) {
                insertCommandResponse(response, responseContext, MessageKind.ERROR, text)
                finishCommandResponse(response)
            } else {
                val bufferId = ensureServerBuffer(networkId, st)
                insertSystem(bufferId, responseContext, MessageKind.ERROR, "", text)
            }
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
            val response = commandResponse(networkId, e.message.tags["label"])
            if (response != null) {
                if (e.message.command == "ACK") {
                    finishCommandResponse(response)
                    return
                }
                insertCommandResponse(response, serverCtx(), MessageKind.SERVER_INFO, rawCommandResponseText(e.message))
                if (commandResponseEnds(response.command, e.message.command)) finishCommandResponse(response)
                return
            }
            if (e.message.command !in SERVER_INFO_NUMERICS) return
            val st = stateFor(networkId)
            val bufferId = ensureServerBuffer(networkId, st)
            // params[0] is our nick for these numerics; drop it and join the rest as the info line.
            val text =
                e.message.params
                    .drop(1)
                    .joinToString(" ")
                    .trim()
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
            val route = resolveReactionRoute(networkId, source, target, historyTarget, st)
            val account =
                message.tags["account"] ?: if (origin == EventOrigin.LIVE) {
                    userDao.byNick(networkId, st.normalize(source))?.account
                } else {
                    null
                }
            val targetEvent =
                db.canonicalTimelineDao().eventByAlias(
                    networkId,
                    EventAliasNamespace.MSGID,
                    targetMsgid.toByteArray(Charsets.UTF_8),
                )
            val bufferId =
                targetEvent?.bufferId ?: existingReactionRoomId(
                    networkId,
                    route,
                    st,
                    account,
                ) ?: return true
            if (targetEvent != null) {
                db.canonicalTimelineDao().resolveReactions(bufferId, targetMsgid, targetEvent.id)
            }
            val actorKey = st.actorKey(source, account)
            val nickKey = st.actorKey(source, account = null)
            deleteLegacyReactionAliases(bufferId, targetMsgid, source, nickKey, emoji)
            reactionDao.delete(bufferId, targetMsgid, actorKey, emoji)
            if (actorKey != nickKey) reactionDao.delete(bufferId, targetMsgid, nickKey, emoji)
            return true
        }

        /** Own away confirmation (305/306) → presence event in the SERVER buffer. */
        private suspend fun onSelfAwayChanged(
            networkId: Long,
            e: IrcEvent.SelfAwayChanged,
        ) {
            val text = if (e.isAway) "You are away" else "You are back"
            val kind = if (e.isAway) MessageKind.AWAY else MessageKind.BACK
            val response = commandResponse(networkId, e.ctx?.label)
            if (response != null) {
                insertCommandResponse(response, e.ctx ?: serverCtx(), kind, text, isSelf = true)
                finishCommandResponse(response)
            } else {
                val st = stateFor(networkId)
                val bufferId = ensureServerBuffer(networkId, st)
                insertSystem(bufferId, e.ctx ?: serverCtx(), kind, st.selfNick, text, isSelf = true)
            }
        }

        /** Disconnected marker → SERVER buffer for cheap in-history reconnect visibility. */
        private suspend fun onDisconnected(
            networkId: Long,
            e: IrcEvent.Disconnected,
        ) {
            rosterSnapshots.keys.removeAll { it.networkId == networkId }
            messageDao.failJoiningInvitesForNetwork(networkId, e.reason ?: "disconnected")
            val st = stateFor(networkId)
            val bufferId = ensureServerBuffer(networkId, st)
            val text = "disconnected" + (e.reason?.let { ": $it" } ?: "")
            insertSystem(bufferId, serverCtx(), MessageKind.SERVER_INFO, "", text)
        }

        /** Context for live events carrying no protocol timestamp or identity tags. */
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
        private suspend fun ensureServerBuffer(
            networkId: Long,
            st: NetworkState,
        ): Long {
            bufferDao.byName(networkId, "*")?.let { return it.id }
            val displayName = networkDao.byId(networkId)?.name ?: "Server"
            return bufferStore.getOrCreate(networkId, "*", displayName, BufferType.SERVER).id
        }

        // -- pending-send insert path (delegated by ConnectionManagerImpl.sendMessage) --

        /** TARGETS has already classified and normalized this query using the live connection rules. */
        suspend fun ensureHistoryQuery(
            networkId: Long,
            target: String,
            normalizedTarget: String,
        ): RoomId =
            sequencer.withNetwork(networkId) {
                bufferStore
                    .getOrCreate(
                        networkId = networkId,
                        normalizedName = normalizedTarget,
                        displayName = target,
                        type = BufferType.QUERY,
                        initiallyDismissed = true,
                    ).id
            }

        /** Persist the complete outgoing plan, aliases, and LOCAL_SEND observations before any write. */
        suspend fun persistOutgoingPlan(
            bufferId: Long,
            sender: String,
            events: List<OutgoingEventPlan>,
            replyToEventId: TimelineEventId?,
            replyToMsgid: String?,
            channelContext: String? = null,
        ): List<DurableOutgoingEvent> {
            require(events.isNotEmpty()) { "outgoing plan is empty" }
            val networkId =
                requireNotNull(bufferDao.rawById(bufferId)?.networkId) {
                    "missing buffer $bufferId"
                }
            return sequencer.withNetwork(networkId) {
                db.withTransaction {
                    var canonicalBuffer =
                        requireNotNull(bufferDao.observeById(bufferId)) {
                            "missing buffer $bufferId"
                        }
                    check(canonicalBuffer.networkId == networkId) { "buffer network changed" }
                    if (canonicalBuffer.type == BufferType.QUERY && canonicalBuffer.dismissed) {
                        bufferDao.reviveQuery(canonicalBuffer.id)
                        canonicalBuffer = requireNotNull(bufferDao.observeById(canonicalBuffer.id))
                    }
                    val now = System.currentTimeMillis()
                    val normalizedSender = stateFor(networkId).normalize(sender)
                    val observations =
                        events.map { event ->
                            TimelineObservation(
                                networkId = networkId,
                                event =
                                    MessageEntity(
                                        bufferId = canonicalBuffer.id,
                                        msgid = null,
                                        serverTime = now,
                                        sender = sender,
                                        normalizedActor = normalizedSender,
                                        kind = event.kind,
                                        text = event.text,
                                        ircFormattedText = event.ircFormattedText,
                                        isSelf = true,
                                        hasMention = false,
                                        replyToMsgid = replyToMsgid,
                                        replyToEventId = replyToEventId,
                                        channelContext = channelContext,
                                        pendingLabel = event.label,
                                        dedupKey = SemanticIdentity.pendingKey(event.label),
                                        serverTimeAuthoritative = false,
                                    ),
                                origin = ObservationOrigin.LOCAL_SEND,
                                connectionGeneration = connectionGenerations[networkId],
                                label = event.label,
                                batchId = null,
                                timeProvenance = TimeProvenance.LOCAL_CLOCK,
                                persistHistoryCursor = networkId !in activeProtocolPageCursorWrites,
                            )
                        }
                    canonicalTimeline.ingestBatch(observations).mapIndexed { index, result ->
                        traceMessageWrite("canonical_pending_insert", result.event, fromHistory = false)
                        DurableOutgoingEvent(result.event.id, events[index].label)
                    }
                }
            }
        }

        /** Compatibility helper for single-event internal/test setup. */
        suspend fun insertPending(
            bufferId: Long,
            label: String,
            sender: String,
            text: String,
            replyToMsgid: String?,
            kind: MessageKind,
        ): Long =
            persistOutgoingPlan(
                bufferId = bufferId,
                sender = sender,
                events = listOf(OutgoingEventPlan(label, text, kind)),
                replyToEventId = null,
                replyToMsgid = replyToMsgid,
            ).single().eventId

        suspend fun beginRetry(
            eventId: TimelineEventId,
            label: String,
        ): MessageEntity? {
            val networkId = networkIdForEvent(eventId) ?: return null
            return sequencer.withNetwork(networkId) {
                db.withTransaction {
                    val event = messageDao.byCanonicalId(eventId) ?: return@withTransaction null
                    val canonicalBuffer =
                        bufferDao.observeById(event.bufferId)
                            ?: return@withTransaction null
                    if (canonicalBuffer.networkId != networkId) return@withTransaction null
                    canonicalTimeline.beginRetry(
                        networkId = networkId,
                        eventId = event.id,
                        label = label,
                        connectionGeneration = connectionGenerations[networkId],
                    )
                }
            }
        }

        suspend fun pendingOutgoingByLabel(
            networkId: Long,
            label: String,
        ): MessageEntity? =
            sequencer.withNetwork(networkId) {
                messageDao.pendingByNetworkLabel(networkId, label)
            }

        suspend fun replanPendingOutgoing(
            networkId: Long,
            eventId: TimelineEventId,
            oldLabel: String,
            events: List<OutgoingEventPlan>,
        ): ReplannedOutgoingPlan? =
            sequencer.withNetwork(networkId) {
                val replanned =
                    canonicalTimeline.replanPendingLocalSend(
                        networkId = networkId,
                        eventId = eventId,
                        oldLabel = oldLabel,
                        events = events,
                        connectionGeneration = connectionGenerations[networkId],
                    ) ?: return@withNetwork null
                ReplannedOutgoingPlan(
                    bufferId = replanned.first().bufferId,
                    events = replanned.map { DurableOutgoingEvent(it.id, requireNotNull(it.pendingLabel)) },
                )
            }

        suspend fun failPendingEvents(eventIds: List<TimelineEventId>) {
            if (eventIds.isEmpty()) return
            val networkId = networkIdForEvent(eventIds.first()) ?: return
            sequencer.withNetwork(networkId) {
                db.withTransaction {
                    val first = messageDao.byCanonicalId(eventIds.first()) ?: return@withTransaction
                    val canonicalBuffer = bufferDao.observeById(first.bufferId) ?: return@withTransaction
                    if (canonicalBuffer.networkId != networkId) return@withTransaction
                    val canonicalIds =
                        messageDao
                            .byIds(eventIds)
                            .filter { event ->
                                bufferDao.observeById(event.bufferId)?.id == canonicalBuffer.id
                            }.map { it.id }
                    if (canonicalIds.isNotEmpty()) messageDao.failPending(canonicalIds)
                }
            }
        }

        suspend fun recoverInterruptedPending(): Int {
            var recovered = 0
            for (networkId in messageDao.pendingNetworkIds()) {
                recovered +=
                    sequencer.withNetwork(networkId) {
                        db.withTransaction { messageDao.recoverInterruptedPending(networkId) }
                    }
            }
            return recovered
        }

        /** Mark a pending row failed if it is still pending after the echo timeout. */
        suspend fun failIfStillPending(
            bufferId: Long,
            label: String,
        ) {
            val networkId = bufferDao.rawById(bufferId)?.networkId ?: return
            sequencer.withNetwork(networkId) {
                db.withTransaction {
                    val canonicalBuffer = bufferDao.observeById(bufferId) ?: return@withTransaction
                    if (canonicalBuffer.networkId != networkId) return@withTransaction
                    if (messageDao.failIfStillPending(canonicalBuffer.id, label) > 0) {
                        messageDao.byPendingLabel(canonicalBuffer.id, label)?.let { failed ->
                            traceMessageWrite("room_pending_failed", failed, fromHistory = false)
                        }
                    }
                }
            }
        }

        /** A successful write on a server without echo-message is final local confirmation. */
        suspend fun confirmIfStillPending(
            bufferId: Long,
            label: String,
        ) {
            val networkId = bufferDao.rawById(bufferId)?.networkId ?: return
            sequencer.withNetwork(networkId) {
                db.withTransaction {
                    val canonicalBuffer = bufferDao.observeById(bufferId) ?: return@withTransaction
                    if (canonicalBuffer.networkId != networkId) return@withTransaction
                    messageDao.confirmIfStillPending(canonicalBuffer.id, label)
                }
            }
        }

        private suspend fun networkIdForEvent(eventId: TimelineEventId): Long? {
            val event = messageDao.byCanonicalId(eventId) ?: return null
            return bufferDao.rawById(event.bufferId)?.networkId
        }

        suspend fun evictNetwork(networkId: Long) {
            sequencer.withNetwork(networkId) {
                states.remove(networkId)
                networkIgnoreCache.invalidate(networkId)
                rosterSnapshots.keys.removeAll { it.networkId == networkId }
                activeHistoryMultiplicities.remove(networkId)
                activeHistoryOccurrences.remove(networkId)
                activeHistoryChatRoutes.remove(networkId)
                activeHistoryTargets.remove(networkId)
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
            activeHistoryTargets.clear()
            activeProtocolPageCursorWrites.clear()
        }

        internal fun sequencerSize(): Int = sequencer.size()

        // -- helpers ------------------------------------------------------------

        private suspend fun deleteLegacyReactionAliases(
            bufferId: RoomId,
            targetMsgid: String,
            sender: String,
            currentNickActorKey: String,
            emoji: String,
        ) {
            // v10 reactions were irreversibly keyed with RFC1459, independent of current rules.
            val baseActorKey = IrcIdentityRules(IrcCaseMapping.Rfc1459).actorKey(sender, account = null)
            val legacyPrefix = "$baseActorKey\u0000legacy:"
            reactionDao.deleteActorAliases(
                bufferId,
                targetMsgid,
                baseActorKey,
                currentNickActorKey == baseActorKey,
                legacyPrefix,
                "$legacyPrefix\uFFFF",
                emoji,
            )
        }

        private fun isChannel(
            networkId: Long,
            target: String,
            st: NetworkState,
        ): Boolean {
            val active = activeHistoryTargets[networkId]
            if (active != null && (
                    target == active.target || st.normalize(target) == st.normalize(active.target)
                )
            ) {
                return active.type == BufferType.CHANNEL
            }
            return st.isChannel(target)
        }

        /** Route TAGMSG mutations through the enclosing query batch across historical nick changes. */
        private fun resolveReactionRoute(
            networkId: Long,
            source: String,
            target: String,
            historyTarget: String?,
            st: NetworkState,
        ): ReactionRoute {
            val active = historyTarget?.let { activeHistoryTargets[networkId] }
            val isDm =
                active?.type == BufferType.QUERY ||
                    (active == null && !isChannel(networkId, target, st))
            val historyPeer =
                when {
                    active?.type == BufferType.QUERY -> active.target
                    else -> historyTarget?.takeIf { isDm && !isChannel(networkId, it, st) }
                }
            val sourceIsSelf =
                if (historyPeer != null) {
                    st.normalize(target) == st.normalize(historyPeer)
                } else {
                    st.isSelfNick(source)
                }
            return ReactionRoute(
                bufferName =
                    active?.target
                        ?: if (isDm) historyPeer ?: if (sourceIsSelf) target else source else target,
                type = active?.type ?: if (isDm) BufferType.QUERY else BufferType.CHANNEL,
                sourceIsSelf = sourceIsSelf,
                roomId = active?.roomId,
            )
        }

        private suspend fun existingReactionRoomId(
            networkId: Long,
            route: ReactionRoute,
            st: NetworkState,
            account: String?,
        ): RoomId? =
            route.roomId?.let { bufferDao.canonicalId(it) ?: it } ?: if (route.type == BufferType.QUERY) {
                val peerAccount =
                    account
                        ?.takeUnless { it.isEmpty() || it == "*" || route.sourceIsSelf }
                bufferStore.resolveQueryRoom(networkId, st.normalize(route.bufferName), peerAccount)?.id
            } else {
                existingChannelBuffer(networkId, route.bufferName, st)?.id
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
            origin: EventOrigin,
        ): ChatRoute {
            val channelContext = validChannelContext(event.ctx.clientTags, event.target, st::isChannel)
            val active = historyTarget?.let { activeHistoryTargets[networkId] }.takeIf { channelContext == null }
            val type =
                if (channelContext != null) {
                    BufferType.CHANNEL
                } else if (active != null) {
                    active.type
                } else if (isChannel(networkId, event.target, st)) {
                    BufferType.CHANNEL
                } else {
                    BufferType.QUERY
                }
            val isDm = type == BufferType.QUERY
            // Server-sourced NOTICEs never create query rooms.
            if (isDm && event.kind == IrcEvent.ChatKind.NOTICE && isServerSource(event.source.nick)) {
                return ChatRoute(
                    bufferId = ensureServerBuffer(networkId, st),
                    bufferName = "*",
                    type = BufferType.SERVER,
                    storedText = event.text,
                    serverNotice = true,
                    sourceIsSelf = false,
                    selfAttributionAuthoritative = false,
                )
            }
            val historyPeer =
                when {
                    active?.type == BufferType.QUERY -> active.target
                    else -> historyTarget?.takeIf { isDm && !isChannel(networkId, it, st) }
                }
            val historySourceIsPeer =
                historyPeer != null && (
                    st.normalize(event.source.nick) == st.normalize(historyPeer) ||
                        active?.roomId?.let { targetRoomId ->
                            bufferStore
                                .resolveQueryRoom(
                                    networkId,
                                    st.normalize(event.source.nick),
                                    event.ctx.account,
                                )?.id
                                ?.let { sourceRoomId ->
                                    (bufferDao.canonicalId(sourceRoomId) ?: sourceRoomId) ==
                                        (bufferDao.canonicalId(targetRoomId) ?: targetRoomId)
                                }
                        } == true
                )
            // Query history needs both sides of the wire message. Some bouncers replay an incoming PM
            // with the peer as its target as well as its source, so target == historyPeer alone cannot
            // prove an outgoing message. A source already bound to this query is incoming; otherwise an
            // event targeting the query peer is the historical-self/outgoing case.
            val sourceIsSelf =
                when {
                    (origin == EventOrigin.HISTORY || origin == EventOrigin.REPLAY) && historyPeer != null -> {
                        !historySourceIsPeer && st.normalize(event.target) == st.normalize(historyPeer)
                    }

                    origin == EventOrigin.HISTORY || origin == EventOrigin.REPLAY -> {
                        event.isSelf
                    }

                    else -> {
                        event.isSelf || st.isSelfNick(event.source.nick)
                    }
                }
            val bufferName =
                channelContext ?: active?.target ?: if (isDm) {
                    historyPeer ?: if (sourceIsSelf) event.target else event.source.nick
                } else {
                    event.target
                }
            // Only a replayed query carries a peer strong enough to overrule the wire's own self flag.
            val selfAttributionAuthoritative =
                (origin == EventOrigin.HISTORY || origin == EventOrigin.REPLAY) && historyPeer != null
            // Every route, not just a DM: targeted replay resolves the console as SERVER-typed, and
            // that path used to store `sasl set-password <secret>` echoes verbatim.
            if (networkDao.isBouncerConsole(networkId, bufferName)) {
                return ChatRoute(
                    bufferId = ensureBuffer(networkId, bufferName, BufferType.SERVER, st),
                    bufferName = bufferName,
                    type = BufferType.SERVER,
                    storedText =
                        if (sourceIsSelf) {
                            redactBouncerServCommand(event.text)
                        } else {
                            redactBouncerServReply(event.text)
                        },
                    serverNotice = false,
                    sourceIsSelf = sourceIsSelf,
                    selfAttributionAuthoritative = selfAttributionAuthoritative,
                    bouncerConsole = true,
                )
            }
            val normalizedName = active?.normalizedName ?: st.normalize(bufferName)
            var bufferId =
                active?.roomId ?: if (type == BufferType.QUERY) {
                    val normalizedNick = normalizedName
                    bufferStore.resolveQueryRoom(networkId, normalizedNick, account = null)?.id
                        ?: bufferStore
                            .resolveQueryRoom(
                                networkId,
                                normalizedNick,
                                event.ctx.account.takeUnless { sourceIsSelf },
                            )?.id
                        ?: ensureBuffer(networkId, bufferName, type, st)
                } else {
                    ensureBuffer(networkId, bufferName, type, st)
                }
            if (type == BufferType.QUERY) {
                bufferId =
                    bufferStore
                        .bindQueryIdentity(
                            roomId = bufferId,
                            networkId = networkId,
                            normalizedNick = normalizedName,
                            displayNick = bufferName,
                            account = event.ctx.account.takeUnless { sourceIsSelf },
                        ).id
            }
            return ChatRoute(
                bufferId,
                bufferName,
                type,
                event.text,
                serverNotice = false,
                sourceIsSelf = sourceIsSelf,
                selfAttributionAuthoritative = selfAttributionAuthoritative,
            )
        }

        private suspend fun ensureBuffer(
            networkId: Long,
            name: String,
            type: BufferType,
            st: NetworkState,
        ): Long = ensureBufferEntity(networkId, name, type, st).id

        private suspend fun ensureBufferEntity(
            networkId: Long,
            name: String,
            type: BufferType,
            st: NetworkState,
        ): BufferEntity {
            val norm = st.normalize(name)
            if (type == BufferType.QUERY) {
                bufferStore.resolveQueryRoom(networkId, norm, account = null)?.let { return it }
                return bufferStore.getOrCreate(networkId, norm, name, type)
            }
            if (type == BufferType.CHANNEL) {
                bufferStore.resolveChannelRoom(networkId, norm)?.let { return it }
            }
            return bufferStore.getOrCreate(networkId, norm, name, type)
        }

        private suspend fun existingChannelBuffer(
            networkId: Long,
            target: String,
            st: NetworkState,
        ): BufferEntity? = bufferStore.resolveChannelRoom(networkId, st.normalize(target))

        private suspend fun existingRoom(
            networkId: Long,
            target: String,
            st: NetworkState,
        ): BufferEntity? =
            if (isChannel(networkId, target, st)) {
                existingChannelBuffer(networkId, target, st)
            } else {
                bufferStore.resolveQueryRoom(networkId, st.normalize(target), account = null)
            }

        private suspend fun historicalTargetBuffer(
            networkId: Long,
            target: String?,
        ): Long? {
            if (target == null) return null
            val st = stateFor(networkId)
            activeHistoryTargets[networkId]
                ?.takeIf {
                    it.target == target || st.normalize(it.target) == st.normalize(target)
                }?.let { return bufferDao.canonicalId(it.roomId) ?: it.roomId }
            val type =
                when {
                    isChannel(networkId, target, st) -> BufferType.CHANNEL
                    networkDao.isBouncerConsole(networkId, target) -> BufferType.SERVER
                    else -> BufferType.QUERY
                }
            return ensureBuffer(networkId, target, type, st)
        }

        private suspend fun markJoined(
            bufferId: Long,
            joined: Boolean,
        ) {
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
            eventPayload: String? = null,
            isSelf: Boolean = false,
            origin: EventOrigin = if (ctx.batchId == null) EventOrigin.LIVE else EventOrigin.HISTORY,
        ) {
            val networkId = bufferDao.observeById(bufferId)?.networkId ?: return
            val normalizedSender = stateFor(networkId).normalize(sender)
            val row =
                MessageEntity(
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
                    eventPayload = eventPayload,
                    serverTimeAuthoritative = ctx.serverTimeSource == ServerTimeSource.TAG,
                )
            val batchKey = CanonicalBatchKey(bufferId, row.kind, normalizedSender, text, row.serverTime)
            val multiplicity = activeHistoryMultiplicities[networkId]?.get(batchKey)
            val result =
                canonicalTimeline.ingest(
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
            recordPlaybackResult(networkId, result)
            traceMessageWrite("canonical_system_${result::class.simpleName}", result.event, ctx.batchId != null)
        }

        private fun traceMessageWrite(
            event: String,
            row: MessageEntity,
            fromHistory: Boolean,
        ) {
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

        private fun recordPlaybackResult(
            networkId: Long,
            result: IngestResult,
        ) {
            activeHistoryCanonicalOrder[networkId]?.add(result.event.id)
            if (result is IngestResult.Inserted) {
                activeHistoryInsertedIds[networkId]?.add(result.event.id)
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
        private suspend fun buffersOfNick(
            networkId: Long,
            nick: String,
        ): List<Long> = memberDao.bufferIdsForNick(networkId, nick)

        private suspend fun upsertUser(
            networkId: Long,
            nick: String,
            mutate: (UserEntity) -> UserEntity,
        ) {
            val normalized = stateFor(networkId).normalize(nick)
            val existing =
                userDao.byNick(networkId, normalized)
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
            consoleNotice: Boolean = false,
            watchedChat: Boolean = false,
        ) {
            if (e.isSelf) return
            // Never raise a notification for a SERVER buffer: a motd line containing the user's nick
            // must not fire a mention. The bouncer console's own NOTICEs are the one exemption.
            if (!shouldNotifyIncoming(type, hasMention, consoleNotice, watchedChat)) return
            notifier.onCanonicalIncoming(networkId, bufferId, type, hasMention, eventId, e)
        }

        /**
         * Atomically serialize notification presentation, but only mark it durable after the notifier
         * returns. Startup releases interrupted claims and rebuilds the notification from Room.
         */
        private suspend fun presentNotification(
            eventId: TimelineEventId,
            present: suspend () -> Unit,
        ) {
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

        private fun kindOf(k: IrcEvent.ChatKind): MessageKind =
            when (k) {
                IrcEvent.ChatKind.PRIVMSG -> MessageKind.PRIVMSG
                IrcEvent.ChatKind.NOTICE -> MessageKind.NOTICE
                IrcEvent.ChatKind.ACTION -> MessageKind.ACTION
            }

        private fun EventOrigin.toObservationOrigin(): ObservationOrigin =
            when (this) {
                EventOrigin.LIVE -> ObservationOrigin.LIVE
                EventOrigin.HISTORY, EventOrigin.REPLAY -> ObservationOrigin.HISTORY
                EventOrigin.PUSH -> ObservationOrigin.PUSH
            }

        private fun ServerTimeSource.toTimeProvenance(): TimeProvenance =
            when (this) {
                ServerTimeSource.TAG -> TimeProvenance.SERVER_TAG
                ServerTimeSource.LOCAL -> TimeProvenance.LOCAL_CLOCK
                ServerTimeSource.UNKNOWN -> TimeProvenance.UNKNOWN
            }

        private companion object {
            /**
             * Informational numerics persisted to the SERVER buffer as SERVER_INFO:
             * welcome (001..004), lusers (251..255, 265, 266), motd (375, 372, 376), and RPL_AWAY
             * (301). WHOIS is consumed by the nick-sheet request path. LIST numerics
             * (321/322/323) are deliberately excluded so a browse never floods the buffer. Own away
             * confirmations (305/306) are no longer Raw: they
             * map to [IrcEvent.SelfAwayChanged] and render the identical line from that branch.
             */
            val SERVER_INFO_NUMERICS: Set<String> =
                setOf(
                    "001",
                    "002",
                    "003",
                    "004",
                    "251",
                    "252",
                    "253",
                    "254",
                    "255",
                    "265",
                    "266",
                    "375",
                    "372",
                    "376",
                    "301",
                )

            val JOIN_ERROR_NUMERICS: Set<String> =
                setOf(
                    "403",
                    "405",
                    "471",
                    "473",
                    "474",
                    "475",
                    "476",
                )
            val PART_ALREADY_CLOSED_NUMERICS: Set<String> = setOf("403", "442")
            val REDACTABLE_MESSAGE_KINDS: Set<MessageKind> =
                setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION, MessageKind.REDACTED)

            // The server confirms you are no longer on this channel. Flip joined=false so the channel
            // UI shows its parted banner instead of an enabled composer.
            val NOT_IN_CHANNEL_NUMERICS: Set<String> = setOf("403", "442")
            const val DCC_OFFER_EXPIRY_MS: Long = 5 * 60 * 1000
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
    // suspend so implementations read Room / DataStore with plain suspend calls. The events
    // collector runs on the application Default scope; a blocking read here
    // (runBlocking { suspend Room query }) can still deadlock against Room's executors — same
    // class of bug as the findSelfEchoCandidate fix. Callers are already in suspend context.
    suspend fun onIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        message: IrcEvent.ChatMessage,
    )

    /** Canonical-id-aware notification hook. Legacy/test implementations inherit the old hook. */
    suspend fun onCanonicalIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        eventId: TimelineEventId,
        message: IrcEvent.ChatMessage,
    ) = onIncoming(networkId, bufferId, type, hasMention, message)

    /** A local or synchronized marker advanced through this exact timeline tuple. */
    suspend fun onRead(
        bufferId: Long,
        anchor: io.github.trevarj.motd.data.db.TimelineAnchor,
    ) = Unit

    /** Retire presentation state keyed by a losing room id after canonical room coalescing. */
    suspend fun onRoomsMerged(
        winnerId: RoomId,
        loserId: RoomId,
    ) = Unit

    /** A newly persisted, live, actionable invitation. */
    suspend fun onInvitation(
        networkId: Long,
        bufferId: Long,
        messageId: Long,
    ) = Unit

    /** Cancel notification state after Join/Dismiss resolves an invitation. */
    suspend fun onInvitationResolved(messageId: Long) = Unit

    /** A newly persisted live DCC file offer. */
    suspend fun onDccTransferOffer(
        networkId: Long,
        bufferId: Long,
        messageId: Long,
    ) = Unit

    /** No-op notifier for tests / headless contexts. */
    object Noop : MessageNotifier {
        override suspend fun onIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            message: IrcEvent.ChatMessage,
        ) = Unit
    }
}

internal fun shouldNotifyIncoming(
    type: BufferType,
    hasMention: Boolean,
    consoleNotice: Boolean,
    watchedChat: Boolean,
): Boolean {
    if (consoleNotice) return true
    if (type == BufferType.SERVER) return false
    return type == BufferType.QUERY || hasMention || watchedChat
}
