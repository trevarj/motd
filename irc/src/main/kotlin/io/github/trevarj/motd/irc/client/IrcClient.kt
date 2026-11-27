package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.*
import io.github.trevarj.motd.irc.ext.BatchAssembler
import io.github.trevarj.motd.irc.ext.BatchChild
import io.github.trevarj.motd.irc.ext.BatchTree
import io.github.trevarj.motd.irc.ext.BouncerCommands
import io.github.trevarj.motd.irc.ext.ChatHistoryCommands
import io.github.trevarj.motd.irc.ext.ReadMarkerCommands
import io.github.trevarj.motd.irc.ext.SearchCommands
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.irc.ext.TypingOutbox
import io.github.trevarj.motd.irc.ext.WebPushCommands
import io.github.trevarj.motd.irc.ext.parseSearchResult
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportConfigurationException
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SaslMechanism { NONE, PLAIN, EXTERNAL }

enum class NickServIdentifySyntax { NICK_PASSWORD, PASSWORD_NICK, PASSWORD_ONLY }

/** One automatic preferred-nick recovery across every socket owned by a connection actor. */
class NickRecoveryGuard {
    private val claimed = AtomicBoolean(false)

    internal fun claim(): Boolean = claimed.compareAndSet(false, true)
}

data class IrcClientConfig(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val nick: String,
    val username: String,
    val realname: String,
    val sasl: SaslMechanism = SaslMechanism.NONE,
    val saslUser: String? = null,
    val saslPassword: String? = null,
    /** Optional IRC server password, sent with PASS before registration. */
    val serverPassword: String? = null,
    /** Optional per-network away message to apply during or immediately after registration. */
    val initialAwayMessage: String? = null,
    /** Optional NickServ fallback, used only when SASL is disabled. */
    val nickServPassword: String? = null,
    val nickServIdentifySyntax: NickServIdentifySyntax = NickServIdentifySyntax.NICK_PASSWORD,
    /** Empty disables automatic recovery; otherwise commands run in order before renicking. */
    val nickServRecoveryCommands: List<String> = emptyList(),
    /** soju: bind this connection to a bouncer network before CAP END. */
    val bouncerNetId: String? = null,
    /** Extra caps to request beyond the built-in tiers (rarely needed). */
    val extraCaps: Set<String> = emptySet(),
    /**
     * Opt-in IRC-over-WebSocket transport. When non-null (e.g.
     * `wss://bnc.example.com:443/`) the connection is tunneled over a WebSocket to that URL to
     * blend with ordinary HTTPS; when null the default TCP/TLS line transport is used. TLS,
     * hostname verification, and cert pinning still key on the WS URL's real host/port.
     */
    val wsUrl: String? = null,
)

data class ChatHistoryRequest(
    val subcommand: Subcommand,
    val target: String,
    /** Bounds are "timestamp=<ISO8601>" or "msgid=<id>" selectors, pre-rendered. */
    val bound1: String? = null,
    val bound2: String? = null,
    val limit: Int,
) {
    enum class Subcommand { LATEST, BEFORE, AFTER, AROUND, BETWEEN, TARGETS }
}

data class BouncerNetwork(
    val netId: String,
    val attrs: Map<String, String>,
) // attrs: name,host,state,nickname,...

/** One RPL_LIST (322) row. */
data class ChannelListing(
    val name: String,
    val userCount: Int,
    val topic: String,
)

data class ChannelListProgress(
    val listings: List<ChannelListing>,
    val matchedCount: Long,
)

/** Collects one unlabelled LIST response. The caller must start this before writing LIST. */
internal class UnlabeledChannelListCollector(
    scope: CoroutineScope,
    events: SharedFlow<IrcEvent>,
    private val isRecipient: (String) -> Boolean,
    private val onListLine: (IrcMessage) -> Unit,
    optionalTerminators: Int = 0,
) {
    val terminal = CompletableDeferred<IrcCommandException?>()

    @Volatile
    var optionalTerminators = optionalTerminators
        private set

    val job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                events.first(::observe)
            } finally {
                terminal.cancel()
            }
        }

    private fun observe(event: IrcEvent): Boolean {
        when (event) {
            is IrcEvent.Raw -> {
                val message = event.message
                val command = message.command
                if (command != "321" && command != "322" && command != "323" && command != "263") return false
                if ("label" in message.tags || "batch" in message.tags) return false
                if (message.params.firstOrNull()?.let(isRecipient) != true) return false
                if (command == "323") {
                    if (optionalTerminators > 0) {
                        optionalTerminators--
                        return terminal.isCompleted && optionalTerminators == 0
                    }
                    terminal.complete(null)
                    return true
                }
                if (terminal.isCompleted) return false
                if (command == "321" || command == "322") {
                    optionalTerminators = 0
                    if (command == "322") onListLine(message)
                } else {
                    message.listRejection()?.let(::reject)
                }
            }

            is IrcEvent.StandardReply -> {
                if (!terminal.isCompleted && event.ctx.label == null && event.ctx.batchId == null &&
                    event.severity == IrcEvent.StandardReplySeverity.FAIL && event.commandName.equals("LIST", ignoreCase = true)
                ) {
                    reject(IrcCommandException("LIST", event.code, event.description))
                }
            }

            else -> {
                return false
            }
        }
        return false
    }

    private fun reject(error: IrcCommandException) {
        optionalTerminators++
        terminal.complete(error)
    }
}

private fun IrcMessage.isListThrottle(): Boolean = command == "263" && params.getOrNull(1).equals("LIST", ignoreCase = true)

private fun IrcMessage.listRejection(): IrcCommandException? =
    when {
        isListThrottle() -> {
            IrcCommandException("LIST", command, params.getOrNull(2).orEmpty())
        }

        command == "FAIL" && params.firstOrNull().equals("LIST", ignoreCase = true) -> {
            IrcCommandException("LIST", params.getOrNull(1) ?: "FAIL", params.lastOrNull().orEmpty())
        }

        else -> {
            null
        }
    }

private data class UnlabeledChannelListResponse(
    val transport: IrcTransport,
    val collector: UnlabeledChannelListCollector,
)

/** Memory-bounded accumulator that retains the busiest channels and preserves arrival order ties. */
private class BoundedChannelListings(
    private val capacity: Int,
) {
    private data class Entry(
        val listing: ChannelListing,
        val order: Long,
    )

    @Volatile
    var progress: Channel<ChannelListProgress>? = null

    private val entries =
        PriorityQueue<Entry>(
            compareBy<Entry> { it.listing.userCount }.thenByDescending { it.order },
        )
    private var matchedCount = 0L
    private var lastProgressNanos = 0L

    fun add(element: ChannelListing) {
        val order = matchedCount++
        if (capacity > 0 && (entries.size < capacity || element.userCount > entries.peek().listing.userCount)) {
            if (entries.size == capacity) entries.remove()
            entries.add(Entry(element, order))
        }
        val mailbox = progress ?: return
        val now = System.nanoTime()
        if (matchedCount != 1L && now - lastProgressNanos < 200_000_000L) return
        lastProgressNanos = now
        mailbox.trySend(ChannelListProgress(toList(), matchedCount))
    }

    fun stopProgress() {
        progress?.cancel()
        progress = null
    }

    fun toList(): List<ChannelListing> =
        entries
            .sortedWith(compareByDescending<Entry> { it.listing.userCount }.thenBy { it.order })
            .map(Entry::listing)
}

data class WhoxResult(
    val rows: List<IrcEvent.WhoxRow>,
    val completed: Boolean,
)

