package io.github.trevarj.motd.service

import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.InviteEnrollmentStore
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.OutgoingEventPlan
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.client.NO_IMPLICIT_NAMES_ALIASES
import io.github.trevarj.motd.irc.client.NickRecoveryGuard
import io.github.trevarj.motd.irc.client.NickServIdentifySyntax
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.client.hasMessageRedactionCap
import io.github.trevarj.motd.irc.client.preferredExtendedMonitor
import io.github.trevarj.motd.irc.client.preferredNoImplicitNames
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.MonitorCommands
import io.github.trevarj.motd.irc.ext.MonitorSupport
import io.github.trevarj.motd.irc.ext.monitorSupport
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.irc.format.splitIrcFormattedLinesUtf8
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.obfs.VlessLink
import io.github.trevarj.motd.push.PushHealthStore
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.push.pushSuspendedNetworkIds
import io.github.trevarj.motd.sidecar.SidecarBinder
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarPrefs
import io.github.trevarj.motd.sidecar.SidecarTransportFactory
import io.github.trevarj.motd.sidecar.SidecarWakeRegistrar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

internal data class OutgoingMessageChunk(
    val wireText: String,
    val displayText: String,
    val kind: MessageKind,
    val ircFormattedText: String? = null,
)

/**
 * Convert composer text into safe, independently sendable IRC payloads.
 *
 * Physical newlines are always message boundaries. ACTION payloads are split on their display
 * text before the CTCP wrapper is added, so every chunk remains a valid ACTION and every stored
 * row contains only the visible action text. BouncerServ rejects unsafe multi-command input and
 * stores a redacted transcript for the commands it does accept.
 */
internal fun prepareOutgoingMessageChunks(
    text: String,
    isBouncerServ: Boolean,
    maxBytes: Int = ConnectionManagerImpl.MAX_BYTES,
    preferLogicalMultiline: Boolean = false,
): List<OutgoingMessageChunk> {
    if (isBouncerServ && ('\r' in text || '\n' in text || text.toByteArray(Charsets.UTF_8).size > maxBytes)) {
        return emptyList()
    }
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if (preferLogicalMultiline && !isBouncerServ && !normalized.startsWith("/me ")) {
        if (normalized.split('\n').all(String::isEmpty)) return emptyList()
        return listOf(
            OutgoingMessageChunk(
                wireText = normalized,
                displayText = plainIrcText(normalized),
                kind = MessageKind.PRIVMSG,
                ircFormattedText = normalized.takeIf { plainIrcText(normalized) != normalized },
            ),
        )
    }
    val isAction = text.startsWith("/me ")
    val payload = if (isAction) text.removePrefix("/me ") else text
    val formattedLines =
        if (isBouncerServ) {
            payload.split(Regex("\\r\\n|\\r|\\n")).map { line -> splitUtf8(line, maxBytes) }
        } else {
            splitIrcFormattedLinesUtf8(payload, maxBytes - if (isAction) ACTION_OVERHEAD_BYTES else 0)
        }
    if (formattedLines.all { line -> line.all(String::isEmpty) }) return emptyList()
    return formattedLines.flatMapIndexed { lineIndex, line ->
        line.filter(String::isNotEmpty).map { chunk ->
            val plainText = if (isBouncerServ) chunk else plainIrcText(chunk)
            val formattedText = chunk.takeIf { !isBouncerServ && plainText != chunk }
            val displayText =
                if (isBouncerServ) redactBouncerServCommand(chunk) else plainText
            val lineIsAction = isAction && lineIndex == 0
            OutgoingMessageChunk(
                wireText = if (lineIsAction) "\u0001ACTION $chunk\u0001" else chunk,
                displayText = displayText,
                kind = if (lineIsAction) MessageKind.ACTION else MessageKind.PRIVMSG,
                ircFormattedText = formattedText,
            )
        }
    }
}

/** Split [text] into chunks of at most [maxBytes] UTF-8 bytes without splitting code points. */
internal fun splitUtf8(
    text: String,
    maxBytes: Int,
): List<String> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)

    val out = ArrayList<String>()
    var remaining = text
    while (remaining.isNotEmpty()) {
        var end = 0
        var bytes = 0
        var lastSpace = -1
        while (end < remaining.length) {
            val codePoint = remaining.codePointAt(end)
            val codePointLength = Character.charCount(codePoint)
            val codePointBytes =
                String(Character.toChars(codePoint))
                    .toByteArray(Charsets.UTF_8)
                    .size
            if (bytes + codePointBytes > maxBytes) break
            if (codePoint == ' '.code) lastSpace = end
            bytes += codePointBytes
            end += codePointLength
        }
        require(end > 0) { "maxBytes is smaller than one UTF-8 code point" }
        if (end == remaining.length) {
            out += remaining
            break
        }

        val split = if (lastSpace > 0) lastSpace else end
        val chunk = remaining.substring(0, split).trimEnd()
        if (chunk.isNotEmpty()) out += chunk
        remaining = remaining.substring(split).trimStart()
    }
    return out
}

/** Attempt an already-durable ordered plan and fail the current plus every unattempted event. */
internal suspend fun transmitDurableOutgoingPlan(
    eventIds: List<Long>,
    write: suspend (Int) -> ImmediateWireAcceptance,
    onWritten: suspend (Int) -> Unit,
    failRemaining: suspend (List<Long>) -> Unit,
): ImmediateWireAcceptance {
    for (index in eventIds.indices) {
        val wireAcceptance =
            try {
                write(index)
            } catch (_: Exception) {
                ImmediateWireAcceptance.FAILED
            }
        if (wireAcceptance != ImmediateWireAcceptance.ACCEPTED) {
            withContext(NonCancellable) { failRemaining(eventIds.drop(index)) }
            return wireAcceptance
        }
        try {
            onWritten(index)
        } catch (_: Exception) {
            withContext(NonCancellable) { failRemaining(eventIds.drop(index)) }
            return ImmediateWireAcceptance.FAILED
        }
    }
    return ImmediateWireAcceptance.ACCEPTED
}

/** Keep lifecycle teardown exclusive without serializing sends from different networks. */
internal class DurableSendLifecycle {
    private val stateLock = Mutex()
    private val quiesceLock = Mutex()
    private var activeSends = 0
    private var blocked: CompletableDeferred<Unit>? = null
    private var drained: CompletableDeferred<Unit>? = null

    suspend fun <T> sending(block: suspend () -> T): T {
        while (true) {
            val waitForQuiesce =
                stateLock.withLock {
                    blocked ?: run {
                        activeSends++
                        null
                    }
                }
            if (waitForQuiesce == null) break
            waitForQuiesce.await()
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                stateLock.withLock {
                    activeSends--
                    if (activeSends == 0) drained?.complete(Unit)
                }
            }
        }
    }

    suspend fun <T> quiesce(
        onBlocked: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T =
        quiesceLock.withLock {
            val completion = CompletableDeferred<Unit>()
            val waitForDrain =
                stateLock.withLock {
                    check(blocked == null)
                    blocked = completion
                    if (activeSends == 0) {
                        null
                    } else {
                        CompletableDeferred<Unit>().also { drained = it }
                    }
                }
            try {
                onBlocked()
                waitForDrain?.await()
                block()
            } finally {
                withContext(NonCancellable) {
                    stateLock.withLock {
                        drained = null
                        blocked = null
                        completion.complete(Unit)
                    }
                }
            }
        }
}

internal fun isGenericRetryEligible(
    buffer: BufferEntity,
    message: MessageEntity,
): Boolean =
    message.isSelf && message.failed && message.msgid == null &&
        // SERVER covers the bouncer console; a user merely nicked BouncerServ elsewhere may retry.
        buffer.type != BufferType.SERVER &&
        !message.text.contains("<redacted>")

internal data class CurrentReadTarget(
    val buffer: BufferEntity,
    val anchor: TimelineAnchor,
    val authoritative: io.github.trevarj.motd.data.db.TimelineBoundaryRow?,
)

/** Resolve stale notification/viewport tuples through current event and room identity. */
internal suspend fun resolveCurrentReadTarget(
    db: MotdDatabase,
    bufferId: Long,
    requested: TimelineAnchor,
): CurrentReadTarget? {
    if (requested.serverTime <= 0 || requested.eventId <= 0) return null
    val buffer = db.bufferDao().observeById(bufferId) ?: return null
    val canonicalEventId = db.canonicalTimelineDao().canonicalEventId(requested.eventId)
    if (canonicalEventId <= 0) return null
    val event = db.messageDao().byId(canonicalEventId) ?: return null
    val eventRoomId = db.bufferDao().canonicalId(event.bufferId) ?: return null
    if (eventRoomId != buffer.id || event.serverTime <= 0) return null
    val current = TimelineAnchor(event.serverTime, event.id, event.timelineOrder)
    val stored =
        buffer.localReadAnchorTime?.let {
            val storedId = buffer.localReadAnchorEventId ?: 0L
            val storedOrder = db.messageDao().byCanonicalId(storedId)?.timelineOrder ?: storedId
            TimelineAnchor(it, storedId, storedOrder)
        }
    if (stored != null && current < stored) return null
    return CurrentReadTarget(
        buffer = buffer,
        anchor = current,
        authoritative =
            db.messageDao().authoritativeChatAtOrBefore(
                buffer.id,
                current.serverTime,
                current.eventId,
            ),
    )
}

internal suspend fun resolveAndAdvanceCurrentReadTarget(
    db: MotdDatabase,
    bufferId: Long,
    requested: TimelineAnchor,
): CurrentReadTarget? =
    db.withTransaction {
        val target = resolveCurrentReadTarget(db, bufferId, requested) ?: return@withTransaction null
        db.bufferDao().advanceLocalReadAnchor(
            target.buffer.id,
            target.anchor.serverTime,
            target.anchor.eventId,
        )
        target
    }

/** Return durable acceptance even if the caller is cancelled after the transaction commits. */
internal suspend fun completeDurableAcceptance(
    eventIds: List<Long>,
    transition: suspend () -> ImmediateWireAcceptance,
    secondaryEffect: suspend () -> Unit,
    storedTexts: List<String> = emptyList(),
): SendAcceptance.Accepted =
    withContext(NonCancellable) {
        val wireAcceptance =
            try {
                transition()
            } catch (_: Exception) {
                ImmediateWireAcceptance.FAILED
            }
        try {
            secondaryEffect()
        } catch (_: Exception) {
            // The durable timeline state is authoritative; presentation effects are best effort.
        }
        SendAcceptance.Accepted(eventIds, wireAcceptance, storedTexts)
    }

private const val ACTION_OVERHEAD_BYTES = 9 // SOH + "ACTION " + SOH

internal fun identityRulesFallback(
    live: IrcIdentityRules?,
    liveReady: Boolean,
    persisted: NetworkIdentityEntity?,
): IrcIdentityRules = live.takeIf { liveReady } ?: persisted?.identityRules ?: IrcIdentityRules()

/**
 * Narrow transport boundary for a TOPIC write. The caller deliberately does not mirror the draft
 * into Room: only the server's TOPIC echo is IRC-derived state and is persisted by EventProcessor.
 */
internal suspend fun writeChannelTopicIfReady(
    buffer: BufferEntity?,
    topic: String,
    client: IrcClient?,
): Boolean {
    val readyClient = client?.takeIf { it.state.value is IrcClientState.Ready } ?: return false
    return attemptChannelTopicWrite(buffer, topic) { message -> readyClient.sendIfConnected(message) }
}

internal suspend fun attemptChannelTopicWrite(
    buffer: BufferEntity?,
    topic: String,
    send: suspend (io.github.trevarj.motd.irc.proto.IrcMessage) -> Boolean,
): Boolean {
    if (buffer == null) return false
    return send(
        io.github.trevarj.motd.irc.proto.IrcMessage(
            command = "TOPIC",
            params = listOf(buffer.ircTarget, topic),
        ),
    )
}

/**
 * Narrow transport boundary for a PART write. Acceptance means only that a Ready client wrote to
 * its live transport; EventProcessor still waits for self-PART, while server rejection needs a
 * labeled response (when available) or the server error path.
 */
internal suspend fun writeChannelPartIfReady(
    buffer: BufferEntity?,
    reason: String?,
    client: IrcClient?,
): Boolean {
    val readyClient = client?.takeIf { it.state.value is IrcClientState.Ready } ?: return false
    return attemptChannelPartWrite(buffer, reason) { message -> readyClient.sendIfConnected(message) }
}

internal suspend fun attemptChannelPartWrite(
    buffer: BufferEntity?,
    reason: String?,
    send: suspend (io.github.trevarj.motd.irc.proto.IrcMessage) -> Boolean,
): Boolean {
    if (buffer == null) return false
    val params = if (reason.isNullOrBlank()) listOf(buffer.ircTarget) else listOf(buffer.ircTarget, reason)
    return send(
        io.github.trevarj.motd.irc.proto
            .IrcMessage(command = "PART", params = params),
    )
}

/** True when [raw] is one safe IRC middle parameter suitable for an INVITE target nick. */
internal fun normalizedInviteNick(raw: String): String? =
    raw.trim().takeIf { nick ->
        nick.isNotEmpty() &&
            nick.none { it.isWhitespace() || it.isISOControl() || it == ':' || it == ',' }
    }

/** Narrow transport boundary for an outgoing IRC INVITE. */
internal suspend fun writeChannelInviteIfReady(
    buffer: BufferEntity?,
    nick: String,
    client: IrcClient?,
): Boolean {
    val ready = client?.state?.value as? IrcClientState.Ready ?: return false
    val targetNick = normalizedInviteNick(nick) ?: return false
    if (client.isupport.normalize(targetNick) == client.isupport.normalize(ready.nick)) return false
    return attemptChannelInviteWrite(buffer, targetNick) { message -> client.sendIfConnected(message) }
}

