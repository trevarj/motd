package io.github.trevarj.motd.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.OutgoingEventPlan
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.obfs.VlessLink
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.NO_IMPLICIT_NAMES_ALIASES
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.client.preferredNoImplicitNames
import io.github.trevarj.motd.irc.client.preferredExtendedMonitor
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.ext.MonitorCommands
import io.github.trevarj.motd.irc.ext.MonitorSupport
import io.github.trevarj.motd.irc.ext.monitorSupport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.push.PushHealthStore
import io.github.trevarj.motd.push.pushSuspendedNetworkIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class OutgoingMessageChunk(
    val wireText: String,
    val displayText: String,
    val kind: MessageKind,
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
): List<OutgoingMessageChunk> {
    if (isBouncerServ && ('\r' in text || '\n' in text || text.toByteArray(Charsets.UTF_8).size > maxBytes)) {
        return emptyList()
    }
    val lines = text.split(Regex("\\r\\n|\\r|\\n")).filter { it.isNotEmpty() }
    if (lines.isEmpty()) return emptyList()

    val isAction = text.startsWith("/me ")
    return lines.flatMapIndexed { lineIndex, line ->
        val lineIsAction = isAction && lineIndex == 0
        if (lineIsAction) {
            val displayText = line.removePrefix("/me ")
            splitUtf8(displayText, maxBytes - ACTION_OVERHEAD_BYTES).map { chunk ->
                OutgoingMessageChunk(
                    wireText = "\u0001ACTION $chunk\u0001",
                    displayText = chunk,
                    kind = MessageKind.ACTION,
                )
            }
        } else {
            splitUtf8(line, maxBytes).map { chunk ->
                OutgoingMessageChunk(
                    wireText = chunk,
                    displayText = if (isBouncerServ) redactBouncerServCommand(chunk) else chunk,
                    kind = MessageKind.PRIVMSG,
                )
            }
        }
    }
}