/** One instance per physical socket. Restartable: start() after stop() reconnects fresh. */
class IrcClient(
    val config: IrcClientConfig,
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    private val observerBufferCapacity: Int = OBSERVER_EVENT_CAPACITY,
    private val nickRecoveryGuard: NickRecoveryGuard = NickRecoveryGuard(),
    clientVersion: String = "motd",
    /** Must dispatch rather than resume inline on the protocol reader. */
    private val listProgressDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<IrcClientState>(IrcClientState.Disconnected)
    val state: StateFlow<IrcClientState> = _state.asStateFlow()
    private val _joinedChannels = MutableStateFlow<Set<String>>(emptySet())
    val joinedChannels: StateFlow<Set<String>> = _joinedChannels.asStateFlow()
    private val selfPrivileges = SelfChannelPrivileges()
    val selfChannelModes: StateFlow<Map<String, Set<Char>>> = selfPrivileges.modes

    @Volatile
    var latestSelfMembershipEvent: IrcEvent? = null
        private set
    private val _targetClassificationReady = MutableStateFlow(false)

    /** True once CHANTYPES is explicit or the registration burst confirms protocol defaults. */
    val targetClassificationReady: StateFlow<Boolean> = _targetClassificationReady.asStateFlow()
    private val _pendingFeatureCaps = MutableStateFlow<Set<String>>(emptySet())

    /** Runtime/deferred CAP requests that have not received ACK or NAK yet. */
    val pendingFeatureCaps: StateFlow<Set<String>> = _pendingFeatureCaps.asStateFlow()

    private val _events =
        MutableSharedFlow<IrcEvent>(
            replay = 0,
            extraBufferCapacity = observerBufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /** Best-effort fan-out for transient request correlation and UI-adjacent observers. */
    val broadcastEvents: SharedFlow<IrcEvent> = _events.asSharedFlow()

    private var criticalEventChannel = Channel<IrcEvent>(CRITICAL_EVENT_CAPACITY)

    /**
     * Ordered, non-dropping delivery for the sole persistence consumer. A new channel is installed
     * by [start] and closes only after the connection's final event has been published.
     */
    val criticalEvents: ReceiveChannel<IrcEvent>
        get() = criticalEventChannel

    // Live snapshot of the bouncer's networks (netId -> attrs), fed by BOUNCER NETWORK
    // notifications. soju advertises no labeled-response, so the LISTNETWORKS reply and the
    // passive soju.im/bouncer-networks-notify pushes both arrive as ordinary events; we
    // accumulate them here so bouncerListNetworks() has real data to return.
    private val _bouncerNetworks = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val bouncerNetworks: StateFlow<Map<String, Map<String, String>>> = _bouncerNetworks.asStateFlow()

    private val selfNick = AtomicReference(config.nick)
    private val _isupport = AtomicReference(Isupport())
    private val ackedCaps = AtomicReference<Set<String>>(emptySet())
    private var negotiatedCaps: Set<String> = emptySet()
    private val runtimeAdvertisedCaps = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val labels = LabelCorrelator()
    private val unlabeledChatHistory = UnlabeledChatHistoryCorrelator()
    private val unlabeledChatHistoryLock = Mutex()
    private val unlabeledSearch = UnlabeledSearchCorrelator()
    private val unlabeledSearchLock = Mutex()
    private val unlabeledWhois = AtomicReference<UnlabeledWhoisCorrelator?>(null)
    private val unlabeledWhoisLock = Mutex()
    private val unlabeledChannelListLock = Mutex()
    private var unlabeledChannelListDrain: UnlabeledChannelListResponse? = null
    private val channelBanSession = AtomicReference<ChannelBanRequests?>(null)
    private val joinRejections = AtomicReference<JoinRejections?>(null)
    private val accountRegistrationLock = Mutex()
    private val sensitiveEchoes = ConcurrentHashMap<String, Int>()
    private val outboundLock = Mutex()
    private val batches = BatchAssembler()
    private val typingOutbox = TypingOutbox()
    private val eventMapper =
        EventMapper(
            selfNick = { selfNick.get() },
            isupport = { _isupport.get() },
            sojuReadCap = { hasCap("soju.im/read") },
            clientVersion = clientVersion,
        )
    private val whoxRequests = ConcurrentHashMap<String, Deferred<WhoxResult>>()
    private val whoxTokens = WhoxTokenPool()

    @Volatile private var transport: IrcTransport? = null

    @Volatile private var watchdog: PingWatchdog? = null

    @Volatile private var lagMonitor: LagMonitor? = null
    private var runJob: Job? = null

    private val _lag = MutableStateFlow<Long?>(null)

    /**
     * Latest PING/PONG round-trip latency in ms (issue #34); null until the first probe completes
     * or while disconnected. Stable across the connection's lifetime: the active [LagMonitor] writes
     * here directly, so a collector that attaches before the first probe still observes later
     * readings instead of pinning a throwaway flow.
     */
    val lag: StateFlow<Long?> = _lag.asStateFlow()

    // Set once registration completes; used to gate steady-state routing.
    @Volatile private var registered = false

    /**
     * Completed by the first server line dispatched AFTER registration, per connection. This is the
     * "the stream is flowing again" proof [RegistrationStateMachine.Action.SendDeferred] waits on:
     * the soju child fallback marks Ready from the pre-welcome CAP mutation, and its deferred
     * feature `CAP REQ`s go out as soon as any later line lands instead of always waiting the full
     * fallback ceiling. Replaced in [start] so a retry's waiter can never be satisfied by the
     * previous connection's traffic.
     */
    @Volatile private var postRegistrationActivity = CompletableDeferred<Unit>()

    fun start() {
        stop()
        eventMapper.reset()
        require(observerBufferCapacity > 0) { "observer buffer capacity must be positive" }
        postRegistrationActivity = CompletableDeferred()
        val criticalEvents = Channel<IrcEvent>(CRITICAL_EVENT_CAPACITY)
        criticalEventChannel = criticalEvents
        registered = false
        _targetClassificationReady.value = false
        _pendingFeatureCaps.value = emptySet()
        _bouncerNetworks.value = emptyMap() // drop any stale networks from a prior connection
        _state.value = IrcClientState.Connecting
        runJob = scope.launch { run(criticalEvents) }
    }

    fun stop() {
        _joinedChannels.value = emptySet()
        selfPrivileges.clear()
        latestSelfMembershipEvent = null
        watchdog?.stop()
        watchdog = null
        lagMonitor?.stop()
        lagMonitor = null
        channelBanSession.getAndSet(null)?.close()
        joinRejections.getAndSet(null)?.close()
        runJob?.cancel()
        runJob = null
        criticalEventChannel.cancel(CancellationException("client stopped"))
        labels.failAll(CancellationException("client stopped"))
        unlabeledChatHistory.failAll(CancellationException("client stopped"))
        unlabeledSearch.failAll(CancellationException("client stopped"))
        unlabeledWhois.getAndSet(null)?.failAll(CancellationException("client stopped"))
        cancelWhoxRequests("client stopped")
        sensitiveEchoes.clear()
        batches.reset()
        eventMapper.reset()
        val t = transport
        transport = null
        registered = false
        _targetClassificationReady.value = false
        _pendingFeatureCaps.value = emptySet()
        ackedCaps.set(emptySet())
        runtimeAdvertisedCaps.clear()
        if (t != null) scope.launch { runCatching { t.close() } }
        if (_state.value != IrcClientState.Disconnected) {
            _state.value = IrcClientState.Disconnected
        }
    }

    /** Wait until the socket reader has published and closed the current critical event channel. */
    suspend fun awaitTermination() {
        runJob?.join()
    }

    /**
     * Probe a registered connection immediately using the watchdog's normal PING/grace
     * semantics. A response (or any other inbound line) keeps the Ready socket in place; a timeout
     * transitions it to Disconnected through the watchdog callback so the owning actor can retry.
     */
    suspend fun probeLiveness(graceMs: Long): Boolean {
        if (_state.value !is IrcClientState.Ready) return false
        return watchdog?.probe(graceMs) == true
    }

    private suspend fun run(criticalEvents: Channel<IrcEvent>) {
        val disconnectedPublished = AtomicBoolean(false)
        try {
            runConnection(criticalEvents, disconnectedPublished)
        } finally {
            criticalEvents.close()
        }
    }

    private suspend fun runConnection(
        criticalEvents: Channel<IrcEvent>,
        disconnectedPublished: AtomicBoolean,
    ) {
        // proxy is null here; the app's per-network AppTransportFactory captures its own proxy.
        val t = factory.create(config.host, config.port, config.tls, config.wsUrl, null)
        val joinSession = JoinRejections(t)
        joinRejections.set(joinSession)
        val whoisSession = UnlabeledWhoisCorrelator(t)
        unlabeledWhois.set(whoisSession)
        transport = t
        val banSession = ChannelBanRequests(t)
        channelBanSession.set(banSession)
        try {
            t.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Invalid persisted transport config cannot recover through backoff. Keep ordinary
            // socket/TLS failures retryable, but park until the user changes this setting.
            _state.value =
                IrcClientState.Failed(
                    "connect failed: ${e.message}",
                    fatal = e is TransportConfigurationException,
                )
            emitDisconnected(criticalEvents, disconnectedPublished, e.message)
            if (transport === t) transport = null
            if (joinRejections.compareAndSet(joinSession, null)) joinSession.close()
            if (unlabeledWhois.compareAndSet(whoisSession, null)) whoisSession.failAllDisconnected(e.message)
            return
        }

        _state.value = IrcClientState.Registering
        val reg = RegistrationStateMachine(config, nickRecoveryGuard::claim)
        for (a in reg.start()) applyRegAction(a, t, criticalEvents, disconnectedPublished)

        val wd =
            PingWatchdog(
                scope = scope,
                sendPing = { payload -> runCatching { sendSerialized(t, "PING $payload") } },
                onDead = {
                    _state.value = IrcClientState.Disconnected
                    emitDisconnected(criticalEvents, disconnectedPublished, "watchdog timeout")
                    runCatching { t.close() }
                },
            )
        watchdog = wd
        wd.start()

        val lm =
            LagMonitor(
                scope = scope,
                sendPing = { payload -> runCatching { sendSerialized(t, "PING $payload") } },
                isRegistered = { registered },
                sink = _lag,
            )
        lagMonitor = lm
        lm.start()

        var metadataWireSequence = 0L
        try {
            t.incoming.collect { line ->
                wd.onInbound()
                val parsed =
                    try {
                        IrcMessage.parse(line)
                    } catch (_: IrcParseException) {
                        return@collect
                    }
                val isMetadataFrame =
                    when (parsed.command) {
                        "366", "730", "731", "760", "761", "766", "770", "771", "772", "774", "775",
                        "JOIN", "PART", "KICK", "QUIT", "NICK", "RENAME", "METADATA",
                        -> true

                        "BATCH" -> parsed.params.getOrNull(1) == "metadata" && parsed.params[0].startsWith("+")

                        "NOTE", "WARN", "FAIL" -> parsed.params.firstOrNull() == "METADATA"

                        else -> false
                    }
                val msg =
                    if (isMetadataFrame && METADATA_VERSIONED_CAPS.any(::hasCap) && hasCap("batch")) {
                        parsed.copy(wireSequence = ++metadataWireSequence, selfNickAtRead = selfNick.get())
                    } else if (!registered && parsed.command == "001") {
                        parsed.copy(wireSequence = ++metadataWireSequence)
                    } else {
                        parsed
                    }
                // Answer server PING immediately, before any mapping.
                if (msg.command == "PING") {
                    runCatching { sendSerialized(t, "PONG ${msg.params.firstOrNull().orEmpty()}") }
                    return@collect
                }
                // Correlate our own lag probes; an unmatched PONG (e.g. the watchdog's keepalive
                // echo or an unsolicited server PONG) falls through to dispatch as before.
                if (msg.command == "PONG" && lm.onPong(msg.params)) return@collect
                if (!registered) {
                    // Bouncer children can receive valued CAP NEW immediately before the
                    // registration machine marks them Ready. Retain those values so the
                    // subsequent value-less deferred ACK cannot widen a limited capability.
                    rememberAdvertisedCaps(msg)
                    for (a in reg.onMessage(msg)) {
                        applyRegAction(a, t, criticalEvents, disconnectedPublished, msg.wireSequence)
                    }
                } else {
                    // First post-registration line: release any deferred feature CAP REQs waiting
                    // on proof the stream survived the registration fallback (see SendDeferred).
                    if (msg.command == "CAP") {
                        dispatch(msg, t, criticalEvents)
                        postRegistrationActivity.complete(Unit)
                    } else {
                        postRegistrationActivity.complete(Unit)
                        dispatch(msg, t, criticalEvents)
                    }
                }
            }
            // Clean EOF.
            if (_state.value !is IrcClientState.Failed) {
                _state.value = IrcClientState.Disconnected
            }
            emitDisconnected(criticalEvents, disconnectedPublished, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_state.value !is IrcClientState.Failed) {
                _state.value = IrcClientState.Disconnected
            }
            emitDisconnected(criticalEvents, disconnectedPublished, e.message)
        } finally {
            if (channelBanSession.compareAndSet(banSession, null)) banSession.close()
            if (joinRejections.compareAndSet(joinSession, null)) joinSession.close()
            wd.stop()
            lm.stop()
            if (transport === t) lagMonitor = null
            if (currentCoroutineContext().isActive) {
                labels.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                unlabeledChatHistory.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                unlabeledSearch.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                if (unlabeledWhois.compareAndSet(whoisSession, null)) {
                    whoisSession.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                }
            } else {
                labels.failAll(CancellationException("connection closed"))
                unlabeledChatHistory.failAll(CancellationException("connection closed"))
                unlabeledSearch.failAll(CancellationException("connection closed"))
                if (unlabeledWhois.compareAndSet(whoisSession, null)) {
                    whoisSession.failAll(CancellationException("connection closed"))
                }
            }
            cancelWhoxRequests("connection closed")
            batches.reset()
            eventMapper.reset()
            if (transport === t) transport = null
        }
    }

    private fun cancelWhoxRequests(reason: String) {
        whoxRequests.values.forEach { it.cancel(CancellationException(reason)) }
        whoxRequests.clear()
        whoxTokens.clear()
    }

    private suspend fun applyRegAction(
        a: RegistrationStateMachine.Action,
        t: IrcTransport,
        criticalEvents: Channel<IrcEvent>,
        disconnectedPublished: AtomicBoolean,
        wireSequence: Long = 0L,
    ) {
        when (a) {
            is RegistrationStateMachine.Action.Send -> {
                runCatching { sendSerialized(t, a.line) }
            }

            is RegistrationStateMachine.Action.SendDeferred -> {
                // Capture this connection's activity signal; the field is replaced on restart, so
                // a late waiter can never be released by the next connection's traffic (the
                // transport identity check below guards the send itself either way).
                val activity = postRegistrationActivity
                scope.launch {
                    // Send as soon as the post-Complete stream proves live, or at the fallback
                    // ceiling — never earlier than the CAP-mutation window the ceiling protects.
                    withTimeoutOrNull(a.delayMs) { activity.await() }
                    runCatching {
                        outboundLock.withLock {
                            if (transport !== t) return@withLock
                            val cap = a.line.substringAfter("CAP REQ :")
                            if (cap !in _pendingFeatureCaps.value) return@withLock
                            if (cap == "labeled-response" && !hasCap("batch")) {
                                _pendingFeatureCaps.update { it - cap }
                                return@withLock
                            }
                            if (cap in METADATA_CAP_ALIASES) {
                                val selected =
                                    CapNegotiator
                                        .runtimeRequestSet(runtimeAdvertisedCaps.keys, caps, config.extraCaps)
                                        .firstOrNull { it in METADATA_CAP_ALIASES }
                                if (selected != cap) {
                                    _pendingFeatureCaps.update { pending -> if (selected == null) pending - cap else pending - cap + selected }
                                    if (selected != null) t.send("CAP REQ :$selected")
                                    return@withLock
                                }
                            }
                            t.send(a.line)
                        }
                    }
                }
            }

            is RegistrationStateMachine.Action.Emit -> {
                publish(criticalEvents, a.event)
            }

            is RegistrationStateMachine.Action.SetNick -> {
                selfNick.set(a.nick)
            }

            is RegistrationStateMachine.Action.Complete -> {
                selfNick.set(a.nick)
                _isupport.set(a.isupport)
                val removedIsupport = a.isupport.removedKeys
                _targetClassificationReady.value =
                    a.isupport["CHANTYPES"] != null || "CHANTYPES" in removedIsupport || a.assumeDefaultTargetClassification
                ackedCaps.set(a.caps)
                negotiatedCaps = a.ackedCaps
                _pendingFeatureCaps.value = a.deferredCaps
                registered = true
                val isupportMap = isupportToMap(a.isupport)
                _state.value = IrcClientState.Ready(a.nick, a.caps, isupportMap, removedIsupport)
                val sequence = wireSequence.takeIf { hasCap(METADATA_V3_CAP) && hasCap("batch") } ?: 0L
                publish(criticalEvents, IrcEvent.Registered(a.nick, a.caps, isupportMap, removedIsupport, wireSequence = sequence))
            }

            is RegistrationStateMachine.Action.Fail -> {
                _state.value = IrcClientState.Failed(a.reason, a.fatal)
                emitDisconnected(criticalEvents, disconnectedPublished, a.reason)
                runCatching { t.close() }
            }
        }
    }

    /** Steady-state dispatch: label correlation → CAP NEW/DEL → batch assembly → event mapping. */
    private suspend fun dispatch(
        incoming: IrcMessage,
        t: IrcTransport,
        criticalEvents: Channel<IrcEvent>,
    ) {
        // Credential-bearing service commands must never become persisted self-echo chat rows.
        if (consumeSensitiveEcho(incoming)) return
        // Labeled responses are consumed by the correlator (incl. their batch contents).
        if (labels.route(incoming)) return
        val whoisSession = unlabeledWhois.get()
        val msg = if (whoisSession != null && whoisSession.transport === t) whoisSession.route(incoming, isupport) ?: return else incoming
        when (msg.command) {
            "367", "368", "403", "442", "482", "FAIL" -> {
                val banSession = channelBanSession.get()
                if (banSession != null && banSession.transport === t &&
                    banSession.routeUnlabeled(msg, selfNick.get(), isupport.identityRules.caseMapping)
                ) {
                    return
                }
            }
        }
        // Released soju lacks labeled-response, but its CHATHISTORY/SEARCH replies remain batched.
        if (unlabeledChatHistory.route(msg)) return
        if (unlabeledSearch.route(msg)) return

        // Runtime CAP NEW/DEL.
        if (msg.command == "CAP") {
            handleRuntimeCap(msg, t, criticalEvents)
            return
        }

        // A bouncer may publish Ready before 001; the upstream's authoritative nick still wins.
        if (msg.command == "001") {
            val nick = msg.params.firstOrNull()?.takeIf { it.isNotBlank() }
            val current = _state.value as? IrcClientState.Ready
            if (nick != null && current != null && nick != current.nick) {
                selfNick.set(nick)
                _state.value = current.copy(nick = nick)
                publish(criticalEvents, IrcEvent.Registered(nick, current.caps, current.isupport, current.removedIsupport, startsSession = false))
            }
        }

        // 005 normally arrives after 001. Keep Ready's snapshot current so app-owned feature
        // gates (notably CLIENTTAGDENY) do not operate on the empty registration-time map.
        if (msg.command == "005") updateRuntimeIsupport(msg, criticalEvents)
        if (msg.command == "376" || msg.command == "422") {
            _targetClassificationReady.value = true
        }

        when (val outcome = batches.route(msg)) {
            BatchAssembler.Outcome.Buffered -> {
                return
            }

            is BatchAssembler.Outcome.Closed -> {
                emitBatch(outcome, t, criticalEvents)
            }

            is BatchAssembler.Outcome.Overflow -> {
                throw IrcProtocolException("BATCH", outcome.detail)
            }

            BatchAssembler.Outcome.PassThrough -> {
                val ev =
                    eventMapper.map(msg, batchId = null) { reply ->
                        // CTCP auto-reply (e.g. VERSION) — fire and forget on the client scope.
                        scope.launch { runCatching { sendSerialized(t, reply) } }
                    }
                if (ev != null) {
                    applySelfNickChange(ev, t)
                    emitEvent(if ("batch" in msg.tags) ev else correlateJoinRejection(t, ev), criticalEvents)
                }
            }
        }
    }

    /** Confirm self identity before mapping later traffic, then make at most one recovery attempt. */
    private suspend fun applySelfNickChange(
        ev: IrcEvent,
        t: IrcTransport,
    ) {
        if (ev !is IrcEvent.NickChanged || !ev.isSelf) return
        selfNick.set(ev.to)
        (_state.value as? IrcClientState.Ready)?.let { ready ->
            _state.value = ready.copy(nick = ev.to)
        }
        if (_isupport.get().normalize(ev.to) == _isupport.get().normalize(config.nick)) return
        if (config.sasl != SaslMechanism.NONE || config.nickServPassword.isNullOrEmpty() ||
            config.nickServRecoveryCommands.isEmpty() || !nickRecoveryGuard.claim()
        ) {
            return
        }
        for (line in nickServRecoveryLines(config)) sendSerialized(t, line)
    }

    /** Emit an event, accumulating bouncer-network snapshots as a side effect. */
    private suspend fun emitEvent(
        ev: IrcEvent,
        criticalEvents: Channel<IrcEvent>,
    ) {
        selfPrivileges.apply(ev, isupport, selfNick.get(), _joinedChannels.value)
        updateJoinedChannels(ev)
        if (ev is IrcEvent.BouncerNetworkState) {
            _bouncerNetworks.update { cur ->
                // Empty attrs is soju's `BOUNCER NETWORK <id> *` deletion marker.
                if (ev.attrs.isEmpty()) cur - ev.netId else cur + (ev.netId to ev.attrs)
            }
        }
        publish(criticalEvents, ev)
    }

    private fun updateJoinedChannels(
        event: IrcEvent,
        publishedEvent: IrcEvent = event,
    ) {
        val channel =
            when (event) {
                is IrcEvent.Joined -> {
                    event.channel.takeIf { event.isSelf }
                }

                is IrcEvent.Parted -> {
                    event.channel.takeIf { event.isSelf }
                }

                is IrcEvent.Kicked -> {
                    event.channel.takeIf { event.isSelf }
                }

                is IrcEvent.ChannelRenamed -> {
                    val old = isupport.normalize(event.oldName)
                    _joinedChannels.update { joined ->
                        if (joined.none { isupport.normalize(it) == old }) {
                            joined
                        } else {
                            joined.filterNotTo(linkedSetOf()) { isupport.normalize(it) == old } + event.newName
                        }
                    }
                    return
                }

                is IrcEvent.NetworkBatch -> {
                    if (event.historyMetadata?.isContext != true) {
                        event.events.forEach { updateJoinedChannels(it, publishedEvent) }
                    }
                    return
                }

                else -> {
                    return
                }
            } ?: return
        latestSelfMembershipEvent = publishedEvent
        val normalized = isupport.normalize(channel)
        _joinedChannels.update { joined ->
            val containsChannel = joined.any { isupport.normalize(it) == normalized }
            when {
                event is IrcEvent.Joined -> if (containsChannel) joined else joined + channel
                containsChannel -> joined.filterNotTo(linkedSetOf()) { isupport.normalize(it) == normalized }
                else -> joined
            }
        }
        if (event !is IrcEvent.Joined) {
            channelBanSession.get()?.left(channel, isupport.identityRules.caseMapping)
        }
    }

    private fun correlateJoinRejection(
        t: IrcTransport,
        event: IrcEvent,
    ): IrcEvent = joinRejections.get()?.takeIf { it.transport === t }?.correlate(event, isupport, selfNick.get()) ?: event

    private suspend fun emitBatch(
        closed: BatchAssembler.Outcome.Closed,
        t: IrcTransport,
        criticalEvents: Channel<IrcEvent>,
    ) {
        for (event in mapBatchTree(closed.tree)) {
            // Batch mapping carries an outer response label down to its replies.
            emitEvent(if (event.messageContextOrNull()?.label == null) event else correlateJoinRejection(t, event), criticalEvents)
        }
    }

    internal fun mapBatchTree(
        tree: BatchTree,
        historical: Boolean = tree.type == "chathistory" || tree.type == "znc.in/playback",
    ): List<IrcEvent> = mapBatchTree(tree, historical, inheritedLabel = null)

    private fun mapBatchTree(
        tree: BatchTree,
        historical: Boolean,
        inheritedLabel: String?,
        joinedChannels: Set<String> = emptySet(),
    ): List<IrcEvent> {
        val responseLabel = tree.opening.tags["label"] ?: inheritedLabel
        if (tree.type == "metadata" && !historical && hasCap(METADATA_V3_CAP) && hasCap("batch")) {
            val target = tree.params.firstOrNull() ?: return emptyList()
            if (responseLabel == null || (responseLabel == inheritedLabel && isupport.normalize(target) in joinedChannels)) {
                val messages = tree.children.mapNotNull { (it as? BatchChild.Message)?.message }
                return listOf(
                    IrcEvent.MetadataSnapshot(
                        target,
                        messages,
                        tree.opening.wireSequence,
                        tree.openedAtNanos,
                        tree.opening.selfNickAtRead,
                        joinResponseLabel = responseLabel,
                    ),
                )
            }
        }

        val responseWrapper = tree.type.equals("labeled-response", ignoreCase = true)
        val eventBatchId = tree.ref.takeUnless { responseWrapper }
        val metadataJoinTargets =
            if (responseWrapper && responseLabel != null && !historical &&
                tree.children.any { it is BatchChild.Nested } && hasCap(METADATA_V3_CAP) && hasCap("batch")
            ) {
                tree.children.mapNotNullTo(joinedChannels.toMutableSet()) { child ->
                    val message = (child as? BatchChild.Message)?.message ?: return@mapNotNullTo null
                    if (message.command != "JOIN" || (message.tags["label"] ?: responseLabel) != responseLabel) return@mapNotNullTo null
                    val nick = message.source?.nick ?: return@mapNotNullTo null
                    val ownNick = message.selfNickAtRead ?: return@mapNotNullTo null
                    if (isupport.normalize(nick) != isupport.normalize(ownNick)) return@mapNotNullTo null
                    message.params.firstOrNull()?.let(isupport::normalize)
                }
            } else {
                joinedChannels
            }

        fun labeled(message: IrcMessage): IrcMessage {
            var tags = message.tags
            if (responseWrapper && tags["batch"] == tree.ref) tags = tags - "batch"
            if (responseLabel != null && "label" !in tags) tags = tags + ("label" to responseLabel)
            return if (tags === message.tags) message else message.copy(tags = tags)
        }
        if (tree.type == MULTILINE_CAP) {
            mapMultilineBatch(tree, historical, responseLabel)?.let { return listOf(it) }
            return mapMalformedMultiline(tree, historical, responseLabel)
        }

        if (tree.type == "netsplit" || tree.type == "netjoin") {
            val leaves = tree.leafMessages()
            val expected = if (tree.type == "netsplit") "QUIT" else "JOIN"
            if (tree.params.size == 2 && leaves.isNotEmpty() && leaves.all { it.first.command == expected }) {
                val events =
                    leaves.mapNotNull { (message, batchRef) ->
                        eventMapper.map(labeled(message), batchId = batchRef, historical = historical)
                    }
                if (events.size == leaves.size) {
                    val historyReference = historyReference(tree.opening)
                    return listOf(
                        IrcEvent.NetworkBatch(
                            kind =
                                if (tree.type == "netsplit") {
                                    IrcEvent.NetworkBatchKind.NETSPLIT
                                } else {
                                    IrcEvent.NetworkBatchKind.NETJOIN
                                },
                            serverA = tree.params[0],
                            serverB = tree.params[1],
                            events = events,
                            historyMetadata =
                                HistoryEventMetadata(
                                    isContext = "draft/chathistory-context" in tree.opening.tags,
                                    msgid = historyReference?.msgid,
                                    serverTime = historyReference?.serverTime,
                                ),
                        ),
                    )
                }
            }
        }

        val flattened =
            tree.children.flatMap { child ->
                when (child) {
                    is BatchChild.Message -> {
                        listOfNotNull(
                            eventMapper.map(labeled(child.message), batchId = eventBatchId, historical = historical),
                        )
                    }

                    is BatchChild.Nested -> {
                        val nestedHistory = historical || child.batch.type == "chathistory" || child.batch.type == "znc.in/playback"
                        val sameLabelTargets =
                            if (child.batch.opening.tags["label"]
                                    ?.let { it != responseLabel } == true
                            ) {
                                emptySet()
                            } else {
                                metadataJoinTargets
                            }
                        mapBatchTree(child.batch, nestedHistory, responseLabel, sameLabelTargets)
                    }
                }
            }
        if (tree.type == "metadata" && !historical && responseLabel == null && hasCap("draft/metadata-2") && hasCap("batch")) {
            val target = tree.params.firstOrNull() ?: return flattened
            val messages = tree.children.mapNotNull { (it as? BatchChild.Message)?.message }
            return flattened +
                IrcEvent.MetadataSnapshot(
                    target,
                    messages,
                    tree.opening.wireSequence,
                    tree.openedAtNanos,
                    tree.opening.selfNickAtRead,
                )
        }
        return if (tree.type == "chathistory") {
            val target = tree.params.firstOrNull().orEmpty()
            listOf(
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.CHATHISTORY,
                    target = target,
                    items =
                        flattened.mapIndexed { ordinal, event ->
                            val targeted =
                                if (event is IrcEvent.NetworkBatch && event.target == null) {
                                    event.copy(target = target)
                                } else {
                                    event
                                }
                            IrcEvent.PlaybackItem.from(targeted, ordinal)
                        },
                ),
            )
        } else if (tree.type == "znc.in/playback") {
            val target = tree.params.firstOrNull().orEmpty()
            listOf(
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.ZNC_PLAYBACK,
                    target = target,
                    items =
                        flattened.mapIndexed { ordinal, event ->
                            IrcEvent.PlaybackItem.from(event, ordinal)
                        },
                ),
            )
        } else {
            flattened
        }
    }

    private fun mapMultilineBatch(
        tree: BatchTree,
        historical: Boolean = false,
        responseLabel: String? = null,
    ): IrcEvent.ChatMessage? {
        val target = tree.params.firstOrNull() ?: return null
        val messages =
            tree.children.map {
                (it as? BatchChild.Message)?.message ?: return null
            }
        if (messages.isEmpty()) return null
        val command = messages.first().command
        if (command != "PRIVMSG" && command != "NOTICE") return null
        if (messages.any { it.command != command || it.params.getOrNull(0) != target }) return null
        if (messages.any {
                MULTILINE_CONCAT_TAG in it.tags &&
                    it.params
                        .getOrNull(1)
                        .orEmpty()
                        .isEmpty()
            }
        ) {
            return null
        }

        val combined = StringBuilder()
        var sawNonBlank = false
        messages.forEachIndexed { index, message ->
            val text = message.params.getOrNull(1).orEmpty()
            val concat = MULTILINE_CONCAT_TAG in message.tags
            if (concat && index == 0) return null
            if (text.isNotEmpty()) sawNonBlank = true
            if (index > 0 && !concat) combined.append('\n')
            combined.append(text)
        }
        if (!sawNonBlank) return null

        val first = messages.first()
        val synthetic =
            IrcMessage(
                tags = tree.opening.tags + listOfNotNull(responseLabel?.let { "label" to it }),
                source = first.source ?: tree.opening.source,
                command = command,
                params = listOf(target, combined.toString()),
            )
        return eventMapper.map(
            synthetic,
            batchId = tree.opening.tags["batch"] ?: tree.ref,
            historical = historical,
        ) as? IrcEvent.ChatMessage
    }

    private fun mapMalformedMultiline(
        tree: BatchTree,
        historical: Boolean,
        responseLabel: String?,
    ): List<IrcEvent> =
        tree.children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> {
                    val text =
                        child.message.params
                            .getOrNull(1)
                            .orEmpty()
                    if ((child.message.command == "PRIVMSG" || child.message.command == "NOTICE") && text.isEmpty()) {
                        emptyList()
                    } else {
                        listOfNotNull(
                            eventMapper.map(
                                child.message.copy(
                                    tags = child.message.tags + listOfNotNull(responseLabel?.let { "label" to it }),
                                ),
                                batchId = tree.ref,
                                historical = historical,
                            ),
                        )
                    }
                }

                is BatchChild.Nested -> {
                    mapBatchTree(child.batch, historical, responseLabel)
                }
            }
        }

    private fun BatchTree.leafMessages(): List<Pair<IrcMessage, String>> =
        children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> listOf(child.message to ref)
                is BatchChild.Nested -> child.batch.leafMessages()
            }
        }

    private suspend fun handleRuntimeCap(
        msg: IrcMessage,
        t: IrcTransport,
        criticalEvents: Channel<IrcEvent>,
    ) {
        val sub = msg.params.getOrNull(1) ?: return
        val tokens =
            msg.params
                .last()
                .split(' ')
                .filter { it.isNotEmpty() }
        val caps = tokens.map { it.removePrefix("-").substringBefore('=') }.toSet()
        rememberAdvertisedCaps(msg)
        when (sub) {
            "NEW" -> {
                // REQ any tier cap newly advertised that we want.
                val activeMetadata =
                    METADATA_CAP_ALIASES.firstOrNull { alias ->
                        alias in caps && negotiatedCaps.any { it.substringBefore('=') == alias }
                    }
                if (activeMetadata != null) {
                    val value = runtimeAdvertisedCaps[activeMetadata].orEmpty()
                    val token = if (value.isEmpty()) activeMetadata else "$activeMetadata=$value"
                    if (token !in negotiatedCaps) {
                        negotiatedCaps = negotiatedCaps.filterNotTo(linkedSetOf()) { it.substringBefore('=') == activeMetadata } + token
                        publishRuntimeCaps(criticalEvents, setOf(activeMetadata), emptySet())
                    }
                }
                val newlyAvailable = caps - _pendingFeatureCaps.value
                val candidates = if (hasCap("batch")) newlyAvailable else newlyAvailable - METADATA_VERSIONED_CAPS
                requestRuntimeCaps(t, CapNegotiator.runtimeRequestSet(candidates, negotiatedAndPendingMetadataCaps(), config.extraCaps))
            }

            "DEL" -> {
                negotiatedCaps = negotiatedCaps.filterNotTo(linkedSetOf()) { it.substringBefore('=') in caps }
                publishRuntimeCaps(criticalEvents, emptySet(), caps, caps)
            }

            "ACK" -> {
                val removed =
                    tokens
                        .filter { it.startsWith("-") }
                        .map { it.removePrefix("-").substringBefore('=') }
                        .toSet()
                val added =
                    tokens
                        .filterNot { it.startsWith("-") }
                        .map { token ->
                            val name = token.substringBefore('=')
                            val value =
                                token.substringAfter(
                                    '=',
                                    missingDelimiterValue = runtimeAdvertisedCaps[name].orEmpty(),
                                )
                            if (value.isEmpty()) name else "$name=$value"
                        }.toSet()
                negotiatedCaps = negotiatedCaps.filterNotTo(linkedSetOf()) { it.substringBefore('=') in removed } + added
                publishRuntimeCaps(criticalEvents, added.mapTo(linkedSetOf()) { it.substringBefore('=') }, removed, caps)
            }

            "NAK" -> {
                _pendingFeatureCaps.value -= caps
            }
        }
        if (sub == "ACK" && tokens.any { it.substringBefore('=') == "batch" } &&
            CapNegotiator.wantsLabeledResponse(runtimeAdvertisedCaps.keys, negotiatedCaps)
        ) {
            requestRuntimeCaps(t, setOf("labeled-response"))
        }
        if ((sub == "DEL" && METADATA_CAP_ALIASES.any { it in caps }) || (sub == "ACK" && "batch" in caps && hasCap("batch"))) {
            val advertisedMetadata = METADATA_CAP_ALIASES.filterTo(linkedSetOf(), runtimeAdvertisedCaps::containsKey)
            if (advertisedMetadata.isNotEmpty()) {
                requestRuntimeCaps(
                    t,
                    CapNegotiator.runtimeRequestSet(advertisedMetadata, negotiatedAndPendingMetadataCaps(), config.extraCaps),
                )
            }
        }
    }

    private fun negotiatedAndPendingMetadataCaps(): Set<String> =
        if (METADATA_CAP_ALIASES.any { it in _pendingFeatureCaps.value }) {
            negotiatedCaps + METADATA_CAP_ALIASES.filter { it in _pendingFeatureCaps.value }
        } else {
            negotiatedCaps
        }

    private suspend fun requestRuntimeCaps(
        t: IrcTransport,
        caps: Set<String>,
    ) {
        val want = caps - _pendingFeatureCaps.value
        if (want.isEmpty()) return
        _pendingFeatureCaps.value += want
        for (b in CapNegotiator.batches(want)) runCatching { sendSerialized(t, "CAP REQ :$b") }
    }

    private suspend fun publishRuntimeCaps(
        criticalEvents: Channel<IrcEvent>,
        added: Set<String>,
        removed: Set<String>,
        settled: Set<String> = emptySet(),
    ) {
        val usable = CapNegotiator.usableCaps(negotiatedCaps)
        val previouslyUsable = ackedCaps.getAndSet(usable)
        updateReadyCaps(usable)
        val labeled = "labeled-response"
        _pendingFeatureCaps.update { pending ->
            val remaining = pending - settled
            if ("batch" in removed) remaining - labeled else remaining
        }
        val hadLabels = previouslyUsable.any { it.substringBefore('=') == labeled }
        val hasLabels = usable.any { it.substringBefore('=') == labeled }
        val effectiveRemoved =
            if (removed.isEmpty()) {
                emptySet()
            } else {
                removed.filterTo(linkedSetOf()) { name ->
                    previouslyUsable.any { it.substringBefore('=') == name }
                }
            }
        publish(
            criticalEvents,
            IrcEvent.CapsChanged(
                if (hasLabels && !hadLabels) added + labeled else added - labeled,
                if (hadLabels && !hasLabels) effectiveRemoved + labeled else effectiveRemoved - labeled,
            ),
        )
    }

    private fun rememberAdvertisedCaps(msg: IrcMessage) {
        if (msg.command != "CAP") return
        val sub = msg.params.getOrNull(1) ?: return
        val tokens =
            msg.params
                .lastOrNull()
                ?.split(' ')
                ?.filter(String::isNotEmpty)
                .orEmpty()
        when (sub) {
            "LS", "NEW" -> {
                tokens.forEach { token ->
                    val normalized = token.removePrefix("-")
                    val name = normalized.substringBefore('=')
                    runtimeAdvertisedCaps[name] = normalized.substringAfter('=', missingDelimiterValue = "")
                }
            }

            "DEL" -> {
                tokens.forEach { token ->
                    runtimeAdvertisedCaps.remove(token.removePrefix("-").substringBefore('='))
                }
            }
        }
    }

    private fun updateReadyCaps(caps: Set<String>) {
        val current = _state.value
        if (current is IrcClientState.Ready) {
            _state.value = current.copy(caps = caps)
        }
    }

    private suspend fun updateRuntimeIsupport(
        msg: IrcMessage,
        criticalEvents: Channel<IrcEvent>,
    ) {
        val isupport = _isupport.get()
        val priorPrefix = isupport["PREFIX"]
        val priorCaseMapping = isupport.identityRules.caseMapping
        isupport.update(msg.params.drop(1).dropLast(1))
        if (priorPrefix != isupport["PREFIX"] || priorCaseMapping != isupport.identityRules.caseMapping) selfPrivileges.clear()
        val removedIsupport = isupport.removedKeys
        if (isupport["CHANTYPES"] != null || "CHANTYPES" in removedIsupport) _targetClassificationReady.value = true
        channelBanSession.get()?.updateMapping(isupport.identityRules.caseMapping)
        val current = _state.value as? IrcClientState.Ready ?: return
        val snapshot = isupportToMap(isupport)
        _state.value = current.copy(isupport = snapshot, removedIsupport = removedIsupport)
        publish(criticalEvents, IrcEvent.Registered(current.nick, current.caps, snapshot, removedIsupport, startsSession = false))
    }

    private suspend fun emitDisconnected(
        criticalEvents: Channel<IrcEvent>,
        disconnectedPublished: AtomicBoolean,
        reason: String?,
    ) {
        if (!disconnectedPublished.compareAndSet(false, true)) return
        val banSession = channelBanSession.get()
        if (criticalEvents === criticalEventChannel && channelBanSession.compareAndSet(banSession, null)) banSession?.close()
        _joinedChannels.value = emptySet()
        selfPrivileges.clear()
        latestSelfMembershipEvent = null
        publish(criticalEvents, IrcEvent.Disconnected(reason))
    }

    private suspend fun publish(
        criticalEvents: Channel<IrcEvent>,
        event: IrcEvent,
    ) {
        criticalEvents.send(event)
        _events.emit(event)
    }

    private suspend fun sendSerialized(
        t: IrcTransport,
        msg: IrcMessage,
        unlabeledWhoisLabel: String? = null,
        onWrite: (() -> Unit)? = null,
    ): Boolean {
        if (msg.command.equals("WHOIS", ignoreCase = true) && (unlabeledWhoisLabel != null || !hasCap("labeled-response"))) {
            val session = unlabeledWhois.get()?.takeIf { it.transport === t } ?: return false
            return sendUnlabeledWhois(session, msg, rawLabel = unlabeledWhoisLabel ?: msg.tags["label"], onWrite = onWrite)
        }
        val line = msg.serialize()
        if (msg.command.equals("JOIN", ignoreCase = true)) {
            outboundLock.withLock {
                val session = joinRejections.get()?.takeIf { it.transport === t }
                if (session != null) {
                    val label = msg.tags["label"].takeIf { hasCap("labeled-response") }
                    msg.params.firstOrNull()?.split(',')?.forEach { channel ->
                        if (channel.isNotEmpty() && channel != "0") session.sent(channel, label, requestId = null)
                    }
                }
                onWrite?.invoke()
                t.send(line)
            }
            return true
        }
        if (!msg.command.equals("MODE", ignoreCase = true) || msg.params.getOrNull(1)?.contains('b') != true) {
            sendSerialized(t, line, onWrite)
            return true
        }
        outboundLock.withLock {
            if ("label" !in msg.tags || !hasCap("labeled-response")) {
                channelBanSession.get()?.takeIf { it.transport === t }?.rawMode(msg, isupport)
            }
            onWrite?.invoke()
            t.send(line)
        }
        return true
    }

    private suspend fun sendSerialized(
        t: IrcTransport,
        line: String,
        onWrite: (() -> Unit)? = null,
    ) {
        outboundLock.withLock {
            onWrite?.invoke()
            t.send(line)
        }
    }

    // -- public send API --

    suspend fun send(msg: IrcMessage) {
        transport?.let { sendSerialized(it, msg) }
    }

    /**
     * Send one raw IRC message. A conflicting unlabeled WHOIS is not written.
     * [unlabeledWhoisLabel] routes its replies locally, without adding a tag to the wire command.
     * [onWrite] runs under the outbound lock just before the transport write.
     */
    suspend fun sendIfConnected(
        msg: IrcMessage,
        unlabeledWhoisLabel: String? = null,
        onWrite: (() -> Unit)? = null,
    ): Boolean {
        val t = transport ?: return false
        return sendSerialized(t, msg, unlabeledWhoisLabel, onWrite)
    }

    /**
     * Write a single-channel JOIN with [requestId] as its response label when available.
     * Without labels, wait briefly for a prior same-target JOIN to drain. An unresolved
     * predecessor makes rejections ambiguous, but never prevents sending the retry.
     */
    suspend fun sendJoinForRejection(
        channel: String,
        key: String?,
        requestId: String,
    ): Boolean {
        val session = joinRejections.get() ?: return false
        while (true) {
            val prior =
                outboundLock.withLock {
                    if (joinRejections.get() !== session || transport !== session.transport || state.value !is IrcClientState.Ready) {
                        return false
                    }
                    val label = requestId.takeIf { hasCap("labeled-response") }
                    val pending = if (label == null) session.prior(channel) else null
                    if (pending != null) {
                        pending
                    } else {
                        val line =
                            IrcMessage(
                                tags = if (label == null) emptyMap() else mapOf("label" to label),
                                command = "JOIN",
                                params = listOfNotNull(channel, key),
                            ).serialize()
                        session.sent(channel, label, requestId.takeIf { label == null })
                        session.transport.send(line)
                        return true
                    }
                }
            if (withTimeoutOrNull(JOIN_DRAIN_WAIT_MS) { prior.await() } == null) session.expire(channel, prior)
        }
    }

    /** Drop local ownership without forgetting an unresolved wire JOIN. */
    fun releaseJoinRejection(
        channel: String,
        requestId: String,
    ) {
        joinRejections.get()?.release(channel, requestId)
    }

    /** PRIVMSG whose own echo contains a credential. Suppression is in-memory and narrowly exact. */
    suspend fun sendSensitivePrivmsg(
        target: String,
        text: String,
    ): Boolean {
        val key = sensitiveEchoKey(target, text)
        sensitiveEchoes.merge(key, 1, Int::plus)
        val accepted =
            try {
                sendIfConnected(IrcMessage(command = "PRIVMSG", params = listOf(target, text)))
            } catch (error: Throwable) {
                decrementSensitiveEcho(key)
                throw error
            }
        if (!accepted) {
            decrementSensitiveEcho(key)
            return false
        }
        scope.launch {
            delay(SENSITIVE_ECHO_TTL_MS)
            decrementSensitiveEcho(key)
        }
        return true
    }

    private fun consumeSensitiveEcho(msg: IrcMessage): Boolean {
        if (msg.command != "PRIVMSG" || msg.source?.nick?.let { _isupport.get().normalize(it) } != _isupport.get().normalize(selfNick.get())) {
            return false
        }
        val target = msg.params.getOrNull(0) ?: return false
        val text = msg.params.getOrNull(1) ?: return false
        val key = sensitiveEchoKey(target, text)
        if (sensitiveEchoes[key] == null) return false
        decrementSensitiveEcho(key)
        return true
    }

    private fun sensitiveEchoKey(
        target: String,
        text: String,
    ): String = "${_isupport.get().normalize(target)}\u0000$text"

    private fun decrementSensitiveEcho(key: String) {
        sensitiveEchoes.computeIfPresent(key) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    /** Attach a label tag, suspend until the labeled response/ack batch completes. */
    suspend fun sendLabeled(msg: IrcMessage): List<IrcMessage> = sendLabeledResponse(msg).messages

    /** Like [sendLabeled], but recheck feature-specific write fencing under the outbound lock. */
    suspend fun sendLabeled(
        msg: IrcMessage,
        beforeWrite: () -> Unit,
    ): List<IrcMessage> = sendLabeledResponse(msg, beforeWrite = beforeWrite).messages

    /** The metadata batch opening, not its contained rows, orders a GET snapshot against live updates. */
    suspend fun sendLabeledMetadataGet(
        msg: IrcMessage,
        beforeWrite: () -> Unit,
    ): Pair<Long, List<IrcMessage>> {
        val response = sendLabeledResponse(msg, beforeWrite = beforeWrite)
        val opening =
            response.rootBatch?.takeIf { it.params.getOrNull(1) == "metadata" }
                ?: response.messages.firstOrNull { it.command == "BATCH" && it.params.getOrNull(1) == "metadata" }
        return (opening?.wireSequence ?: 0L) to response.messages
    }

    private suspend fun sendLabeledResponse(
        msg: IrcMessage,
        beforeWrite: (() -> Unit)? = null,
        consumer: ((IrcMessage) -> Unit)? = null,
    ): CorrelatedResponse {
        val t = transport ?: throw IrcDisconnectedException(msg.command, null)
        // Degrade without labeled-response: send unlabeled, complete immediately with empty list.
        if (!hasCap("labeled-response")) {
            if (!sendSerialized(t, msg, onWrite = beforeWrite)) throw unlabeledWhoisBusy()
            return CorrelatedResponse(emptyList(), rootBatch = null)
        }
        val label = labels.next()
        val deferred = CompletableDeferred<CorrelatedResponse>()
        labels.register(label, msg.command, deferred, consumer = consumer)
        val labeled = msg.copy(tags = msg.tags + ("label" to label))
        return try {
            sendSerialized(t, labeled, onWrite = beforeWrite)
            withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IrcTimeoutException(label)
        } finally {
            labels.unregister(label, deferred)
        }
    }

    private suspend fun sendUnlabeledWhois(
        session: UnlabeledWhoisCorrelator,
        message: IrcMessage,
        rawLabel: String? = null,
        deferred: CompletableDeferred<List<IrcMessage>>? = null,
        onWrite: (() -> Unit)? = null,
    ): Boolean {
        val line = message.serialize()
        return outboundLock.withLock {
            if (unlabeledWhois.get() !== session || transport !== session.transport) return false
            if (!session.register(message, isupport, rawLabel, deferred)) return false
            onWrite?.invoke()
            session.transport.send(line)
            true
        }
    }

    private fun unlabeledWhoisBusy() = IrcCommandException("WHOIS", "BUSY", "a reply for this nick is still pending")

    /** Collect WHOIS details without leaking unlabeled numerics into the ordinary event stream. */
    suspend fun whois(nick: String): List<IrcMessage> {
        val message = IrcMessage(command = "WHOIS", params = listOf(nick))
        if (hasCap("labeled-response")) return sendLabeled(message)
        val session = unlabeledWhois.get() ?: throw IrcDisconnectedException("WHOIS", null)
        return unlabeledWhoisLock.withLock {
            val deferred = CompletableDeferred<List<IrcMessage>>()
            try {
                if (!sendUnlabeledWhois(session, message, deferred = deferred)) {
                    if (unlabeledWhois.get() !== session) throw IrcDisconnectedException("WHOIS", null)
                    throw unlabeledWhoisBusy()
                }
                withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw IrcTimeoutException("WHOIS")
            } finally {
                session.abandon(deferred)
            }
        }
    }

    /** Send one chat message using the app's exact durable attempt label. */
    suspend fun sendMessage(
        target: String,
        text: String,
        replyToMsgid: String?,
        label: String,
        forceLegacy: Boolean = false,
        channelContext: String? = null,
        clientTags: Map<String, String> = emptyMap(),
    ): Boolean = sendChatMessage(target, text, replyToMsgid, label, forceLegacy, channelContext, clientTags)

    suspend fun sendGuardedMessage(
        target: String,
        text: String,
        label: String,
        beforeWrite: suspend () -> Boolean,
    ): Boolean = sendChatMessage(target, text, replyToMsgid = null, label = label, forceLegacy = true, beforeWrite = beforeWrite)

    private suspend fun sendChatMessage(
        target: String,
        text: String,
        replyToMsgid: String?,
        label: String,
        forceLegacy: Boolean,
        channelContext: String? = null,
        clientTags: Map<String, String> = emptyMap(),
        beforeWrite: (suspend () -> Boolean)? = null,
    ): Boolean {
        requireValidChatLabel(label)
        val t = transport ?: return false
        val caps = ackedCaps.get()
        val labelTag = label.takeIf { caps.any { it.substringBefore('=') == "labeled-response" } }
        val isupport = _isupport.get()
        val isupportMap = isupportToMap(isupport)
        val contextTag =
            channelContext?.takeIf {
                !isupport.isChannel(target) && isupport.isChannel(it) &&
                    canSendClientTag(caps, isupportMap, "+channel-context")
            }
        val requestedTags =
            if (clientTags.isEmpty()) {
                emptyMap()
            } else {
                clientTags.filter { (key) -> key.startsWith('+') && canSendClientTag(caps, isupportMap, key) }
            }
        val protocolTags = contextTag?.let { requestedTags + ("+channel-context" to it) } ?: requestedTags
        val plan =
            planChatMessage(
                target = target,
                text = text,
                replyToMsgid = replyToMsgid,
                label = labelTag,
                multilineLimits = if (hasMultilineWireSupport() && !forceLegacy) multilineLimits else null,
                forceLegacy = forceLegacy,
                protocolTags = protocolTags,
            ) ?: return false
        // Do NOT register a correlator deferred: the labeled echo must flow through as a normal
        // self ChatMessage event (carrying label in ctx) so the app can dedup the pending row.
        when (plan) {
            is MultilineSendPlan.Single -> {
                if (plan.message.params.any { it.any { char -> char == '\r' || char == '\n' } }) {
                    return false
                }
                if (beforeWrite == null) {
                    sendSerialized(t, plan.message)
                } else {
                    val line = plan.message.serialize()
                    outboundLock.withLock {
                        if (transport !== t || !beforeWrite() || transport !== t) return false
                        t.send(line)
                    }
                }
            }

            is MultilineSendPlan.Batch -> {
                outboundLock.withLock {
                    t.send(plan.opening.serialize())
                    plan.components.forEach { t.send(it.serialize()) }
                    t.send(plan.closing.serialize())
                }
            }
        }
        return true
    }

    suspend fun sendTyping(
        target: String,
        state: String,
    ) {
        if (!hasCap("message-tags")) return
        val t = transport ?: return
        if (!typingOutbox.shouldSend(target, state)) return
        val msg = IrcMessage(tags = mapOf("+typing" to state), command = "TAGMSG", params = listOf(target))
        sendSerialized(t, msg)
    }

    suspend fun sendReact(
        target: String,
        msgid: String,
        emoji: String,
    ) {
        val caps = ackedCaps.get()
        val isupport = isupportToMap(_isupport.get())
        val replyTag = preferredReplyTag(caps, isupport) ?: return
        if (!canSendClientTag(caps, isupport, "+draft/react")) return
        val t = transport ?: return
        val msg =
            IrcMessage(
                tags = mapOf("+draft/react" to emoji, replyTag to msgid),
                command = "TAGMSG",
                params = listOf(target),
            )
        sendSerialized(t, msg)
    }

    suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
        val limit = clampHistoryLimit(req.limit)
        val msg =
            when (req.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> {
                    ChatHistoryCommands.latest(req.target, req.bound1, limit)
                }

                ChatHistoryRequest.Subcommand.BEFORE -> {
                    ChatHistoryCommands.before(req.target, req.bound1.orEmpty(), limit)
                }

                ChatHistoryRequest.Subcommand.AFTER -> {
                    ChatHistoryCommands.after(req.target, req.bound1.orEmpty(), limit)
                }

                ChatHistoryRequest.Subcommand.AROUND -> {
                    ChatHistoryCommands.around(req.target, req.bound1.orEmpty(), limit)
                }

                ChatHistoryRequest.Subcommand.BETWEEN -> {
                    ChatHistoryCommands.between(req.target, req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
                }

                ChatHistoryRequest.Subcommand.TARGETS -> {
                    ChatHistoryCommands.targets(req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
                }
            }
        val response =
            if (hasCap("labeled-response")) {
                sendLabeledResponse(msg)
            } else {
                sendUnlabeledChatHistory(req, msg)
            }
        val root =
            response.rootBatch
                ?: throw IrcProtocolException("CHATHISTORY", "response did not contain a complete root batch")
        // Two accepted shapes, matching `search`: released soju sends a bare `chathistory` root,
        // while soju master (post-70c4ded) wraps that batch in an outer `labeled-response` batch.
        // In the wrapped shape the label opens the wrapper and the history batch sits one level in,
        // so rejecting it would turn a server upgrade into silently broken scrollback.
        // Unlike `search`, only the first batch inside the wrapper is the history batch: multiline
        // and netsplit batches nest within it rather than beside it, so they must not be matched
        // here. They are reassembled later by `mapCorrelatedHistory`, which walks the wrapper.
        val hist =
            if (root.params
                    .getOrNull(1)
                    .orEmpty()
                    .equals("labeled-response", ignoreCase = true)
            ) {
                response.messages.firstOrNull {
                    it.command == "BATCH" && it.params.firstOrNull()?.startsWith("+") == true
                } ?: throw IrcProtocolException("CHATHISTORY", "labeled-response wrapper contained no batch")
            } else {
                root
            }
        val expectedType =
            if (req.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
                "draft/chathistory-targets"
            } else {
                "chathistory"
            }
        if (hist.command != "BATCH" ||
            !hist.params
                .getOrNull(1)
                .orEmpty()
                .equals(expectedType, ignoreCase = true)
        ) {
            throw IrcProtocolException(
                "CHATHISTORY",
                "unexpected batch type ${hist.params.getOrNull(1).orEmpty()}",
            )
        }
        val endOfHistory = "draft/chathistory-end" in hist.tags
        return if (req.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
            ChatHistoryResponse.Targets(parseTargets(response.messages), endOfHistory)
        } else {
            val mapped = mapCorrelatedHistory(root, response.messages)
            val primaryMessages =
                mapped.filter { (message, _) ->
                    "draft/chathistory-context" !in message.tags
                }
            val oldest = primaryMessages.firstOrNull()?.first?.let(::historyReference)
            val newest = primaryMessages.lastOrNull()?.first?.let(::historyReference)
            ChatHistoryResponse.Messages(
                events = mapped.map { it.second },
                // Message IDs are opaque and may be the only exact selector. Keep the server's
                // completed-batch order rather than attempting to sort or normalize references.
                oldest = oldest,
                newest = newest,
                endOfHistory = endOfHistory,
                primaryMessageCount = primaryMessages.size,
            )
        }
    }

    /**
     * True when this connection can run server-side SEARCH: Ready with `soju.im/search` acked.
     * `labeled-response` is NOT required — released soju (through 0.10.x) advertises
     * `soju.im/search` without it, and unlabelled responses are correlated by their
     * `soju.im/search` batch type instead (serialized one at a time, like CHATHISTORY).
     */
    val searchAvailable: Boolean
        get() = _state.value is IrcClientState.Ready && hasCap("soju.im/search")

    /**
     * Run one soju SEARCH and return its hits in server (ascending) order.
     *
     * Results are transient: they are never routed into the event stream and never persisted, since
     * a hit says nothing about which intervals of history the client holds.
     *
     * @throws IrcCommandException on a `FAIL SEARCH` reply.
     * @throws IrcTimeoutException after [LABEL_TIMEOUT_MS] with no completed response.
     * @throws IrcProtocolException on a malformed or wrongly typed response batch.
     * @throws IllegalStateException when [searchAvailable] is false.
     * @throws IrcDisconnectedException when there is no transport.
     */
    suspend fun search(req: SearchRequest): List<SearchResultMessage> {
        check(searchAvailable) { "SEARCH is unavailable on this connection" }
        // Buffering under the correlator keeps the result PRIVMSGs out of the normal event flow,
        // the same property `chathistory` relies on.
        val msg = SearchCommands.search(req)
        val response =
            if (hasCap("labeled-response")) {
                sendLabeledResponse(msg)
            } else {
                sendUnlabeledSearch(msg)
            }
        val root =
            response.rootBatch
                ?: if (response.messages.isEmpty()) {
                    // Tolerated: a zero-result SEARCH may complete without ever opening a batch.
                    return emptyList()
                } else {
                    throw IrcProtocolException("SEARCH", "response did not contain a complete root batch")
                }
        // Two accepted shapes: released soju sends a bare `soju.im/search` root, while soju master
        // (post-70c4ded) wraps that batch in an outer `labeled-response` batch. Rejecting the
        // wrapper would turn a server upgrade into a silent protocol failure.
        val rootType = root.params.getOrNull(1).orEmpty()
        val labeledWrapper = rootType.equals("labeled-response", ignoreCase = true)
        if (
            root.command != "BATCH" ||
            !(labeledWrapper || rootType.equals("soju.im/search", ignoreCase = true))
        ) {
            throw IrcProtocolException("SEARCH", "unexpected batch type $rootType")
        }
        if (labeledWrapper) {
            // Every batch opened inside the wrapper must itself be the search batch.
            val wrongInner =
                response.messages.firstOrNull {
                    it.command == "BATCH" &&
                        it.params.firstOrNull()?.startsWith("+") == true &&
                        !it.params
                            .getOrNull(1)
                            .orEmpty()
                            .equals("soju.im/search", ignoreCase = true)
                }
            if (wrongInner != null) {
                throw IrcProtocolException(
                    "SEARCH",
                    "unexpected batch type ${wrongInner.params.getOrNull(1).orEmpty()}",
                )
            }
        }
        return response.messages.mapNotNull(::parseSearchResult)
    }

    /**
     * SEARCH without `labeled-response`: register the type-correlated collector before writing so
     * the reply batch cannot race past dispatch, and serialize requests because an unlabelled
     * `soju.im/search` batch carries no other request identity.
     */
    private suspend fun sendUnlabeledSearch(message: IrcMessage): CorrelatedResponse =
        unlabeledSearchLock.withLock {
            val t = transport ?: throw IrcDisconnectedException("SEARCH", null)
            val deferred = CompletableDeferred<CorrelatedResponse>()
            unlabeledSearch.register(deferred)
            try {
                sendSerialized(t, message)
                withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw IrcTimeoutException("SEARCH")
            } finally {
                unlabeledSearch.clear(deferred)
            }
        }

    /** Rebuild nested response framing so one multiline history item remains one logical event. */
    private fun mapCorrelatedHistory(
        root: IrcMessage,
        messages: List<IrcMessage>,
    ): List<Pair<IrcMessage, IrcEvent>> {
        val assembler = BatchAssembler()
        if (assembler.route(root) != BatchAssembler.Outcome.Buffered) {
            throw IrcProtocolException("CHATHISTORY", "root batch could not be assembled")
        }
        messages.forEach { message ->
            if (assembler.route(message) != BatchAssembler.Outcome.Buffered) {
                throw IrcProtocolException("CHATHISTORY", "response contained a line outside its root batch")
            }
        }

        val rootRef =
            root.params
                .firstOrNull()
                ?.removePrefix("+")
                .orEmpty()
        val closed =
            assembler.route(IrcMessage(command = "BATCH", params = listOf("-$rootRef")))
                as? BatchAssembler.Outcome.Closed
                ?: throw IrcProtocolException("CHATHISTORY", "root batch did not assemble completely")
        return mapCorrelatedHistoryChildren(closed.tree)
    }

    private fun mapCorrelatedHistoryChildren(tree: BatchTree): List<Pair<IrcMessage, IrcEvent>> =
        tree.children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> {
                    listOfNotNull(
                        eventMapper
                            .map(
                                child.message,
                                batchId = child.message.tags["batch"] ?: tree.ref,
                                historical = true,
                            )?.let { child.message to it },
                    )
                }

                is BatchChild.Nested -> {
                    val event =
                        when (child.batch.type) {
                            MULTILINE_CAP -> mapMultilineBatch(child.batch, historical = true)
                            "netsplit", "netjoin" -> mapBatchTree(child.batch, historical = true).singleOrNull()
                            else -> null
                        }
                    if (event != null) {
                        val reference = historyReference(child.batch.opening)
                        val enriched =
                            (event as? IrcEvent.NetworkBatch)?.copy(
                                historyMetadata =
                                    HistoryEventMetadata(
                                        isContext = "draft/chathistory-context" in child.batch.opening.tags,
                                        msgid = reference?.msgid,
                                        serverTime = reference?.serverTime,
                                    ),
                            ) ?: event
                        listOf(child.batch.opening to enriched)
                    } else {
                        mapCorrelatedHistoryChildren(child.batch)
                    }
                }
            }
        }

    private suspend fun sendUnlabeledChatHistory(
        request: ChatHistoryRequest,
        message: IrcMessage,
    ): CorrelatedResponse =
        unlabeledChatHistoryLock.withLock {
            val t = transport ?: throw IrcDisconnectedException("CHATHISTORY", null)
            val deferred = CompletableDeferred<CorrelatedResponse>()
            unlabeledChatHistory.register(request, deferred)
            try {
                sendSerialized(t, message)
                withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw IrcTimeoutException("CHATHISTORY")
            } finally {
                unlabeledChatHistory.clear(deferred)
            }
        }

    suspend fun markRead(
        target: String,
        timestampMs: Long,
    ) {
        val t = transport ?: return
        val command = readMarkerCommand() ?: return
        sendSerialized(t, ReadMarkerCommands.set(command, target, timestampMs))
    }

    suspend fun fetchReadMarker(target: String) {
        val t = transport ?: return
        val command = readMarkerCommand() ?: return
        sendSerialized(t, ReadMarkerCommands.get(command, target))
    }

    /**
     * True when either read-marker extension is negotiated: the IRCv3 `draft/read-marker`
     * (MARKREAD) or soju's older `soju.im/read` (READ) fallback. The app gates marker sync on this
     * so a soju-connected chat without the IRCv3 draft still syncs.
     */
    fun hasReadMarkerCap(): Boolean = hasCap("draft/read-marker") || hasCap("soju.im/read")

    /**
     * The read-marker command to send on this connection: MARKREAD when the IRCv3 draft is acked,
     * else READ when only soju's extension is acked, else null (no-op). The IRCv3 standard wins so
     * soju broadcasts MARKREAD to this client when both are advertised.
     */
    private fun readMarkerCommand(): String? =
        when {
            hasCap("draft/read-marker") -> "MARKREAD"
            hasCap("soju.im/read") -> "READ"
            else -> null
        }

    /**
     * Fetch one correlated WHOX snapshot. Equal normalized masks share one in-flight request;
     * different masks may run concurrently. Timeout is reported as incomplete so callers cannot
     * mistake it for an authoritative empty roster.
     */
    suspend fun whox(mask: String): WhoxResult {
        if (_isupport.get()["WHOX"] == null) return WhoxResult(emptyList(), completed = false)
        val normalized = _isupport.get().normalize(mask)
        val candidate = scope.async(start = CoroutineStart.LAZY) { performWhox(mask, normalized) }
        val existing = whoxRequests.putIfAbsent(normalized, candidate)
        val request = existing ?: candidate.also { it.start() }
        if (existing != null) candidate.cancel()
        return try {
            request.await()
        } finally {
            whoxRequests.remove(normalized, request)
        }
    }

    private suspend fun performWhox(
        mask: String,
        normalizedMask: String,
    ): WhoxResult {
        val token = whoxTokens.acquire() ?: return WhoxResult(emptyList(), completed = false)
        val t =
            transport ?: run {
                whoxTokens.release(token)
                return WhoxResult(emptyList(), completed = false)
            }
        val rows = ArrayList<IrcEvent.WhoxRow>()
        val collector =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
                broadcastEvents.first { event ->
                    when (event) {
                        is IrcEvent.WhoxRow -> {
                            if (event.token == token) rows += event
                            false
                        }

                        is IrcEvent.WhoxComplete -> {
                            _isupport.get().normalize(event.mask) == normalizedMask
                        }

                        else -> {
                            false
                        }
                    }
                }
            }
        return try {
            sendSerialized(t, WhoxCommands.request(mask, token))
            val completed =
                withTimeoutOrNull(WHOX_TIMEOUT_MS) {
                    collector.await()
                    true
                } == true
            WhoxResult(rows.toList(), completed)
        } finally {
            collector.cancel()
            whoxTokens.release(token)
        }
    }

    // -- soju bouncer-networks --

    suspend fun bouncerListNetworks(): List<BouncerNetwork> {
        val t = transport ?: throw IrcDisconnectedException("BOUNCER LISTNETWORKS", null)
        // soju advertises no labeled-response, so sendLabeled would return empty. It instead
        // pushes BOUNCER NETWORK notifications (soju.im/bouncer-networks-notify) that we already
        // accumulate in _bouncerNetworks. Send an explicit LISTNETWORKS to force a refresh for
        // servers that do not push, then return the snapshot once it settles (notifications
        // usually arrive by the time the caller reaches Ready).
        // A failed write is materially different from an empty bouncer account. Do not swallow it
        // into the snapshot: callers need to offer a retryable discovery error. soju does not
        // negotiate labeled-response, so an unlabelled server-side LIST rejection cannot be
        // correlated; an accepted request that produces no NETWORK notifications is Loaded(empty).
        sendSerialized(t, BouncerCommands.listNetworks())
        if (_bouncerNetworks.value.isEmpty()) {
            withTimeoutOrNull(2000) { while (_bouncerNetworks.value.isEmpty()) delay(50) }
        }
        // A write into a socket the OS has not yet failed does not throw, and an already-populated
        // snapshot needs no notification to answer with. An explicit refresh against a bouncer that
        // has gone away would therefore report success and hand back stale rows. Confirm the link
        // with the watchdog's own PING/grace first; a timeout also transitions the socket to
        // Disconnected, so the owning actor starts reconnecting instead of waiting for the OS.
        if (!probeLiveness(BOUNCER_LIST_PROBE_GRACE_MS)) {
            throw IrcDisconnectedException("BOUNCER LISTNETWORKS", "no reply to a liveness probe")
        }
        return snapshotBouncerNetworks()
    }

    private fun snapshotBouncerNetworks(): List<BouncerNetwork> = _bouncerNetworks.value.map { (id, attrs) -> BouncerNetwork(id, attrs) }

    suspend fun bouncerAddNetwork(attrs: Map<String, String>): String {
        val response = sendLabeled(BouncerCommands.addNetwork(attrs))
        return response.firstNotNullOfOrNull { BouncerCommands.parseAddReply(it) }
            ?: throw IrcCommandException(
                "BOUNCER ADDNETWORK",
                "NO_RESPONSE",
                "The bouncer did not confirm adding the network.",
            )
    }

    suspend fun bouncerDeleteNetwork(netId: String) {
        sendLabeled(BouncerCommands.deleteNetwork(netId))
    }

    // -- channel list (LIST / ELIST) --

    /**
     * LIST. [mask] is a server-side filter; otherwise a safe [nameContains] can use an ELIST 'M'
     * mask. [nameContains] is always matched literally under the server's CASEMAPPING. [minUsers]
     * is exclusive and [maxUsers] is inclusive. ELIST 'U' also sends the count filters; multiple
     * wire filters share one comma-separated argument. Uses labeled-response when available;
     * otherwise collects raw 322s until 323, a rejection, or a 15s timeout. Returns the [cap]
     * most populated matching rows, keeping memory bounded even for a large unfiltered LIST.
     * [onProgress] receives conflated, bounded snapshots outside the socket reader.
     * The first match is immediate, then matching rows publish at most once every 200 ms.
     */
    suspend fun listChannels(
        mask: String? = null,
        minUsers: Int? = null,
        cap: Int = 2000,
        maxUsers: Int? = null,
        nameContains: String? = null,
        onProgress: ((ChannelListProgress) -> Unit)? = null,
    ): List<ChannelListing> {
        val out = BoundedChannelListings(cap)
        if (onProgress == null) return collectChannelList(mask, minUsers, maxUsers, nameContains, out)
        return coroutineScope {
            val mailbox = Channel<ChannelListProgress>(Channel.CONFLATED)
            out.progress = mailbox
            val consumer =
                launch(listProgressDispatcher) {
                    try {
                        for (snapshot in mailbox) {
                            ensureActive()
                            onProgress(snapshot)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed observer must not interrupt the LIST response.
                    } finally {
                        out.stopProgress()
                    }
                }
            try {
                collectChannelList(mask, minUsers, maxUsers, nameContains, out)
            } finally {
                consumer.cancel()
                out.stopProgress()
            }
        }
    }

    private suspend fun collectChannelList(
        mask: String?,
        minUsers: Int?,
        maxUsers: Int?,
        nameContains: String?,
        out: BoundedChannelListings,
    ): List<ChannelListing> {
        val support = _isupport.get()
        val caseMapping = support.identityRules.caseMapping
        val elist = support["ELIST"]
        val nameTerm = nameContains?.takeIf { it.isNotEmpty() }
        val nameMatches =
            nameTerm?.let { term ->
                val foldedTerm = caseMapping.normalize(term)

                fun(channel: String) = caseMapping.normalize(channel).contains(foldedTerm)
            }
        val wireMask =
            mask?.takeIf { it.isNotBlank() }
                ?: nameTerm
                    ?.takeIf {
                        elist?.contains('M', ignoreCase = true) == true &&
                            it.none { char -> char in "*?\\,[]" || char.isWhitespace() || char.isISOControl() }
                    }?.let { "*$it*" }
        val filters =
            buildList {
                wireMask?.let { add(it) }
                if (elist?.contains('U', ignoreCase = true) == true) {
                    if (minUsers != null) add(">$minUsers")
                    if (maxUsers != null && maxUsers < Int.MAX_VALUE) add("<${maxUsers + 1}")
                }
            }
        val params = if (filters.size <= 1) filters else listOf(filters.joinToString(","))
        val msg = IrcMessage(command = "LIST", params = params)
        val collectRow: (IrcMessage) -> Unit = { message -> parseListMessage(message, minUsers, maxUsers, nameMatches)?.let(out::add) }

        if (hasCap("labeled-response")) {
            var rejection: IrcCommandException? = null
            sendLabeledResponse(msg) { message ->
                rejection = rejection ?: message.listRejection()
                if (rejection == null) collectRow(message)
            }
            rejection?.let { throw it }
            return out.toList()
        }

        // Raw LIST numerics carry no request identity. Serialize requests and synchronously enter
        // collection before writing so a fast server cannot answer before the subscriber exists.
        return unlabeledChannelListLock.withLock {
            val t = transport ?: throw IrcDisconnectedException("LIST", null)
            val optionalTerminators = awaitPreviousChannelListResponse(t)
            if (transport !== t) throw IrcDisconnectedException("LIST", null)
            val isRecipient: (String) -> Boolean = { recipient ->
                val nick = selfNick.get()
                recipient == nick || caseMapping.normalize(recipient) == caseMapping.normalize(nick)
            }
            val collector = UnlabeledChannelListCollector(scope, broadcastEvents, isRecipient, collectRow, optionalTerminators)
            var sent = false
            var drained = false
            try {
                sendSerialized(t, msg)
                sent = true
                val completed =
                    withTimeoutOrNull(LIST_TIMEOUT_MS) {
                        collector.terminal.join()
                        true
                    } == true
                if (!completed) throw IrcTimeoutException("channel list")
                collector.terminal.await()?.let { throw it }
                drained = true
                out.toList()
            } finally {
                if (sent && !drained) {
                    out.stopProgress()
                    unlabeledChannelListDrain = UnlabeledChannelListResponse(t, collector)
                } else {
                    if (sent) unlabeledChannelListDrain = null
                    collector.job.cancelAndJoin()
                }
            }
        }
    }

    /** Drains the previous response. A bouncer's PONG can precede the upstream's optional 323. */
    private suspend fun awaitPreviousChannelListResponse(t: IrcTransport): Int {
        val previous = unlabeledChannelListDrain ?: return 0
        val collector = previous.collector
        if (previous.transport !== t) {
            collector.job.cancel()
            unlabeledChannelListDrain = null
            return 0
        }
        val drained =
            withTimeoutOrNull(LIST_DRAIN_WAIT_MS) {
                if (collector.terminal.await() != null && collector.optionalTerminators > 0) {
                    coroutineScope {
                        val token = "list-${labels.next()}"
                        val pong =
                            async(start = CoroutineStart.UNDISPATCHED) {
                                broadcastEvents.first { event ->
                                    event is IrcEvent.Raw && event.message.command == "PONG" && event.message.params.lastOrNull() == token
                                }
                            }
                        sendSerialized(t, "PING $token")
                        pong.await()
                    }
                }
                true
            } == true
        if (!drained) throw IrcTimeoutException("previous channel list")
        collector.job.cancelAndJoin()
        return collector.optionalTerminators
    }

    /** RPL_LIST: params = [me, channel, count, topic]. */
    private fun parseListMessage(
        msg: IrcMessage,
        minUsers: Int?,
        maxUsers: Int?,
        nameMatches: ((String) -> Boolean)?,
    ): ChannelListing? {
        if (msg.command != "322") return null
        val channel = msg.params.getOrNull(1) ?: return null
        val count = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
        if ((minUsers != null && count <= minUsers) || (maxUsers != null && count > maxUsers)) return null
        if (nameMatches != null && !nameMatches(channel)) return null
        val topic = msg.params.getOrNull(3).orEmpty()
        return ChannelListing(channel, count, topic)
    }

    /** Read a joined channel's active bans; an empty list is valid only after a matching 368. */
    suspend fun listChannelBans(channel: String): ChannelBanListResult {
        val session = channelBanSession.get() ?: return ChannelBanListResult.Unavailable
        if (liveBanTarget(session, channel) == null) return ChannelBanListResult.Unavailable
        var request: ChannelBanRequest? = null
        var labeled: CompletableDeferred<CorrelatedResponse>? = null
        return try {
            withTimeoutOrNull(BAN_LIST_TIMEOUT_MS) {
                val pending =
                    outboundLock.withLock {
                        val target = liveBanTarget(session, channel) ?: return@withLock null
                        val label = if (hasCap("labeled-response")) labels.next() else null
                        val line = channelBanModeLine(target, label = label) ?: return@withLock null
                        val candidate = ChannelBanRequest(target, label)
                        synchronized(session) {
                            if (liveBanTarget(session, target) == null || !session.begin(candidate, isupport.identityRules.caseMapping)) {
                                return@withLock null
                            }
                        }
                        request = candidate
                        if (label != null) {
                            val response = CompletableDeferred<CorrelatedResponse>()
                            labeled = response
                            labels.register(
                                label,
                                "MODE",
                                response,
                                accepts = {
                                    channelBanSession.get() === session &&
                                        candidate.acceptsLabeled(it, selfNick.get(), isupport.identityRules.caseMapping)
                                },
                            )
                        }
                        if (!candidate.stopped.isCompleted && !writeChannelBanLine(session.transport, line)) {
                            return@withTimeoutOrNull ChannelBanListResult.Incomplete
                        }
                        candidate
                    } ?: return@withTimeoutOrNull ChannelBanListResult.Unavailable

                val response = labeled ?: return@withTimeoutOrNull pending.stopped.await()
                select<ChannelBanListResult> {
                    pending.stopped.onAwait { it }
                    response.onAwait { completed ->
                        val wrapper = completed.rootBatch
                        if (wrapper != null && !wrapper.params.getOrNull(1).equals("labeled-response", ignoreCase = true)) {
                            ChannelBanListResult.Incomplete
                        } else {
                            val rows = pending.rows
                            completed.messages.forEach(rows::accept)
                            rows.result()
                        }
                    }
                }
            }?.let { result ->
                if (result is ChannelBanListResult.Complete && liveBanTarget(session, channel) == null) {
                    ChannelBanListResult.Incomplete
                } else {
                    result
                }
            } ?: ChannelBanListResult.Incomplete
        } catch (_: IrcProtocolException) {
            ChannelBanListResult.Incomplete
        } catch (_: IrcDisconnectedException) {
            ChannelBanListResult.Incomplete
        } finally {
            request?.let { pending ->
                labeled?.let { labels.unregister(checkNotNull(pending.label), it) }
                session.abandon(pending)
            }
        }
    }

    /** Remove this literal mask. True means the local transport accepted the write, not server success. */
    suspend fun removeChannelBan(
        channel: String,
        mask: String,
    ): Boolean {
        val session = channelBanSession.get() ?: return false
        return outboundLock.withLock {
            val target = liveBanTarget(session, channel) ?: return false
            val line = channelBanModeLine(target, mask) ?: return false
            // Record ambiguity before sending: even a failed write can already have reached the server.
            session.removing(target, isupport.identityRules.caseMapping)
            writeChannelBanLine(session.transport, line)
        }
    }

    private fun liveBanTarget(
        session: ChannelBanRequests,
        requested: String,
    ): String? {
        if (channelBanSession.get() !== session || transport !== session.transport ||
            _state.value !is IrcClientState.Ready || !_targetClassificationReady.value
        ) {
            return null
        }
        val identity = isupport.identityRules
        if (!identity.isChannel(requested) || !requested.isBanModeParameter() || ',' in requested) return null
        val folded = identity.normalize(requested)
        return _joinedChannels.value.firstOrNull { identity.isChannel(it) && identity.normalize(it) == folded }
    }

    /** Called with the outbound lock held. A failed send may already have put bytes on the wire. */
    private suspend fun writeChannelBanLine(
        t: IrcTransport,
        line: String,
    ): Boolean =
        try {
            t.send(line)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            false
        } catch (_: IllegalStateException) {
            false
        }

    // -- account registration --

    val accountRegistrationPolicy: AccountRegistrationPolicy?
        get() = accountRegistrationPolicy(caps)

    suspend fun registerAccount(
        account: String,
        email: String?,
        password: String,
    ): AccountRegistrationResult {
        requireRegistrationToken(account, allowStar = true)
        email?.let { requireRegistrationToken(it, allowStar = false) }
        require(password.isNotEmpty() && password.toByteArray(Charsets.UTF_8).size <= 300 && password.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "invalid account registration password"
        }
        return accountRegistrationCommand(
            command = "REGISTER",
            message = IrcMessage(command = "REGISTER", params = listOf(account, email ?: "*", password)),
        )
    }

    suspend fun verifyAccount(
        account: String,
        code: String,
    ): AccountRegistrationResult {
        requireRegistrationToken(account, allowStar = true)
        requireRegistrationToken(code, allowStar = false)
        return accountRegistrationCommand(
            command = "VERIFY",
            message = IrcMessage(command = "VERIFY", params = listOf(account, code)),
        )
    }

    private fun requireRegistrationToken(
        value: String,
        allowStar: Boolean,
    ) {
        require(
            value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 300 &&
                value.none { it.isWhitespace() || it == '\r' || it == '\n' || it == '\u0000' } &&
                (allowStar || value != "*"),
        ) {
            "invalid account registration field"
        }
    }

    private suspend fun accountRegistrationCommand(
        command: String,
        message: IrcMessage,
    ): AccountRegistrationResult =
        accountRegistrationLock.withLock {
            if (!hasCap(ACCOUNT_REGISTRATION_CAP)) throw IrcProtocolException(command, "account registration is unsupported")
            val policy = accountRegistrationPolicy
            if (command == "REGISTER") {
                val password = message.params.last()
                val length = password.toByteArray(Charsets.UTF_8).size
                require(policy?.minPasswordLength?.let { length >= it } != false) { "password is shorter than server minimum" }
                require(policy?.maxPasswordLength?.let { length <= it } != false) { "password exceeds server maximum" }
            }
            val t = transport ?: throw IrcDisconnectedException(command, null)
            coroutineScope {
                val response =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        broadcastEvents
                            .mapNotNull { event ->
                                when (event) {
                                    is IrcEvent.Raw -> {
                                        val raw = event.message
                                        if (raw.command != command) return@mapNotNull null
                                        when (raw.params.firstOrNull()) {
                                            "SUCCESS" -> {
                                                AccountRegistrationWire.Success(
                                                    raw.params.getOrNull(1).orEmpty(),
                                                    raw.params.lastOrNull().orEmpty(),
                                                )
                                            }

                                            "VERIFICATION_REQUIRED" -> {
                                                AccountRegistrationWire.VerificationRequired(
                                                    raw.params.getOrNull(1).orEmpty(),
                                                    raw.params.lastOrNull().orEmpty(),
                                                )
                                            }

                                            else -> {
                                                null
                                            }
                                        }
                                    }

                                    is IrcEvent.StandardReply -> {
                                        if (event.severity == IrcEvent.StandardReplySeverity.FAIL &&
                                            event.commandName.equals(command, ignoreCase = true)
                                        ) {
                                            AccountRegistrationWire.Failure(event.code, event.description)
                                        } else {
                                            null
                                        }
                                    }

                                    is IrcEvent.Disconnected -> {
                                        AccountRegistrationWire.Disconnected(event.reason)
                                    }

                                    else -> {
                                        null
                                    }
                                }
                            }.first()
                    }
                try {
                    sendSerialized(t, message)
                    when (val result = withTimeout(ACCOUNT_REGISTRATION_TIMEOUT_MS) { response.await() }) {
                        is AccountRegistrationWire.Success -> {
                            AccountRegistrationResult.Success(result.account, result.message)
                        }

                        is AccountRegistrationWire.VerificationRequired -> {
                            AccountRegistrationResult.VerificationRequired(result.account, result.message)
                        }

                        is AccountRegistrationWire.Failure -> {
                            throw IrcCommandException(command, result.code, result.message)
                        }

                        is AccountRegistrationWire.Disconnected -> {
                            throw IrcDisconnectedException(command, result.reason)
                        }
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    throw IrcTimeoutException(command)
                } finally {
                    response.cancel()
                }
            }
        }

    // -- soju webpush --

    suspend fun webpushRegister(
        endpoint: String,
        p256dh: ByteArray,
        auth: ByteArray,
    ) {
        webpushCommand(
            action = "REGISTER",
            endpoint = endpoint,
            message = WebPushCommands.register(endpoint, p256dh, auth),
        )
    }

    suspend fun webpushUnregister(endpoint: String) {
        webpushCommand(
            action = "UNREGISTER",
            endpoint = endpoint,
            message = WebPushCommands.unregister(endpoint),
        )
    }

    /**
     * soju does not currently advertise labeled-response, so WEBPUSH has to correlate its raw
     * command reply. Start collecting before writing: a local bouncer can acknowledge quickly
     * enough for send-then-collect to miss the response on this replay-free event stream.
     */
    private suspend fun webpushCommand(
        action: String,
        endpoint: String,
        message: IrcMessage,
    ) {
        val t = transport ?: throw IllegalStateException("IRC client is not connected")
        coroutineScope {
            val response =
                async(start = CoroutineStart.UNDISPATCHED) {
                    broadcastEvents
                        .mapNotNull { event ->
                            when (event) {
                                is IrcEvent.Raw -> {
                                    val raw = event.message
                                    when {
                                        raw.command == "WEBPUSH" &&
                                            raw.params.getOrNull(0) == action &&
                                            raw.params.getOrNull(1) == endpoint -> WebPushResponse.Success

                                        else -> null
                                    }
                                }

                                is IrcEvent.StandardReply -> {
                                    if (event.severity == IrcEvent.StandardReplySeverity.FAIL &&
                                        event.commandName == "WEBPUSH" &&
                                        event.context.any { it == action }
                                    ) {
                                        WebPushResponse.Failure(event.code, event.description)
                                    } else {
                                        null
                                    }
                                }

                                is IrcEvent.Disconnected -> {
                                    WebPushResponse.Disconnected(event.reason)
                                }

                                else -> {
                                    null
                                }
                            }
                        }.first()
                }
            try {
                sendSerialized(t, message)
                when (val reply = withTimeout(WEBPUSH_TIMEOUT_MS) { response.await() }) {
                    WebPushResponse.Success -> Unit

                    is WebPushResponse.Failure -> throw IrcCommandException(
                        ircCommand = "WEBPUSH $action",
                        code = reply.code,
                        text = reply.text,
                    )

                    is WebPushResponse.Disconnected -> throw IrcDisconnectedException(
                        ircCommand = "WEBPUSH $action",
                        reason = reply.reason,
                    )
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                throw IrcTimeoutException("WEBPUSH $action")
            } finally {
                response.cancel()
            }
        }
    }

    /** Caps ACKed on this connection; empty until Ready. */
    val caps: Set<String> get() = ackedCaps.get()

    val multilineLimits: MultilineLimits?
        get() = multilineLimits(caps)

    fun hasCap(cap: String): Boolean = ackedCaps.get().any { it == cap || it.startsWith("$cap=") }

    fun canSendMultilineMessage(text: String): Boolean =
        hasMultilineWireSupport() &&
            planChatMessage(
                target = "#motd-check",
                text = text,
                replyToMsgid = null,
                label = "motd-check",
                multilineLimits = multilineLimits,
            ) is MultilineSendPlan.Batch

    private fun hasMultilineWireSupport(): Boolean =
        hasCap(MULTILINE_CAP) &&
            hasCap("batch") &&
            multilineLimits != null

    /** Live ISUPPORT state (normalize(), prefixModes, ...); empty until Ready. */
    val isupport: Isupport get() = _isupport.get()

    val historyAvailability: HistoryAvailability
        get() {
            if (_state.value !is IrcClientState.Ready) return HistoryAvailability.NegotiatingOrOffline
            if (!hasCap(CHATHISTORY_CAP)) {
                // An absent CHATHISTORY is "not decided yet" only while the request for it is
                // genuinely outstanding: a bouncer child publishes Ready before its deferred /
                // post-BIND CAP ACK lands, and runtime CAP NEW can introduce the extension later.
                // Once the deferred set settles without it, the answer is final.
                //
                // Deciding this from the login SHAPE instead (an authcid containing '/') pinned
                // every ZNC network — which never advertises the extension at all — at
                // NegotiatingOrOffline for the whole Ready session. That value never converged, so
                // every waiter on it blocked forever instead of settling on "unsupported".
                val awaitingCapDecision =
                    _pendingFeatureCaps.value.any {
                        it == CHATHISTORY_CAP || it.startsWith("$CHATHISTORY_CAP=")
                    }
                return if (awaitingCapDecision) {
                    HistoryAvailability.NegotiatingOrOffline
                } else {
                    HistoryAvailability.Unsupported
                }
            }
            val referenceTypes =
                _isupport.get()["MSGREFTYPES"]?.let { advertised ->
                    advertised.split(',', ' ').mapNotNullTo(linkedSetOf()) { type ->
                        when {
                            type.equals("timestamp", ignoreCase = true) -> HistoryReferenceType.TIMESTAMP
                            type.equals("msgid", ignoreCase = true) -> HistoryReferenceType.MSGID
                            else -> null
                        }
                    }
                } ?: linkedSetOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)
            val advertisedLimit = _isupport.get()["CHATHISTORY"]?.toIntOrNull()
            val pageLimit =
                when {
                    advertisedLimit == 0 -> Int.MAX_VALUE
                    advertisedLimit != null && advertisedLimit > 0 -> advertisedLimit
                    else -> DEFAULT_HISTORY_PAGE_LIMIT
                }
            return HistoryAvailability.Ready(
                referenceTypes,
                pageLimit,
                supportsConcurrentRequests = hasCap("labeled-response"),
            )
        }

    // -- helpers --

    private fun clampHistoryLimit(requested: Int): Int {
        val advertised = _isupport.get()["CHATHISTORY"]?.toIntOrNull()
        val max =
            when {
                advertised == 0 -> Int.MAX_VALUE
                advertised != null && advertised > 0 -> advertised
                else -> DEFAULT_HISTORY_PAGE_LIMIT
            }
        return requested.coerceAtLeast(1).coerceAtMost(max)
    }

    private fun parseTargets(response: List<IrcMessage>): List<ChatHistoryTarget> =
        buildList {
            for (message in response) {
                // Generic labeled correlation retains nested batch framing. It is the only unrelated
                // protocol shape valid inside an otherwise completed TARGETS batch.
                if (message.command == "BATCH") continue
                if (message.command != "CHATHISTORY" ||
                    !message.params
                        .firstOrNull()
                        .orEmpty()
                        .equals("TARGETS", ignoreCase = true)
                ) {
                    throw IrcProtocolException(
                        "CHATHISTORY TARGETS",
                        "unexpected ${message.command} record",
                    )
                }
                if (message.params.size != 3) {
                    throw IrcProtocolException(
                        "CHATHISTORY TARGETS",
                        "record must contain subcommand, target, and timestamp",
                    )
                }
                val target = message.params[1]
                if (target.isEmpty()) {
                    throw IrcProtocolException("CHATHISTORY TARGETS", "record target is empty")
                }
                val timestamp =
                    runCatching {
                        java.time.Instant
                            .parse(message.params[2])
                            .toEpochMilli()
                    }.getOrElse {
                        throw IrcProtocolException("CHATHISTORY TARGETS", "record timestamp is invalid")
                    }
                add(ChatHistoryTarget(target, timestamp))
            }
        }

    private fun historyReference(message: IrcMessage): ChatHistoryReference? {
        val msgid = message.tags["msgid"]?.takeIf(String::isNotEmpty)
        val serverTime =
            message.tags["time"]?.let { encoded ->
                runCatching {
                    java.time.Instant
                        .parse(encoded)
                        .toEpochMilli()
                }.getOrNull()
            }
        if (msgid == null && serverTime == null) return null
        return ChatHistoryReference(msgid, serverTime)
    }

    private companion object {
        const val LABEL_TIMEOUT_MS = 30_000L

        // Generous enough that a slow link answering a PING is not mistaken for a dead bouncer.
        const val BOUNCER_LIST_PROBE_GRACE_MS = 5_000L
        const val LIST_TIMEOUT_MS = 15_000L
        const val BAN_LIST_TIMEOUT_MS = 15_000L
        const val LIST_DRAIN_WAIT_MS = 15_000L
        const val JOIN_DRAIN_WAIT_MS = 5_000L
        const val ACCOUNT_REGISTRATION_TIMEOUT_MS = 30_000L
        const val SENSITIVE_ECHO_TTL_MS = 30_000L
        const val WEBPUSH_TIMEOUT_MS = 30_000L
        const val WHOX_TIMEOUT_MS = 15_000L
        const val CRITICAL_EVENT_CAPACITY = 4096
        const val OBSERVER_EVENT_CAPACITY = 4096
        const val DEFAULT_HISTORY_PAGE_LIMIT = 100
        const val CHATHISTORY_CAP = "draft/chathistory"
    }
}

private sealed interface AccountRegistrationWire {
    data class Success(
        val account: String,
        val message: String,
    ) : AccountRegistrationWire

    data class VerificationRequired(
        val account: String,
        val message: String,
    ) : AccountRegistrationWire

    data class Failure(
        val code: String,
        val message: String,
    ) : AccountRegistrationWire

    data class Disconnected(
        val reason: String?,
    ) : AccountRegistrationWire
}

private sealed interface WebPushResponse {
    data object Success : WebPushResponse

    data class Failure(
        val code: String,
        val text: String,
    ) : WebPushResponse

    data class Disconnected(
        val reason: String?,
    ) : WebPushResponse
}

/** Snapshot ISUPPORT into the plain map exposed on Ready/Registered. */
private fun isupportToMap(isupport: Isupport): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (key in listOf(
        "CASEMAPPING",
        "CHANTYPES",
        "PREFIX",
        "CHATHISTORY",
        "MSGREFTYPES",
        "MONITOR",
        "BOUNCER_NETID",
        "VAPID",
        "NETWORK",
        "draft/ICON",
        "BOT",
        "CLIENTTAGDENY",
        "soju.im/FILEHOST",
    )) {
        isupport[key]?.let { out[key] = it }
    }
    return out
}
