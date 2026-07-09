package io.github.trevarj.motd.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.SaslMechanism
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.push.WebPushRegistrar
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val certStore: CertTrustStore,
    private val baseTransportFactory: TransportFactory,
    // Lazy to break the WebPushRegistrar <-> ConnectionManager ctor cycle.
    private val webPushRegistrar: dagger.Lazy<WebPushRegistrar>,
) : ConnectionManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val networkDao get() = db.networkDao()
    private val bufferDao get() = db.bufferDao()
    private val messageDao get() = db.messageDao()

    private val actors = HashMap<Long, ConnectionActor>()
    // Fingerprint of the config each actor was built from, so config changes trigger a restart.
    private val fingerprints = HashMap<Long, String>()

    // Latest full network set, kept so buildClient can resolve a BOUNCER_CHILD's root row (its
    // bouncer endpoint + account SASL) without a suspend DB read. Updated on every reconcile.
    @Volatile private var networksById: Map<Long, NetworkEntity> = emptyMap()

    // Sticky in-memory user intent per network (plans/16 §4): true = force-connect,
    // false = force-disconnect, absent = follow autoConnect. Survives reconcile emissions so a
    // manual disconnect/connect is not undone by the next DB write. Reset by stopAll (not persisted).
    private val userIntents = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()

    private val _states = MutableStateFlow<Map<Long, IrcClientState>>(emptyMap())
    override val connectionStates: StateFlow<Map<Long, IrcClientState>> = _states.asStateFlow()

    private val stsStore = StsPolicyStore(settings)
    private val pendingEchoTimeouts = HashMap<String, Job>()

    private val _certPrompts = MutableStateFlow<List<CertPrompt>>(emptyList())
    override val certPrompts: StateFlow<List<CertPrompt>> = _certPrompts.asStateFlow()

    // Latest untrusted-cert failure per network, set from the handshake trust manager and consumed
    // by the actor to park in "awaiting trust" instead of backoff-looping.
    private val certFailures = java.util.concurrent.ConcurrentHashMap<Long, CertUntrustedException>()

    @Volatile private var started = false
    private var reconcileJob: Job? = null
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
        scope.launch {
            settings.settings.collect { s ->
                if (s.deliveryMode == DeliveryMode.UNIFIED_PUSH) maybeStopForPush()
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
        val webpushClients = actors.keys.filter { id -> clientFor(id)?.hasCap(WEBPUSH_CAP) == true }
        if (webpushClients.isEmpty()) return
        val allArmed = webpushClients.all { id -> pushPrefs.endpointFor(id) != null }
        if (!allArmed) return
        for (actor in actors.values) actor.stop()
        actors.clear(); fingerprints.clear()
        _states.value = emptyMap()
        // Hoist the Intent to a local so lint doesn't read it as an implicit SAM instance.
        val stopIntent = android.content.Intent(appContext, IrcForegroundService::class.java)
        appContext.stopService(stopIntent)
    }

    override suspend fun stopAll() {
        started = false
        reconcileJob?.cancel(); reconcileJob = null
        unregisterConnectivityCallback()
        for (actor in actors.values) actor.stop()
        actors.clear(); fingerprints.clear()
        // Service teardown resets sticky user intent (in-memory only).
        userIntents.clear()
        _states.value = emptyMap()
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
        actors.remove(networkId)?.stop()
        fingerprints.remove(networkId)
        ensureActor(row)
    }

    override suspend fun disconnect(networkId: Long) {
        // Record intent before removal so the next reconcile does not re-create the actor.
        userIntents[networkId] = false
        actors.remove(networkId)?.stop()
        fingerprints.remove(networkId)
        _states.value = _states.value - networkId
    }

    /** Add/remove/restart actors so the live set matches the wanted set derived from [all] rows
     *  and the sticky user-intent map (plans/16 §4). */
    private fun reconcile(all: List<NetworkEntity>) {
        // Keep a synchronous lookup so buildClient can resolve a child's root bouncer row.
        networksById = all.associateBy { it.id }
        val wantedIds = wantedNetworkIds(all, userIntents)
        // Remove actors whose network is no longer wanted (deleted, autoConnect off, user-disconnected).
        for (id in actors.keys.toList()) {
            if (id !in wantedIds) {
                actors.remove(id)?.stop()
                fingerprints.remove(id)
                _states.value = _states.value - id
            }
        }
        for (row in all) if (row.id in wantedIds) ensureActor(row)
    }

    private fun ensureActor(row: NetworkEntity) {
        val fp = fingerprint(row)
        val existing = actors[row.id]
        if (existing != null && fingerprints[row.id] == fp) return
        existing?.stop()
        val actor = ConnectionActor(
            networkId = row.id,
            scope = scope,
            connectionFactory = { IrcClientConnection(buildClient(row)) },
            onState = { id, state ->
                // Surface failures to logcat for on-device diagnosis (#43): the UI only shows a
                // terse "Failed", so log the network id + reason + fatality under a stable tag.
                if (state is IrcClientState.Failed) {
                    android.util.Log.w(CONN_LOG_TAG, "network $id Failed: ${state.reason} (fatal=${state.fatal})")
                }
                _states.value = _states.value + (id to state)
            },
            onEvent = { id, event -> eventProcessor.process(id, event) },
            onReady = { conn -> onReady(row, (conn as IrcClientConnection).client) },
            pendingCertFailure = { certFailures.remove(row.id) },
            onCertUntrusted = { id, ex -> publishCertPrompt(id, ex) },
        )
        actors[row.id] = actor
        fingerprints[row.id] = fp
        actor.start()
    }

    private fun fingerprint(row: NetworkEntity): String = networkFingerprint(row)

    private fun buildClient(row: NetworkEntity): IrcClient {
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
        val factory = AppTransportFactory(
            appContext = appContext,
            stsStore = stsStore,
            certStore = certStore,
            // TLS/cert trust follows the transport endpoint: the bouncer's for a bound child.
            clientCertAlias = (root ?: row).clientCertAlias,
            // Stash the failure keyed by network so the actor can park on it; unwrap defensively.
            onCertUntrusted = { ex -> certFailures[row.id] = ex },
        )
        return IrcClient(config, factory, scope)
    }

    /** On Ready: persist any STS policy, then run reconnect catch-up (plans/04). */
    private suspend fun onReady(row: NetworkEntity, client: IrcClient) {
        // Persist STS policy if the server advertised one.
        val stsValue = client.caps.firstOrNull { it == "sts" || it.startsWith("sts=") }?.substringAfter('=', "")
        stsStore.parse(row.host, stsValue?.ifEmpty { null }, row.tls, row.port)?.let { stsStore.upsert(it) }
        // In push mode, re-arm webpush on this network if we already hold its endpoint (the
        // socket just reached Ready after a reconnect / quick foreground connect).
        if (settings.settings.first().deliveryMode == DeliveryMode.UNIFIED_PUSH) {
            webPushRegistrar.get().reRegisterIfNeeded(row.id)
        }
        catchUp(row.id, client)
    }

    // -- catch-up (plans/04) -------------------------------------------------

    private suspend fun catchUp(networkId: Long, client: IrcClient) {
        val catchUp = CatchUp(
            bufferDao = bufferDao,
            messageDao = messageDao,
            processor = eventProcessor,
            history = clientHistorySource(client),
            normalize = { client.isupport.normalize(it) },
        )
        catchUp.run(networkId, openBuffers(networkId))
    }

    /** Adapt a live [IrcClient] to the [CatchUp.HistorySource] seam. */
    private fun clientHistorySource(client: IrcClient): CatchUp.HistorySource =
        object : CatchUp.HistorySource {
            override fun hasCap(cap: String): Boolean = client.hasCap(cap)
            override suspend fun chathistory(req: ChatHistoryRequest): io.github.trevarj.motd.irc.client.ChatHistoryResult =
                client.chathistory(req)
            override suspend fun fetchReadMarker(target: String) = client.fetchReadMarker(target)
        }

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

        // /me → CTCP ACTION wrapped in \x01. Only the FIRST physical line of a multiline
        // paste can carry the /me action; the rest are plain PRIVMSGs.
        val isAction = text.startsWith("/me ")

        // A composer can contain embedded newlines (multiline paste); each physical line must be
        // its own IRC message or the server would parse the tail as a raw command (line injection).
        // Split on CR/LF first, then apply the >400-byte UTF-8 word-boundary split per line.
        val lines = text.split(Regex("\r\n|\r|\n")).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return

        for ((lineIndex, line) in lines.withIndex()) {
            val lineIsAction = isAction && lineIndex == 0
            val displayLine = if (lineIsAction) line.removePrefix("/me ") else line
            val body = if (lineIsAction) "ACTION $displayLine" else line
            val kind = if (lineIsAction) MessageKind.ACTION else MessageKind.PRIVMSG

            // Split >400-byte UTF-8 payloads on word boundaries; each chunk gets its own row.
            val chunks = splitUtf8(body, MAX_BYTES)
            for (chunk in chunks) {
                val label = client.sendMessage(target, chunk, replyToMsgid)
                // Stored display text = wire chunk minus the CTCP ACTION \x01 wrapper.
                val displayChunk = if (lineIsAction) {
                    chunk.removePrefix("ACTION ").removeSuffix("")
                } else {
                    chunk
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
                eventProcessor.insertPending(bufferId, label, buffer_meNick(client), displayChunk, replyToMsgid, kind)
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
        clientFor(buffer.networkId)?.sendReact(buffer.name, msgid, emoji)
    }

    override suspend fun joinChannel(networkId: Long, channel: String) {
        val client = clientFor(networkId) ?: return
        client.send(io.github.trevarj.motd.irc.proto.IrcMessage(command = "JOIN", params = listOf(channel)))
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
        val buffer = bufferDao.observeById(bufferId) ?: return
        // SERVER buffers use "*" as their name, which is not a valid MARKREAD target; the Room
        // read-marker advance above still runs. Skip the wire send for them (plans/16 §4).
        if (buffer.type == BufferType.SERVER) return
        clientFor(buffer.networkId)?.markRead(buffer.name, upToTime)
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
        // Stable logcat tag for connection failures (#43).
        const val CONN_LOG_TAG = "MotdConn"
        const val CHATHISTORY_CAP = "draft/chathistory"
        const val WEBPUSH_CAP = "soju.im/webpush"
        const val ECHO_TIMEOUT_MS = 30_000L
        const val MAX_BYTES = 400
        // Stable, casemapping-invariant name for the per-network SERVER buffer.
        const val SERVER_BUFFER_NAME = "*"

        /** Whether MainActivity/BootReceiver should keep the foreground service alive. */
        fun shouldRunService(deliveryPersistent: Boolean, hasNetworks: Boolean): Boolean =
            deliveryPersistent && hasNetworks
    }
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
 * endpoint (host/port/tls) and the account SASL credentials are taken from the resolved [root]
 * row, and the upstream network is selected with `BOUNCER BIND <bouncerNetId>` during
 * registration. Falls back to the child's own fields when the root cannot be resolved (orphan
 * rows are excluded from the wanted set upstream, so this is defensive only). Extracted for tests.
 */
internal fun buildChildConfig(row: NetworkEntity, root: NetworkEntity?): IrcClientConfig {
    // The endpoint + account identity a bound child inherits from its bouncer root.
    val endpoint = if (row.role == NetworkRole.BOUNCER_CHILD) (root ?: row) else row
    return IrcClientConfig(
        host = endpoint.host,
        port = endpoint.port,
        tls = endpoint.tls,
        // Identity (NICK/USER) stays the child's so the drawer/nick reflect the bound network.
        nick = row.nick,
        username = row.username,
        realname = row.realname,
        // SASL authenticates the bouncer *account* (bare user), never a per-network user/network
        // form — network selection is done by BOUNCER BIND below, not by the SASL authcid.
        sasl = runCatching { SaslMechanism.valueOf(endpoint.saslMechanism) }.getOrDefault(SaslMechanism.NONE),
        saslUser = endpoint.saslUser,
        saslPassword = endpoint.saslPassword,
        bouncerNetId = if (row.role == NetworkRole.BOUNCER_CHILD) row.bouncerNetId else null,
        // WSS transport follows the physical endpoint: the bouncer's wsUrl for a bound child.
        wsUrl = endpoint.wsUrl,
    )
}

/**
 * Connection-affecting fingerprint for one network row. Any change here restarts the actor
 * (rebuilds the socket): endpoint, identity, SASL, bouncer bind, client-cert alias, and the opt-in
 * WSS URL (plans/19 §3.3), so toggling/editing the WebSocket transport reconnects. Extracted for
 * unit tests.
 */
internal fun networkFingerprint(row: NetworkEntity): String =
    "${row.host}:${row.port}:${row.tls}:${row.nick}:${row.saslMechanism}:${row.bouncerNetId}:${row.clientCertAlias}:${row.wsUrl}"

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

internal fun wantedNetworkIds(
    all: List<NetworkEntity>,
    userIntents: Map<Long, Boolean>,
): Set<Long> =
    all.asSequence()
        .filter { userIntents[it.id] ?: it.autoConnect }
        .filter { it.role != NetworkRole.BOUNCER_CHILD || it.parentId != null }
        .map { it.id }
        .toSet()