/** Split [text] into chunks of at most [maxBytes] UTF-8 bytes without splitting code points. */
internal fun splitUtf8(text: String, maxBytes: Int): List<String> {
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
            val codePointBytes = String(Character.toChars(codePoint))
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
        val wireAcceptance = try {
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
            val waitForQuiesce = stateLock.withLock {
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
    ): T = quiesceLock.withLock {
        val completion = CompletableDeferred<Unit>()
        val waitForDrain = stateLock.withLock {
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

internal fun isGenericRetryEligible(buffer: BufferEntity, message: MessageEntity): Boolean =
    message.isSelf && message.failed && message.msgid == null &&
        buffer.type != BufferType.SERVER &&
        !buffer.ircTarget.equals("BouncerServ", ignoreCase = true) &&
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
    val current = TimelineAnchor(event.serverTime, event.id)
    val stored = buffer.localReadAnchorTime?.let {
        TimelineAnchor(it, buffer.localReadAnchorEventId ?: 0L)
    }
    if (stored != null && current < stored) return null
    return CurrentReadTarget(
        buffer = buffer,
        anchor = current,
        authoritative = db.messageDao().authoritativeChatAtOrBefore(
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
): CurrentReadTarget? = db.withTransaction {
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
): SendAcceptance.Accepted = withContext(NonCancellable) {
    val wireAcceptance = try {
        transition()
    } catch (_: Exception) {
        ImmediateWireAcceptance.FAILED
    }
    try {
        secondaryEffect()
    } catch (_: Exception) {
        // The durable timeline state is authoritative; presentation effects are best effort.
    }
    SendAcceptance.Accepted(eventIds, wireAcceptance)
}

private const val ACTION_OVERHEAD_BYTES = 9 // SOH + "ACTION " + SOH

internal fun identityRulesFallback(
    live: IrcIdentityRules?,
    liveReady: Boolean,
    persisted: NetworkIdentityEntity?,
): IrcIdentityRules = live.takeIf { liveReady } ?: persisted?.identityRules ?: IrcIdentityRules()

/**
 * Hilt @Singleton connection subsystem (plans/05). Outlives the foreground service — the service
 * is merely its keeper. Spawns one [ConnectionActor] per connectable network row (BOUNCER_ROOT
 * gets the root actor; each BOUNCER_CHILD a bound actor copying the root host/SASL with its
 * bouncerNetId; DIRECT one each), reconciles on networkDao changes, and reacts to deliveryMode.
 */
@Singleton
class ConnectionManagerImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val db: MotdDatabase,
    private val eventProcessor: EventProcessor,
    private val settings: DataStoreSettingsRepository,
    private val pushPrefs: PushPrefs,
    private val replyPrefs: ReplyPrefs,
    private val certStore: CertTrustStore,
    private val baseTransportFactory: TransportFactory,
    private val localSocksProvider: LocalSocksProvider,
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
    private val registry = ConnectionRegistry(
        scope = scope,
        actorFactory = ::createActor,
        isConfigurationFailure = ::isConfigurationFailure,
    )

    // Latest full network set, kept so buildClient can resolve a BOUNCER_CHILD's root row (its
    // bouncer endpoint + account SASL) without a suspend DB read. Updated on every reconcile.
    @Volatile private var networksById: Map<Long, NetworkEntity> = emptyMap()

    // Sticky in-memory user intent per network (plans/16 §4): true = force-connect,
    // false = force-disconnect, absent = follow autoConnect. Survives reconcile emissions so a
    // manual disconnect/connect is not undone by the next DB write. Reset by stopAll (not persisted).
    private val userIntents = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    override val connectionStates: StateFlow<Map<Long, IrcClientState>> = registry.connectionStates

    private val _rosterStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = _rosterStates.asStateFlow()
    private val rosterRequests = java.util.concurrent.ConcurrentHashMap<Long, Deferred<Unit>>()

    private val _presenceStates = MutableStateFlow<Map<PresenceKey, PresenceState>>(emptyMap())
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> = _presenceStates.asStateFlow()
    private val monitoredTargets = java.util.concurrent.ConcurrentHashMap<Long, Map<String, String>>()
    private val monitorInitialized = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val monitorLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private val sendLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private val sendLifecycle = DurableSendLifecycle()
    private val pendingRecoveryLock = Mutex()
    private var pendingRecovered = false

    private val stsStore = StsPolicyStore(settings)

    private val _certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
    override val certPrompts: StateFlow<List<CertPrompt>> = _certPrompts.asStateFlow()

    // Latest untrusted-cert failure per network, set from the handshake trust manager and consumed
    // by the actor to park in "awaiting trust" instead of backoff-looping.
    private val certFailures = java.util.concurrent.ConcurrentHashMap<Long, CertUntrustedException>()

    @Volatile private var appForeground = false
    @Volatile private var deviceIdle = false
    private val pushSuspendedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val backgroundRetention = BackgroundConnectionRetention(
        scope = scope,
        graceMs = EMBEDDED_REALITY_BACKGROUND_GRACE_MS,
    )

    override fun clientFor(networkId: Long): IrcClient? =
        (registry.snapshot.value.actors[networkId]?.connection as? IrcClientConnection)?.client

    // -- lifecycle ----------------------------------------------------------

    override suspend fun startAll() {
        var startBegan = false
        try {
            val shouldStart = sendLifecycle.quiesce {
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
            reconcile(networkDao.observeAll().first())
            val reconcileJob = scope.launch {
                networkDao.observeAll().collect { all ->
                    reconcile(all)
                }
            }
            // Delivery-mode reaction: UNIFIED_PUSH tears verified sockets down only after Android
            // enters Doze. Merely switching away from MOTD keeps active conversations connected.
            val deliveryModeJob = scope.launch {
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
                        reconcile(networkDao.observeAll().first())
                    }
                }
            }
            val monitorDesiredJob = scope.launch {
                combine(settings.settings, bufferDao.observeChatList()) { currentSettings, rows ->
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
     * limitation — plans/11 risk 6).
     */
    private suspend fun maybeStopForPush() {
        if (!shouldApplyDozePushHandoff(
                appForeground = appForeground,
                deviceIdle = deviceIdle,
                deliveryMode = settings.settings.first().deliveryMode,
            )
        ) return
        if (backgroundRetention.isRetaining) return
        val all = networkDao.observeAll().first()
        val wanted = wantedNetworkIds(all, userIntents, connectionStates.value)
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
        if (settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH &&
            hasWantedEmbeddedReality(all)
        ) {
            // Start while the app is visibly foreground. Android 12+ may reject a foreground-
            // service launch after onStop; pre-arming the thin keeper makes the subsequent grace
            // a real retention guarantee instead of relying on an unprotected process timer.
            startForegroundKeeper()
        }
        reconnectStale()
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
     * Doze entry is the UnifiedPush hand-off boundary. Doze exit intentionally leaves a completed
     * hand-off suspended until MOTD foregrounds; reconnecting sockets behind another foreground
     * app would defeat the battery-saving mode without improving delivery.
     */
    internal fun onDeviceIdleModeChanged(idle: Boolean) {
        deviceIdle = idle
        if (idle && !appForeground) scope.launch { maybeStopForPush() }
    }

    private suspend fun beginEmbeddedRealityBackgroundRetention(all: List<NetworkEntity>) {
        if (appForeground || settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH ||
            !hasWantedEmbeddedReality(all) || backgroundRetention.graceElapsed
        ) return
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
        wantedNetworkIds(all, userIntents, connectionStates.value).any { networkId ->
            wantedNetworkUsesEmbeddedReality(networkId, all)
        }

    /**
     * The grace keeper is no longer needed once every wanted network has healthy push delivery.
     * Outside Doze we leave actors alone, preserving the existing keep-until-Doze semantics.
     */
    private suspend fun releaseKeeperWhenPushCanOwnEverything() {
        if (appForeground || settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH) return
        val all = networkDao.observeAll().first()
        val wanted = wantedNetworkIds(all, userIntents, connectionStates.value)
        val pushOwned = pushSuspendedNetworkIds(
            all,
            wanted,
            pushPrefs.endpoints(),
            pushHealthStore.snapshot(),
        )
        if (wanted.all { it in pushOwned }) stopForegroundKeeper()
    }

    private fun startForegroundKeeper() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                android.content.Intent(appContext, IrcForegroundService::class.java),
            )
        }.onFailure { Log.w(TAG, "unable to start socket fallback service", it) }
    }

    private fun stopForegroundKeeper() {
        val stopIntent = android.content.Intent(appContext, IrcForegroundService::class.java)
        appContext.stopService(stopIntent)
    }

    override suspend fun stopAll() = withContext(NonCancellable) {
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
            pushSuspendedIds.clear()
            rosterRequests.values.forEach { it.cancel() }
            rosterRequests.clear()
            _rosterStates.value = emptyMap()
            monitoredTargets.clear()
            monitorInitialized.clear()
            monitorLocks.clear()
            _presenceStates.value = emptyMap()
            eventProcessor.shutdown()
        }
    }

    override suspend fun connect(networkId: Long) {
        val row = networkDao.byId(networkId) ?: return
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
        registry.disconnect(networkId)
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
     *  and the sticky user-intent map (plans/16 §4). */
    private suspend fun reconcile(all: List<NetworkEntity>) {
        val deletedIds = networksById.keys - all.mapTo(mutableSetOf()) { it.id }
        // Keep a synchronous lookup so buildClient can resolve a child's root bouncer row.
        networksById = all.associateBy { it.id }
        val wantedIds = wantedNetworkIds(all, userIntents, registry.snapshot.value.states) - pushSuspendedIds
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
        deletedIds.forEach { eventProcessor.evictNetwork(it) }
    }

    private fun createActor(row: NetworkEntity, generation: Long): ConnectionLifecycleActor {
        val fp = fingerprint(row)
        return ConnectionActor(
            networkId = row.id,
            scope = scope,
            connectionFactory = { buildConnection(row) },
            onState = { id, state ->
                diagnostics.record("connections", "state_changed") {
                    buildMap {
                        put("network_id", id)
                        put("state", state::class.simpleName)
                        when (state) {
                            is IrcClientState.Ready -> {
                                put("caps", state.caps.sorted().joinToString(","))
                                put("isupport_keys", state.isupport.keys.sorted().joinToString(","))
                            }
                            is IrcClientState.Failed -> {
                                put("fatal", state.fatal)
                                put("error_fp", diagnostics.fingerprint(state.reason))
                            }
                            else -> Unit
                        }
                    }
                }
                registry.actorState(id, generation, fp, state)
            },
            onEvent = { id, event ->
                registry.runIfCurrent(id, generation) { handleConnectionEvent(id, event) }
            },
            onConnectionChanged = { id, connection ->
                registry.actorConnection(id, generation, connection)
            },
            onStopped = { id -> registry.actorStopped(id, generation) },
            onReady = { conn ->
                registry.runIfCurrent(row.id, generation) {
                    onReady(row, (conn as IrcClientConnection).client) {
                        registry.isCurrent(row.id, generation)
                    }
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
        )
    }

    private suspend fun handleConnectionEvent(networkId: Long, event: IrcEvent) {
        avatarCoordinator.onEvent(networkId, event)
        eventProcessor.process(networkId, event)
        when (event) {
            is IrcEvent.Joined -> if (event.isSelf) {
                bufferForChannel(networkId, event.channel)?.let { buffer ->
                    val ready = clientFor(networkId)?.state?.value as? IrcClientState.Ready
                    setRosterState(
                        buffer.id,
                        if (ready != null && preferredNoImplicitNames(ready.caps) != null) {
                            RosterLoadState.NOT_LOADED
                        } else {
                            RosterLoadState.LOADING
                        },
                    )
                }
            }
            is IrcEvent.NamesStarted -> bufferForChannel(networkId, event.channel)?.let {
                setRosterState(it.id, RosterLoadState.LOADING)
            }
            is IrcEvent.Names -> bufferForChannel(networkId, event.channel)?.let {
                // An explicit lazy refresh is complete only after its correlated WHOX has also
                // finished. The NAMES snapshot itself has converged in EventProcessor, but
                // exposing LOADED here would incorrectly turn a later WHOX timeout into an
                // authoritative, supposedly enriched roster.
                setRosterState(it.id, rosterStateAfterNames(rosterRequests[it.id] != null))
            }
            is IrcEvent.Parted -> if (event.isSelf) clearRoster(networkId, event.channel)
            is IrcEvent.Kicked -> if (event.isSelf) clearRoster(networkId, event.channel)
            is IrcEvent.Disconnected -> {
                invalidateRosters(networkId)
                invalidatePresence(networkId)
            }
            is IrcEvent.MonitorOnline -> onMonitorOnline(networkId, event)
            is IrcEvent.MonitorOffline -> updatePresence(networkId, event.nicks, PresenceState.OFFLINE)
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
                            bufferDao.observeChatList().first(),
                            fresh = true,
                        )
                    }
                }
            }
            is IrcEvent.NickChanged -> rekeyPresence(networkId, event.from, event.to)
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
                            bufferDao.observeChatList().first(),
                            fresh = false,
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    private suspend fun bufferForChannel(networkId: Long, channel: String) =
        bufferStore.resolveChannelRoom(networkId, normalize(networkId, channel))

    private fun setRosterState(bufferId: Long, state: RosterLoadState) {
        _rosterStates.update { it + (bufferId to state) }
    }

    private suspend fun clearRoster(networkId: Long, channel: String) {
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
        rows: List<io.github.trevarj.motd.data.db.ChatListRow>,
        fresh: Boolean,
    ) {
        if (networksById[networkId]?.role == NetworkRole.BOUNCER_ROOT) return
        val ready = client.state.value as? IrcClientState.Ready ?: return
        val support = monitorSupport(ready.isupport)
        val selection = selectMonitorTargets(
            friends = friends,
            queryRows = rows.filter { it.networkId == networkId && it.type == BufferType.QUERY },
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

    private fun setPresence(networkId: Long, nick: String, state: PresenceState) {
        val normalize = clientFor(networkId)?.isupport?.let { support -> support::normalize }
            ?: { value: String -> value.lowercase() }
        val key = PresenceKey(networkId, normalize(nick))
        _presenceStates.update { current -> presenceIfTracked(current, key, state) }
    }

    private fun updatePresence(networkId: Long, nicks: List<String>, state: PresenceState) {
        nicks.forEach { setPresence(networkId, it, state) }
    }

    private fun onMonitorOnline(networkId: Long, event: IrcEvent.MonitorOnline) {
        val client = clientFor(networkId) ?: return
        event.identities.forEach { identity ->
            val key = PresenceKey(networkId, client.isupport.normalize(identity.nick))
            val wasOnline = _presenceStates.value[key] == PresenceState.ONLINE
            setPresence(networkId, identity.nick, PresenceState.ONLINE)
            if (!wasOnline) scope.launch { client.whox(identity.nick) }
        }
    }

    private fun rekeyPresence(networkId: Long, from: String, to: String) {
        val normalize = clientFor(networkId)?.isupport?.let { support -> support::normalize } ?: return
        val oldKey = PresenceKey(networkId, normalize(from))
        val newKey = PresenceKey(networkId, normalize(to))
        _presenceStates.update { current -> rekeyPresenceState(current, oldKey, newKey) }
    }

    private fun invalidatePresence(networkId: Long) {
        monitoredTargets.remove(networkId)
        monitorInitialized.remove(networkId)
        _presenceStates.update { current -> invalidatePresenceState(current, networkId) }
    }

    private fun fingerprint(row: NetworkEntity): String = networkFingerprint(
        row,
        if (row.role == NetworkRole.BOUNCER_CHILD) row.parentId?.let { networksById[it] } else null,
    )

    private suspend fun buildConnection(row: NetworkEntity): IrcClientConnection {
        // A BOUNCER_CHILD is a *bound connection to the bouncer*, not a direct socket to the
        // upstream network. Its own host/port/tls/SASL may carry the upstream server's details
        // (soju's BOUNCER NETWORK attrs report the upstream host), so connecting on them would
        // SASL the bouncer account against the upstream server and fail (SASL 904, #40). Resolve
        // the root row and build the config from the bouncer endpoint + account SASL, binding the
        // network via bouncerNetId.
        val root = if (row.role == NetworkRole.BOUNCER_CHILD) {
            row.parentId?.let { networksById[it] }
        } else {
            null
        }
        val config = buildChildConfig(row, root)
        // Obfuscation/proxy follows the transport endpoint too: a bound child tunnels through the
        // bouncer root's socket, so it inherits the root's proxy (plans/20 Phase 1).
        val endpoint = root ?: row
        val security = prepareTransportSecurity(
            host = config.host,
            port = config.port,
            wsUrl = config.wsUrl,
            policyFor = stsStore::policyFor,
            pinnedFor = certStore::pinnedFor,
        )
        // Resolve EMBEDDED_REALITY only after suspending policy reads complete, so a failed read
        // cannot leak a newly acquired local proxy lease.
        val proxyResolution = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = row.id.toString())
        val factory = AppTransportFactory(
            appContext = appContext,
            security = security,
            // TLS/cert trust follows the transport endpoint: the bouncer's for a bound child.
            clientCertAlias = endpoint.clientCertAlias,
            // Stash the failure keyed by network so the actor can park on it; unwrap defensively.
            onCertUntrusted = { ex -> certFailures[row.id] = ex },
            proxy = proxyResolution.proxy,
            proxyConfigurationError = proxyResolution.error,
        )
        return IrcClientConnection(IrcClient(config, factory, scope), proxyResolution.release)
    }

    /** On Ready: persist any STS policy, re-establish bouncer children, then run catch-up (plans/04). */
    private suspend fun onReady(
        row: NetworkEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent()) return
        avatarCoordinator.onReady(row.id, client)
        if (!isCurrent()) return
        reconcileMonitor(
            row.id,
            client,
            settings.settings.first().friends,
            bufferDao.observeChatList().first(),
            fresh = true,
        )
        if (!isCurrent()) return
        // Persist STS policy if the server advertised one.
        val stsValue = client.caps.firstOrNull { it == "sts" || it.startsWith("sts=") }?.substringAfter('=', "")
        stsStore.parse(row.host, stsValue?.ifEmpty { null }, row.tls, row.port)?.let { stsStore.upsert(it) }
        val enrollmentResult = presetEnrollmentCoordinator.onReady(
            networkId = row.id,
            isCurrent = isCurrent,
        ) { channel ->
            client.send(
                io.github.trevarj.motd.irc.proto.IrcMessage(command = "JOIN", params = listOf(channel)),
            )
        }
        if (enrollmentResult == EnrollmentJoinResult.FAILED) {
            Log.w(TAG, "One-shot Libera #motd JOIN write failed for network ${row.id}")
        }
        // A BOUNCER_ROOT reaching Ready means bound children can establish BOUNCER BIND again.
        // Only revive a wanted child that is absent, dead, or terminally disconnected/failed.
        // A child that is still Connecting/Registering owns its own transition to Ready; rebuilding
        // it here races registration. Rebuilding a healthy Ready child causes needless bouncer
        // churn and can interrupt the foreground channel.
        if (row.role == NetworkRole.BOUNCER_ROOT) {
            val snapshot = registry.snapshot.value
            val actorAlive = snapshot.actors.mapValues { (_, registered) -> registered.isAlive }
            for (childId in childrenNeedingReconnect(
                rootId = row.id,
                all = networksById.values.toList(),
                userIntents = userIntents,
                actorAlive = actorAlive,
                states = snapshot.states,
            )) {
                if (!isCurrent()) return
                connect(childId)
            }
        }
        // A fresh direct socket has no channel membership. Restore only channels whose durable
        // self JOIN state is still true; explicit PART/KICK rows set joined=false. Bouncer children
        // remain entirely bouncer-managed.
        if (row.role == NetworkRole.DIRECT) {
            for (channel in recoveryReader.joinedChannels(row.id)) {
                if (!isCurrent()) return
                client.send(
                    io.github.trevarj.motd.irc.proto.IrcMessage(
                        command = "JOIN",
                        params = listOf(channel),
                    ),
                )
            }
        }
        if (!isCurrent()) return
        // A bound soju child becomes Ready before its post-bind feature CAP ACKs. Keep these
        // feature waiters alive for the exact connection rather than treating an early snapshot as
        // final. ConnectionActor cancels this scope as soon as Ready ends.
        coroutineScope {
            val readMarkers = async {
                awaitCapabilityAvailable(client, READ_MARKER_CAP)
                if (isCurrent()) reconcileReadMarkersForConnection(row, client, isCurrent)
            }
            launch {
                if (!awaitHistoryReady(client)) return@launch
                if (!isCurrent()) return@launch
                // If read-marker was already negotiated, establish the durable max before
                // history rows arrive. If it appears later, its own watcher will still converge.
                if (client.hasCap(READ_MARKER_CAP)) readMarkers.join()
                if (isCurrent()) catchUpForConnection(row.id, client)
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
                    client.hasCap(WEBPUSH_CAP) -> Unit
                    row.role == NetworkRole.BOUNCER_CHILD -> awaitCapabilityAvailable(client, WEBPUSH_CAP)
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

    // -- catch-up (plans/04) -------------------------------------------------

    /**
     * Own reconnect catch-up for the lifetime of this exact client. Its caller already waited for
     * CHATHISTORY to appear, so this only retries actual transport/server failures.
     */
    private suspend fun catchUpForConnection(networkId: Long, client: IrcClient) {
        var attempt = 0
        while (clientFor(networkId) === client) {
            val buffers = openBuffers(networkId)
            when (val result = historyResyncCoordinator.resyncNetwork(
                networkId = networkId,
                openBuffers = buffers,
                client = client,
                isCurrent = { clientFor(networkId) === client },
            )) {
                is HistoryResyncState.Failed -> {
                    diagnostics.record("history", "catch_up_failed") {
                        mapOf(
                            "network_id" to networkId,
                            "attempt" to attempt,
                            "error_fp" to diagnostics.fingerprint(result.reason),
                        )
                    }
                    if (clientFor(networkId) !== client) return
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
                    return
                }
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
        if (row.role == NetworkRole.BOUNCER_ROOT || !client.hasCap(READ_MARKER_CAP)) return
        val requests = readMarkerSyncRequests(readMarkerRepository.storedForNetwork(row.id))
        coroutineScope {
            requests.map { request ->
                async {
                    if (!isCurrent()) return@async
                    val response = try {
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
                    withTimeoutOrNull(READ_MARKER_PERSIST_TIMEOUT_MS) {
                        bufferDao.observe(request.bufferId).first { buffer ->
                            buffer?.readMarkerTime?.let { it >= timestamp } == true
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun awaitCapabilityAvailable(client: IrcClient, capability: String) {
        if (client.hasCap(capability)) return
        client.state.filterIsInstance<IrcClientState.Ready>().first { ready ->
            ready.caps.any { it == capability || it.startsWith("$capability=") }
        }
    }

    private suspend fun awaitHistoryReady(client: IrcClient): Boolean {
        when (client.historyAvailability) {
            is HistoryAvailability.Ready -> return true
            HistoryAvailability.Unsupported -> return false
            HistoryAvailability.NegotiatingOrOffline -> Unit
        }
        client.state.filterIsInstance<IrcClientState.Ready>().first {
            client.historyAvailability !is HistoryAvailability.NegotiatingOrOffline
        }
        return client.historyAvailability is HistoryAvailability.Ready
    }

    private suspend fun openBuffers(networkId: Long): List<Pair<Long, String>> =
        bufferDao.openTargets(networkId).map { it.id to it.name }

    private suspend fun normalize(networkId: Long, name: String): String {
        return identityRules(networkId).normalize(name)
    }

    private suspend fun identityRules(networkId: Long): IrcIdentityRules {
        val client = clientFor(networkId)
        return identityRulesFallback(
            live = client?.isupport?.identityRules,
            liveReady = client?.state?.value is IrcClientState.Ready,
            persisted = db.networkIdentityDao().byNetwork(networkId),
        )
    }

    // -- send paths ---------------------------------------------------------

    override suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: Long?,
    ): SendAcceptance = sendLifecycle.sending {
        ensurePendingRecovered()
        val buffer = bufferDao.observeById(bufferId)
            ?: return@sending SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        if (buffer.type == BufferType.SERVER) {
            return@sending SendAcceptance.Rejected(SendRejectionReason.UNSUPPORTED_BUFFER)
        }
        sendLocks.getOrPut(buffer.networkId) { Mutex() }.withLock {
            val client = clientFor(buffer.networkId)
            val ready = client?.state?.value as? IrcClientState.Ready
            val parentId: Long? = replyToEventId
            val canonicalParent: MessageEntity? = if (parentId != null) {
                messageDao.byCanonicalId(parentId)
            } else {
                null
            }
            val parent: MessageEntity? = canonicalParent
                ?.takeIf { candidate: MessageEntity -> candidate.bufferId == buffer.id }
            val replyTagAllowed = parent?.msgid != null && ready != null &&
                canSendClientTag(ready.caps, ready.isupport, "+reply")
            val delivery = prepareReplyDelivery(
                text = text,
                replyToMsgid = parent?.msgid,
                parentSender = parent?.sender,
                bufferType = buffer.type,
                visibleChannelPrefix = parent?.let { replyPrefs.config.first().visibleChannelPrefix } == true,
                replyTagAllowed = replyTagAllowed,
            )
            val chunks = prepareOutgoingMessageChunks(
                delivery.text,
                buffer.ircTarget.equals("BouncerServ", ignoreCase = true),
            )
            if (chunks.isEmpty()) {
                return@withLock SendAcceptance.Rejected(SendRejectionReason.INVALID_CONTENT)
            }
            val planned = chunks.map { chunk ->
                PlannedOutgoingChunk(chunk, newOutgoingLabel())
            }
            currentCoroutineContext().ensureActive()
            val durable = try {
                withContext(NonCancellable) {
                    eventProcessor.persistOutgoingPlan(
                        bufferId = buffer.id,
                        sender = ready?.nick ?: client?.config?.nick
                            ?: networkDao.byId(buffer.networkId)?.nick.orEmpty(),
                        events = planned.map { OutgoingEventPlan(it.label, it.chunk.displayText, it.chunk.kind) },
                        replyToEventId = parent?.id,
                        replyToMsgid = parent?.msgid,
                    )
                }
            } catch (_: Exception) {
                return@withLock SendAcceptance.Rejected(SendRejectionReason.PERSISTENCE_FAILED)
            }
            val eventIds = durable.map { it.eventId }
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
                    )
                },
                secondaryEffect = { notifyOutgoingAccepted(buffer.id) },
            )
        }
    }

    override suspend fun retryMessage(eventId: Long): SendAcceptance = sendLifecycle.sending {
        ensurePendingRecovered()
        val original = messageDao.byCanonicalId(eventId)
            ?: return@sending SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
        val buffer = bufferDao.observeById(original.bufferId)
            ?: return@sending SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
        if (!isGenericRetryEligible(buffer, original)) {
            return@sending SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
        }
        sendLocks.getOrPut(buffer.networkId) { Mutex() }.withLock {
            val current = messageDao.byCanonicalId(eventId)
                ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
            val currentBuffer = bufferDao.observeById(current.bufferId)
                ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.BUFFER_NOT_FOUND)
            if (!isGenericRetryEligible(currentBuffer, current)) {
                return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
            }
            val label = newOutgoingLabel()
            currentCoroutineContext().ensureActive()
            val retry = try {
                withContext(NonCancellable) { eventProcessor.beginRetry(current.id, label) }
            } catch (_: Exception) {
                return@withLock SendAcceptance.Rejected(SendRejectionReason.PERSISTENCE_FAILED)
            } ?: return@withLock SendAcceptance.Rejected(SendRejectionReason.EVENT_NOT_RETRYABLE)
            val wireText = if (retry.kind == MessageKind.ACTION) {
                "\u0001ACTION ${retry.text}\u0001"
            } else {
                retry.text
            }
            val planned = listOf(
                PlannedOutgoingChunk(
                    OutgoingMessageChunk(wireText, retry.text, retry.kind),
                    label,
                ),
            )
            completeDurableAcceptance(
                eventIds = listOf(retry.id),
                transition = transition@{
                    val retryBuffer = bufferDao.observeById(retry.bufferId)
                        ?: return@transition ImmediateWireAcceptance.DISCONNECTED
                    val client = clientFor(retryBuffer.networkId)
                    val ready = client?.state?.value as? IrcClientState.Ready
                    val parentId: Long? = retry.replyToEventId
                    val parent: MessageEntity? = if (parentId != null) {
                        messageDao.byCanonicalId(parentId)
                    } else {
                        null
                    }
                    val wireReply = parent?.msgid?.takeIf {
                        ready != null && canSendClientTag(ready.caps, ready.isupport, "+reply")
                    }
                    writeDurablePlan(retryBuffer, client, ready, planned, listOf(retry.id), wireReply)
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
    ): ImmediateWireAcceptance {
        if (planned.size != eventIds.size) {
            eventProcessor.failPendingEvents(eventIds)
            return ImmediateWireAcceptance.FAILED
        }
        if (client == null || ready == null || clientFor(buffer.networkId) !== client) {
            eventProcessor.failPendingEvents(eventIds)
            return ImmediateWireAcceptance.DISCONNECTED
        }
        return transmitDurableOutgoingPlan(
            eventIds = eventIds,
            write = { index ->
                val item = planned[index]
                if (client.sendMessage(buffer.ircTarget, item.chunk.wireText, replyToMsgid, item.label)) {
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

    private fun armEchoTimeout(bufferId: Long, label: String) {
        val key = "$bufferId:$label"
        registry.armEchoTimeout(key, ECHO_TIMEOUT_MS) {
            eventProcessor.failIfStillPending(bufferId, label)
        }
    }

    override suspend fun sendTyping(bufferId: Long, state: String) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        clientFor(buffer.networkId)?.sendTyping(buffer.ircTarget, state)
    }

    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        val client = clientFor(buffer.networkId) ?: return
        val ready = client.state.value as? IrcClientState.Ready ?: return
        val canonicalBufferId = buffer.id
        val sender = ready.nick
        val identityRules = client.isupport.identityRules
        val nickKey = identityRules.actorKey(sender, account = null)
        val account = db.userDao().byNick(buffer.networkId, identityRules.normalize(sender))?.account
        val actorKey = identityRules.actorKey(sender, account)
        val previous = reactionMutations.findOwn(
            canonicalBufferId,
            msgid,
            listOf(actorKey, nickKey).distinct(),
            emoji,
        )
        val removing = previous != null
        // Recheck at the mutation boundary so a stale sheet cannot create local-only state after a
        // capability or CLIENTTAGDENY change.
        if (!canSendReactionTags(ready.caps, ready.isupport, removing)) return

        val reaction = ReactionEntity(
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

    override suspend fun joinChannel(networkId: Long, channel: String) {
        val client = clientFor(networkId) ?: return
        client.send(io.github.trevarj.motd.irc.proto.IrcMessage(command = "JOIN", params = listOf(channel)))
    }

    override suspend fun acceptInvite(messageId: Long) {
        val initial = messageDao.byCanonicalId(messageId) ?: return
        val canonicalMessageId = initial.id
        val payload = InvitePayloadV1.decode(initial.eventPayload) ?: return
        val buffer = bufferDao.observeById(initial.bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL ||
            normalize(buffer.networkId, buffer.ircTarget) != normalize(buffer.networkId, payload.channel)
        ) return
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
                    connectionStates.map { it[buffer.networkId] }
                        .filterIsInstance<IrcClientState.Ready>()
                        .first()
                } != null
            },
            stillJoining = {
                messageDao.byId(canonicalMessageId)?.inviteState == InviteState.JOINING
            },
            sendJoin = {
                val client = clientFor(buffer.networkId)
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

    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        val canonicalBufferId = buffer.id
        if (buffer.type != BufferType.CHANNEL || !buffer.joined) return
        if (!force && rosterStates.value[bufferId] == RosterLoadState.LOADED) return
        val client = clientFor(buffer.networkId) ?: run {
            setRosterState(bufferId, RosterLoadState.FAILED)
            return
        }
        if (client.state.value !is IrcClientState.Ready) {
            setRosterState(bufferId, RosterLoadState.FAILED)
            return
        }

        val candidate = scope.async(start = CoroutineStart.LAZY) {
            setRosterState(bufferId, RosterLoadState.LOADING)
            val completed = withTimeoutOrNull(ROSTER_REQUEST_TIMEOUT_MS) {
                coroutineScope {
                    val names = async(start = CoroutineStart.UNDISPATCHED) {
                        client.broadcastEvents.filterIsInstance<IrcEvent.Names>().first {
                            client.isupport.normalize(it.channel) == client.isupport.normalize(buffer.ircTarget)
                        }
                    }
                    val whox = async { client.whox(buffer.displayName) }
                    try {
                        client.send(
                            io.github.trevarj.motd.irc.proto.IrcMessage(
                                command = "NAMES",
                                params = listOf(buffer.displayName),
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

    override suspend fun partChannel(bufferId: Long, reason: String?) {
        sendPart(bufferId, reason)
    }

    override suspend fun partChannelForClose(bufferId: Long, reason: String?): Boolean = try {
        sendPart(bufferId, reason)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private suspend fun sendPart(bufferId: Long, reason: String?): Boolean {
        val buffer = bufferDao.observeById(bufferId) ?: return false
        // A close request is retried from the durable coordinator; never treat a disconnected
        // client (or one still registering) as a successful PART write.
        val client = clientFor(buffer.networkId) ?: return false
        if (client.state.value !is IrcClientState.Ready) return false
        // Append the reason as the PART trailing param when the user supplied one (/part <reason>).
        val params = if (reason.isNullOrBlank()) {
            listOf(buffer.ircTarget)
        } else {
            listOf(buffer.ircTarget, reason)
        }
        return client.sendIfConnected(
            io.github.trevarj.motd.irc.proto.IrcMessage(command = "PART", params = params),
        )
    }

    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long {
        val norm = normalize(networkId, nick)
        return bufferStore.getOrCreate(networkId, norm, nick, BufferType.QUERY).id
    }

    override suspend fun ensureServerBuffer(networkId: Long): Long {
        // "*" is stable under both casemapping normalizers, so no normalize() needed.
        return bufferStore.getOrCreate(
            networkId,
            SERVER_BUFFER_NAME,
            networkDao.byId(networkId)?.name ?: "Server",
            BufferType.SERVER,
        ).id
    }

    override suspend fun markRead(bufferId: Long, anchor: TimelineAnchor) {
        val target = resolveAndAdvanceCurrentReadTarget(db, bufferId, anchor) ?: return
        val buffer = target.buffer
        val canonicalBufferId = buffer.id
        messageNotifier.onRead(canonicalBufferId, target.anchor)
        AutoFollowTrace.record("local_markread", canonicalBufferId) {
            "marker=${target.anchor.serverTime}:${target.anchor.eventId}"
        }
        // SERVER buffers use "*" as their name, which is not a valid MARKREAD target; the Room
        // read-marker advance above still runs. Skip the wire send for them (plans/16 §4).
        if (buffer.type == BufferType.SERVER) return
        val authoritative = target.authoritative ?: return
        val currentRemote = bufferDao.observeById(canonicalBufferId)?.readMarkerTime
        if (currentRemote != null && authoritative.timestamp <= currentRemote) return
        val client = clientFor(buffer.networkId)
        AutoFollowTrace.record("wire_markread_out", canonicalBufferId) {
            "marker=${authoritative.timestamp} connected=${client != null} " +
                "supported=${client?.hasCap("draft/read-marker") == true}"
        }
        client?.markRead(buffer.ircTarget, authoritative.timestamp)
    }

    override suspend fun evaluatePushMode() {
        // Re-run the push teardown check after per-network endpoint changes; no-op off push mode.
        // Fixes v1: the settings-collect fired before any endpoint existed and never re-ran.
        if (settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH) maybeStopForPush()
    }

    // -- TOFU cert trust (plans/12) -----------------------------------------

    /** Publish a prompt for [networkId], deduping so re-attempts don't stack duplicates. */
    private fun publishCertPrompt(networkId: Long, ex: CertUntrustedException) {
        val prompt = CertPrompt(
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

    private fun connectivityObserverJob(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return@launch
        val callback = object : ConnectivityManager.NetworkCallback() {
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
        const val WEBPUSH_CAP = "soju.im/webpush"
        const val READ_MARKER_RESPONSE_TIMEOUT_MS = 5_000L
        const val READ_MARKER_PERSIST_TIMEOUT_MS = 2_000L
        const val ECHO_TIMEOUT_MS = 30_000L
        const val INVITE_READY_TIMEOUT_MS = 30_000L
        const val ROSTER_REQUEST_TIMEOUT_MS = 15_000L
        const val EMBEDDED_REALITY_BACKGROUND_GRACE_MS = 5 * 60 * 1000L
        const val MAX_BYTES = 400
        // Stable, casemapping-invariant name for the per-network SERVER buffer.
        const val SERVER_BUFFER_NAME = "*"

        /** Whether MainActivity/BootReceiver should keep the foreground service alive. */
        fun shouldRunService(deliveryPersistent: Boolean, hasNetworks: Boolean): Boolean =
            deliveryPersistent && hasNetworks
    }
}

internal fun shouldApplyDozePushHandoff(
    appForeground: Boolean,
    deviceIdle: Boolean,
    deliveryMode: DeliveryMode,
): Boolean = !appForeground && deviceIdle && deliveryMode == DeliveryMode.UNIFIED_PUSH

internal fun wantedNetworkUsesEmbeddedReality(
    networkId: Long,
    all: List<NetworkEntity>,
): Boolean {
    val byId = all.associateBy { it.id }
    val row = byId[networkId] ?: return false
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) {
        row.parentId?.let(byId::get) ?: row
    } else {
        row
    }
    return endpoint.obfsMode == ObfsMode.EMBEDDED_REALITY
}

internal fun catchUpRetryDelayMs(attempt: Int): Long =
    (2_000L * (1L shl attempt.coerceIn(0, 4))).coerceAtMost(30_000L)

/** Wire requests needed to converge local markers with a read-marker-capable server. */
internal fun readMarkerSyncRequests(markers: List<BufferReadMarker>): List<ReadMarkerSyncRequest> =
    markers.map { ReadMarkerSyncRequest(it.bufferId, it.target, it.timestamp) }

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
): IrcEvent.ReadMarker? = coroutineScope {
    val expected = normalize(target)
    val response = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(timeoutMs) {
            events.filterIsInstance<IrcEvent.ReadMarker>().first { normalize(it.target) == expected }
        }
    }
    request()
    response.await()
}

/**
 * Pure wanted-set computation for [ConnectionManagerImpl.reconcile] (plans/16 §4), extracted for
 * unit tests. A network is wanted when the sticky user intent (if present) or, absent an intent,
 * its `autoConnect` flag is true — and it is not an orphan BOUNCER_CHILD (a child with no parentId
 * has no root connection to bind through and must be excluded).
 */
/**
 * Build the [IrcClientConfig] for one network row (plans/05 §soju bouncer-networks). For a
 * BOUNCER_CHILD the physical socket is the *bouncer's*, not the upstream network's: the transport
 * endpoint (host/port/tls) and the account SASL credentials are taken from the resolved [root].
 * soju's pre-welcome BOUNCER BIND path mutates capabilities in a way that can stall on Android's
 * embedded transport, so children select their upstream with the stable account/network SASL authcid
 * form that soju also supports for bouncer networks.
 * Falls back to the child's own fields when the root cannot be resolved (orphan rows are excluded
 * from the wanted set upstream, so this is defensive only). Extracted for tests.
 */
internal fun buildChildConfig(row: NetworkEntity, root: NetworkEntity?): IrcClientConfig {
    // The endpoint + account identity a bound child inherits from its bouncer root.
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) (root ?: row) else row
    val childNetworkSelector = row.name.takeIf { row.role == NetworkRole.BOUNCER_CHILD && root != null }
    val saslUser = if (childNetworkSelector != null && !endpoint.saslUser.isNullOrBlank()) {
        "${endpoint.saslUser}/$childNetworkSelector"
    } else {
        endpoint.saslUser
    }
    return IrcClientConfig(
        host = endpoint.host,
        port = endpoint.port,
        tls = endpoint.tls,
        // Identity (NICK/USER) stays the child's so the drawer/nick reflect the bound network.
        nick = row.nick,
        username = row.username,
        realname = row.realname,
        sasl = runCatching { SaslMechanism.valueOf(endpoint.saslMechanism) }.getOrDefault(SaslMechanism.NONE),
        saslUser = saslUser,
        saslPassword = endpoint.saslPassword,
        serverPassword = endpoint.serverPassword,
        bouncerNetId = null,
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
        val link = VlessLink.parse(endpoint.obfsLink.orEmpty()).getOrElse { error ->
            return TransportProxyResolution(
                proxy = null,
                error = "Embedded REALITY configuration: ${error.message ?: "invalid VLESS link"}",
            )
        }
        // Keep each physical IRC actor on its own libbox service. Sharing one SOCKS inbound across
        // the root and a bouncer child makes the native core serialize their TLS streams at the
        // capability transition; both sessions appear Ready but post-registration writes vanish.
        val lease = localSocksProvider.acquire(link, ownerKey = ownerKey ?: endpoint.id.toString()).getOrElse { error ->
            return TransportProxyResolution(
                proxy = null,
                error = "Embedded REALITY configuration: ${error.message ?: "provider unavailable"}",
            )
        }
        // start() validates its returned port. Retain this guard so a future provider change
        // cannot turn a bad local endpoint into a direct connection.
        val proxy = proxyForNetwork(ObfsMode.SOCKS5, lease.endpoint.host, lease.endpoint.port)
            ?: return TransportProxyResolution(
                proxy = null,
                error = "Embedded REALITY configuration: invalid local SOCKS endpoint",
                release = lease.release,
            )
        return TransportProxyResolution(proxy = proxy, error = null, release = lease.release)
    }

    val error = proxyConfigurationErrorForNetwork(
        endpoint.obfsMode,
        endpoint.proxyHost,
        endpoint.proxyPort,
    )
    return TransportProxyResolution(
        proxy = if (error == null) {
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
internal fun networkFingerprint(row: NetworkEntity, root: NetworkEntity? = null): String {
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) root ?: row else row
    return "${endpoint.host}:${endpoint.port}:${endpoint.tls}:${row.nick}:${row.username}:${row.realname}:" +
        "${endpoint.saslMechanism}:${endpoint.saslUser}:${endpoint.saslPassword}:${endpoint.serverPassword}:" +
        "${row.bouncerNetId}:" +
        "${endpoint.clientCertAlias}:${endpoint.wsUrl}:${endpoint.obfsMode}:${endpoint.proxyHost}:${endpoint.proxyPort}:${endpoint.obfsLink}"
}

/** Configuration failures cannot become healthy through reconnect/backoff; only an effective
 * fingerprint change can release their actor park. [IrcClient] prefixes thrown transport errors. */
internal fun isConfigurationFailure(reason: String): Boolean =
    reason.startsWith("connect failed: SOCKS5 proxy ") ||
        reason.startsWith("connect failed: WebSocket transport cannot ") ||
        reason.startsWith("connect failed: Embedded REALITY configuration")

/**
 * Network ids parked on the given `host:port` cert endpoint (plans/12, #48). When a TOFU cert is
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
    certFailures.asSequence()
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
    states: Map<Long, IrcClientState> = emptyMap(),
): Set<Long> =
    all.asSequence()
        .filter { userIntents[it.id] ?: it.autoConnect }
        .filter { it.role != NetworkRole.BOUNCER_CHILD || it.parentId != null }
        .filter {
            it.role != NetworkRole.BOUNCER_CHILD ||
                states[it.parentId] is IrcClientState.Ready
        }
        .map { it.id }
        .toSet()

/**
 * BOUNCER_CHILD ids to revive when their [rootId] transitions into Ready. A bound child tunnels
 * through the root's transport (BOUNCER BIND), so an absent, dead, or terminally
 * disconnected/failed child may need a fresh actor once its root is available. A child that is
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
    all.asSequence()
        .filter { it.role == NetworkRole.BOUNCER_CHILD && it.parentId == rootId }
        .filter { userIntents[it.id] ?: it.autoConnect }
        .filter { child ->
            actorAlive[child.id] != true ||
                states[child.id] is IrcClientState.Failed ||
                states[child.id] == IrcClientState.Disconnected
        }
        .map { it.id }
        .toSet()