internal suspend fun attemptChannelInviteWrite(
    buffer: BufferEntity?,
    nick: String,
    send: suspend (io.github.trevarj.motd.irc.proto.IrcMessage) -> Boolean,
): Boolean {
    if (buffer?.type != BufferType.CHANNEL || !buffer.joined || buffer.pendingCloseAt != null) return false
    val targetNick = normalizedInviteNick(nick) ?: return false
    return try {
        send(
            io.github.trevarj.motd.irc.proto.IrcMessage(
                command = "INVITE",
                params = listOf(targetNick, buffer.ircTarget),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** Narrow transport boundary for one IRCv3 REDACT command. */
internal suspend fun writeMessageRedactionIfReady(
    buffer: BufferEntity?,
    msgid: String,
    client: IrcClient?,
): Boolean {
    val ready = client?.state?.value as? IrcClientState.Ready ?: return false
    if (!hasMessageRedactionCap(ready.caps)) return false
    return attemptMessageRedactionWrite(buffer, msgid) { message -> client.sendIfConnected(message) }
}

internal suspend fun attemptMessageRedactionWrite(
    buffer: BufferEntity?,
    msgid: String,
    send: suspend (IrcMessage) -> Boolean,
): Boolean {
    if (buffer == null || buffer.type == BufferType.SERVER || buffer.pendingCloseAt != null) return false
    if (msgid.isEmpty() || msgid.first() == ':' || msgid.any { it.isWhitespace() || it.isISOControl() }) return false
    return try {
        send(IrcMessage(command = "REDACT", params = listOf(buffer.ircTarget, msgid)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/**
 * Hilt @Singleton connection subsystem. Outlives the foreground service — the service
 * is merely its keeper. Spawns one [ConnectionActor] per connectable network row (BOUNCER_ROOT
 * gets the root actor; each BOUNCER_CHILD a bound actor copying the root host/SASL with its
 * bouncerNetId; DIRECT one each), reconciles on networkDao changes, and reacts to deliveryMode.
 */
@Singleton
class ConnectionManagerImpl
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val db: MotdDatabase,
        private val eventProcessor: EventProcessor,
        private val settings: DataStoreSettingsRepository,
        private val pushPrefs: PushPrefs,
        private val replyPrefs: ReplyPrefs,
        private val certStore: CertTrustStore,
        private val inviteEnrollmentStore: InviteEnrollmentStore,
        private val baseTransportFactory: TransportFactory,
        private val localSocksProvider: LocalSocksProvider,
        private val sidecarBinder: SidecarBinder,
        private val sidecarPrefs: SidecarPrefs,
        private val sidecarWakeRegistrar: SidecarWakeRegistrar,
        private val historyResyncCoordinator: HistoryResyncCoordinator,
        private val readMarkerRepository: ReadMarkerRepository,
        private val messageNotifier: MessageNotifier,
        private val chatSoundPlayer: ChatSoundPlayer,
        private val presetEnrollmentCoordinator: PresetEnrollmentCoordinator,
        private val avatarCoordinator: AvatarCoordinator,
        private val pushHealthStore: PushHealthStore,
        private val diagnostics: DiagnosticLogger,
        @ApplicationScope private val scope: CoroutineScope,
        // Lazy to break the WebPushRegistrar <-> ConnectionManager ctor cycle.
        private val webPushRegistrar: dagger.Lazy<WebPushRegistrar>,
        private val bufferStore: BufferStore = BufferStore(db),
    ) : ConnectionManager {
        private val networkDao get() = db.networkDao()
        private val bufferDao get() = db.bufferDao()
        private val messageDao get() = db.messageDao()
        private val reactionMutations = RoomReactionMutationStore(db)
        private val recoveryReader = ConnectionRecoveryReader(bufferDao)
        private val registry =
            ConnectionRegistry(
                scope = scope,
                actorFactory = ::createActor,
                isConfigurationFailure = ::isConfigurationFailure,
                diagnostics = diagnostics,
            )

        // Latest full network set, kept so buildClient can resolve a BOUNCER_CHILD's root row (its
        // bouncer endpoint + account SASL) without a suspend DB read. Updated on every reconcile.
        @Volatile private var networksById: Map<Long, NetworkEntity> = emptyMap()

        @Volatile private var sidecarsEnabled = false

        // Sticky in-memory user intent per network: true = force-connect,
        // false = force-disconnect, absent = follow autoConnect. Survives reconcile emissions so a
        // manual disconnect/connect is not undone by the next DB write. Reset by stopAll (not persisted).
        private val userIntents = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

        override val connectionStates: StateFlow<Map<Long, IrcClientState>> = registry.connectionStates
        override val connectionActivity: StateFlow<ConnectionActivitySnapshot> = registry.connectionActivity

        private val _channelJoinOutcomes = MutableSharedFlow<ChannelJoinOutcome>(extraBufferCapacity = 16)
        override val channelJoinOutcomes: SharedFlow<ChannelJoinOutcome> = _channelJoinOutcomes.asSharedFlow()

        private val _rosterStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
        override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = _rosterStates.asStateFlow()
        private val rosterRequests = java.util.concurrent.ConcurrentHashMap<Long, Deferred<Unit>>()

        private val _presenceStates = MutableStateFlow<Map<PresenceKey, PresenceState>>(emptyMap())
        override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> = _presenceStates.asStateFlow()

        private val _lagStates = MutableStateFlow<Map<Long, Long?>>(emptyMap())
        override val lagStates: StateFlow<Map<Long, Long?>> = _lagStates.asStateFlow()

        private val _selfAwayStates = MutableStateFlow<Map<Long, String?>>(emptyMap())
        override val selfAwayStates: StateFlow<Map<Long, String?>> = _selfAwayStates.asStateFlow()

        /** Away text this device last wrote, attached to the state once the server confirms with 306. */
        private val pendingAwayMessages = java.util.concurrent.ConcurrentHashMap<Long, String>()
        private val monitoredTargets = java.util.concurrent.ConcurrentHashMap<Long, Map<String, String>>()
        private val monitorInitialized =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Long>()
        private val monitorLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
        private val sendLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
        private val sendLifecycle = DurableSendLifecycle()
        private val multilineFallbackLabels =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<String>()
        private val pendingRecoveryLock = Mutex()
        private var pendingRecovered = false

        private val stsStore = StsPolicyStore(settings)

        private val _certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
        override val certPrompts: StateFlow<List<CertPrompt>> = _certPrompts.asStateFlow()

        // Latest untrusted-cert failure per network, set from the handshake trust manager and consumed
        // by the actor to park in "awaiting trust" instead of backoff-looping.
        private val certFailures = java.util.concurrent.ConcurrentHashMap<Long, CertUntrustedException>()

        // Last catch-up pass that completed successfully, per network. Keyed on the exact client so a
        // reconnect always re-verifies; elapsed-realtime because it keeps counting through Doze.
        private val completedCatchUps = java.util.concurrent.ConcurrentHashMap<Long, CompletedCatchUp>()

        // Foreground-verification passes only; the Ready-session pass stays inline (see launchCatchUp).
        // The owning client is stored alongside the job: single flight is per CONNECTION, not merely
        // per network, or a job pinned to a long-dead client outranks every later candidate.
        private val catchUpJobs = java.util.concurrent.ConcurrentHashMap<Long, CatchUpJob>()

        @Volatile private var appForeground = false

        @Volatile private var deviceIdle = false
        private val pushSuspendedIds =
            java.util.concurrent.ConcurrentHashMap
                .newKeySet<Long>()
        private val backgroundRetention =
            BackgroundConnectionRetention(
                scope = scope,
                graceMs = EMBEDDED_REALITY_BACKGROUND_GRACE_MS,
            )

        override fun isSidecarNetwork(networkId: Long): Boolean = networksById[networkId]?.connectionTransport == ConnectionTransport.SIDECAR

        override fun sidecarsEnabled(): Boolean = sidecarsEnabled

        override fun clientFor(networkId: Long): IrcClient? =
            (
                registry.snapshot.value.actors[networkId]
                    ?.connection as? IrcClientConnection
            )?.client

        // -- lifecycle ----------------------------------------------------------

        override suspend fun startAll() {
            var startBegan = false
            try {
                val shouldStart =
                    sendLifecycle.quiesce {
                        if (!registry.beginStart()) {
                            false
                        } else {
                            startBegan = true
                            ensurePendingRecovered(force = true)
                            true
                        }
                    }
                if (!shouldStart) return
                // Seed actors from the current full set (reconcile applies autoConnect + sticky intent),
                // then keep reconciling on every DB change. The collector no longer pre-filters: reconcile
                // owns the wanted-set computation so manual connect/disconnect intents survive DB writes.
                val initialNetworks = networkDao.observeAll().first()
                sidecarsEnabled = sidecarPrefs.enabled.first()
                sidecarWakeRegistrar.reconcile(initialNetworks, sidecarsEnabled)
                reconcile(initialNetworks)
                val reconcileJob =
                    scope.launch {
                        combine(networkDao.observeAll(), sidecarPrefs.enabled) { all, enabled -> all to enabled }
                            .collect { (all, enabled) ->
                                sidecarsEnabled = enabled
                                sidecarWakeRegistrar.reconcile(all, enabled)
                                reconcile(all)
                            }
                    }
                // Delivery-mode reaction: UNIFIED_PUSH tears verified sockets down only after Android
                // enters Doze. Merely switching away from motd keeps active conversations connected.
                val deliveryModeJob =
                    scope.launch {
                        settings.settings.map { it.deliveryMode }.distinctUntilChanged().collect { mode ->
                            if (mode == DeliveryMode.UNIFIED_PUSH) {
                                val all = networkDao.observeAll().first()
                                if (appForeground && hasWantedEmbeddedReality(all)) {
                                    startForegroundKeeper()
                                } else if (!appForeground) {
                                    beginEmbeddedRealityBackgroundRetention(all)
                                    if (deviceIdle) maybeStopForPush()
                                }
                            } else {
                                backgroundRetention.cancel()
                                pushSuspendedIds.clear()
                                val all = networkDao.observeAll().first()
                                reconcile(all)
                                // Switching INTO PERSISTENT_SOCKET has to arm the keeper itself: this mode's
                                // socket is only persistent while the service holds the process out of the
                                // cached/frozen band, and MainActivity decides that once, in onCreate.
                                // Guarded on appForeground because the switch is a user action taken in the
                                // app, and Android 12+ rejects a foreground-service launch from background.
                                if (appForeground && shouldArmKeeperOnForeground(mode, all)) {
                                    startForegroundKeeper()
                                }
                            }
                        }
                    }
                val monitorDesiredJob =
                    scope.launch {
                        // distinctUntilChanged: every messages write invalidates the projection, but the
                        // desired MONITOR set only changes when the rows themselves do.
                        combine(
                            settings.settings,
                            bufferDao.observeMonitorQueryRows().distinctUntilChanged(),
                        ) { currentSettings, rows ->
                            currentSettings.friends to rows
                        }.collect { (friends, rows) ->
                            registry.snapshot.value.actors.keys.forEach { networkId ->
                                val client = clientFor(networkId) ?: return@forEach
                                if (client.state.value is IrcClientState.Ready && networkId in monitorInitialized) {
                                    reconcileMonitor(networkId, client, friends, rows, fresh = false)
                                }
                            }
                        }
                    }
                registry.attachObservers(
                    listOf(connectivityObserverJob(), reconcileJob, deliveryModeJob, monitorDesiredJob),
                )
            } catch (failure: Throwable) {
                if (startBegan) registry.stop()
                throw failure
            }
        }

        private suspend fun ensurePendingRecovered(force: Boolean = false) {
            pendingRecoveryLock.withLock {
                if (force || !pendingRecovered) {
                    eventProcessor.recoverInterruptedPending()
                    pendingRecovered = true
                }
            }
        }

        /**
         * Under UNIFIED_PUSH: tear the sockets down only once EVERY live webpush-capable client
         * holds a persisted endpoint (and at least one such client exists). This gates teardown on
         * push actually being armed on all push-eligible networks, so a network still awaiting its
         * endpoint keeps its socket. Non-webpush DIRECT networks are ignored here (documented
         * limitation).
         */
        private suspend fun maybeStopForPush() {
            if (!shouldApplyDozePushHandoff(
                    appForeground = appForeground,
                    deviceIdle = deviceIdle,
                    deliveryMode = settings.settings.first().deliveryMode,
                )
            ) {
                return
            }
            if (backgroundRetention.isRetaining) return
            val all = networkDao.observeAll().first()
            val wanted = wantedNetworkIds(all, userIntents)
            val endpoints = pushPrefs.endpoints()
            val health = pushHealthStore.snapshot()
            val suspend = pushSuspendedNetworkIds(all, wanted, endpoints, health)

            pushSuspendedIds.clear()
            pushSuspendedIds.addAll(suspend)
            reconcile(all)

            val needsSocket = wanted.any { it !in pushSuspendedIds }
            if (needsSocket) {
                startForegroundKeeper()
            } else {
                stopForegroundKeeper()
            }
        }

        /**
         * Process foreground: reconnect every wanted network and run the normal catch-up path. A
         * transient msgid-less push creates its buffer before notifying, so [openBuffers] includes a
         * new DM/channel target when the post-notification reconnect requests CHATHISTORY.
         */
        internal suspend fun onAppForegrounded() {
            appForeground = true
            backgroundRetention.cancel()
            pushSuspendedIds.clear()
            startAll()
            val all = networkDao.observeAll().first()
            reconcile(all)
            if (shouldArmKeeperOnForeground(settings.settings.first().deliveryMode, all)) {
                // Start while the app is visibly foreground. Android 12+ may reject a foreground-
                // service launch after onStop; pre-arming the thin keeper makes the subsequent grace
                // a real retention guarantee instead of relying on an unprotected process timer.
                //
                // Every foreground re-arms it, not just the cold one: MainActivity only decides this in
                // onCreate, so a warm re-entry (a notification tap through onNewIntent, or a return
                // after the user tapped the status notification's Stop) brought the connections back
                // through [startAll] with no keeper behind them. A PERSISTENT_SOCKET session whose
                // process is freezable the moment it backgrounds is exactly what the mode exists to
                // prevent.
                startForegroundKeeper()
            }
            historyCheckpoint()
        }

        /**
         * The reconnect-then-verify pair every foreground or device-wake checkpoint runs.
         *
         * The order is the point: [verifyHistoryAtCheckpoint] paints AwaitingConnection on every open
         * buffer of a network that is not Ready, and that optimistic badge is only honest while
         * something is actually driving the connection. [reconnectStale] is what wakes an actor parked
         * in exponential backoff; without it in front, a checkpoint could leave every buffer of a
         * backing-off network waiting on nothing but that backoff's own expiry.
         */
        private suspend fun historyCheckpoint() {
            reconnectStale()
            verifyHistoryAtCheckpoint()
        }

        /** [startForegroundKeeper] precondition for a visibly-foreground process. */
        private fun shouldArmKeeperOnForeground(
            mode: DeliveryMode,
            all: List<NetworkEntity>,
        ): Boolean =
            shouldArmForegroundKeeper(
                deliveryMode = mode,
                hasWantedEmbeddedReality = hasWantedEmbeddedReality(all),
                hasWantedNetwork = wantedNetworkIds(all, userIntents).isNotEmpty(),
            )

        // A warm notification tap is a foreground entry that ProcessLifecycleOwner never reports, so it
        // runs the same reconnect-then-verify pair ON_START does rather than the verification alone.
        // The tapped buffer is reconciled first and independently: post trust-live-socket the network
        // checkpoint usually decides there is nothing to do, and even when it does run a pass, the
        // conversation the user just opened must not wait behind discovery.
        override suspend fun checkpointHistory(focusBufferId: Long?) {
            focusBufferId?.let(::reconcileFocusedBuffer)
            historyCheckpoint()
        }

        override suspend fun checkpointNetwork(networkId: Long) {
            val row = networkDao.byId(networkId) ?: return
            if (row.connectionTransport != ConnectionTransport.SIDECAR || !sidecarsEnabled) return
            connect(networkId)
            val client =
                withTimeoutOrNull(SIDECAR_WAKE_TIMEOUT_MS) {
                    connectionStates
                        .map { states -> states[networkId] }
                        .first { it is IrcClientState.Ready }
                    clientFor(networkId)
                } ?: return
            catchUpForConnection(networkId, client)
        }

        /**
         * Reconcile one tapped buffer immediately, on this coordinator's own scope.
         *
         * Deliberately launched rather than awaited: it is a single-buffer flight with its own request
         * key, so it coalesces with a chat screen that is opening the same buffer, interleaves with any
         * network pass at the loader's per-request permit, and must not delay the checkpoint behind it.
         * A buffer whose network is not Ready is skipped — the reconnect's own catch-up pass will
         * prioritize it anyway, because the wave plan orders the foreground buffer first.
         */
        private fun reconcileFocusedBuffer(bufferId: Long) {
            scope.launch {
                // Canonical, not raw: a notification can carry a room id that has since been merged
                // into another, and reconciling the losing side would fetch into a room nothing reads.
                val buffer = db.bufferDao().observeById(bufferId) ?: return@launch
                val client = clientFor(buffer.networkId) ?: return@launch
                if (registry.snapshot.value.states[buffer.networkId] !is IrcClientState.Ready) return@launch
                historyResyncCoordinator.reconcileBuffer(buffer, client) {
                    clientFor(buffer.networkId) === client
                }
            }
        }

        /**
         * A foreground is a history checkpoint only for connections that cannot vouch for themselves.
         *
         * A socket that has stayed Ready since its own catch-up converged received everything the
         * server sent, live — there is nothing for a pass to discover, and running one anyway spent
         * wire traffic and flashed sync chrome on essentially every app switch. The case the old
         * time-based throttle was defending against is not silent at all: a server buffer that fills
         * while the process is frozen stalls the socket, the ping watchdog kills it, and the
         * reconnect's new client fails [shouldRunForegroundVerification]'s identity check, so its A1
         * pass runs. [historyCheckpoint] runs `reconnectStale()` (and with it the registry's
         * liveness probe) in front of this, which is what turns a silently-dead socket into that
         * reconnect.
         *
         * Networks that are not Ready get the optimistic waiting state instead of a pass, so the list
         * shows queued feedback immediately; the reconnect this method follows adopts those entries
         * when it finally runs a real pass.
         */
        internal suspend fun verifyHistoryAtCheckpoint() {
            val snapshot = registry.snapshot.value
            if (!snapshot.started) return
            for ((networkId, state) in snapshot.states) {
                val client = clientFor(networkId)
                if (client == null || state !is IrcClientState.Ready) {
                    historyResyncCoordinator.markAwaitingConnection(
                        networkId,
                        openBuffers(networkId).map { it.id },
                    )
                    continue
                }
                val shouldVerify =
                    shouldRunForegroundVerification(
                        recorded = completedCatchUps[networkId],
                        currentClient = client,
                    )
                if (shouldVerify) launchCatchUp(networkId, client)
            }
        }

        /**
         * Per-network single flight for verification passes. The Ready-session catch-up at
         * [onReadySession] deliberately stays inline (the history gate must not be released before that
         * pass's visible wave converges); a same-client overlap between the two degrades to the
         * coordinator's own request coalescing rather than issuing the pass twice.
         */
        private fun launchCatchUp(
            networkId: Long,
            client: IrcClient,
        ) {
            // The caller read [client] before this call, so the actor can have swapped in a replacement
            // in between. A candidate pinned to that superseded connection would win ownership on
            // identity, cancel the LIVE client's tracked pass, then exit on its own first loop check —
            // leaving the network with no pass at all until the next checkpoint.
            if (clientFor(networkId) !== client) return
            val candidate =
                CatchUpJob(
                    client,
                    scope.launch(start = CoroutineStart.LAZY) { catchUpForConnection(networkId, client) },
                )
            // Recorded inside the atomic compute, cancelled outside it: ConcurrentHashMap.compute runs
            // its remapping function under a bin lock, so it must not do anything that can block.
            var displaced: CatchUpJob? = null
            val owner =
                catchUpJobs.compute(networkId) { _, existing ->
                    chooseCatchUpOwner(existing, candidate).also { if (it !== existing) displaced = existing }
                }
            // A pass pinned to a superseded connection has nothing left to verify; letting it run on
            // would only re-attempt fetches against a dead socket behind its own replacement.
            displaced?.job?.cancel()
            if (owner !== candidate) {
                candidate.job.cancel()
                return
            }
            candidate.job.invokeOnCompletion { catchUpJobs.remove(networkId, candidate) }
            candidate.job.start()
        }

        /**
         * Cancel tracked verification passes for networks the wanted set no longer contains.
         *
         * These jobs run on the application scope, not the actor's, so actor teardown cannot stop them:
         * a row deleted mid-pass or a Doze push hand-off (which retires actors through [reconcile]
         * without going through [disconnect]) would otherwise leave the entry behind for the process
         * lifetime. Cancelled, not retired: the pass's own abandon puts its buffers back into the
         * optimistic waiting state, which backgrounding and Doze deliberately keep.
         */
        private fun cancelCatchUpsOutside(wantedIds: Set<Long>) {
            catchUpJobs.keys.filterNot { it in wantedIds }.forEach { networkId ->
                catchUpJobs.remove(networkId)?.job?.cancel()
            }
        }

        /** Process background: retain embedded REALITY through a short app switch, even in Doze. */
        internal fun onAppBackgrounded() {
            appForeground = false
            scope.launch {
                val all = networkDao.observeAll().first()
                beginEmbeddedRealityBackgroundRetention(all)
                if (deviceIdle) maybeStopForPush()
            }
        }

        /**
         * Doze entry hands healthy UnifiedPush networks to push. When the device wakes, reconnect them
         * in the background and run the normal history checkpoint; they stay open until the next Doze
         * entry, subject to Android freezing this non-foreground process.
         */
        internal fun onDeviceIdleModeChanged(idle: Boolean) {
            val wasIdle = deviceIdle
            deviceIdle = idle
            if (idle && !appForeground) {
                scope.launch { maybeStopForPush() }
            } else if (wasIdle && !idle && !appForeground) {
                scope.launch {
                    val mode = settings.settings.first().deliveryMode
                    if (shouldResumeSocketsAfterDozeExit(appForeground, wasIdle, deviceIdle, mode)) {
                        pushSuspendedIds.clear()
                        startAll()
                        historyCheckpoint()
                    }
                }
            }
        }

        private suspend fun beginEmbeddedRealityBackgroundRetention(all: List<NetworkEntity>) {
            if (appForeground || settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH ||
                !hasWantedEmbeddedReality(all) || backgroundRetention.graceElapsed
            ) {
                return
            }
            startForegroundKeeper()
            backgroundRetention.onBackgrounded {
                if (appForeground) return@onBackgrounded
                if (deviceIdle) {
                    maybeStopForPush()
                } else {
                    releaseKeeperWhenPushCanOwnEverything()
                }
            }
        }

        private fun hasWantedEmbeddedReality(all: List<NetworkEntity>): Boolean =
            wantedNetworkIds(all, userIntents).any { networkId ->
                wantedNetworkUsesEmbeddedReality(networkId, all)
            }

        /**
         * The grace keeper is no longer needed once every wanted network has healthy push delivery.
         * Outside Doze we leave actors alone, preserving the existing keep-until-Doze semantics.
         */
        private suspend fun releaseKeeperWhenPushCanOwnEverything() {
            if (appForeground || settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH) return
            val all = networkDao.observeAll().first()
            val wanted = wantedNetworkIds(all, userIntents)
            val pushOwned =
                pushSuspendedNetworkIds(
                    all,
                    wanted,
                    pushPrefs.endpoints(),
                    pushHealthStore.snapshot(),
                )
            if (wanted.all { it in pushOwned }) stopForegroundKeeper()
        }

        private fun startForegroundKeeper() {
            // A refused keeper silently loses the EMBEDDED_REALITY retention guarantee, so the refusal
            // is recorded and not only logged.
            startForegroundSafely(diagnostics, source = "keeper") {
                ContextCompat.startForegroundService(
                    appContext,
                    android.content.Intent(appContext, IrcForegroundService::class.java),
                )
            }
        }

        private fun stopForegroundKeeper() {
            val stopIntent = android.content.Intent(appContext, IrcForegroundService::class.java)
            appContext.stopService(stopIntent)
        }

        override suspend fun stopAll() =
            withContext(NonCancellable) {
                // Captured before the registry stops, for the same reason as in [disconnect]: a pass still
                // winding down in the coordinator's scope is only silenceable by its connection's identity.
                val retiring = networksToRetire(networksById.keys, catchUpJobs.keys).associateWith { clientFor(it) }
                sendLifecycle.quiesce(
                    onBlocked = {
                        backgroundRetention.cancel()
                        // Disconnect first so in-flight writes cannot keep producing pending rows. This
                        // also cancels and joins every echo timeout before recovery scans Room.
                        registry.stop()
                    },
                ) {
                    eventProcessor.recoverInterruptedPending()
                    pendingRecoveryLock.withLock { pendingRecovered = false }
                    localSocksProvider.stop()
                    // Service teardown resets sticky user intent (in-memory only).
                    userIntents.clear()
                    retiring.forEach { (networkId, client) ->
                        catchUpJobs.remove(networkId)?.job?.cancelAndJoin()
                        historyResyncCoordinator.retireNetwork(networkId, client)
                    }
                    completedCatchUps.clear()
                    // Jobs registered between the retiring-map capture and this sweep were cancelled but
                    // never retired; retire them too so a catch-up racing shutdown can't leave badges
                    // or progress painted.
                    catchUpJobs.keys.filter { it !in retiring }.forEach { networkId ->
                        catchUpJobs.remove(networkId)?.job?.cancelAndJoin()
                        historyResyncCoordinator.retireNetwork(networkId, null)
                    }
                    catchUpJobs.values.forEach { it.job.cancel() }
                    catchUpJobs.clear()
                    pushSuspendedIds.clear()
                    rosterRequests.values.forEach { it.cancel() }
                    rosterRequests.clear()
                    _rosterStates.value = emptyMap()
                    monitoredTargets.clear()
                    monitorInitialized.clear()
                    monitorLocks.clear()
                    _presenceStates.value = emptyMap()
                    _lagStates.value = emptyMap()
                    pendingAwayMessages.clear()
                    _selfAwayStates.value = emptyMap()
                    eventProcessor.shutdown()
                }
            }

        override suspend fun connect(networkId: Long) {
            val row = networkDao.byId(networkId) ?: return
            if (row.connectionTransport == ConnectionTransport.SIDECAR && !sidecarsEnabled) return
            // Seed the network snapshot so buildClient can resolve a child's root even before the
            // first reconcile emission (e.g. connecting a freshly imported BOUNCER_CHILD).
            if (row.role == NetworkRole.BOUNCER_CHILD && row.parentId != null && networksById[row.parentId] == null) {
                networkDao.byId(row.parentId)?.let { parent ->
                    networksById = networksById + (parent.id to parent) + (row.id to row)
                }
            }
            // Record the sticky intent BEFORE touching actors so a concurrent reconcile honors it.
            userIntents[networkId] = true
            registry.connect(row, fingerprint(row))
        }

        override suspend fun disconnect(networkId: Long) {
            // Record intent before removal so the next reconcile does not re-create the actor.
            userIntents[networkId] = false
            // Captured before the actor is torn down: retirement names the exact connection whose
            // catch-up pass may still be winding down inside the coordinator's own scope. Null when the
            // actor is already gone, which retires the network's existing passes without naming one.
            val client = clientFor(networkId)
            completedCatchUps.remove(networkId)
            registry.disconnect(networkId)
            // The verification loop is ours, not the actor's, so nothing else stops it; stop it before
            // retiring so it cannot start another attempt behind the retirement.
            catchUpJobs.remove(networkId)?.job?.cancelAndJoin()
            // A deliberate disconnect is the one case where "waiting for connection" is a lie: nothing
            // is coming. Backgrounding and Doze deliberately keep those entries.
            historyResyncCoordinator.retireNetwork(networkId, client)
            invalidateRosters(networkId)
            invalidatePresence(networkId)
        }

        override suspend fun reconnectStale() {
            // Canonical app-foreground reconnect entry (registered on ProcessLifecycleOwner in
            // MotdApplication). Re-runs the self-healing reconcile against the current DB snapshot so any
            // actor that died/parked in the background (Doze/network drop) is dropped and rebuilt. Then
            // wake surviving non-ready actors so foregrounding does not wait out an old exponential
            // backoff after a proxy or bouncer has returned, and probe Ready actors in place. The
            // actor wake-up/probe requests are conflated and merely interrupt retry or validate the
            // current socket; a healthy Ready connection is never unconditionally rebuilt. No-op until
            // started.
            if (!registry.snapshot.value.started) return
            reconcile(networkDao.observeAll().first())
            registry.wakeNonReady()
            registry.probeReady()
        }

        /** Add/remove/restart actors so the live set matches the wanted set derived from [all] rows
         *  and the sticky user-intent map. */
        private suspend fun reconcile(all: List<NetworkEntity>) {
            val deletedIds = networksById.keys - all.mapTo(mutableSetOf()) { it.id }
            // Keep a synchronous lookup so buildClient can resolve a child's root bouncer row.
            networksById = all.associateBy { it.id }
            val eligible = if (sidecarsEnabled) all else all.filter { it.connectionTransport != ConnectionTransport.SIDECAR }
            val wantedIds = wantedNetworkIds(eligible, userIntents) - pushSuspendedIds
            diagnostics.record("connections", "reconcile") {
                mapOf(
                    "configured" to all.size,
                    "wanted" to wantedIds.size,
                    "deleted" to deletedIds.size,
                    "push_suspended" to pushSuspendedIds.size,
                )
            }
            registry.reconcile(
                rows = all.map { it to fingerprint(it) },
                wantedIds = wantedIds,
                awaitingCertTrust = _certPrompts.value.mapTo(mutableSetOf()) { it.networkId },
            )
            // Reconcile is the only teardown path a deleted row or the Doze push hand-off takes, and
            // neither goes through [disconnect] or [stopAll], the two places that used to be able to
            // clear a tracked pass.
            cancelCatchUpsOutside(wantedIds)
            deletedIds.forEach {
                eventProcessor.evictNetwork(it)
                _lagStates.update { states -> states - it }
            }
        }

        private fun createActor(
            row: NetworkEntity,
            generation: Long,
        ): ConnectionLifecycleActor {
            val fp = fingerprint(row)
            val nickRecoveryGuard = NickRecoveryGuard()
            return ConnectionActor(
                networkId = row.id,
                scope = scope,
                connectionFactory = { buildConnection(row, nickRecoveryGuard) },
                onState = { id, state ->
                    diagnostics.record("connections", "state_changed") {
                        buildMap {
                            put("network_id", id)
                            put("state", state::class.simpleName)
                            when (state) {
                                is IrcClientState.Ready -> {
                                    putAll(readyStateDiagnosticFields(state))
                                }

                                is IrcClientState.Failed -> {
                                    put("fatal", state.fatal)
                                    put("error_fp", diagnostics.fingerprint(state.reason))
                                }

                                else -> {
                                    Unit
                                }
                            }
                        }
                    }
                    registry.actorState(id, generation, fp, state)
                    // Startup step 3A: a root reaching Registering has already proven the bouncer
                    // endpoint reachable (tunnel + TCP + TLS done), which is all a dead child was
                    // waiting for — its own SASL bind needs nothing from the root's session. Launched
                    // so the actor's state pipeline never blocks on the registry round trips inside
                    // connect(); runIfCurrent drops the revival if this actor generation is replaced
                    // first. The Ready edge in onReadySession remains the self-heal for this same set.
                    if (state is IrcClientState.Registering && row.role == NetworkRole.BOUNCER_ROOT) {
                        scope.launch {
                            registry.runIfCurrent(id, generation) { reviveChildrenOf(id) }
                        }
                    }
                },
                onEvent = { id, event ->
                    registry.runIfCurrent(id, generation) { handleConnectionEvent(id, event) }
                },
                onConnectionChanged = { id, connection ->
                    registry.actorConnection(id, generation, connection)
                },
                onLag = { id, lag -> setLag(id, lag) },
                onStopped = { id -> registry.actorStopped(id, generation) },
                onReady = { conn ->
                    if (registry.isCurrent(row.id, generation)) {
                        onReady(
                            row,
                            (conn as IrcClientConnection).client,
                            generation,
                        ) { registry.isCurrent(row.id, generation) }
                    }
                },
                pendingCertFailure = {
                    var failure: CertUntrustedException? = null
                    registry.runIfCurrent(row.id, generation) {
                        failure = certFailures.remove(row.id)
                    }
                    failure
                },
                onCertUntrusted = { id, ex ->
                    registry.runIfCurrent(id, generation) { publishCertPrompt(id, ex) }
                },
                onBackoff = { phase, attempt, delayMs ->
                    diagnostics.record("connections", "reconnect_backoff") {
                        mapOf(
                            "network_id" to row.id,
                            "phase" to phase,
                            "attempt" to attempt,
                            "delay_ms" to delayMs,
                            "capped" to (delayMs >= ConnectionActor.CAP_MS),
                        )
                    }
                },
            )
        }

        private suspend fun handleConnectionEvent(
            networkId: Long,
            event: IrcEvent,
        ) {
            avatarCoordinator.onEvent(networkId, event)
            eventProcessor.process(networkId, event)
            applySelfAway(networkId, event)
            channelJoinOutcome(networkId, event, clientFor(networkId)?.isupport?.identityRules ?: IrcIdentityRules())
                ?.let { _channelJoinOutcomes.emit(it) }
            when (event) {
                is IrcEvent.Joined -> {
                    if (event.isSelf) {
                        bufferForChannel(networkId, event.channel)?.let { buffer ->
                            val client = clientFor(networkId)
                            val ready = client?.state?.value as? IrcClientState.Ready
                            setRosterState(
                                buffer.id,
                                if (ready != null && preferredNoImplicitNames(ready.caps) != null) {
                                    RosterLoadState.NOT_LOADED
                                } else {
                                    RosterLoadState.LOADING
                                },
                            )
                            if (client != null) seedJoinedChannelHistory(buffer, client)
                        }
                    }
                }

                is IrcEvent.NamesStarted -> {
                    bufferForChannel(networkId, event.channel)?.let {
                        setRosterState(it.id, RosterLoadState.LOADING)
                    }
                }

                is IrcEvent.Names -> {
                    bufferForChannel(networkId, event.channel)?.let {
                        // An explicit lazy refresh is complete only after its correlated WHOX has also
                        // finished. The NAMES snapshot itself has converged in EventProcessor, but
                        // exposing LOADED here would incorrectly turn a later WHOX timeout into an
                        // authoritative, supposedly enriched roster.
                        setRosterState(it.id, rosterStateAfterNames(rosterRequests[it.id] != null))
                    }
                }

                is IrcEvent.Parted -> {
                    if (event.isSelf) clearRoster(networkId, event.channel)
                }

                is IrcEvent.Kicked -> {
                    if (event.isSelf) clearRoster(networkId, event.channel)
                }

                is IrcEvent.Disconnected -> {
                    invalidateRosters(networkId)
                    invalidatePresence(networkId)
                }

                is IrcEvent.MultilineRejected -> {
                    onMultilineRejected(networkId, event)
                }

                is IrcEvent.MonitorOnline -> {
                    onMonitorOnline(networkId, event)
                }

                is IrcEvent.MonitorOffline -> {
                    updatePresence(networkId, event.nicks, PresenceState.OFFLINE)
                }

                is IrcEvent.MonitorLimitExceeded -> {
                    val normalize = clientFor(networkId)?.isupport?.let { it::normalize }
                    if (normalize != null) {
                        val rejected = event.targets.mapTo(HashSet(), normalize)
                        monitoredTargets.computeIfPresent(networkId) { _, accepted ->
                            accepted.filterKeys { it !in rejected }
                        }
                    }
                    event.targets.forEach { target ->
                        setPresence(networkId, target, PresenceState.UNKNOWN)
                    }
                }

                is IrcEvent.Registered -> {
                    // Registration reaches Ready on 001, while servers commonly advertise ISUPPORT
                    // later in 005. The client republishes Registered when that runtime snapshot
                    // changes; initialize MONITOR here if the first Ready-time pass saw it as absent.
                    if (shouldInitializeMonitorFromRegistration(event.isupport, monitoredTargets.containsKey(networkId))) {
                        clientFor(networkId)?.let { client ->
                            reconcileMonitor(
                                networkId,
                                client,
                                settings.settings.first().friends,
                                bufferDao.observeMonitorQueryRows().first(),
                                fresh = true,
                            )
                        }
                    }
                }

                is IrcEvent.NickChanged -> {
                    rekeyPresence(networkId, event.from, event.to)
                }

                is IrcEvent.CapsChanged -> {
                    if (event.removed.any { it in NO_IMPLICIT_NAMES_ALIASES }) invalidateRosters(networkId)
                    if (event.added.any { it.substringBefore('=') == WEBPUSH_CAP }) {
                        // This also covers an endpoint callback racing a soju child's initial Ready
                        // snapshot: re-arm precisely when the post-BIND CAP ACK makes WEBPUSH usable.
                        val client = clientFor(networkId)
                        if (client != null && settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH &&
                            pushPrefs.endpointFor(networkId) != null
                        ) {
                            scope.launch {
                                if (clientFor(networkId) !== client) return@launch
                                webPushRegistrar.get().reRegisterIfNeeded(networkId)
                                if (clientFor(networkId) === client) evaluatePushMode()
                            }
                        }
                    }
                    if (
                        event.added.any { it.substringBefore('=') in io.github.trevarj.motd.irc.client.EXTENDED_MONITOR_ALIASES } ||
                        event.removed.any { it in io.github.trevarj.motd.irc.client.EXTENDED_MONITOR_ALIASES }
                    ) {
                        clientFor(networkId)?.let { client ->
                            val currentSettings = settings.settings.first()
                            reconcileMonitor(
                                networkId,
                                client,
                                currentSettings.friends,
                                bufferDao.observeMonitorQueryRows().first(),
                                fresh = false,
                            )
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        private suspend fun bufferForChannel(
            networkId: Long,
            channel: String,
        ) = bufferStore.resolveChannelRoom(networkId, normalize(networkId, channel))

        private fun setRosterState(
            bufferId: Long,
            state: RosterLoadState,
        ) {
            _rosterStates.update { it + (bufferId to state) }
        }

        /** Publish one network's latest latency reading; a null [lag] clears it (#34). */
        private fun setLag(
            networkId: Long,
            lag: Long?,
        ) {
            _lagStates.update { current ->
                if (lag == null) current - networkId else current + (networkId to lag)
            }
        }

        private suspend fun clearRoster(
            networkId: Long,
            channel: String,
        ) {
            bufferForChannel(networkId, channel)?.let { buffer ->
                rosterRequests.remove(buffer.id)?.cancel()
                _rosterStates.update { it - buffer.id }
            }
        }

        private suspend fun invalidateRosters(networkId: Long) {
            val ids = bufferDao.channelIds(networkId).toSet()
            ids.forEach { rosterRequests.remove(it)?.cancel() }
            _rosterStates.update { states ->
                states + ids.associateWith { RosterLoadState.NOT_LOADED }
            }
        }

        private suspend fun reconcileMonitor(
            networkId: Long,
            client: IrcClient,
            friends: Set<String>,
            rows: List<io.github.trevarj.motd.data.db.MonitorQueryRow>,
            fresh: Boolean,
        ) {
            if (networksById[networkId]?.role == NetworkRole.BOUNCER_ROOT) return
            val ready = client.state.value as? IrcClientState.Ready ?: return
            val support = monitorSupport(ready.isupport)
            val selection =
                selectMonitorTargets(
                    friends = friends,
                    queryRows = rows.filter { it.networkId == networkId },
                    limit = (support as? MonitorSupport.Limited)?.limit,
                    normalize = client.isupport::normalize,
                )
            updateDesiredPresence(networkId, selection.allDesired, client.isupport::normalize)
            if (support is MonitorSupport.Unsupported) {
                monitoredTargets.remove(networkId)
                if (fresh) monitorInitialized += networkId
                return
            }
            if (support is MonitorSupport.Malformed) {
                monitoredTargets.remove(networkId)
                if (fresh) monitorInitialized += networkId
                if (fresh) {
                    eventProcessor.process(
                        networkId,
                        IrcEvent.ServerError("MONITOR", emptyList(), "invalid MONITOR ISUPPORT limit"),
                    )
                }
                return
            }

            monitorLocks.getOrPut(networkId) { Mutex() }.withLock {
                if (clientFor(networkId) !== client || client.state.value !is IrcClientState.Ready) return@withLock
                val desired = selection.selected.associateBy(client.isupport::normalize)
                val previous = if (fresh) emptyMap() else monitoredTargets[networkId].orEmpty()
                val plan = monitorReconciliation(previous, desired, fresh)
                runCatching {
                    if (plan.clear) client.send(MonitorCommands.clear())
                    MonitorCommands.remove(plan.remove).forEach { client.send(it) }
                    MonitorCommands.add(plan.add).forEach { client.send(it) }
                    if (plan.status) client.send(MonitorCommands.status())
                }.onSuccess {
                    monitoredTargets[networkId] = desired
                    if (fresh) monitorInitialized += networkId
                }.onFailure {
                    desired.values.forEach { setPresence(networkId, it, PresenceState.UNKNOWN) }
                }
            }
        }

        private fun updateDesiredPresence(
            networkId: Long,
            desired: List<String>,
            normalize: (String) -> String,
        ) {
            val keys = desired.mapTo(HashSet(), normalize)
            _presenceStates.update { current -> presenceForDesired(current, networkId, keys) }
        }

        private fun setPresence(
            networkId: Long,
            nick: String,
            state: PresenceState,
        ) {
            val normalize =
                clientFor(networkId)?.isupport?.let { support -> support::normalize }
                    ?: { value: String -> value.lowercase() }
            val key = PresenceKey(networkId, normalize(nick))
            _presenceStates.update { current -> presenceIfTracked(current, key, state) }
        }

        private fun updatePresence(
            networkId: Long,
            nicks: List<String>,
            state: PresenceState,
        ) {
            nicks.forEach { setPresence(networkId, it, state) }
        }

        private fun onMonitorOnline(
            networkId: Long,
            event: IrcEvent.MonitorOnline,
        ) {
            val client = clientFor(networkId) ?: return
            event.identities.forEach { identity ->
                val key = PresenceKey(networkId, client.isupport.normalize(identity.nick))
                val wasOnline = _presenceStates.value[key] == PresenceState.ONLINE
                setPresence(networkId, identity.nick, PresenceState.ONLINE)
                if (!wasOnline) scope.launch { client.whox(identity.nick) }
            }
        }

        private fun rekeyPresence(
            networkId: Long,
            from: String,
            to: String,
        ) {
            val normalize = clientFor(networkId)?.isupport?.let { support -> support::normalize } ?: return
            val oldKey = PresenceKey(networkId, normalize(from))
            val newKey = PresenceKey(networkId, normalize(to))
            _presenceStates.update { current -> rekeyPresenceState(current, oldKey, newKey) }
        }

        private fun invalidatePresence(networkId: Long) {
            monitoredTargets.remove(networkId)
            monitorInitialized.remove(networkId)
            _presenceStates.update { current -> invalidatePresenceState(current, networkId) }
            // Our own away state is server-confirmed; a connection that is gone confirms nothing.
            pendingAwayMessages.remove(networkId)
            _selfAwayStates.update { current -> current - networkId }
        }

        /** Fold one event into the server-confirmed self-away state (never optimistic). */
        private fun applySelfAway(
            networkId: Long,
            event: IrcEvent,
        ) {
            if (!affectsSelfAway(event)) return
            val client = clientFor(networkId)
            val isupport = client?.isupport
            val normalize: (String) -> String =
                if (isupport != null) isupport::normalize else { value -> value.lowercase() }
            val selfNick = (client?.state?.value as? IrcClientState.Ready)?.nick
            val pending = pendingAwayMessages[networkId]
            _selfAwayStates.update { current ->
                selfAwayAfterEvent(current, networkId, event, pending, selfNick, normalize)
            }
            // The confirmation consumed the pending text either way: 305 means we are back, 306 means
            // the message (if any) is now published in the state.
            if (event is IrcEvent.SelfAwayChanged) pendingAwayMessages.remove(networkId)
        }

        override suspend fun setAway(
            networkId: Long,
            message: String?,
        ) {
            val client = clientFor(networkId) ?: return
            val text = message?.takeIf { it.isNotBlank() }
            if (text == null) pendingAwayMessages.remove(networkId) else pendingAwayMessages[networkId] = text
            try {
                client.send(
                    io.github.trevarj.motd.irc.proto
                        .IrcMessage(command = "AWAY", params = listOfNotNull(text)),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Nothing reached the wire, so no confirmation is coming; drop the unpublished text.
                pendingAwayMessages.remove(networkId)
                diagnostics.record("connections", "away_write_failed") {
                    mapOf("network_id" to networkId, "error" to e::class.simpleName)
                }
            }
        }

        private fun fingerprint(row: NetworkEntity): String =
            networkFingerprint(
                row,
                if (row.role == NetworkRole.BOUNCER_CHILD) row.parentId?.let { networksById[it] } else null,
            )

        private suspend fun onMultilineRejected(
            networkId: Long,
            event: IrcEvent.MultilineRejected,
        ) {
            if (!multilineFallbackLabels.add(event.label)) return
            val pending = eventProcessor.pendingOutgoingByLabel(networkId, event.label) ?: return
            if (pending.kind != MessageKind.PRIVMSG || !pending.isSelf || pending.msgid != null || pending.failed) return
            val buffer = bufferDao.observeById(pending.bufferId) ?: return
            if (buffer.networkId != networkId || buffer.isBouncerConsole) return
            val client = clientFor(networkId)
            val ready = client?.state?.value as? IrcClientState.Ready
            val wireReply =
                pending.replyToMsgid?.takeIf {
                    ready != null && canSendClientTag(ready.caps, ready.isupport, "+reply")
                }
            replanAndWriteLegacyFallback(
                buffer = buffer,
                client = client,
                ready = ready,
                eventId = pending.id,
                oldLabel = event.label,
                text = pending.text,
                replyToMsgid = wireReply,
                channelContext = pending.channelContext,
            )
        }

        private suspend fun replanAndWriteLegacyFallback(
            buffer: BufferEntity,
            client: IrcClient?,
            ready: IrcClientState.Ready?,
            eventId: Long,
            oldLabel: String,
            text: String,
            replyToMsgid: String?,
            channelContext: String?,
        ): ImmediateWireAcceptance {
            val chunks = prepareOutgoingMessageChunks(text, isBouncerServ = false)
            if (chunks.isEmpty()) {
                eventProcessor.failPendingEvents(listOf(eventId))
                return ImmediateWireAcceptance.FAILED
            }
            val planned = chunks.map { chunk -> PlannedOutgoingChunk(chunk, newOutgoingLabel()) }
            val replanned =
                eventProcessor.replanPendingOutgoing(
                    networkId = buffer.networkId,
                    eventId = eventId,
                    oldLabel = oldLabel,
                    events =
                        planned.map {
                            OutgoingEventPlan(
                                label = it.label,
                                text = it.chunk.displayText,
                                kind = it.chunk.kind,
                                ircFormattedText = it.chunk.ircFormattedText,
                            )
                        },
                ) ?: return ImmediateWireAcceptance.FAILED
            val currentBuffer = bufferDao.observeById(replanned.bufferId) ?: buffer
            return writeDurablePlan(
                buffer = currentBuffer,
                client = client,
                ready = ready,
                planned = planned,
                eventIds = replanned.events.map { it.eventId },
                replyToMsgid = replyToMsgid,
                forceLegacy = true,
                channelContext = channelContext,
            )
        }

        private fun requiresMultilineWire(chunk: OutgoingMessageChunk): Boolean =
            chunk.kind == MessageKind.PRIVMSG &&
                (
                    chunk.wireText.any { it == '\r' || it == '\n' } ||
                        chunk.wireText.toByteArray(Charsets.UTF_8).size > MAX_BYTES
                )

        /**
         * Revive this root's children that cannot recover on their own — absent, dead-looped, or
         * terminally disconnected/failed ([childrenNeedingReconnect]). Called on the root's Registering
         * edge (the earliest proof the shared bouncer endpoint is reachable this session: SOCKS/tunnel,
         * TCP, and TLS all succeeded) and again on Ready as self-heal. A live child — Connecting,
         * backing off, Registering, or Ready — is never touched, so repeated edges cannot storm the
         * endpoint or interrupt a healthy session.
         */
        private suspend fun reviveChildrenOf(
            rootId: Long,
            isCurrent: () -> Boolean = { true },
        ) {
            val snapshot = registry.snapshot.value
            val actorAlive = snapshot.actors.mapValues { (_, registered) -> registered.isAlive }
            for (childId in childrenNeedingReconnect(
                rootId = rootId,
                all = networksById.values.toList(),
                userIntents = userIntents,
                actorAlive = actorAlive,
                states = snapshot.states,
            )) {
                if (!isCurrent()) return
                connect(childId)
            }
        }

        private suspend fun buildConnection(
            row: NetworkEntity,
            nickRecoveryGuard: NickRecoveryGuard,
        ): IrcClientConnection {
            // A BOUNCER_CHILD is a *bound connection to the bouncer*, not a direct socket to the
            // upstream network. Its own host/port/tls/SASL may carry the upstream server's details
            // (soju's BOUNCER NETWORK attrs report the upstream host), so connecting on them would
            // SASL the bouncer account against the upstream server and fail (SASL 904, #40). Resolve
            // the root row and build the config from the bouncer endpoint + account SASL, binding the
            // network via bouncerNetId.
            val root =
                if (row.role == NetworkRole.BOUNCER_CHILD) {
                    row.parentId?.let { networksById[it] }
                } else {
                    null
                }
            val config = buildChildConfig(row, root)
            if (row.connectionTransport == ConnectionTransport.SIDECAR) {
                val component =
                    ComponentName(
                        requireNotNull(row.sidecarPackage) { "Companion provider package is missing" },
                        requireNotNull(row.sidecarService) { "Companion provider service is missing" },
                    )
                val accountId = requireNotNull(row.sidecarAccountId) { "Companion provider account is missing" }
                return IrcClientConnection(
                    IrcClient(
                        config,
                        SidecarTransportFactory(sidecarBinder, component, accountId),
                        scope,
                        nickRecoveryGuard = nickRecoveryGuard,
                    ),
                )
            }
            // Obfuscation/proxy follows the transport endpoint too: a bound child dials the same
            // bouncer endpoint on its OWN socket (and, for EMBEDDED_REALITY, its own libbox core — see
            // resolveTransportProxy), so it inherits the root's proxy CONFIGURATION, never its
            // connection.
            val endpoint = root ?: row
            val security =
                prepareTransportSecurity(
                    host = config.host,
                    port = config.port,
                    wsUrl = config.wsUrl,
                    policyFor = stsStore::policyFor,
                    pinnedFor = certStore::pinnedFor,
                )
            // Resolve EMBEDDED_REALITY only after suspending policy reads complete, so a failed read
            // cannot leak a newly acquired local proxy lease.
            val proxyResolution = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = row.id.toString())
            val factory =
                AppTransportFactory(
                    appContext = appContext,
                    security = security,
                    // TLS/cert trust follows the transport endpoint: the bouncer's for a bound child.
                    clientCertAlias = endpoint.clientCertAlias,
                    // Stash the failure keyed by network so the actor can park on it; unwrap defensively.
                    onCertUntrusted = { ex -> certFailures[row.id] = ex },
                    proxy = proxyResolution.proxy,
                    proxyConfigurationError = proxyResolution.error,
                    // Splits Connecting time into transport establishment (which contains the whole
                    // SOCKS/VLESS tunnel + remote TCP for obfuscated networks) and the TLS handshake.
                    // Classification only: phase name and duration — never the endpoint.
                    onConnectPhase = { phase, elapsedMs ->
                        diagnostics.record("connections", "dial_phase") {
                            mapOf("network_id" to row.id, "phase" to phase, "elapsed_ms" to elapsedMs)
                        }
                    },
                )
            return IrcClientConnection(
                IrcClient(config, factory, scope, nickRecoveryGuard = nickRecoveryGuard),
                proxyResolution.release,
            )
        }

        /** On Ready: persist any STS policy, re-establish bouncer children, then run catch-up. */
        private suspend fun onReady(
            row: NetworkEntity,
            client: IrcClient,
            generation: Long,
            isCurrent: () -> Boolean,
        ) {
            onReadySession(row, client, generation, isCurrent)
        }

        private suspend fun onReadySession(
            row: NetworkEntity,
            client: IrcClient,
            generation: Long,
            isCurrent: () -> Boolean,
        ) {
            if (!isCurrent()) return
            // Some DIRECT-configured endpoints (a WeeChat relay-irc listener behind a real ircd
            // session, or any other stateful backend not modeled as a bouncer role) replay a
            // synthetic self JOIN (+332/353/366) for every already-joined channel unprompted, right
            // after registration — sometimes before this method's other Ready-edge work (avatar sync,
            // MONITOR reconciliation, STS, preset enrollment) has even started. Start listening for
            // that replay immediately, in parallel with the rest of Ready, and only settle/consume it
            // right before the recovery JOIN loop below — starting the collector any later risks
            // missing replay events emitted while this method was still doing other suspending work.
            val replayedChannels = mutableSetOf<String>()
            val replayCapture =
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(JOIN_REPLAY_SETTLE_TIMEOUT_MS) {
                        client.broadcastEvents
                            .filterIsInstance<IrcEvent.Joined>()
                            .filter { it.isSelf }
                            .collect { replayedChannels += client.isupport.normalize(it.channel) }
                    }
                }
            avatarCoordinator.onReady(row.id, client)
            if (row.connectionTransport == ConnectionTransport.SIDECAR) {
                sidecarWakeRegistrar.reconcile(networksById.values.toList(), sidecarsEnabled)
            }
            if (row.connectionTransport == ConnectionTransport.SIDECAR && client.hasCap("draft/metadata-2")) {
                client.send(IrcMessage(command = "METADATA", params = listOf("*", "SUB", "display-name")))
                client.send(
                    IrcMessage(
                        command = "METADATA",
                        params = listOf("*", "SUB", SidecarContract.SECURITY_METADATA_KEY),
                    ),
                )
            }
            if (!isCurrent()) return
            reconcileMonitor(
                row.id,
                client,
                settings.settings.first().friends,
                bufferDao.observeMonitorQueryRows().first(),
                fresh = true,
            )
            if (!isCurrent()) return
            // Persist STS policy if the server advertised one.
            val stsValue = client.caps.firstOrNull { it == "sts" || it.startsWith("sts=") }?.substringAfter('=', "")
            stsStore.parse(row.host, stsValue?.ifEmpty { null }, row.tls, row.port)?.let { stsStore.upsert(it) }
            val enrollmentResult =
                presetEnrollmentCoordinator.onReady(
                    networkId = row.id,
                    isCurrent = isCurrent,
                ) { channel ->
                    client.send(
                        io.github.trevarj.motd.irc.proto
                            .IrcMessage(command = "JOIN", params = listOf(channel)),
                    )
                }
            if (enrollmentResult == EnrollmentJoinResult.FAILED) {
                Log.w(TAG, "One-shot Libera #motd JOIN write failed for network ${row.id}")
            }
            // Final self-heal for children whose loops died while the endpoint was down (startup
            // step 3A/3B): the primary revival now runs on the root's Registering edge, but Ready is
            // kept as the belt-and-braces pass — childrenNeedingReconnect makes both idempotent, so a
            // child already dialing (or revived by the earlier edge) is never touched here.
            if (row.role == NetworkRole.BOUNCER_ROOT) {
                reviveChildrenOf(row.id, isCurrent)
            }
            // A fresh direct socket has no channel membership. Restore only channels whose durable
            // self JOIN state is still true; explicit PART/KICK rows set joined=false. Bouncer children
            // remain entirely bouncer-managed. Skip re-JOINing whatever the server already replayed
            // (captured above) and send the rest as few JOIN lines as possible (comma-separated
            // channel lists, IRC allows this) rather than one command per channel: an unbatched burst
            // of dozens of JOINs queues up behind a shared connection's own outbound flood control
            // (observed with WeeChat's relay-irc) and delays unrelated traffic multiplexed over that
            // same connection for up to a minute.
            if (row.role == NetworkRole.DIRECT) {
                val remembered = recoveryReader.joinedChannels(row.id)
                if (remembered.isNotEmpty()) {
                    replayCapture.join()
                    val toJoin = channelsNeedingJoin(remembered, replayedChannels, client.isupport::normalize)
                    val (keyed, keyless) =
                        toJoin.partition {
                            inviteEnrollmentStore.channelKey(row.id, client.isupport.normalize(it)) != null
                        }
                    for (channel in keyed) {
                        if (!isCurrent()) return
                        val key = inviteEnrollmentStore.channelKey(row.id, client.isupport.normalize(channel))
                        client.send(
                            io.github.trevarj.motd.irc.proto.IrcMessage(
                                command = "JOIN",
                                params = listOfNotNull(channel, key),
                            ),
                        )
                    }
                    for (batch in chunkChannelsForJoin(keyless)) {
                        if (!isCurrent()) return
                        client.send(
                            io.github.trevarj.motd.irc.proto.IrcMessage(
                                command = "JOIN",
                                params = listOf(batch.joinToString(",")),
                            ),
                        )
                    }
                } else {
                    replayCapture.cancel()
                }
            } else {
                replayCapture.cancel()
            }
            if (!isCurrent()) return
            // A bound soju child becomes Ready before its post-bind feature CAP ACKs. Keep these
            // feature waiters alive for the exact connection rather than treating an early snapshot as
            // final. ConnectionActor cancels this scope as soon as Ready ends.
            coroutineScope {
                val initialReadMarkers =
                    async {
                        val negotiated = awaitReadMarkerCapabilityDecision(client)
                        if (negotiated && isCurrent()) {
                            reconcileReadMarkersForConnection(row, client, isCurrent)
                        }
                        negotiated
                    }
                // Runtime CAP NEW can introduce read-marker support after the entry-critical deferred
                // CAP decision. Keep that convergence alive for the connection without extending the
                // initial history barrier indefinitely on servers that do not support the extension.
                launch {
                    if (!initialReadMarkers.await()) {
                        awaitReadMarkerCapabilityAvailable(client)
                        if (isCurrent()) reconcileReadMarkersForConnection(row, client, isCurrent)
                    }
                }
                launch {
                    // One release per Ready session, whoever reaches it first. The catch-up hands the
                    // gate back as soon as its visible wave converges, so a chat opened during the
                    // paced overflow sweep is not held on the entry timeout by background work; the
                    // decision branch's own exit paths (unsupported verdict, cancellation, a throw)
                    // still guarantee the gate is released exactly once.
                    val releasedEntryGate = AtomicBoolean(false)
                    // Startup step 1: the catch-up no longer waits for read markers, so the gate itself
                    // must. The settlement clock starts HERE, at Ready, not at each release attempt —
                    // a bouncer that never answers its read-marker CAP REQ can therefore delay the gate
                    // by at most the same ceiling the old pre-catch-up wait had. Joining a cancelled
                    // session's async returns immediately, so the NonCancellable exit release cannot
                    // park on it.
                    val markerSettlement =
                        async {
                            withTimeoutOrNull(READ_MARKER_SETTLE_TIMEOUT_MS) { initialReadMarkers.join() }
                        }
                    val releaseEntryGate: suspend () -> Unit = {
                        releaseEntryGateAfterMarkers(
                            awaitMarkerSettlement = { markerSettlement.join() },
                            released = releasedEntryGate,
                            release = { registry.historyCatchUpFinished(row.id, generation) },
                        )
                    }
                    runHistoryCatchUpSession(
                        client = client,
                        isCurrent = isCurrent,
                        liveClient = { clientFor(row.id) },
                        releaseGate = releaseEntryGate,
                        catchUp = {
                            catchUpForConnection(row.id, client, onCatchUpConverged = releaseEntryGate)
                        },
                        backfill = {
                            historyResyncCoordinator.backfillTargets(row.id, client) {
                                clientFor(row.id) === client
                            }
                        },
                    )
                }
                launch {
                    // A bouncer child publishes its post-BIND CAP ACK after the first Ready snapshot.
                    // Keep an endpoint in a non-protecting state until that exact connection can prove
                    // it has re-registered it, rather than turning an early snapshot into a permanent
                    // false "unsupported" fallback.
                    if (settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH ||
                        pushPrefs.endpointFor(row.id) == null
                    ) {
                        return@launch
                    }
                    pushHealthStore.waitingForServer(row.id)
                    when {
                        client.hasCap(WEBPUSH_CAP) -> {
                            Unit
                        }

                        row.role == NetworkRole.BOUNCER_CHILD -> {
                            awaitCapabilityAvailable(client, WEBPUSH_CAP)
                        }

                        else -> {
                            pushHealthStore.capability(row.id, supported = false)
                            if (isCurrent()) evaluatePushMode()
                            return@launch
                        }
                    }
                    if (!isCurrent() || settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH) {
                        return@launch
                    }
                    webPushRegistrar.get().reRegisterIfNeeded(row.id)
                    if (isCurrent()) evaluatePushMode()
                }
            }
        }

        // -- catch-up -------------------------------------------------

        /**
         * Own reconnect catch-up for the lifetime of this exact client. Its caller already waited for
         * CHATHISTORY to appear, so this only retries actual transport/server failures.
         */
        private suspend fun catchUpForConnection(
            networkId: Long,
            client: IrcClient,
            // The Ready session's entry gate, handed back the moment the visible half of a pass has
            // converged rather than at the end of the whole loop. Null for a verification pass, which
            // owns no gate. See [HistoryResyncCoordinator.resyncNetwork]'s `onCatchUpConverged`.
            onCatchUpConverged: (suspend () -> Unit)? = null,
        ) {
            var attempt = 0
            // Decided once, before the first attempt, and for the whole catch-up: whether this
            // connection has ever proven itself. It is the same question the foreground checkpoint
            // asks, and the answer is what separates a real outage — a reconnect, a first-ever sync of
            // a fresh network — from a re-verification of a socket that already converged (the CAP NEW
            // re-arm). Only the former may show the user sync chrome, and even then only if discovery
            // reports something changed. Read before the loop because a converged attempt records
            // itself, and a retry must not turn silent halfway through.
            val chromeEligible = shouldRunForegroundVerification(completedCatchUps[networkId], client)
            while (clientFor(networkId) === client) {
                val buffers = openBuffers(networkId)
                // Read per attempt: the user can change the depth between a failure and its retry.
                val initialLookbackMs =
                    settings.settings
                        .first()
                        .historySyncDepth.lookbackMs
                when (
                    val result =
                        historyResyncCoordinator.resyncNetwork(
                            networkId = networkId,
                            openBuffers = buffers,
                            client = client,
                            isCurrent = { clientFor(networkId) === client },
                            initialLookbackMs = initialLookbackMs,
                            chromeEligible = chromeEligible,
                            onCatchUpConverged = onCatchUpConverged,
                        )
                ) {
                    is HistoryResyncState.Failed -> {
                        if (result is HistoryResyncState.Incomplete && result.awaitsTargetClassification) {
                            // Bounded, and with the ordinary retry budget behind it. A silent socket
                            // stays Ready for the ~120s the ping watchdog allows, so this branch is
                            // reachable on a connection that will never publish CHANTYPES at all; the
                            // unbounded wait that used to sit here parked this pass on that dead
                            // client's StateFlow for the rest of the process, and a job stuck in
                            // [catchUpJobs] used to outrank — and cancel — every later candidate.
                            val classified = awaitTargetClassification(client.targetClassificationReady)
                            if (clientFor(networkId) !== client) return
                            if (classified) continue
                            if (attempt >= CATCH_UP_MAX_ATTEMPTS) {
                                Log.w(
                                    TAG,
                                    "CHANTYPES never settled for network $networkId; giving up after " +
                                        "$attempt attempts",
                                )
                                historyResyncCoordinator.settleNetworkPass(networkId, result, client)
                                return
                            }
                            delay(catchUpRetryDelayMs(attempt++))
                            continue
                        }
                        if (result is HistoryResyncState.Incomplete || result is HistoryResyncState.Capped) {
                            diagnostics.record("history", "catch_up_incomplete") {
                                mapOf(
                                    "network_id" to networkId,
                                    "result" to result::class.simpleName,
                                    "retry_recommended" to shouldRetryIncompleteCatchUp(result),
                                    "error_fp" to diagnostics.fingerprint(result.reason),
                                )
                            }
                            if (shouldRetryIncompleteCatchUp(result)) {
                                if (clientFor(networkId) !== client) return
                                if (attempt >= CATCH_UP_MAX_ATTEMPTS) {
                                    Log.w(TAG, "CHATHISTORY catch-up gave up after $attempt attempts for network $networkId")
                                    historyResyncCoordinator.settleNetworkPass(networkId, result, client)
                                    return
                                }
                                val retryMs = catchUpRetryDelayMs(attempt++)
                                Log.w(
                                    TAG,
                                    "CHATHISTORY has not reached the latest message for network " +
                                        "$networkId; retrying in ${retryMs}ms: ${result.reason}",
                                )
                                delay(retryMs)
                                continue
                            }
                            // Non-retryable partial results are this server's best proof. Remember it,
                            // or the same healthy socket re-enumerates history on every foreground.
                            historyResyncCoordinator.settleNetworkPass(networkId, result, client)
                            if (terminalCatchUpCanVouchForConnection(result)) {
                                completedCatchUps[networkId] = CompletedCatchUp(client)
                            }
                            return
                        }
                        diagnostics.record("history", "catch_up_failed") {
                            mapOf(
                                "network_id" to networkId,
                                "attempt" to attempt,
                                "error_fp" to diagnostics.fingerprint(result.reason),
                            )
                        }
                        if (clientFor(networkId) !== client) return
                        if (attempt >= CATCH_UP_MAX_ATTEMPTS) {
                            Log.w(TAG, "CHATHISTORY catch-up gave up after $attempt attempts for network $networkId")
                            historyResyncCoordinator.settleNetworkPass(networkId, result, client)
                            return
                        }
                        val retryMs = catchUpRetryDelayMs(attempt++)
                        Log.w(TAG, "CHATHISTORY catch-up failed for network $networkId; retrying in ${retryMs}ms: ${result.reason}")
                        delay(retryMs)
                    }

                    else -> {
                        diagnostics.record("history", "catch_up_finished") {
                            mapOf(
                                "network_id" to networkId,
                                "attempts" to attempt,
                                "result" to result::class.simpleName,
                            )
                        }
                        // Only a pass that actually converged lets this socket vouch for itself at the
                        // next foreground; see [shouldRunForegroundVerification].
                        completedCatchUps[networkId] = CompletedCatchUp(client)
                        return
                    }
                }
            }
        }

        /** A registration JOIN can race the network target snapshot; seed history if that pass missed it. */
        private fun seedJoinedChannelHistory(
            buffer: BufferEntity,
            client: IrcClient,
        ) {
            scope.launch {
                if (clientFor(buffer.networkId) !== client) return@launch
                if (db.historyCursorDao().byRoom(buffer.id) != null) return@launch
                historyResyncCoordinator.reconcileBuffer(buffer, client) {
                    clientFor(buffer.networkId) === client
                }
            }
        }

        /**
         * Converge the durable local marker with the server maximum. A SET also fetches: IRCv3/soju
         * replies with its newer value when the local timestamp is stale. Reads performed without a
         * live socket therefore upload on the next connection instead of being silently lost.
         */
        private suspend fun reconcileReadMarkersForConnection(
            row: NetworkEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ) {
            if (row.role == NetworkRole.BOUNCER_ROOT || !client.hasReadMarkerCap()) return
            val requests = readMarkerSyncRequests(readMarkerRepository.storedForNetwork(row.id))
            coroutineScope {
                requests
                    .map { request ->
                        async {
                            if (!isCurrent()) return@async
                            val response =
                                try {
                                    awaitReadMarkerResponse(
                                        events = client.broadcastEvents,
                                        target = request.target,
                                        normalize = client.isupport::normalize,
                                        timeoutMs = READ_MARKER_RESPONSE_TIMEOUT_MS,
                                    ) {
                                        request.timestamp?.let { client.markRead(request.target, it) }
                                            ?: client.fetchReadMarker(request.target)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    null
                                } ?: return@async
                            val timestamp = response.timestamp ?: return@async
                            // ConnectionActor's long-lived collector persists the same event through
                            // EventProcessor. Wait until that durable max-only write is visible before
                            // allowing CHATHISTORY to populate unread-count queries.
                            bufferDao.observe(request.bufferId).first { buffer ->
                                buffer?.let {
                                    it.readMarkerTime?.let { marker -> marker >= timestamp } == true &&
                                        it.localReadAnchorTime?.let { anchor -> anchor >= timestamp } == true
                                } == true
                            }
                        }
                    }.awaitAll()
            }
        }

        /**
         * Wait until either read-marker extension is negotiated. Soju offers `draft/read-marker`
         * (MARKREAD) and/or `soju.im/read` (READ); a server exposing only the soju fallback would leave
         * a `draft/read-marker`-only waiter blocked forever, so accept either. Bouncer children publish
         * their post-bind CAP ACK via successive Ready snapshots, so the filter matches whenever either
         * cap lands.
         */
        private suspend fun awaitReadMarkerCapabilityDecision(client: IrcClient): Boolean {
            if (client.hasReadMarkerCap()) return true
            client.pendingFeatureCaps.first { pending ->
                client.hasReadMarkerCap() || pending.none(::isReadMarkerCap)
            }
            return client.hasReadMarkerCap()
        }

        private suspend fun awaitReadMarkerCapabilityAvailable(client: IrcClient) {
            if (client.hasReadMarkerCap()) return
            client.state.filterIsInstance<IrcClientState.Ready>().first { ready ->
                ready.caps.any(::isReadMarkerCap)
            }
        }

        /** True for either `draft/read-marker` or `soju.im/read`, with or without a `=value` suffix. */
        private fun isReadMarkerCap(cap: String): Boolean =
            cap == READ_MARKER_CAP || cap.startsWith("$READ_MARKER_CAP=") ||
                cap == SOJU_READ_CAP || cap.startsWith("$SOJU_READ_CAP=")

        private suspend fun openBuffers(networkId: Long): List<OpenBufferTarget> = bufferDao.openTargets(networkId).map { OpenBufferTarget(it.id, it.name, it.pinned) }

        private suspend fun normalize(
            networkId: Long,
            name: String,
        ): String = identityRules(networkId).normalize(name)

        private suspend fun identityRules(networkId: Long): IrcIdentityRules {
            val client = clientFor(networkId)
            return identityRulesFallback(
                live = client?.isupport?.identityRules,
                liveReady = client?.state?.value is IrcClientState.Ready,
                persisted = db.networkIdentityDao().byNetwork(networkId),
            )
        }

        // -- send paths ---------------------------------------------------------

        override suspend fun sendCommand(
            networkId: Long,
            originBufferId: Long,
            message: IrcMessage,
            channelContext: String?,
        ) {
            val buffer = bufferDao.observeById(originBufferId) ?: return
            if (buffer.networkId != networkId) return
            val client = clientFor(networkId) ?: return
            val awayMessage =
                if (message.command == "AWAY") {
                    message.params.firstOrNull()?.takeIf(String::isNotBlank)
                } else {
                    null
                }
            if (message.command == "AWAY") {
                if (awayMessage == null) {
                    pendingAwayMessages.remove(networkId)
                } else {
                    pendingAwayMessages[networkId] = awayMessage
                }
            }
            val label = newOutgoingLabel().takeIf { client.hasCap("labeled-response") }
            val ready = client.state.value as? IrcClientState.Ready
            val contextTag =
                channelContext?.takeIf { context ->
                    message.command == "NOTICE" && message.params.firstOrNull()?.let(client.isupport::isChannel) == false &&
                        client.isupport.isChannel(context) && ready != null &&
                        canSendClientTag(ready.caps, ready.isupport, "+channel-context")
                }
            val sessionId =
                eventProcessor.beginCommandResponse(
                    networkId = networkId,
                    bufferId = buffer.id,
                    command = message.command,
                    label = label,
                )
            val tags =
                message.tags +
                    listOfNotNull(
                        label?.let { "label" to it },
                        contextTag?.let { "+channel-context" to it },
                    )
            val tagged = message.copy(tags = tags)
            val sent =
                try {
                    client.sendIfConnected(tagged)
                } catch (cancelled: CancellationException) {
                    eventProcessor.cancelCommandResponse(sessionId)
                    if (message.command == "AWAY") pendingAwayMessages.remove(networkId)
                    throw cancelled
                } catch (error: Exception) {
                    diagnostics.record("connections", "command_write_failed") {
                        mapOf("network_id" to networkId, "command" to message.command, "error" to error::class.simpleName)
                    }
                    false
                }
            if (!sent) {
                eventProcessor.cancelCommandResponse(sessionId)
                if (message.command == "AWAY") pendingAwayMessages.remove(networkId)
            }
        }

        override suspend fun sendMessage(
            bufferId: Long,
            text: String,
            replyToEventId: Long?,
            channelContext: String?,
        ): SendAcceptance =
            sendLifecycle.sending {
                ensurePendingRecovered()
                val buffer =
                    bufferDao.observeById(bufferId)
                        ?: return@sending SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
                val network = networkDao.byId(buffer.networkId)
                if (network?.connectionTransport == ConnectionTransport.SIDECAR && !sidecarsEnabled) {
                    return@sending SendAcceptance.Rejected(SendRejectionReason.SIDECARS_DISABLED)
                }
                // The soju console is the one SERVER-typed room that accepts writes.
                if (buffer.type == BufferType.SERVER && !buffer.isBouncerConsole) {
                    return@sending SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER)
                }
                // The composer is disabled when a channel is parted, but joined can flip during a submit
                // (race) or be stale on a bouncer that never echoes the self-PART. Refuse to persist a
                // doomed outgoing row when local state already says we are not a member.
                if (buffer.type == BufferType.CHANNEL && !buffer.joined && buffer.pendingCloseAt == null) {
                    return@sending SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL)
                }
                val client = clientFor(buffer.networkId)
                val ready = client?.state?.value as? IrcClientState.Ready
                val parentId: Long? = replyToEventId
                val canonicalParent: MessageEntity? =
                    if (parentId != null) {
                        messageDao.byCanonicalId(parentId)
                    } else {
                        null
                    }
                val parent: MessageEntity? =
                    canonicalParent
                        ?.takeIf { candidate: MessageEntity -> candidate.bufferId == buffer.id }
                val replyTagAllowed =
                    parent?.msgid != null && ready != null &&
                        canSendClientTag(ready.caps, ready.isupport, "+reply")
                val delivery =
                    prepareReplyDelivery(
                        text = text,
                        replyToMsgid = parent?.msgid,
                        parentSender = parent?.sender,
                        bufferType = buffer.type,
                        visibleChannelPrefix = parent?.let { replyPrefs.config.first().visibleChannelPrefix } == true,
                        replyTagAllowed = replyTagAllowed,
                    )
                val bouncerServConsole = buffer.isBouncerConsole
                val preferLogicalMultiline =
                    client != null &&
                        ready != null &&
                        !bouncerServConsole &&
                        !delivery.text
                            .replace("\r\n", "\n")
                            .replace('\r', '\n')
                            .startsWith("/me ") &&
                        client.canSendMultilineMessage(delivery.text)
                val chunks =
                    prepareOutgoingMessageChunks(
                        delivery.text,
                        bouncerServConsole,
                        preferLogicalMultiline = preferLogicalMultiline,
                    )
                if (chunks.isEmpty()) {
                    return@sending SendAcceptance.Rejected(SendRejectionReason.INVALID_CONTENT)
                }
                val planned =
                    chunks.map { chunk ->
                        PlannedOutgoingChunk(chunk, newOutgoingLabel())
                    }
                currentCoroutineContext().ensureActive()
                // Persisted outside the send lock on purpose: the pending row's visibility must not queue
                // behind another send's wire write, whose blocking flush can wedge for seconds on a
                // degraded socket. The insert is already per-network serialized by the event sequencer,
                // and if two rapid sends race persist order against wire order below, the rows are
                // label-bound and re-timed by the authoritative echo, so the timeline settles to wire
                // order either way.
                val durable =
                    try {
                        withContext(NonCancellable) {
                            eventProcessor.persistOutgoingPlan(
                                bufferId = buffer.id,
                                sender =
                                    ready?.nick ?: client?.config?.nick
                                        ?: networkDao.byId(buffer.networkId)?.nick.orEmpty(),
                                events =
                                    planned.map {
                                        OutgoingEventPlan(
                                            label = it.label,
                                            text = it.chunk.displayText,
                                            kind = it.chunk.kind,
                                            ircFormattedText = it.chunk.ircFormattedText,
                                        )
                                    },
                                replyToEventId = parent?.id,
                                replyToMsgid = parent?.msgid,
                                channelContext = channelContext,
                            )
                        }
                    } catch (_: Exception) {
                        return@sending SendAcceptance.Rejected(SendRejectionReason.PERSISTENCE_FAILED)
                    }
                val eventIds = durable.map { it.eventId }
                // The lock now serializes only the wire: frames from concurrent sends must not interleave.
                sendLocks.getOrPut(buffer.networkId) { Mutex() }.withLock {
                    completeDurableAcceptance(
                        eventIds = eventIds,
                        transition = {
                            writeDurablePlan(
                                buffer = bufferDao.observeById(bufferId) ?: buffer,
                                client = client,
                                ready = ready,
                                planned = planned,
                                eventIds = eventIds,
                                replyToMsgid = delivery.wireReplyToMsgid,
                                channelContext = channelContext,
                            )
                        },
                        secondaryEffect = { notifyOutgoingAccepted(buffer.id) },
                        storedTexts = planned.map { it.chunk.ircFormattedText ?: it.chunk.displayText },
                    )
                }
            }

        override suspend fun retryMessage(eventId: Long): SendAcceptance =
            sendLifecycle.sending {
                ensurePendingRecovered()
                val original =
                    messageDao.byCanonicalId(eventId)
                        ?: return@sending SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
                val buffer =
                    bufferDao.observeById(original.bufferId)
                        ?: return@sending SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
                if (!isGenericRetryEligible(buffer, original)) {
                    return@sending SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
                }
                sendLocks.getOrPut(buffer.networkId) { Mutex() }.withLock {
                    val current =
                        messageDao.byCanonicalId(eventId)
                            ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
                    val currentBuffer =
                        bufferDao.observeById(current.bufferId)
                            ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
                    if (!isGenericRetryEligible(currentBuffer, current)) {
                        return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
                    }
                    if (networkDao.byId(currentBuffer.networkId)?.connectionTransport == ConnectionTransport.SIDECAR &&
                        !sidecarsEnabled
                    ) {
                        return@withLock SendAcceptance.Rejected(SendRejectionReason.SIDECARS_DISABLED)
                    }
                    if (currentBuffer.type == BufferType.CHANNEL && !currentBuffer.joined &&
                        currentBuffer.pendingCloseAt == null
                    ) {
                        return@withLock SendAcceptance.Rejected(SendRejectionReason.NOT_IN_CHANNEL)
                    }
                    val label = newOutgoingLabel()
                    currentCoroutineContext().ensureActive()
                    val retry =
                        try {
                            withContext(NonCancellable) { eventProcessor.beginRetry(current.id, label) }
                        } catch (_: Exception) {
                            return@withLock SendAcceptance.Rejected(SendRejectionReason.PERSISTENCE_FAILED)
                        } ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
                    val formattedText = retry.ircFormattedText
                    val retryPayload = formattedText ?: retry.text
                    val wireText =
                        if (retry.kind == MessageKind.ACTION) {
                            "\u0001ACTION $retryPayload\u0001"
                        } else {
                            retryPayload
                        }
                    val planned =
                        listOf(
                            PlannedOutgoingChunk(
                                OutgoingMessageChunk(wireText, retry.text, retry.kind, formattedText),
                                label,
                            ),
                        )
                    completeDurableAcceptance(
                        eventIds = listOf(retry.id),
                        transition = transition@{
                            val retryBuffer =
                                bufferDao.observeById(retry.bufferId)
                                    ?: return@transition ImmediateWireAcceptance.DISCONNECTED
                            val client = clientFor(retryBuffer.networkId)
                            val ready = client?.state?.value as? IrcClientState.Ready
                            val parentId: Long? = retry.replyToEventId
                            val parent: MessageEntity? =
                                if (parentId != null) {
                                    messageDao.byCanonicalId(parentId)
                                } else {
                                    null
                                }
                            val wireReply =
                                parent?.msgid?.takeIf {
                                    ready != null && canSendClientTag(ready.caps, ready.isupport, "+reply")
                                }
                            writeDurablePlan(
                                retryBuffer,
                                client,
                                ready,
                                planned,
                                listOf(retry.id),
                                wireReply,
                                channelContext = retry.channelContext,
                            )
                        },
                        secondaryEffect = { notifyOutgoingAccepted(currentBuffer.id) },
                    )
                }
            }

        private data class PlannedOutgoingChunk(
            val chunk: OutgoingMessageChunk,
            val label: String,
        )

        private fun newOutgoingLabel(): String = "motd-${UUID.randomUUID()}"

        private suspend fun writeDurablePlan(
            buffer: BufferEntity,
            client: IrcClient?,
            ready: IrcClientState.Ready?,
            planned: List<PlannedOutgoingChunk>,
            eventIds: List<Long>,
            replyToMsgid: String?,
            forceLegacy: Boolean = false,
            channelContext: String? = null,
        ): ImmediateWireAcceptance {
            if (planned.size != eventIds.size) {
                eventProcessor.failPendingEvents(eventIds)
                return ImmediateWireAcceptance.FAILED
            }
            if (client == null || ready == null || clientFor(buffer.networkId) !== client) {
                eventProcessor.failPendingEvents(eventIds)
                return ImmediateWireAcceptance.DISCONNECTED
            }
            if (!forceLegacy && planned.size == 1 && eventIds.size == 1) {
                val item = planned.single()
                if (requiresMultilineWire(item.chunk) && !client.canSendMultilineMessage(item.chunk.wireText)) {
                    return replanAndWriteLegacyFallback(
                        buffer = buffer,
                        client = client,
                        ready = ready,
                        eventId = eventIds.single(),
                        oldLabel = item.label,
                        text = item.chunk.ircFormattedText ?: item.chunk.displayText,
                        replyToMsgid = replyToMsgid,
                        channelContext = channelContext,
                    )
                }
            }
            return transmitDurableOutgoingPlan(
                eventIds = eventIds,
                write = { index ->
                    val item = planned[index]
                    if (
                        client.sendMessage(
                            buffer.ircTarget,
                            item.chunk.wireText,
                            replyToMsgid,
                            item.label,
                            forceLegacy,
                            channelContext,
                        )
                    ) {
                        ImmediateWireAcceptance.ACCEPTED
                    } else {
                        ImmediateWireAcceptance.DISCONNECTED
                    }
                },
                onWritten = { index ->
                    val item = planned[index]
                    if (client.hasCap("echo-message")) {
                        armEchoTimeout(buffer.id, item.label)
                    } else {
                        eventProcessor.confirmIfStillPending(buffer.id, item.label)
                    }
                },
                failRemaining = eventProcessor::failPendingEvents,
            )
        }

        private suspend fun notifyOutgoingAccepted(bufferId: Long) {
            try {
                chatSoundPlayer.onOutgoingAccepted(bufferId)
            } catch (error: Exception) {
                diagnostics.record("chat_sound", "outgoing_failed") {
                    mapOf("buffer_id" to bufferId, "error" to error::class.simpleName)
                }
            }
        }

        private fun armEchoTimeout(
            bufferId: Long,
            label: String,
        ) {
            val key = "$bufferId:$label"
            registry.armEchoTimeout(key, ECHO_TIMEOUT_MS) {
                eventProcessor.failIfStillPending(bufferId, label)
            }
        }

        override suspend fun sendTyping(
            bufferId: Long,
            state: String,
        ) {
            val buffer = bufferDao.observeById(bufferId) ?: return
            clientFor(buffer.networkId)?.sendTyping(buffer.ircTarget, state)
        }

        override suspend fun sendReact(
            bufferId: Long,
            msgid: String,
            emoji: String,
        ) {
            val buffer = bufferDao.observeById(bufferId) ?: return
            val client = clientFor(buffer.networkId) ?: return
            val ready = client.state.value as? IrcClientState.Ready ?: return
            val canonicalBufferId = buffer.id
            val sender = ready.nick
            val identityRules = client.isupport.identityRules
            val nickKey = identityRules.actorKey(sender, account = null)
            val account = db.userDao().byNick(buffer.networkId, identityRules.normalize(sender))?.account
            val actorKey = identityRules.actorKey(sender, account)
            val previous =
                reactionMutations.findOwn(
                    canonicalBufferId,
                    msgid,
                    listOf(actorKey, nickKey).distinct(),
                    emoji,
                )
            val removing = previous != null
            // Recheck at the mutation boundary so a stale sheet cannot create local-only state after a
            // capability or CLIENTTAGDENY change.
            if (!canSendReactionTags(ready.caps, ready.isupport, removing)) return

            val reaction =
                ReactionEntity(
                    bufferId = canonicalBufferId,
                    targetMsgid = msgid,
                    actorKey = actorKey,
                    sender = sender,
                    emoji = emoji,
                    serverTime = System.currentTimeMillis(),
                )
            mutateReaction(reactionMutations, previous, reaction) { kind ->
                if (kind == ReactionMutationKind.REMOVE) {
                    client.send(reactionTagMessage(buffer.ircTarget, msgid, emoji, kind))
                } else {
                    client.sendReact(buffer.ircTarget, msgid, emoji)
                }
            }
        }

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
        ): Boolean {
            val client = clientFor(networkId) ?: return false
            val normalizedChannel = client.isupport.normalize(channel)
            val suppliedKey = key?.takeIf { it.isNotBlank() }
            val effectiveKey = suppliedKey ?: inviteEnrollmentStore.channelKey(networkId, normalizedChannel)
            val accepted =
                client.sendIfConnected(
                    io.github.trevarj.motd.irc.proto.IrcMessage(
                        command = "JOIN",
                        params = listOfNotNull(channel, effectiveKey),
                    ),
                )
            if (accepted && suppliedKey != null) {
                inviteEnrollmentStore.putChannelKey(networkId, normalizedChannel, suppliedKey)
            }
            return accepted
        }

        override suspend fun redactMessage(
            bufferId: Long,
            msgid: String,
        ): Boolean =
            try {
                val buffer = bufferDao.observeById(bufferId)
                writeMessageRedactionIfReady(buffer, msgid, buffer?.let { clientFor(it.networkId) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        override suspend fun inviteToChannel(
            bufferId: Long,
            nick: String,
        ): Boolean =
            try {
                val buffer = bufferDao.observeById(bufferId)
                writeChannelInviteIfReady(buffer, nick, buffer?.let { clientFor(it.networkId) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        override suspend fun acceptInvite(messageId: Long) {
            val initial = messageDao.byCanonicalId(messageId) ?: return
            val canonicalMessageId = initial.id
            val payload = InvitePayloadV1.decode(initial.eventPayload) ?: return
            val buffer = bufferDao.observeById(initial.bufferId) ?: return
            if (buffer.type != BufferType.CHANNEL ||
                normalize(buffer.networkId, buffer.ircTarget) != normalize(buffer.networkId, payload.channel)
            ) {
                return
            }
            performInviteJoin(
                initialState = initial.inviteState,
                claim = { fromState ->
                    messageDao.compareAndSetInviteState(
                        canonicalMessageId,
                        fromState,
                        InviteState.JOINING,
                    ) > 0
                },
                awaitReady = {
                    if (connectionStates.value[buffer.networkId] !is IrcClientState.Ready) connect(buffer.networkId)
                    withTimeoutOrNull(INVITE_READY_TIMEOUT_MS) {
                        connectionStates
                            .map { it[buffer.networkId] }
                            .filterIsInstance<IrcClientState.Ready>()
                            .first()
                    } != null
                },
                stillJoining = {
                    messageDao.byId(canonicalMessageId)?.inviteState == InviteState.JOINING
                },
                sendJoin = {
                    val client =
                        clientFor(buffer.networkId)
                            ?: throw InviteJoinFailure("connection unavailable")
                    client.send(
                        io.github.trevarj.motd.irc.proto.IrcMessage(
                            command = "JOIN",
                            params = listOf(payload.channel),
                        ),
                    )
                },
                fail = { reason -> messageDao.failInvite(canonicalMessageId, reason) },
            )
        }

        override suspend fun dismissInvite(messageId: Long) {
            val canonicalMessageId = messageDao.byCanonicalId(messageId)?.id ?: return
            if (messageDao.dismissInvite(canonicalMessageId) > 0) {
                messageNotifier.onInvitationResolved(canonicalMessageId)
            }
        }

        override suspend fun requestMembers(
            bufferId: Long,
            force: Boolean,
        ) {
            val buffer = bufferDao.observeById(bufferId) ?: return
            val canonicalBufferId = buffer.id
            if (buffer.type != BufferType.CHANNEL || !buffer.joined) return
            if (!force && rosterStates.value[bufferId] == RosterLoadState.LOADED) return
            val client =
                clientFor(buffer.networkId) ?: run {
                    setRosterState(bufferId, RosterLoadState.FAILED)
                    return
                }
            if (client.state.value !is IrcClientState.Ready) {
                setRosterState(bufferId, RosterLoadState.FAILED)
                return
            }

            val candidate =
                scope.async(start = CoroutineStart.LAZY) {
                    setRosterState(bufferId, RosterLoadState.LOADING)
                    val completed =
                        withTimeoutOrNull(ROSTER_REQUEST_TIMEOUT_MS) {
                            coroutineScope {
                                val names =
                                    async(start = CoroutineStart.UNDISPATCHED) {
                                        client.broadcastEvents.filterIsInstance<IrcEvent.Names>().first {
                                            client.isupport.normalize(it.channel) == client.isupport.normalize(buffer.ircTarget)
                                        }
                                    }
                                val whox = async { client.whox(buffer.ircTarget) }
                                try {
                                    client.send(
                                        io.github.trevarj.motd.irc.proto.IrcMessage(
                                            command = "NAMES",
                                            params = listOf(buffer.ircTarget),
                                        ),
                                    )
                                    names.await()
                                    whox.await()
                                } finally {
                                    names.cancel()
                                    whox.cancel()
                                }
                            }
                            true
                        } == true
                    if (clientFor(buffer.networkId) === client) {
                        if (!completed) {
                            eventProcessor.cancelRosterSnapshot(buffer.networkId, canonicalBufferId)
                        }
                        setRosterState(bufferId, rosterStateAfterExplicitRefresh(completed))
                    }
                }
            val existing = rosterRequests.putIfAbsent(bufferId, candidate)
            val request = existing ?: candidate.also { it.start() }
            if (existing != null) candidate.cancel()
            try {
                request.await()
            } finally {
                rosterRequests.remove(bufferId, request)
            }
        }

        override suspend fun partChannel(
            bufferId: Long,
            reason: String?,
        ) {
            sendPart(bufferId, reason)
        }

        override suspend fun partChannelForClose(
            bufferId: Long,
            reason: String?,
        ): Boolean =
            try {
                sendPart(bufferId, reason)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        override suspend fun setChannelTopic(
            bufferId: Long,
            topic: String,
        ): Boolean =
            try {
                sendTopic(bufferId, topic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }

        private suspend fun sendPart(
            bufferId: Long,
            reason: String?,
        ): Boolean {
            val buffer = bufferDao.observeById(bufferId) ?: return false
            val client = clientFor(buffer.networkId)
            val accepted = writeChannelPartIfReady(buffer, reason, client)
            if (accepted && client != null) {
                inviteEnrollmentStore.putChannelKey(
                    buffer.networkId,
                    client.isupport.normalize(buffer.ircTarget),
                    null,
                )
            }
            return accepted
        }

        private suspend fun sendTopic(
            bufferId: Long,
            topic: String,
        ): Boolean {
            val buffer = bufferDao.observeById(bufferId) ?: return false
            return writeChannelTopicIfReady(buffer, topic, clientFor(buffer.networkId))
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long {
            val norm = normalize(networkId, nick)
            return db.withTransaction {
                val room = bufferStore.getOrCreate(networkId, norm, nick, BufferType.QUERY)
                val canonical = requireNotNull(bufferDao.observeById(room.id))
                bufferDao.reviveQuery(canonical.id)
                canonical.id
            }
        }

        override suspend fun ensureServerBuffer(networkId: Long): Long {
            // "*" is stable under both casemapping normalizers, so no normalize() needed.
            return bufferStore
                .getOrCreate(
                    networkId,
                    SERVER_BUFFER_NAME,
                    networkDao.byId(networkId)?.name ?: "Server",
                    BufferType.SERVER,
                ).id
        }

        override suspend fun markRead(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            val target = resolveAndAdvanceCurrentReadTarget(db, bufferId, anchor) ?: return
            val buffer = target.buffer
            val canonicalBufferId = buffer.id
            messageNotifier.onRead(canonicalBufferId, target.anchor)
            AutoFollowTrace.record("local_markread", canonicalBufferId) {
                "marker=${target.anchor.serverTime}:${target.anchor.eventId}"
            }
            // SERVER buffers use "*" as their name, which is not a valid MARKREAD target; the Room
            // read-marker advance above still runs. Skip the wire send for them.
            if (buffer.type == BufferType.SERVER) return
            val authoritative = target.authoritative ?: return
            val currentRemote = bufferDao.observeById(canonicalBufferId)?.readMarkerTime
            if (currentRemote != null && authoritative.timestamp <= currentRemote) return
            val client = clientFor(buffer.networkId)
            AutoFollowTrace.record("wire_markread_out", canonicalBufferId) {
                "marker=${authoritative.timestamp} connected=${client != null} " +
                    "supported=${client?.hasReadMarkerCap() == true}"
            }
            client?.markRead(buffer.ircTarget, authoritative.timestamp)
        }

        override suspend fun evaluatePushMode() {
            // Re-run the push teardown check after per-network endpoint changes; no-op off push mode.
            // Fixes v1: the settings-collect fired before any endpoint existed and never re-ran.
            if (settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH) maybeStopForPush()
        }

        // -- TOFU cert trust -----------------------------------------

        /** Publish a prompt for [networkId], deduping so re-attempts don't stack duplicates. */
        private fun publishCertPrompt(
            networkId: Long,
            ex: CertUntrustedException,
        ) {
            val prompt =
                CertPrompt(
                    networkId = networkId,
                    host = ex.host,
                    port = ex.port,
                    sha256 = ex.sha256,
                    subject = ex.subject,
                    issuer = ex.issuer,
                    notBefore = ex.notBefore,
                    notAfter = ex.notAfter,
                    changed = ex.changed,
                )
            _certPrompts.value = _certPrompts.value.filterNot { it.networkId == networkId } + prompt
        }

        override suspend fun trustCert(prompt: CertPrompt) {
            certStore.pin(prompt.host, prompt.port, prompt.sha256)
            // Pinning is keyed by host:port, so trusting one cert unblocks EVERY network parked on that
            // same endpoint: a soju bouncer root plus all its bound children tunnel through the same
            // host:port. Reconnect all cert-failed networks whose recorded failure targets this
            // endpoint, not just the one whose prompt was shown — otherwise siblings stay Failed until
            // an app restart (#48). The prompt's own network is always included because its recorded
            // failure carries this host:port.
            // Always include the prompt's own network: its failure is normally in certFailures, but a
            // concurrent reconcile could have cleared it, and Trust must still reconnect it.
            val affected = networksSharingCertEndpoint(prompt.host, prompt.port, certFailures) + prompt.networkId
            for (id in affected) {
                _certPrompts.value = _certPrompts.value.filterNot { it.networkId == id }
                certFailures.remove(id)
                connect(id)
            }
        }

        override fun dismissCertPrompt(prompt: CertPrompt) {
            _certPrompts.value = _certPrompts.value.filterNot { it.networkId == prompt.networkId }
            certFailures.remove(prompt.networkId)
            // Network stays disconnected; the actor already parked itself.
        }

        // -- connectivity callback ----------------------------------------------

        private fun connectivityObserverJob(): Job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return@launch
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            registry.networkAvailable()
                        }

                        override fun onLost(network: Network) {
                            registry.networkLost()
                        }
                    }
                val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
                try {
                    awaitCancellation()
                } finally {
                    if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
                }
            }

        companion object {
            // Stable logcat tag for reconnect catch-up failures.
            private const val TAG = "MotdCatchUp"
            const val READ_MARKER_CAP = "draft/read-marker"
            const val SIDECAR_WAKE_TIMEOUT_MS = 8_000L
            const val CHATHISTORY_CAP = "draft/chathistory"
            const val SOJU_READ_CAP = "soju.im/read"
            const val WEBPUSH_CAP = "soju.im/webpush"
            const val READ_MARKER_RESPONSE_TIMEOUT_MS = 5_000L
            const val ECHO_TIMEOUT_MS = 30_000L
            const val INVITE_READY_TIMEOUT_MS = 30_000L
            const val ROSTER_REQUEST_TIMEOUT_MS = 15_000L
            const val EMBEDDED_REALITY_BACKGROUND_GRACE_MS = 5 * 60 * 1000L
            const val MAX_BYTES = 400

            // Stable, casemapping-invariant name for the per-network SERVER buffer.
            const val SERVER_BUFFER_NAME = "*"

            /** Whether MainActivity/BootReceiver should keep the foreground service alive. */
            fun shouldRunService(
                deliveryPersistent: Boolean,
                hasNetworks: Boolean,
            ): Boolean = deliveryPersistent && hasNetworks
        }
    }

/**
 * Whether a visibly-foreground process should have the thin keeper service running.
 *
 * PERSISTENT_SOCKET's socket is only actually persistent while the foreground service holds the
 * process out of the cached/frozen band, and that decision used to be made once, in
 * `MainActivity.onCreate`. Three reachable states then left a live socket with no service behind
 * it: the first network added after that check, a runtime UNIFIED_PUSH → PERSISTENT_SOCKET switch,
 * and a re-entry after the user tapped the status notification's Stop (singleTop routes that
 * through `onNewIntent`, which never re-armed it). Under UNIFIED_PUSH the keeper is still only for
 * embedded REALITY, whose transport cannot survive a frozen process during the background grace.
 * Same pure-function testing style as [shouldApplyDozePushHandoff].
 */
internal fun shouldArmForegroundKeeper(
    deliveryMode: DeliveryMode,
    hasWantedEmbeddedReality: Boolean,
    hasWantedNetwork: Boolean,
): Boolean =
    when (deliveryMode) {
        DeliveryMode.UNIFIED_PUSH -> {
            hasWantedEmbeddedReality
        }

        DeliveryMode.PERSISTENT_SOCKET -> {
            ConnectionManagerImpl.shouldRunService(
                deliveryPersistent = true,
                hasNetworks = hasWantedNetwork,
            )
        }
    }

internal fun shouldApplyDozePushHandoff(
    appForeground: Boolean,
    deviceIdle: Boolean,
    deliveryMode: DeliveryMode,
): Boolean = !appForeground && deviceIdle && deliveryMode == DeliveryMode.UNIFIED_PUSH

internal fun shouldResumeSocketsAfterDozeExit(
    appForeground: Boolean,
    wasDeviceIdle: Boolean,
    deviceIdle: Boolean,
    deliveryMode: DeliveryMode,
): Boolean = !appForeground && wasDeviceIdle && !deviceIdle && deliveryMode == DeliveryMode.UNIFIED_PUSH

/**
 * Diagnostic fields for a Ready connection (`connections`/`state_changed`). The `caps` value is
 * clipped to the diagnostic sanitizer's 256-char budget, so a long soju cap list silently loses
 * its tail; the boolean gates that decide feature availability are therefore derived here as
 * their own fields. Cap names are compared with any `=value` suffix stripped, matching
 * [io.github.trevarj.motd.irc.client.IrcClient.hasCap].
 */
internal fun readyStateDiagnosticFields(state: IrcClientState.Ready): Map<String, Any?> {
    val capNames = state.caps.mapTo(mutableSetOf()) { it.substringBefore('=') }
    return mapOf(
        "caps" to state.caps.sorted().joinToString(","),
        "isupport_keys" to
            state.isupport.keys
                .sorted()
                .joinToString(","),
        "caps_count" to state.caps.size,
        "cap_labeled_response" to ("labeled-response" in capNames),
        "cap_soju_search" to ("soju.im/search" in capNames),
        "cap_chathistory" to ("draft/chathistory" in capNames),
        // Mirrors IrcClient.searchAvailable: the state is already Ready, so the cap decides.
        "search_available" to ("soju.im/search" in capNames),
    )
}

internal fun wantedNetworkUsesEmbeddedReality(
    networkId: Long,
    all: List<NetworkEntity>,
): Boolean {
    val byId = all.associateBy { it.id }
    val row = byId[networkId] ?: return false
    val endpoint =
        if (row.role == NetworkRole.BOUNCER_CHILD) {
            row.parentId?.let(byId::get) ?: row
        } else {
            row
        }
    return endpoint.obfsMode == ObfsMode.EMBEDDED_REALITY
}

/**
 * Networks [ConnectionManagerImpl.stopAll] has to retire in the history coordinator. The known
 * networks are the obvious half. A tracked verification job can outlive its row — the network was
 * deleted while its pass was still in flight — and cancelling that job on its own leaves the waiting
 * badges and progress entry its pass painted up until the next pass or the end of the process, so
 * every network with a tracked job is retired too. Same pure-function testing style as
 * [wantedNetworkIds] / [shouldRunForegroundVerification].
 */
internal fun networksToRetire(
    known: Set<Long>,
    trackedCatchUps: Set<Long>,
): Set<Long> = known + trackedCatchUps

/**
 * A catch-up pass that converged, tagged with the exact socket that served it.
 *
 * The connection is held WEAKLY. This map is keyed by network and lives for the process, so a
 * strong reference would pin a retired [IrcClient] — and everything it holds — for the whole
 * lifetime of that network. Losing the referent is safe in the only direction that matters: a
 * cleared reference reads as "not this connection", which re-verifies rather than skipping.
 */
internal class CompletedCatchUp(
    client: Any,
) {
    private val client = WeakReference(client)

    /** True when [candidate] is the exact connection whose pass converged. */
    fun servedBy(candidate: Any): Boolean = client.get() === candidate
}

/** A tracked foreground verification pass, tagged with the exact connection it verifies. */
internal data class CatchUpJob(
    val client: Any,
    val job: Job,
)

/**
 * Single-flight ownership for [ConnectionManagerImpl.launchCatchUp].
 *
 * A live pass only outranks a new candidate when it is verifying the SAME connection; that is the
 * case the single flight exists for, and the coordinator's own request coalescing handles the
 * overlap. Keying ownership on the network alone let a job that could no longer make progress —
 * one pinned to a client the actor already replaced — silently cancel every later candidate for
 * that network, disabling foreground history verification until the process was killed. Same pure
 * function testing style as [wantedNetworkIds] / [shouldRunForegroundVerification].
 */
internal fun chooseCatchUpOwner(
    existing: CatchUpJob?,
    candidate: CatchUpJob,
): CatchUpJob =
    if (existing != null && existing.job.isActive && existing.client === candidate.client) {
        existing
    } else {
        candidate
    }

/**
 * Bounded wait for a connection's target classification (CHANTYPES / end of the registration burst).
 *
 * [IrcClient.targetClassificationReady] is a StateFlow on ONE client, and a client that dies before
 * 005/376 arrives conflates its `stop()` write to the same `false` and then emits nothing ever
 * again — a StateFlow has no completion signal, so an unbounded collector on it parks forever.
 * Returns false on expiry so the caller falls back to its ordinary attempt-counted retry path
 * instead of holding a process-lifetime coroutine open on a dead socket. The bound matches the one
 * [HistoryResyncCoordinator.resyncNetwork] and `backfillTargets` already use.
 */
internal suspend fun awaitTargetClassification(
    ready: StateFlow<Boolean>,
    timeoutMs: Long = HistoryResyncCoordinator.TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS,
): Boolean {
    if (ready.value) return true
    return withTimeoutOrNull(timeoutMs) { ready.first { it } } ?: false
}

/**
 * How long a Ready session waits for a terminal answer about `draft/chathistory` before releasing
 * the entry gate. Generous, because it only ever elapses on a connection that left a deferred
 * `CAP REQ` unanswered; the CAP NEW re-arm in [ConnectionManagerImpl.onReadySession] keeps the late
 * case covered, so expiring here costs a delay rather than the catch-up itself.
 */
internal const val HISTORY_CAP_DECISION_TIMEOUT_MS = 15_000L

/**
 * How long the entry gate waits for read-marker convergence before releasing anyway. Marker
 * settlement is entry-critical (the frozen unread target prefers the server marker), but a server
 * that never answers its CAP REQ must not be able to hold chat entry for the whole Ready session.
 */
internal const val READ_MARKER_SETTLE_TIMEOUT_MS = 10_000L

/**
 * How long a DIRECT-role Ready session waits, right after registration, for the server to replay a
 * synthetic self JOIN of a remembered channel before falling back to self-JOINing it. Some
 * DIRECT-configured stateful backends (e.g. WeeChat's relay-irc listener) unconditionally mirror
 * already-joined channels back to a freshly connecting client without being asked; waiting here
 * avoids firing a redundant, unpaced JOIN burst that would otherwise queue behind that backend's own
 * outgoing flood control and delay unrelated traffic sharing the same underlying connection.
 */
internal const val JOIN_REPLAY_SETTLE_TIMEOUT_MS = 1_500L

/**
 * Which of [remembered] still need a self-JOIN: those the server did NOT already replay (per
 * [replayedNormalized], a set of channel names normalized the same way as [remembered] via
 * [normalize]) during the settle window.
 */
internal fun channelsNeedingJoin(
    remembered: List<String>,
    replayedNormalized: Set<String>,
    normalize: (String) -> String,
): List<String> = remembered.filter { normalize(it) !in replayedNormalized }

/**
 * Byte budget for one batched reconnect JOIN line's channel-list param, leaving room for the
 * `JOIN ` command word and the trailing CRLF within [IrcMessage]'s 512-byte wire limit. A fixed
 * channel-count batch size can't guarantee this: e.g. 15 legal 50-byte channel names would already
 * overflow 512 bytes on their own, and [IrcMessage.serialize] throws rather than truncate — so
 * batches must be sized by actual UTF-8 byte length, not channel count.
 */
private const val JOIN_LINE_BUDGET_BYTES = 500

/**
 * Greedily groups [channels] into comma-joined batches (IRC allows a channel list in one JOIN
 * command) that each fit [JOIN_LINE_BUDGET_BYTES], accounting for the comma separators added by
 * `joinToString(",")`. A single channel name that alone exceeds the budget still gets its own
 * batch — [IrcMessage.serialize] is the final arbiter and will reject it if truly unsendable.
 */
internal fun chunkChannelsForJoin(channels: List<String>): List<List<String>> {
    val batches = mutableListOf<List<String>>()
    var current = mutableListOf<String>()
    var currentBytes = 0
    for (channel in channels) {
        val channelBytes = channel.toByteArray(Charsets.UTF_8).size
        val separatorBytesIfAppended = if (current.isEmpty()) 0 else 1
        if (current.isNotEmpty() && currentBytes + separatorBytesIfAppended + channelBytes > JOIN_LINE_BUDGET_BYTES) {
            batches += current
            current = mutableListOf()
            currentBytes = 0
        }
        val separatorBytes = if (current.isEmpty()) 0 else 1
        current += channel
        currentBytes += separatorBytes + channelBytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

/**
 * One Ready session's entry-gate release: the gate opens only when BOTH hold — the caller decided
 * a release is due (the visible wave converged, the decision declined, or the branch exited) AND
 * read markers settled or their bounded wait expired.
 *
 * This is where the marker barrier moved when the catch-up stopped waiting for it (startup step 1):
 * CHATHISTORY fetches start at Ready, but the frozen unread target must still prefer the server
 * marker, so chat ENTRY — which `historyCatchUpPending` gates — keeps the marker dependency. The
 * caller starts [awaitMarkerSettlement]'s clock at Ready and bounds it with
 * [READ_MARKER_SETTLE_TIMEOUT_MS], so a convergence release can be delayed by at most that ceiling.
 * [released] makes the release idempotent across the convergence callback, the decision branch's
 * exits, and its NonCancellable finally. Extracted so the both-conditions property is unit-testable
 * without a Ready session.
 */
internal suspend fun releaseEntryGateAfterMarkers(
    awaitMarkerSettlement: suspend () -> Unit,
    released: AtomicBoolean,
    release: suspend () -> Unit,
) {
    awaitMarkerSettlement()
    if (released.compareAndSet(false, true)) release()
}

/** `draft/chathistory`, with or without a `=value` suffix. */
internal fun isChatHistoryCap(cap: String): Boolean =
    cap == ConnectionManagerImpl.CHATHISTORY_CAP ||
        cap.startsWith("${ConnectionManagerImpl.CHATHISTORY_CAP}=")

/**
 * Wait for a terminal answer about CHATHISTORY on one connection.
 *
 * Settle-aware, in exactly the shape `awaitReadMarkerCapabilityDecision` uses, rather than a bare
 * wait for the next Ready snapshot: a bouncer child publishes Ready before its post-BIND CAP ACK
 * lands, but a server that simply does not offer the extension publishes no further snapshot at
 * all, so a snapshot wait never returned there.
 *
 * And bounded, because settle-aware is still not enough on its own: [pendingFeatureCaps] only sheds
 * a cap on ACK/NAK/DEL, so a deferred `CAP REQ :draft/chathistory` the server never answers strands
 * the wait — and with it `historyCatchUpPending`, which every entry-critical wait in the chat screen
 * blocks on — for the whole Ready session. That is C5's defect reached by a second route. On expiry
 * the gate releases with a negative verdict and [rearmHistoryCatchUp] stays armed, so a late ACK
 * still gets its catch-up.
 *
 * Same seam-injected testing style as [awaitTargetClassification].
 */
internal suspend fun awaitHistoryCapDecision(
    availability: () -> HistoryAvailability,
    pendingFeatureCaps: StateFlow<Set<String>>,
    timeoutMs: Long = HISTORY_CAP_DECISION_TIMEOUT_MS,
): Boolean {
    when (availability()) {
        is HistoryAvailability.Ready -> return true
        HistoryAvailability.Unsupported -> return false
        HistoryAvailability.NegotiatingOrOffline -> Unit
    }
    withTimeoutOrNull(timeoutMs) {
        pendingFeatureCaps.first { pending ->
            availability() !is HistoryAvailability.NegotiatingOrOffline ||
                pending.none(::isChatHistoryCap)
        }
    }
    return availability() is HistoryAvailability.Ready
}

/**
 * One Ready session's entry decision for CHATHISTORY: settle the capability, run the catch-up,
 * release the user-facing gate, then trickle the backfill. Read markers no longer sit in front of
 * the catch-up (startup step 1); their entry-criticality lives inside the caller's gate closure —
 * see [releaseEntryGateAfterMarkers].
 *
 * Extracted from [ConnectionManagerImpl.onReadySession] because the defects here live purely in the
 * ORDER of those four steps, which no connection-level test can reach:
 *
 *  - [releaseGate] must fire exactly once and on EVERY exit, cancellation and an unexpected throw
 *    included. `historyCatchUpPending` is what the chat screen's entry waits block on, so a gate
 *    that outlives its branch is a permanently unreachable chat for the rest of the session. The
 *    caller may share the same (idempotent) release with [catchUp], which hands it back as soon as
 *    its visible wave converges; this branch releasing again afterwards is then a no-op.
 *  - [claimed] must resolve at the DECISION, never at the end of the branch. The branch continues
 *    into [backfill], a paced trickle that runs for as long as an account has targets; resolving
 *    the claim behind it would leave [rearmHistoryCatchUp] queued behind a background job — the
 *    exact stall the re-arm exists to prevent.
 *  - [backfill] runs only behind a catch-up that actually happened. A connection with no CHATHISTORY
 *    has nothing to enumerate, and starting it there only spends the classification wait to find
 *    that out.
 *
 * [stillCurrent] is the caller's "this session still owns this connection" check; a false answer is
 * not a failure, it just means someone else owns the work now.
 */
internal suspend fun decideHistoryCatchUp(
    awaitHistoryReady: suspend () -> Boolean,
    stillCurrent: () -> Boolean,
    claimed: CompletableDeferred<Boolean>,
    releaseGate: suspend () -> Unit,
    catchUp: suspend () -> Unit,
    backfill: suspend () -> Unit,
) {
    var gateReleased = false

    suspend fun release() {
        if (gateReleased) return
        gateReleased = true
        if (stillCurrent()) releaseGate()
    }
    try {
        val historyReady = awaitHistoryReady()
        // Read markers deliberately do NOT gate the branch anymore (startup step 1): the fetches
        // start as soon as CHATHISTORY is decided, which on a bouncer child shaves the deferred
        // post-welcome CAP round-trip off every reconnect's first page. Marker settlement remains
        // entry-critical — the frozen unread target must still prefer the server marker — but that
        // barrier lives in [releaseGate] itself ([releaseEntryGateAfterMarkers]), which every exit
        // of this branch and the wave-convergence release both share.
        if (!stillCurrent()) return
        if (!historyReady) {
            release()
            return
        }
        claimed.complete(true)
        catchUp()
        release()
        // Only after the user-facing catch-up gate releases: trickle in targets older than the
        // initial-sync window. Cancelled with this Ready session; resumes durably.
        if (stillCurrent()) backfill()
    } finally {
        // A no-op once the claim above resolved it. Every other exit — an unsupported verdict, a
        // superseded session, cancellation, a throw — means this session claimed no catch-up, which
        // is exactly what re-arms the waiter.
        claimed.complete(false)
        // NonCancellable because the release is a registry round-trip, and cancellation is precisely
        // the case that used to strand the gate.
        withContext(NonCancellable) { release() }
    }
}

/**
 * The CAP NEW stand-in for an entry catch-up the decision declined.
 *
 * RegistrationStateMachine puts `draft/chathistory` in the PRE-bind CAP REQ set, so it never lands
 * in the post-welcome deferred set [awaitHistoryCapDecision] settles on. A bouncer that advertises
 * the extension only through a post-welcome CAP NEW therefore reached Ready with an empty pending
 * set, settled on "unsupported", and skipped its Ready-session catch-up ENTIRELY — nothing was left
 * to re-trigger when the ACK finally landed.
 *
 * Never runs when the decision already [claimed] the catch-up, so this cannot double-issue a pass;
 * and it waits on [claimed] rather than on the decision branch's completion, so it is not queued
 * behind that branch's backfill.
 */
internal suspend fun rearmHistoryCatchUp(
    claimed: Deferred<Boolean>,
    awaitCapability: suspend () -> Unit,
    stillCurrent: () -> Boolean,
    catchUp: suspend () -> Unit,
    backfill: suspend () -> Unit,
) {
    if (claimed.await()) return
    awaitCapability()
    if (!stillCurrent()) return
    catchUp()
    // Same ordering the entry decision uses: the paced older-than-window trickle runs strictly
    // after the catch-up, and only behind one that actually happened.
    if (stillCurrent()) backfill()
}

/** Wait until [capability] is ACKed on this exact connection; returns immediately if it already is. */
internal suspend fun awaitCapabilityAvailable(
    client: IrcClient,
    capability: String,
) {
    if (client.hasCap(capability)) return
    client.state.filterIsInstance<IrcClientState.Ready>().first { ready ->
        ready.caps.any { it == capability || it.startsWith("$capability=") }
    }
}

/** This connection's terminal answer about CHATHISTORY; see [awaitHistoryCapDecision]. */
internal suspend fun awaitHistoryReady(client: IrcClient): Boolean = awaitHistoryCapDecision({ client.historyAvailability }, client.pendingFeatureCaps)

/**
 * One Ready session's whole history catch-up: the entry decision and the CAP NEW re-arm that backs
 * it up, wired to the connection they belong to.
 *
 * Extracted from [ConnectionManagerImpl.onReadySession] so the WIRING is reachable by a test with a
 * real client, not only the two decisions it composes. What has to hold here is which connection
 * property each branch consults — the entry decision settles on [IrcClient.historyAvailability] and
 * [IrcClient.pendingFeatureCaps], the re-arm waits for `draft/chathistory` itself — and pointing
 * either at something that never converges is silent: the session simply never catches up.
 *
 * [ownsConnection] is deliberately ONE predicate shared by both branches: the registry generation
 * is live AND the actor has not swapped the client underneath us. Both halves matter — a pass
 * pinned to a replaced client can no longer verify anything, and the entry gate is keyed on the
 * generation.
 *
 * Both branches are left untracked in `catchUpJobs` on purpose. They are this Ready session's
 * passes and must die with the session, whereas a tracked pass runs on the process-lifetime
 * application scope and would outlive the connection it verifies. Overlap with a concurrent
 * foreground verification is handled a layer down: the coordinator coalesces flights per
 * (network, connection).
 */
internal suspend fun runHistoryCatchUpSession(
    client: IrcClient,
    isCurrent: () -> Boolean,
    liveClient: () -> IrcClient?,
    releaseGate: suspend () -> Unit,
    catchUp: suspend () -> Unit,
    backfill: suspend () -> Unit,
): Unit =
    coroutineScope {
        // True once this session's entry decision has claimed the catch-up; false once it is known to
        // have declined it. Completed at the DECISION rather than at the end of the branch, and never
        // by awaiting the branch itself: the branch goes on to the paced backfill trickle, which runs
        // for as long as an account has targets, and the CAP NEW re-arm must not queue behind it.
        // Always completed, cancellation included, so the re-arm can never be stranded by the branch
        // it observes.
        val claimed = CompletableDeferred<Boolean>()
        val ownsConnection = { isCurrent() && liveClient() === client }
        launch {
            decideHistoryCatchUp(
                awaitHistoryReady = { awaitHistoryReady(client) },
                stillCurrent = ownsConnection,
                claimed = claimed,
                releaseGate = releaseGate,
                catchUp = catchUp,
                backfill = backfill,
            )
        }
        // Runtime CAP NEW can introduce CHATHISTORY after the entry-critical decision, and
        // RegistrationStateMachine puts chathistory in the PRE-bind CAP REQ set, so it is never in the
        // post-welcome deferred set that [awaitHistoryReady] settles on: a bouncer that only advertises
        // the extension later reached Ready with an empty pending set, decided "unsupported", and
        // skipped this Ready session's catch-up entirely — not merely late. Deliberately OUTSIDE the
        // entry gate: entry must never block on a capability that may never arrive.
        launch {
            rearmHistoryCatchUp(
                claimed = claimed,
                awaitCapability = { awaitCapabilityAvailable(client, ConnectionManagerImpl.CHATHISTORY_CAP) },
                stillCurrent = ownsConnection,
                catchUp = catchUp,
                // The entry branch is the only other caller of the backfill, so without this an account
                // that reaches history only through CAP NEW would never enumerate targets older than
                // the initial-sync window at all — not late, never.
                backfill = backfill,
            )
        }
    }

/**
 * Trust a socket that never died: verify only when THIS connection has no converged pass of its own.
 *
 * A continuously-Ready socket received everything the server sent, live, however long ago its pass
 * converged — so elapsed time is not evidence of anything and no longer takes part in the decision.
 * What used to justify the 30 s window (a frozen process might have missed lines) is covered by the
 * connection identity instead: a server buffer that fills while the process is frozen kills the
 * socket through the ping watchdog, the actor reconnects with a NEW client, and this predicate
 * answers true for it. A reconnect can never inherit its predecessor's proof.
 */
internal fun shouldRunForegroundVerification(
    recorded: CompletedCatchUp?,
    currentClient: Any,
): Boolean = recorded == null || !recorded.servedBy(currentClient)

internal fun catchUpRetryDelayMs(attempt: Int): Long = (2_000L * (1L shl attempt.coerceIn(0, 4))).coerceAtMost(30_000L)

/**
 * Whole-network catch-up retries are bounded: each attempt re-runs full TARGETS discovery, so an
 * account soju 0.10.x can never prove complete would otherwise re-enumerate forever. Per-buffer
 * sync status keeps the manual retry affordance after the cap.
 */
internal const val CATCH_UP_MAX_ATTEMPTS = 5

internal fun shouldRetryIncompleteCatchUp(result: HistoryResyncState.Failed): Boolean = result is HistoryResyncState.Incomplete && result.retryRecommended

internal fun terminalCatchUpCanVouchForConnection(result: HistoryResyncState.Failed): Boolean =
    result is HistoryResyncState.Capped ||
        (
            result is HistoryResyncState.Incomplete &&
                !result.awaitsTargetClassification && !result.retryRecommended
        )

/** Wire requests needed to converge local markers with a read-marker-capable server. */
internal fun readMarkerSyncRequests(markers: List<BufferReadMarker>): List<ReadMarkerSyncRequest> = markers.map { ReadMarkerSyncRequest(it.bufferId, it.target, it.timestamp) }

internal data class ReadMarkerSyncRequest(
    val bufferId: Long,
    val target: String,
    val timestamp: Long?,
)

/** Subscribe before sending so even an immediate MARKREAD response cannot be missed. */
internal suspend fun awaitReadMarkerResponse(
    events: Flow<IrcEvent>,
    target: String,
    normalize: (String) -> String,
    timeoutMs: Long,
    request: suspend () -> Unit,
): IrcEvent.ReadMarker? =
    coroutineScope {
        val expected = normalize(target)
        val response =
            async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) {
                    events.filterIsInstance<IrcEvent.ReadMarker>().first { normalize(it.target) == expected }
                }
            }
        request()
        response.await()
    }

/**
 * Pure wanted-set computation for [ConnectionManagerImpl.reconcile], extracted for
 * unit tests. A network is wanted when the sticky user intent (if present) or, absent an intent,
 * its `autoConnect` flag is true — and it is not an orphan BOUNCER_CHILD (a child with no parentId
 * has no root row to inherit the bouncer endpoint and credentials from, so it can never dial).
 */

/**
 * Build the [IrcClientConfig] for one network row (§soju bouncer-networks). For a
 * BOUNCER_CHILD the physical socket is the *bouncer's*, not the upstream network's: the transport
 * endpoint (host/port/tls) and the account SASL credentials are taken from the resolved [root].
 * soju's pre-welcome BOUNCER BIND path mutates capabilities in a way that can stall on Android's
 * embedded transport, so children select their upstream with the stable account/network SASL authcid
 * form that soju also supports for bouncer networks.
 * Falls back to the child's own fields when the root cannot be resolved (orphan rows are excluded
 * from the wanted set upstream, so this is defensive only). Extracted for tests.
 */
internal fun buildChildConfig(
    row: NetworkEntity,
    root: NetworkEntity?,
): IrcClientConfig {
    // The endpoint + account identity a bound child inherits from its bouncer root.
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) (root ?: row) else row
    val childNetworkSelector = row.name.takeIf { row.role == NetworkRole.BOUNCER_CHILD && root != null }
    val saslUser =
        if (childNetworkSelector != null && !endpoint.saslUser.isNullOrBlank()) {
            "${endpoint.saslUser}/$childNetworkSelector"
        } else {
            endpoint.saslUser
        }
    val sasl =
        runCatching {
            SaslMechanism.valueOf(endpoint.saslMechanism)
        }.getOrDefault(SaslMechanism.NONE)
    val useNickServ = row.role == NetworkRole.DIRECT && sasl == SaslMechanism.NONE
    return IrcClientConfig(
        host = endpoint.host,
        port = endpoint.port,
        tls = endpoint.tls,
        // Identity (NICK/USER) stays the child's so the drawer/nick reflect the bound network.
        nick = row.nick,
        username = row.username,
        realname = row.realname,
        sasl = sasl,
        saslUser = saslUser,
        saslPassword = endpoint.saslPassword,
        serverPassword = endpoint.serverPassword,
        initialAwayMessage = row.initialAwayMessage,
        nickServPassword = row.nickServPassword.takeIf { useNickServ },
        nickServIdentifySyntax =
            row.nickServIdentifySyntax?.let {
                runCatching { NickServIdentifySyntax.valueOf(it) }.getOrNull()
            } ?: NickServIdentifySyntax.NICK_PASSWORD,
        nickServRecoveryCommands =
            if (useNickServ && row.nickServRecoveryEnabled) {
                (row.nickServRecoverySequence ?: "REGAIN").split(',').map(String::trim)
            } else {
                emptyList()
            },
        bouncerNetId = null,
        extraCaps = if (row.connectionTransport == ConnectionTransport.SIDECAR) setOf(SidecarContract.IRC_CAPABILITY) else emptySet(),
        // WSS transport follows the physical endpoint: the bouncer's wsUrl for a bound child.
        wsUrl = endpoint.wsUrl,
    )
}

/**
 * Resolve the proxy for the physical connection endpoint. EMBEDDED_REALITY intentionally has no
 * persisted SOCKS host/port: the VLESS link starts libbox and supplies a per-link loopback
 * endpoint. Keep that path separate from the legacy SOCKS validation so it can never fall through
 * to direct TCP or be rejected for its intentionally-null legacy columns.
 */
internal data class TransportProxyResolution(
    val proxy: java.net.Proxy?,
    val error: String?,
    val release: () -> Unit = {},
)

internal fun resolveTransportProxy(
    endpoint: NetworkEntity,
    localSocksProvider: LocalSocksProvider,
    ownerKey: String? = null,
): TransportProxyResolution {
    if (endpoint.obfsMode == ObfsMode.EMBEDDED_REALITY) {
        val link =
            VlessLink.parse(endpoint.obfsLink.orEmpty()).getOrElse { error ->
                return TransportProxyResolution(
                    proxy = null,
                    error = "Embedded REALITY configuration: ${error.message ?: "invalid VLESS link"}",
                )
            }
        // Keep each physical IRC actor on its own libbox service. Sharing one SOCKS inbound across
        // the root and a bouncer child makes the native core serialize their TLS streams at the
        // capability transition; both sessions appear Ready but post-registration writes vanish.
        val lease =
            localSocksProvider.acquire(link, ownerKey = ownerKey ?: endpoint.id.toString()).getOrElse { error ->
                return TransportProxyResolution(
                    proxy = null,
                    error = "Embedded REALITY configuration: ${error.message ?: "provider unavailable"}",
                )
            }
        // start() validates its returned port. Retain this guard so a future provider change
        // cannot turn a bad local endpoint into a direct connection.
        val proxy =
            proxyForNetwork(ObfsMode.SOCKS5, lease.endpoint.host, lease.endpoint.port)
                ?: return TransportProxyResolution(
                    proxy = null,
                    error = "Embedded REALITY configuration: invalid local SOCKS endpoint",
                    release = lease.release,
                )
        return TransportProxyResolution(proxy = proxy, error = null, release = lease.release)
    }

    val error =
        proxyConfigurationErrorForNetwork(
            endpoint.obfsMode,
            endpoint.proxyHost,
            endpoint.proxyPort,
        )
    return TransportProxyResolution(
        proxy =
            if (error == null) {
                proxyForNetwork(endpoint.obfsMode, endpoint.proxyHost, endpoint.proxyPort)
            } else {
                null
            },
        error = error,
    )
}

/**
 * Connection-affecting fingerprint for one network row. A bound child inherits its physical
 * endpoint, bouncer SASL, client certificate, WSS URL, and proxy from [root], so those effective
 * fields must participate too. Otherwise a root proxy/WSS edit leaves existing child actors on the
 * old (possibly direct) socket. Extracted for unit tests.
 */
internal fun networkFingerprint(
    row: NetworkEntity,
    root: NetworkEntity? = null,
): String {
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) root ?: row else row
    return "${endpoint.host}:${endpoint.port}:${endpoint.tls}:${row.nick}:${row.username}:${row.realname}:" +
        "${endpoint.saslMechanism}:${endpoint.saslUser}:${endpoint.saslPassword}:${endpoint.serverPassword}:" +
        "${row.nickServPassword}:${row.nickServIdentifySyntax}:${row.nickServRecoveryEnabled}:" +
        "${row.nickServRecoverySequence}:${row.initialAwayMessage}:" +
        "${row.bouncerNetId}:${row.connectionTransport}:${row.sidecarPackage}:${row.sidecarService}:${row.sidecarAccountId}:" +
        "${endpoint.clientCertAlias}:${endpoint.wsUrl}:${endpoint.obfsMode}:${endpoint.proxyHost}:${endpoint.proxyPort}:${endpoint.obfsLink}"
}

/** Configuration failures cannot become healthy through reconnect/backoff; only an effective
 * fingerprint change can release their actor park. [IrcClient] prefixes thrown transport errors. */
internal fun isConfigurationFailure(reason: String): Boolean =
    reason.startsWith("connect failed: SOCKS5 proxy ") ||
        reason.startsWith("connect failed: WebSocket transport cannot ") ||
        reason.startsWith("connect failed: Embedded REALITY configuration")

/**
 * Network ids parked on the given `host:port` cert endpoint (#48). When a TOFU cert is
 * trusted, the pin is stored keyed by host:port, so every network whose latest untrusted-cert
 * failure targets that same endpoint becomes reconnectable — a soju bouncer root and all its bound
 * children share one physical host:port, so trusting once must reconnect the whole set, not only the
 * network whose prompt was shown. Host match is case-insensitive to mirror the cert store's pin key.
 * Extracted for unit tests.
 */
internal fun networksSharingCertEndpoint(
    host: String,
    port: Int,
    certFailures: Map<Long, CertUntrustedException>,
): Set<Long> =
    certFailures
        .asSequence()
        .filter { (_, ex) -> ex.host.equals(host, ignoreCase = true) && ex.port == port }
        .map { it.key }
        .toSet()

/**
 * Whether [ConnectionManagerImpl.ensureActor] must drop and rebuild an *existing* actor for a
 * wanted network. Extracted as a pure decision so the "stay/reconnect smoothly" logic (#43) is unit
 * tested in isolation (same style as [wantedNetworkIds] / [childrenToReconnect]).
 *
 * Rebuild when:
 *  - the connection-affecting config changed ([fingerprintChanged]) — the pre-existing restart-on-edit
 *    behavior; or
 *  - the actor is stale: its reconnect loop finished ([actorAlive] == false) so it can never recover
 *    on its own. The actor's loop only exits on a fatal Failed or a cert-untrust park, so a dead loop
 *    that is NOT [awaitingCertTrust] is a terminally-Failed actor that a plain reconcile must revive.
 *    [lastState] is passed for clarity/testability of that terminal condition.
 *
 * Never rebuild when [awaitingCertTrust]: that park is intentional and is resolved by
 * [ConnectionManagerImpl.trustCert] / dismiss, not by reconcile — rebuilding would re-loop the
 * handshake and re-spam the prompt.
 *
 * A healthy actor (Ready), one still Connecting/Registering, or one actively backing-off between
 * retries all report [actorAlive] == true and are left alone, so reconcile can never storm them.
 */
internal fun shouldRebuildActor(
    fingerprintChanged: Boolean,
    actorAlive: Boolean,
    lastState: IrcClientState?,
    awaitingCertTrust: Boolean,
): Boolean {
    // The cert-trust park is owned by trustCert/dismiss; never rebuild it from reconcile.
    if (awaitingCertTrust) return false
    if (fingerprintChanged) return true
    // A live loop owns its own recovery (Connecting/Registering/Ready or backing-off between
    // retries); only a dead loop is stuck and needs reviving.
    if (actorAlive) return false
    // Dead loop, not a cert park: it terminated on a Failed (typically fatal, e.g. SASL) and sits
    // parked with a completed job. lastState reflects that terminal Failed; rebuild to revive it.
    return lastState is IrcClientState.Failed || lastState == null
}

internal fun wantedNetworkIds(
    all: List<NetworkEntity>,
    userIntents: Map<Long, Boolean>,
): Set<Long> =
    all
        .asSequence()
        .filter { userIntents[it.id] ?: it.autoConnect }
        // A BOUNCER_CHILD deliberately does NOT wait for its root's connection state (startup
        // step 3B): a child is its own socket to the bouncer endpoint, selecting its upstream with
        // the persisted account/network SASL authcid (buildChildConfig sets bouncerNetId = null),
        // so everything it needs — endpoint, credentials, network name — lives in the DB rows.
        // Children therefore dial in parallel with their roots on cold start instead of paying the
        // root's full tunnel + registration serially first. When the shared endpoint is down they
        // all fail together and each actor's exponential backoff bounds the probing; a child whose
        // loop died terminally during an outage is revived by reviveChildrenOf on the root's
        // Registering/Ready edges. Only an orphan (no parentId) stays excluded: it has no root row
        // to inherit the bouncer endpoint from.
        .filter { it.role != NetworkRole.BOUNCER_CHILD || it.parentId != null }
        .map { it.id }
        .toSet()

/**
 * BOUNCER_CHILD ids to revive when their [rootId] proves the bouncer endpoint reachable again
 * (its actor's Registering edge, and the Ready edge as final self-heal). A child is an independent
 * socket to the same bouncer endpoint — not a stream inside the root's transport — so it is never
 * blocked by the root protocol-wise; but an absent, dead, or terminally disconnected/failed child
 * cannot recover on its own, and the root's successful dial is the cheapest proof that redialing
 * the shared endpoint is worth it now. A child that is
 * Connecting, Registering, or Ready is deliberately excluded: its live loop owns that transition,
 * and forcing a rebuild would race registration or disconnect a healthy session.
 *
 * A child is reconnected when it is that root's own child (`parentId == rootId`) and is *wanted*:
 * its sticky user intent (if present), else its `autoConnect` flag, is true. This respects an
 * explicit user disconnect (sticky `userIntents=false`) so we never resurrect a child the user
 * turned off. Same pure-function testing style as [wantedNetworkIds] / [networksSharingCertEndpoint].
 */
internal fun childrenNeedingReconnect(
    rootId: Long,
    all: List<NetworkEntity>,
    userIntents: Map<Long, Boolean>,
    actorAlive: Map<Long, Boolean>,
    states: Map<Long, IrcClientState>,
): Set<Long> =
    all
        .asSequence()
        .filter { it.role == NetworkRole.BOUNCER_CHILD && it.parentId == rootId }
        .filter { userIntents[it.id] ?: it.autoConnect }
        .filter { child ->
            actorAlive[child.id] != true ||
                states[child.id] is IrcClientState.Failed ||
                states[child.id] == IrcClientState.Disconnected
        }.map { it.id }
        .toSet()
