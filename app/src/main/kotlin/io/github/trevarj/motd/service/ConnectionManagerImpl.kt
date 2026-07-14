package io.github.trevarj.motd.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.avatar.AvatarCoordinator
import io.github.trevarj.motd.bouncer.redactBouncerServCommand
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.obfs.VlessLink
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.client.NO_IMPLICIT_NAMES_ALIASES
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.client.canSendClientTag
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.client.preferredNoImplicitNames
import io.github.trevarj.motd.irc.client.preferredExtendedMonitor
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val presetEnrollmentCoordinator: PresetEnrollmentCoordinator,
    private val avatarCoordinator: AvatarCoordinator,
    private val pushHealthStore: PushHealthStore,
    // Lazy to break the WebPushRegistrar <-> ConnectionManager ctor cycle.
    private val webPushRegistrar: dagger.Lazy<WebPushRegistrar>,
) : ConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkDao get() = db.networkDao()
    private val bufferDao get() = db.bufferDao()
    private val messageDao get() = db.messageDao()
    private val reactionMutations = RoomReactionMutationStore(db)
    private val recoveryReader = ConnectionRecoveryReader(db)

    private val actors = HashMap<Long, ConnectionActor>()
    private val generations = ConnectionGenerationGate()
    // Fingerprint of the config each actor was built from, so config changes trigger a restart.
    private val fingerprints = HashMap<Long, String>()
    // Fatal configuration failures are intentionally parked. Reconcile/foreground events must not
    // recreate them until the effective network configuration changes.
    private val terminalConfigFingerprints = HashMap<Long, String>()

    // Latest full network set, kept so buildClient can resolve a BOUNCER_CHILD's root row (its
    // bouncer endpoint + account SASL) without a suspend DB read. Updated on every reconcile.
    @Volatile private var networksById: Map<Long, NetworkEntity> = emptyMap()

    // Sticky in-memory user intent per network (plans/16 §4): true = force-connect,
    // false = force-disconnect, absent = follow autoConnect. Survives reconcile emissions so a
    // manual disconnect/connect is not undone by the next DB write. Reset by stopAll (not persisted).
    private val userIntents = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private val _states = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
    override val connectionStates: StateFlow<Map<Long, IrcClientState>> = _states.asStateFlow()

    private val _rosterStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
    override val rosterStates: StateFlow<Map<Long, RosterLoadState>> = _rosterStates.asStateFlow()
    private val rosterRequests = java.util.concurrent.ConcurrentHashMap<Long, Deferred<Unit>>()

    private val _presenceStates = MutableStateFlow<Map<PresenceKey, PresenceState>>(emptyMap())
    override val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> = _presenceStates.asStateFlow()
    private val monitoredTargets = java.util.concurrent.ConcurrentHashMap<Long, Map<String, String>>()
    private val monitorInitialized = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val monitorLocks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    private val stsStore = StsPolicyStore(settings)
    private val pendingEchoTimeouts = HashMap<String, Job>()

    private val _certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
    override val certPrompts: StateFlow<List<CertPrompt>> = _certPrompts.asStateFlow()

    // Latest untrusted-cert failure per network, set from the handshake trust manager and consumed
    // by the actor to park in "awaiting trust" instead of backoff-looping.
    private val certFailures = java.util.concurrent.ConcurrentHashMap<Long, CertUntrustedException>()

    @Volatile private var started = false
    @Volatile private var appForeground = false
    @Volatile private var pushGraceElapsed = false
    private var reconcileJob: Job? = null
    private var deliveryModeJob: Job? = null
    private var backgroundGraceJob: Job? = null
    private var monitorDesiredJob: Job? = null
    private val pushSuspendedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    override fun clientFor(networkId: Long): IrcClient? =
        (actors[networkId]?.connection as? IrcClientConnection)?.client

    // -- lifecycle ----------------------------------------------------------

    override suspend fun startAll() {
        if (started) return
        started = true
        registerConnectivityCallback()
        // Seed actors from the current full set (reconcile applies autoConnect + sticky intent),
        // then keep reconciling on every DB change. The collector no longer pre-filters: reconcile
        // owns the wanted-set computation so manual connect/disconnect intents survive DB writes.
        reconcile(networkDao.observeAll().first())
        reconcileJob = scope.launch {
            networkDao.observeAll().collect { all ->
                reconcile(all)
            }
        }
        // Delivery-mode reaction: UNIFIED_PUSH tears the sockets down once webpush is confirmed.
        deliveryModeJob = scope.launch {
            settings.settings.map { it.deliveryMode }.distinctUntilChanged().collect { mode ->
                if (mode == DeliveryMode.UNIFIED_PUSH) {
                    if (!appForeground) schedulePushSuspension()
                } else {
                    backgroundGraceJob?.cancel()
                    pushSuspendedIds.clear()
                    reconcile(networkDao.observeAll().first())
                }
            }
        }
        monitorDesiredJob = scope.launch {
            combine(settings.settings, bufferDao.observeChatList()) { currentSettings, rows ->
                currentSettings.friends to rows
            }.collect { (friends, rows) ->
                actors.keys.toList().forEach { networkId ->
                    val client = clientFor(networkId) ?: return@forEach
                    if (client.state.value is IrcClientState.Ready && networkId in monitorInitialized) {
                        reconcileMonitor(networkId, client, friends, rows, fresh = false)
                    }
                }
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
        if (appForeground || !pushGraceElapsed ||
            settings.settings.first().deliveryMode != DeliveryMode.UNIFIED_PUSH
        ) return
        val all = networkDao.observeAll().first()
        val wanted = wantedNetworkIds(all, userIntents, _states.value)
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
            val stopIntent = android.content.Intent(appContext, IrcForegroundService::class.java)
            appContext.stopService(stopIntent)
        }
    }

    /** Process foreground: reconnect every wanted network and run the normal catch-up path. */
    internal suspend fun onAppForegrounded() {
        appForeground = true
        pushGraceElapsed = false
        backgroundGraceJob?.cancel()
        pushSuspendedIds.clear()
        startAll()
        reconcile(networkDao.observeAll().first())
        reconnectStale()
    }

    /** Process background: give active conversations 30 seconds before applying push suspension. */
    internal fun onAppBackgrounded() {
        appForeground = false
        pushGraceElapsed = false
        schedulePushSuspension()
    }

    private fun schedulePushSuspension() {
        backgroundGraceJob?.cancel()
        backgroundGraceJob = scope.launch {
            delay(PUSH_BACKGROUND_GRACE_MS)
            pushGraceElapsed = true
            maybeStopForPush()
        }
    }

    private fun startForegroundKeeper() {
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                android.content.Intent(appContext, IrcForegroundService::class.java),
            )
        }.onFailure { Log.w(TAG, "unable to start socket fallback service", it) }
    }

    override suspend fun stopAll() {
        started = false
        reconcileJob?.cancel(); reconcileJob = null
        deliveryModeJob?.cancel(); deliveryModeJob = null
        backgroundGraceJob?.cancel(); backgroundGraceJob = null
        monitorDesiredJob?.cancel(); monitorDesiredJob = null
        unregisterConnectivityCallback()
        for (actor in actors.values) actor.stop()
        actors.clear(); fingerprints.clear()
        generations.invalidateAll()
        terminalConfigFingerprints.clear()
        localSocksProvider.stop()
        // Service teardown resets sticky user intent (in-memory only).
        userIntents.clear()
        pushSuspendedIds.clear()
        _states.value = emptyMap()
        rosterRequests.values.forEach { it.cancel() }
        rosterRequests.clear()
        _rosterStates.value = emptyMap()
        monitoredTargets.clear()
        monitorInitialized.clear()
        monitorLocks.clear()
        _presenceStates.value = emptyMap()
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
        // Force-rebuild: a parked fatal-Failed actor sits in `actors` with a completed job and an
        // unchanged fingerprint, so plain ensureActor would no-op. Drop it first to actually reconnect.
        val currentFp = fingerprint(row)
        if (terminalConfigFingerprints[networkId] == currentFp) return
        generations.invalidate(networkId)
        actors.remove(networkId)?.stop()
        fingerprints.remove(networkId)
        ensureActor(row)
    }

    override suspend fun disconnect(networkId: Long) {
        // Record intent before removal so the next reconcile does not re-create the actor.
        userIntents[networkId] = false
        generations.invalidate(networkId)
        actors.remove(networkId)?.stop()
        fingerprints.remove(networkId)
        _states.update { it - networkId }
        invalidateRosters(networkId)
        invalidatePresence(networkId)
    }

    override suspend fun reconnectStale() {
        // Canonical app-foreground reconnect entry (registered on ProcessLifecycleOwner in
        // MotdApplication). Re-runs the self-healing reconcile against the current DB snapshot so any
        // actor that died/parked in the background (Doze/network drop) is dropped and rebuilt. Then
        // wake surviving non-ready actors so foregrounding does not wait out an old exponential
        // backoff after a proxy or bouncer has returned. The actor wake-up is conflated and merely
        // interrupts its current/next retry delay; Ready and manually disconnected networks are
        // untouched. No-op until started.
        if (!started) return
        reconcile(networkDao.observeAll().first())
        val states = _states.value
        actors.forEach { (networkId, actor) ->
            if (actor.isAlive && states[networkId] !is IrcClientState.Ready) {
                actor.onNetworkAvailable()
            }
        }
    }

    /** Add/remove/restart actors so the live set matches the wanted set derived from [all] rows
     *  and the sticky user-intent map (plans/16 §4). */
    private fun reconcile(all: List<NetworkEntity>) {
        // Keep a synchronous lookup so buildClient can resolve a child's root bouncer row.
        networksById = all.associateBy { it.id }
        val wantedIds = wantedNetworkIds(all, userIntents, _states.value) - pushSuspendedIds
        // Remove actors whose network is no longer wanted (deleted, autoConnect off, user-disconnected).
        for (id in actors.keys.toList()) {
            if (id !in wantedIds) {
                generations.invalidate(id)
                actors.remove(id)?.stop()
                fingerprints.remove(id)
                terminalConfigFingerprints.remove(id)
                _states.update { it - id }
            }
        }
        for (row in all) if (row.id in wantedIds) ensureActor(row)
    }

    private fun ensureActor(row: NetworkEntity) {
        val fp = fingerprint(row)
        val existing = actors[row.id]
        if (terminalConfigFingerprints[row.id] == fp) return
        terminalConfigFingerprints.remove(row.id)
        // A live actor whose fingerprint is unchanged is left alone (healthy / connecting /
        // actively retrying / parked awaiting cert trust). Only rebuild on a config change OR when
        // the existing actor is stale — its reconnect loop finished (fatal Failed / cert park with a
        // completed job) so it can never recover on its own. Force-rebuilding a stale actor here is
        // what makes a plain reconcile() self-healing after a background outage (#43): the actor no
        // longer no-ops purely on unchanged fingerprint. See [shouldRebuildActor].
        if (existing != null &&
            !shouldRebuildActor(
                fingerprintChanged = fingerprints[row.id] != fp,
                actorAlive = existing.isAlive,
                lastState = _states.value[row.id],
                awaitingCertTrust = _certPrompts.value.any { it.networkId == row.id },
            )
        ) {
            return
        }
        val generation = generations.begin(row.id)
        existing?.stop()
        val actor = ConnectionActor(
            networkId = row.id,
            scope = scope,
            connectionFactory = { buildConnection(row) },
            onState = { id, state ->
                if (generations.isCurrent(id, generation)) {
                    if (state is IrcClientState.Failed && state.fatal && isConfigurationFailure(state.reason)) {
                        terminalConfigFingerprints[id] = fp
                    }
                    _states.update { it + (id to state) }
                }
            },
            onEvent = { id, event ->
                if (generations.isCurrent(id, generation)) handleConnectionEvent(id, event)
            },
            onReady = { conn ->
                onReady(row, (conn as IrcClientConnection).client) {
                    generations.isCurrent(row.id, generation)
                }
            },
            pendingCertFailure = {
                if (generations.isCurrent(row.id, generation)) certFailures.remove(row.id) else null
            },
            onCertUntrusted = { id, ex ->
                if (generations.isCurrent(id, generation)) publishCertPrompt(id, ex)
            },
        )
        actors[row.id] = actor
        fingerprints[row.id] = fp
        actor.start()
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
            is IrcEvent.NickChanged -> rekeyPresence(networkId, event.from, event.to)
            is IrcEvent.CapsChanged -> {
                if (event.removed.any { it in NO_IMPLICIT_NAMES_ALIASES }) invalidateRosters(networkId)
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
        bufferDao.byName(
            networkId,
            clientFor(networkId)?.isupport?.normalize(channel) ?: normalize(networkId, channel),
        )

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

    private fun buildConnection(row: NetworkEntity): IrcClientConnection {
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
        // Resolve EMBEDDED_REALITY before inspecting legacy proxyHost/proxyPort. Those columns are
        // deliberately null for a VLESS-configured row; validating them first would park a valid
        // embedded configuration as "SOCKS5 proxy host is required".
        val proxyResolution = resolveTransportProxy(endpoint, localSocksProvider, ownerKey = row.id.toString())
        val factory = AppTransportFactory(
            appContext = appContext,
            stsStore = stsStore,
            certStore = certStore,
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
        // In push mode, re-arm webpush on this network if we already hold its endpoint (the
        // socket just reached Ready after a reconnect / quick foreground connect). Registration
        // may wait on a public relay, so run it beside catch-up rather than delaying visible history.
        if (settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH) {
            scope.launch {
                if (!isCurrent()) return@launch
                webPushRegistrar.get().reRegisterIfNeeded(row.id)
                if (isCurrent()) evaluatePushMode()
            }
        }
        // A BOUNCER_ROOT reaching Ready means bound children can establish BOUNCER BIND again.
        // Only revive a wanted child that is absent, dead, or terminally disconnected/failed.
        // A child that is still Connecting/Registering owns its own transition to Ready; rebuilding
        // it here races registration. Rebuilding a healthy Ready child causes needless bouncer
        // churn and can interrupt the foreground channel.
        if (row.role == NetworkRole.BOUNCER_ROOT) {
            val actorAlive = actors.mapValues { (_, actor) -> actor.isAlive }
            for (childId in childrenNeedingReconnect(
                rootId = row.id,
                all = networksById.values.toList(),
                userIntents = userIntents,
                actorAlive = actorAlive,
                states = _states.value,
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
        // Establish the server/local read boundary before replaying missing history. Otherwise
        // CHATHISTORY rows can briefly appear unread while a newer cross-device marker is still in
        // flight. The reconciliation is bounded, so a non-compliant server cannot stall catch-up.
        reconcileReadMarkersForConnection(row, client, isCurrent)
        if (!isCurrent()) return
        catchUpForConnection(row.id, client)
    }

    // -- catch-up (plans/04) -------------------------------------------------

    /**
     * Own reconnect catch-up for the lifetime of this exact client. soju child connections can
     * report Ready before their post-bind feature CAP ACK arrives, so wait for CHATHISTORY rather
     * than treating the early Ready snapshot as a permanent lack of support. Transient tunnel,
     * timeout, and protocol failures retry idempotently; ConnectionActor cancels this coroutine as
     * soon as the socket leaves Ready.
     */
    private suspend fun catchUpForConnection(networkId: Long, client: IrcClient) {
        if (!awaitCapability(client, CHATHISTORY_CAP)) return
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
                    if (clientFor(networkId) !== client) return
                    val retryMs = catchUpRetryDelayMs(attempt++)
                    Log.w(TAG, "CHATHISTORY catch-up failed for network $networkId; retrying in ${retryMs}ms: ${result.reason}")
                    delay(retryMs)
                }
                else -> return
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
        if (row.role == NetworkRole.BOUNCER_ROOT || !awaitCapability(client, READ_MARKER_CAP)) return
        val requests = readMarkerSyncRequests(readMarkerRepository.storedForNetwork(row.id))
        coroutineScope {
            requests.map { request ->
                async {
                    if (!isCurrent()) return@async
                    val response = try {
                        awaitReadMarkerResponse(
                            events = client.events,
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

    private suspend fun awaitCapability(client: IrcClient, capability: String): Boolean =
        client.hasCap(capability) || withTimeoutOrNull(CAP_SETTLE_TIMEOUT_MS) {
            client.state.filterIsInstance<IrcClientState.Ready>().first { ready ->
                ready.caps.any { it == capability || it.startsWith("$capability=") }
            }
            true
        } == true

    private suspend fun openBuffers(networkId: Long): List<Pair<Long, String>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val q = androidx.sqlite.db.SimpleSQLiteQuery(
                "SELECT id, name FROM buffers WHERE networkId = ? AND type != 'SERVER'",
                arrayOf<Any>(networkId),
            )
            db.query(q).use { c ->
                val out = ArrayList<Pair<Long, String>>(c.count)
                while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
                out
            }
        }

    private suspend fun normalize(networkId: Long, name: String): String {
        // Delegate normalization to the live client's isupport when available; else lowercase.
        val client = clientFor(networkId)
        return client?.isupport?.normalize(name) ?: name.lowercase()
    }

    // -- send paths ---------------------------------------------------------

    override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        val client = clientFor(buffer.networkId) ?: return
        val target = buffer.name
        val isBouncerServ = target.equals("BouncerServ", ignoreCase = true)
        val ready = client.state.value as? IrcClientState.Ready
        val replyTagAllowed = ready != null && canSendClientTag(ready.caps, ready.isupport, "+reply")
        val parentSender = replyToMsgid?.let { messageDao.byMsgid(bufferId, it)?.sender }
        val replyConfig = if (replyToMsgid != null) replyPrefs.config.first() else null
        val delivery = prepareReplyDelivery(
            text = text,
            replyToMsgid = replyToMsgid,
            parentSender = parentSender,
            bufferType = buffer.type,
            visibleChannelPrefix = replyConfig?.visibleChannelPrefix == true,
            replyTagAllowed = replyTagAllowed,
        )
        val outgoingText = delivery.text

        // BouncerServ interprets one PRIVMSG as one shell command. Never turn a newline or an
        // oversized command into multiple independently executable service commands.
        if (isBouncerServ && ('\r' in outgoingText || '\n' in outgoingText || outgoingText.toByteArray().size > MAX_BYTES)) {
            return
        }

        // /me → CTCP ACTION wrapped in \x01. Only the FIRST physical line of a multiline
        // paste can carry the /me action; the rest are plain PRIVMSGs.
        val isAction = outgoingText.startsWith("/me ")

        // A composer can contain embedded newlines (multiline paste); each physical line must be
        // its own IRC message or the server would parse the tail as a raw command (line injection).
        // Split on CR/LF first, then apply the >400-byte UTF-8 word-boundary split per line.
        val lines = outgoingText.split(Regex("\r\n|\r|\n")).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        for ((lineIndex, line) in lines.withIndex()) {
            val lineIsAction = isAction && lineIndex == 0
            val displayLine = if (lineIsAction) line.removePrefix("/me ") else line
            val body = if (lineIsAction) "ACTION $displayLine" else line
            val kind = if (lineIsAction) MessageKind.ACTION else MessageKind.PRIVMSG

            // Split >400-byte UTF-8 payloads on word boundaries; each chunk gets its own row.
            val chunks = splitUtf8(body, MAX_BYTES)
            for (chunk in chunks) {
                // Stored display text = wire chunk minus the CTCP ACTION \x01 wrapper.
                val rawDisplayChunk = if (lineIsAction) {
                    chunk.removePrefix("ACTION ").removeSuffix("")
                } else {
                    chunk
                }
                val displayChunk = if (isBouncerServ) redactBouncerServCommand(rawDisplayChunk) else rawDisplayChunk
                // Persist the pending row in beforeSend — BEFORE the PRIVMSG hits the wire — so a
                // fast labeled echo can't be processed ahead of the insert and duplicate the send.
                val label = client.sendMessage(target, chunk, delivery.wireReplyToMsgid) { lbl ->
                    if (lbl.isNotEmpty()) {
                        eventProcessor.insertPending(bufferId, lbl, buffer_meNick(client), displayChunk, replyToMsgid, kind)
                    }
                }
                if (label.isEmpty()) {
                    // No labeled-response: the pending row could never be confirmed (its echo
                    // cannot be correlated). If echo-message is also absent nothing surfaces at
                    // all, so insert an already-confirmed self row now (plans/03 echo degradation,
                    // plans/04 echo flow) with the sha1(serverTime|sender|text) dedup key. When
                    // echo-message IS present the returning self ChatMessage carries the same key
                    // -> INSERT IGNORE no-ops, so we stay at one row either way.
                    insertConfirmedSelf(buffer.networkId, target, displayChunk, replyToMsgid, kind)
                    continue
                }
                armEchoTimeout(bufferId, label)
            }
        }
    }

    /**
     * Insert a confirmed self message row for the no-labeled-response path (#7 / plans/03). Fed
     * through the sole IRC->Room writer as a synthetic self [IrcEvent.ChatMessage] so buffer
     * routing, kind mapping, and the sha1 dedup key all match the echo-message path exactly.
     */
    private suspend fun insertConfirmedSelf(
        networkId: Long,
        target: String,
        displayText: String,
        replyToMsgid: String?,
        kind: MessageKind,
    ) {
        val client = clientFor(networkId) ?: return
        val chatKind = when (kind) {
            MessageKind.ACTION -> IrcEvent.ChatKind.ACTION
            MessageKind.NOTICE -> IrcEvent.ChatKind.NOTICE
            else -> IrcEvent.ChatKind.PRIVMSG
        }
        val event = IrcEvent.ChatMessage(
            ctx = io.github.trevarj.motd.irc.event.MessageContext(
                msgid = null,
                serverTime = System.currentTimeMillis(),
                account = null,
                batchId = null,
                label = null,
            ),
            kind = chatKind,
            source = io.github.trevarj.motd.irc.proto.Prefix(nick = buffer_meNick(client)),
            target = target,
            text = displayText,
            isSelf = true,
            replyToMsgid = replyToMsgid,
        )
        eventProcessor.process(networkId, event)
    }

    private fun buffer_meNick(client: IrcClient): String =
        (client.state.value as? IrcClientState.Ready)?.nick ?: client.config.nick

    private fun armEchoTimeout(bufferId: Long, label: String) {
        val key = "$bufferId:$label"
        pendingEchoTimeouts[key]?.cancel()
        pendingEchoTimeouts[key] = scope.launch {
            kotlinx.coroutines.delay(ECHO_TIMEOUT_MS)
            eventProcessor.failIfStillPending(bufferId, label)
            pendingEchoTimeouts.remove(key)
        }
    }

    override suspend fun sendTyping(bufferId: Long, state: String) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        clientFor(buffer.networkId)?.sendTyping(buffer.name, state)
    }

    override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) {
        val buffer = bufferDao.observeById(bufferId) ?: return
        val client = clientFor(buffer.networkId) ?: return
        val ready = client.state.value as? IrcClientState.Ready ?: return
        val sender = ready.nick
        val normalize: (String) -> String = client.isupport::normalize
        val previous = reactionMutations.findOwn(bufferId, msgid, sender, normalize)
        val removing = previous?.emoji == emoji
        // Recheck at the mutation boundary so a stale sheet cannot create local-only state after a
        // capability or CLIENTTAGDENY change.
        if (!canSendReactionTags(ready.caps, ready.isupport, removing)) return

        val reaction = ReactionEntity(
            bufferId = bufferId,
            targetMsgid = msgid,
            sender = sender,
            emoji = emoji,
            serverTime = System.currentTimeMillis(),
        )
        mutateReaction(reactionMutations, previous, reaction) { kind ->
            if (kind == ReactionMutationKind.REMOVE) {
                client.send(reactionTagMessage(buffer.name, msgid, emoji, kind))
            } else {
                client.sendReact(buffer.name, msgid, emoji)
            }
        }
    }

    override suspend fun joinChannel(networkId: Long, channel: String) {
        val client = clientFor(networkId) ?: return
        client.send(io.github.trevarj.motd.irc.proto.IrcMessage(command = "JOIN", params = listOf(channel)))
    }

    override suspend fun acceptInvite(messageId: Long) {
        val initial = messageDao.byId(messageId) ?: return
        val payload = InvitePayloadV1.decode(initial.eventPayload) ?: return
        val buffer = bufferDao.observeById(initial.bufferId) ?: return
        if (buffer.type != BufferType.CHANNEL ||
            buffer.name != normalize(buffer.networkId, payload.channel)
        ) return
        val claimed = when (initial.inviteState) {
            InviteState.PENDING, InviteState.FAILED -> messageDao.compareAndSetInviteState(
                messageId,
                initial.inviteState,
                InviteState.JOINING,
            )
            else -> 0
        }
        if (claimed == 0) return

        try {
            if (connectionStates.value[buffer.networkId] !is IrcClientState.Ready) connect(buffer.networkId)
            val ready = withTimeoutOrNull(INVITE_READY_TIMEOUT_MS) {
                connectionStates.map { it[buffer.networkId] }
                    .filterIsInstance<IrcClientState.Ready>()
                    .first()
            }
            if (ready == null) {
                messageDao.failInvite(messageId, "connection timed out")
                return
            }
            // Dismiss can race the connection wait. Recheck before the only wire write.
            if (messageDao.byId(messageId)?.inviteState != InviteState.JOINING) return
            val client = clientFor(buffer.networkId)
            if (client == null) {
                messageDao.failInvite(messageId, "connection unavailable")
                return
            }
            client.send(
                io.github.trevarj.motd.irc.proto.IrcMessage(
                    command = "JOIN",
                    params = listOf(payload.channel),
                ),
            )
        } catch (cancelled: CancellationException) {
            messageDao.failInvite(messageId, "join cancelled")
            throw cancelled
        } catch (_: Exception) {
            messageDao.failInvite(messageId, "send failed")
        }
    }

    override suspend fun dismissInvite(messageId: Long) {
        if (messageDao.dismissInvite(messageId) > 0) messageNotifier.onInvitationResolved(messageId)
    }

    override suspend fun requestMembers(bufferId: Long, force: Boolean) {
        val buffer = bufferDao.observeById(bufferId) ?: return
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
                        client.events.filterIsInstance<IrcEvent.Names>().first {
                            client.isupport.normalize(it.channel) == buffer.name
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
                    eventProcessor.cancelRosterSnapshot(buffer.networkId, bufferId)
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
        val buffer = bufferDao.observeById(bufferId) ?: return
        // Append the reason as the PART trailing param when the user supplied one (/part <reason>).
        val params = if (reason.isNullOrBlank()) listOf(buffer.name) else listOf(buffer.name, reason)
        clientFor(buffer.networkId)?.send(
            io.github.trevarj.motd.irc.proto.IrcMessage(command = "PART", params = params),
        )
    }

    override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long {
        val norm = normalize(networkId, nick)
        bufferDao.byName(networkId, norm)?.let { return it.id }
        return bufferDao.insert(
            io.github.trevarj.motd.data.db.BufferEntity(
                networkId = networkId,
                name = norm,
                displayName = nick,
                type = BufferType.QUERY,
            ),
        )
    }

    override suspend fun ensureServerBuffer(networkId: Long): Long {
        // "*" is stable under both casemapping normalizers, so no normalize() needed.
        bufferDao.byName(networkId, SERVER_BUFFER_NAME)?.let { return it.id }
        return bufferDao.insert(
            io.github.trevarj.motd.data.db.BufferEntity(
                networkId = networkId,
                name = SERVER_BUFFER_NAME,
                displayName = networkDao.byId(networkId)?.name ?: "Server",
                type = BufferType.SERVER,
            ),
        )
    }

    override suspend fun markRead(bufferId: Long, upToTime: Long) {
        bufferDao.advanceReadMarker(bufferId, upToTime)
        messageNotifier.onRead(bufferId, upToTime)
        AutoFollowTrace.record("local_markread", bufferId) { "marker=$upToTime" }
        val buffer = bufferDao.observeById(bufferId) ?: return
        // SERVER buffers use "*" as their name, which is not a valid MARKREAD target; the Room
        // read-marker advance above still runs. Skip the wire send for them (plans/16 §4).
        if (buffer.type == BufferType.SERVER) return
        val client = clientFor(buffer.networkId)
        AutoFollowTrace.record("wire_markread_out", bufferId) {
            "marker=$upToTime connected=${client != null} supported=${client?.hasCap("draft/read-marker") == true}"
        }
        client?.markRead(buffer.name, upToTime)
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
            // Rebuild each parked actor fresh so it picks up the new pin and reconnects. connect()
            // already force-drops the stale actor, but drop here too so a concurrent reconcile can't
            // re-observe the parked (completed-job) actor between removal and the connect() call.
            generations.invalidate(id)
            actors.remove(id)?.stop()
            fingerprints.remove(id)
            connect(id)
        }
    }

    override fun dismissCertPrompt(prompt: CertPrompt) {
        _certPrompts.value = _certPrompts.value.filterNot { it.networkId == prompt.networkId }
        certFailures.remove(prompt.networkId)
        // Network stays disconnected; the actor already parked itself.
    }

    // -- connectivity callback ----------------------------------------------

    private fun registerConnectivityCallback() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { for (a in actors.values) a.onNetworkAvailable() }
            override fun onLost(network: Network) { for (a in actors.values) a.onNetworkLost() }
        }
        connectivityCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterConnectivityCallback() {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
        connectivityCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        connectivityCallback = null
    }

    // -- utils --------------------------------------------------------------

    /** Split [text] into chunks of at most [maxBytes] UTF-8 bytes, preferring word boundaries. */
    internal fun splitUtf8(text: String, maxBytes: Int): List<String> {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)
        val out = ArrayList<String>()
        var remaining = text
        while (remaining.toByteArray(Charsets.UTF_8).size > maxBytes) {
            var cut = remaining.length
            // Shrink until the prefix fits.
            while (remaining.substring(0, cut).toByteArray(Charsets.UTF_8).size > maxBytes) cut--
            // Prefer the last space within the fitting prefix.
            val space = remaining.lastIndexOf(' ', cut - 1)
            val split = if (space > 0) space else cut
            out.add(remaining.substring(0, split).trimEnd())
            remaining = remaining.substring(split).trimStart()
        }
        if (remaining.isNotEmpty()) out.add(remaining)
        return out
    }

    companion object {
        // Stable logcat tag for reconnect catch-up failures.
        private const val TAG = "MotdCatchUp"
        const val CHATHISTORY_CAP = "draft/chathistory"
        const val READ_MARKER_CAP = "draft/read-marker"
        const val WEBPUSH_CAP = "soju.im/webpush"
        const val CAP_SETTLE_TIMEOUT_MS = 15_000L
        const val READ_MARKER_RESPONSE_TIMEOUT_MS = 5_000L
        const val READ_MARKER_PERSIST_TIMEOUT_MS = 2_000L
        const val ECHO_TIMEOUT_MS = 30_000L
        const val INVITE_READY_TIMEOUT_MS = 30_000L
        const val ROSTER_REQUEST_TIMEOUT_MS = 15_000L
        const val PUSH_BACKGROUND_GRACE_MS = 30_000L
        const val MAX_BYTES = 400
        // Stable, casemapping-invariant name for the per-network SERVER buffer.
        const val SERVER_BUFFER_NAME = "*"

        /** Whether MainActivity/BootReceiver should keep the foreground service alive. */
        fun shouldRunService(deliveryPersistent: Boolean, hasNetworks: Boolean): Boolean =
            deliveryPersistent && hasNetworks
    }
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
        "${endpoint.saslMechanism}:${endpoint.saslUser}:${endpoint.saslPassword}:${row.bouncerNetId}:" +
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
