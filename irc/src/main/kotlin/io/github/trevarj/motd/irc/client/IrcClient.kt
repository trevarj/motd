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
import io.github.trevarj.motd.irc.ext.parseSearchResult
import io.github.trevarj.motd.irc.ext.WebPushCommands
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportConfigurationException
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class SaslMechanism { NONE, PLAIN, EXTERNAL }

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
    /** soju: bind this connection to a bouncer network before CAP END. */
    val bouncerNetId: String? = null,
    /** Extra caps to request beyond the built-in tiers (rarely needed). */
    val extraCaps: Set<String> = emptySet(),
    /**
     * Opt-in IRC-over-WebSocket transport (plans/19 §3.3). When non-null (e.g.
     * `wss://bnc.example.com:443/`) the connection is tunneled over a WebSocket to that URL to
     * blend with ordinary HTTPS; when null the default TCP/TLS line transport is used. TLS,
     * hostname verification, and cert pinning still key on the WS URL's real host/port.
     */
    val wsUrl: String? = null,
)

data class ChatHistoryRequest(
    val subcommand: Subcommand, val target: String,
    /** Bounds are "timestamp=<ISO8601>" or "msgid=<id>" selectors, pre-rendered. */
    val bound1: String? = null, val bound2: String? = null,
    val limit: Int,
) { enum class Subcommand { LATEST, BEFORE, AFTER, AROUND, BETWEEN, TARGETS } }

data class BouncerNetwork(val netId: String, val attrs: Map<String, String>) // attrs: name,host,state,nickname,...

/** One RPL_LIST (322) row. */
data class ChannelListing(val name: String, val userCount: Int, val topic: String)

/** Collects one unlabelled LIST response. The caller must start this before writing LIST. */
internal fun CoroutineScope.launchUnlabeledChannelListCollector(
    events: SharedFlow<IrcEvent>,
    onListLine: (IrcMessage) -> Unit,
): Job = launch(start = CoroutineStart.UNDISPATCHED) {
    events.collect { event ->
        if (event !is IrcEvent.Raw) return@collect
        when (event.message.command) {
            "322" -> onListLine(event.message)
            "323" -> throw CancellationException("LIST end")
        }
    }
}

/** Memory-bounded accumulator that retains the busiest channels and preserves arrival order ties. */
private class BoundedChannelListings(private val capacity: Int) {
    private data class Entry(val listing: ChannelListing, val order: Long)

    private val entries = PriorityQueue<Entry>(
        compareBy<Entry> { it.listing.userCount }.thenByDescending { it.order },
    )
    private var nextOrder = 0L

    fun add(element: ChannelListing) {
        val entry = Entry(element, nextOrder++)
        if (capacity <= 0) return
        if (entries.size < capacity) {
            entries.add(entry)
            return
        }
        if (element.userCount <= entries.peek().listing.userCount) return
        entries.remove()
        entries.add(entry)
    }

    fun toList(): List<ChannelListing> = entries
        .sortedWith(compareByDescending<Entry> { it.listing.userCount }.thenBy { it.order })
        .map(Entry::listing)
}

data class WhoxResult(val rows: List<IrcEvent.WhoxRow>, val completed: Boolean)

/**
 * An event observed through a bounded fan-out stream. Consumers must treat a non-contiguous
 * [sequence] as lost state and recover from their own authoritative source.
 */
data class SequencedIrcEvent(val sequence: Long, val event: IrcEvent)

/** One instance per physical socket. Restartable: start() after stop() reconnects fresh. */
class IrcClient(
    val config: IrcClientConfig,
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
    private val observerBufferCapacity: Int = OBSERVER_EVENT_CAPACITY,
) {
    private val _state = MutableStateFlow<IrcClientState>(IrcClientState.Disconnected)
    val state: StateFlow<IrcClientState> = _state.asStateFlow()
    private val _targetClassificationReady = MutableStateFlow(false)
    /** True once CHANTYPES is explicit or the registration burst confirms protocol defaults. */
    val targetClassificationReady: StateFlow<Boolean> = _targetClassificationReady.asStateFlow()
    private val _pendingFeatureCaps = MutableStateFlow<Set<String>>(emptySet())
    /** Runtime/deferred CAP requests that have not received ACK or NAK yet. */
    val pendingFeatureCaps: StateFlow<Set<String>> = _pendingFeatureCaps.asStateFlow()

    private val _events = MutableSharedFlow<IrcEvent>(
        replay = 0,
        extraBufferCapacity = observerBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Best-effort fan-out for transient request correlation and UI-adjacent observers. */
    val broadcastEvents: SharedFlow<IrcEvent> = _events.asSharedFlow()

    private val _sequencedEvents = MutableSharedFlow<SequencedIrcEvent>(
        replay = 0,
        extraBufferCapacity = observerBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Bounded, ordered fan-out for stateful observers. It intentionally does not consume
     * [criticalEvents]; a slow observer is never allowed to stall the IRC reader.
     */
    val sequencedEvents: SharedFlow<SequencedIrcEvent> = _sequencedEvents.asSharedFlow()
    private var nextObserverSequence = 0L

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
    private val runtimeAdvertisedCaps = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val labels = LabelCorrelator()
    private val unlabeledChatHistory = UnlabeledChatHistoryCorrelator()
    private val unlabeledChatHistoryLock = Mutex()
    private val unlabeledSearch = UnlabeledSearchCorrelator()
    private val unlabeledSearchLock = Mutex()
    private val unlabeledChannelListLock = Mutex()
    private var unlabeledChannelListDrain: Job? = null
    private val outboundLock = Mutex()
    private val batches = BatchAssembler()
    private val typingOutbox = TypingOutbox()
    private val eventMapper = EventMapper(
        selfNick = { selfNick.get() },
        isupport = { _isupport.get() },
        sojuReadCap = { hasCap("soju.im/read") },
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

    fun start() {
        stop()
        require(observerBufferCapacity > 0) { "observer buffer capacity must be positive" }
        val criticalEvents = Channel<IrcEvent>(CRITICAL_EVENT_CAPACITY)
        criticalEventChannel = criticalEvents
        nextObserverSequence = 0L
        registered = false
        _targetClassificationReady.value = false
        _pendingFeatureCaps.value = emptySet()
        _bouncerNetworks.value = emptyMap()   // drop any stale networks from a prior connection
        _state.value = IrcClientState.Connecting
        runJob = scope.launch { run(criticalEvents) }
    }

    fun stop() {
        watchdog?.stop()
        watchdog = null
        lagMonitor?.stop()
        lagMonitor = null
        runJob?.cancel()
        runJob = null
        criticalEventChannel.cancel(CancellationException("client stopped"))
        labels.failAll(CancellationException("client stopped"))
        unlabeledChatHistory.failAll(CancellationException("client stopped"))
        unlabeledSearch.failAll(CancellationException("client stopped"))
        cancelWhoxRequests("client stopped")
        batches.reset()
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
        transport = t
        try {
            t.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Invalid persisted transport config cannot recover through backoff. Keep ordinary
            // socket/TLS failures retryable, but park until the user changes this setting.
            _state.value = IrcClientState.Failed(
                "connect failed: ${e.message}",
                fatal = e is TransportConfigurationException,
            )
            emitDisconnected(criticalEvents, disconnectedPublished, e.message)
            if (transport === t) transport = null
            return
        }

        _state.value = IrcClientState.Registering
        val reg = RegistrationStateMachine(config)
        for (a in reg.start()) applyRegAction(a, t, criticalEvents, disconnectedPublished)

        val wd = PingWatchdog(
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

        val lm = LagMonitor(
            scope = scope,
            sendPing = { payload -> runCatching { sendSerialized(t, "PING $payload") } },
            isRegistered = { registered },
            sink = _lag,
        )
        lagMonitor = lm
        lm.start()

        try {
            t.incoming.collect { line ->
                wd.onInbound()
                val msg = try {
                    IrcMessage.parse(line)
                } catch (_: IrcParseException) {
                    return@collect
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
                        applyRegAction(a, t, criticalEvents, disconnectedPublished)
                    }
                } else {
                    dispatch(msg, t, criticalEvents)
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
            wd.stop()
            lm.stop()
            if (transport === t) lagMonitor = null
            if (currentCoroutineContext().isActive) {
                labels.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                unlabeledChatHistory.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
                unlabeledSearch.failAllDisconnected((_state.value as? IrcClientState.Failed)?.reason)
            } else {
                labels.failAll(CancellationException("connection closed"))
                unlabeledChatHistory.failAll(CancellationException("connection closed"))
                unlabeledSearch.failAll(CancellationException("connection closed"))
            }
            cancelWhoxRequests("connection closed")
            batches.reset()
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
    ) {
        when (a) {
            is RegistrationStateMachine.Action.Send -> runCatching { sendSerialized(t, a.line) }
            is RegistrationStateMachine.Action.SendDeferred -> scope.launch {
                delay(a.delayMs)
                if (transport === t) runCatching { sendSerialized(t, a.line) }
            }
            is RegistrationStateMachine.Action.Emit -> publish(criticalEvents, a.event)
            is RegistrationStateMachine.Action.SetNick -> selfNick.set(a.nick)
            is RegistrationStateMachine.Action.Complete -> {
                selfNick.set(a.nick)
                _isupport.set(a.isupport)
                _targetClassificationReady.value =
                    a.isupport["CHANTYPES"] != null || a.assumeDefaultTargetClassification
                ackedCaps.set(a.caps)
                _pendingFeatureCaps.value = a.deferredCaps
                registered = true
                val isupportMap = isupportToMap(a.isupport)
                _state.value = IrcClientState.Ready(a.nick, a.caps, isupportMap)
                publish(criticalEvents, IrcEvent.Registered(a.nick, a.caps, isupportMap))
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
        msg: IrcMessage,
        t: IrcTransport,
        criticalEvents: Channel<IrcEvent>,
    ) {
        // Labeled responses are consumed by the correlator (incl. their batch contents).
        if (labels.route(msg)) return
        // Released soju lacks labeled-response, but its CHATHISTORY/SEARCH replies remain batched.
        if (unlabeledChatHistory.route(msg)) return
        if (unlabeledSearch.route(msg)) return

        // Runtime CAP NEW/DEL.
        if (msg.command == "CAP") {
            handleRuntimeCap(msg, t, criticalEvents)
            return
        }

        // 005 normally arrives after 001. Keep Ready's snapshot current so app-owned feature
        // gates (notably CLIENTTAGDENY) do not operate on the empty registration-time map.
        if (msg.command == "005") updateRuntimeIsupport(msg, criticalEvents)
        if (msg.command == "376" || msg.command == "422") {
            _targetClassificationReady.value = true
        }

        when (val outcome = batches.route(msg)) {
            BatchAssembler.Outcome.Buffered -> return
            is BatchAssembler.Outcome.Closed -> emitBatch(outcome, criticalEvents)
            BatchAssembler.Outcome.PassThrough -> {
                val ev = eventMapper.map(msg, batchId = null) { reply ->
                    // CTCP auto-reply (e.g. VERSION) — fire and forget on the client scope.
                    scope.launch { runCatching { sendSerialized(t, reply) } }
                }
                if (ev != null) emitEvent(ev, criticalEvents)
            }
        }
    }

    /** Emit an event, accumulating bouncer-network snapshots as a side effect. */
    private suspend fun emitEvent(
        ev: IrcEvent,
        criticalEvents: Channel<IrcEvent>,
    ) {
        if (ev is IrcEvent.BouncerNetworkState) {
            _bouncerNetworks.update { cur ->
                // Empty attrs is soju's `BOUNCER NETWORK <id> *` deletion marker.
                if (ev.attrs.isEmpty()) cur - ev.netId else cur + (ev.netId to ev.attrs)
            }
        }
        publish(criticalEvents, ev)
    }

    private suspend fun emitBatch(
        closed: BatchAssembler.Outcome.Closed,
        criticalEvents: Channel<IrcEvent>,
    ) {
        for (event in mapBatchTree(closed.tree)) emitEvent(event, criticalEvents)
    }

    internal fun mapBatchTree(
        tree: BatchTree,
        historical: Boolean = tree.type == "chathistory" || tree.type == "znc.in/playback",
    ): List<IrcEvent> {
        if (tree.type == MULTILINE_CAP) {
            mapMultilineBatch(tree, historical)?.let { return listOf(it) }
            return mapMalformedMultiline(tree, historical)
        }

        if (tree.type == "netsplit" || tree.type == "netjoin") {
            val leaves = tree.leafMessages()
            val expected = if (tree.type == "netsplit") "QUIT" else "JOIN"
            if (tree.params.size == 2 && leaves.isNotEmpty() && leaves.all { it.first.command == expected }) {
                val events = leaves.mapNotNull { (message, batchRef) ->
                    eventMapper.map(message, batchId = batchRef, historical = historical)
                }
                if (events.size == leaves.size) {
                    val historyReference = historyReference(tree.opening)
                    return listOf(
                        IrcEvent.NetworkBatch(
                            kind = if (tree.type == "netsplit") {
                                IrcEvent.NetworkBatchKind.NETSPLIT
                            } else {
                                IrcEvent.NetworkBatchKind.NETJOIN
                            },
                            serverA = tree.params[0],
                            serverB = tree.params[1],
                            events = events,
                            historyMetadata = HistoryEventMetadata(
                                isContext = "draft/chathistory-context" in tree.opening.tags,
                                msgid = historyReference?.msgid,
                                serverTime = historyReference?.serverTime,
                            ),
                        ),
                    )
                }
            }
        }

        val flattened = tree.children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> listOfNotNull(
                    eventMapper.map(child.message, batchId = tree.ref, historical = historical),
                )
                is BatchChild.Nested -> mapBatchTree(child.batch, historical)
            }
        }
        return if (tree.type == "chathistory") {
            val target = tree.params.firstOrNull().orEmpty()
            listOf(
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.CHATHISTORY,
                    target = target,
                    items = flattened.mapIndexed { ordinal, event ->
                        val targeted = if (event is IrcEvent.NetworkBatch && event.target == null) {
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
                    items = flattened.mapIndexed { ordinal, event ->
                        IrcEvent.PlaybackItem.from(event, ordinal)
                    },
                ),
            )
        } else {
            flattened
        }
    }

    private fun mapMultilineBatch(tree: BatchTree, historical: Boolean = false): IrcEvent.ChatMessage? {
        val target = tree.params.firstOrNull() ?: return null
        val messages = tree.children.map {
            (it as? BatchChild.Message)?.message ?: return null
        }
        if (messages.isEmpty()) return null
        val command = messages.first().command
        if (command != "PRIVMSG" && command != "NOTICE") return null
        if (messages.any { it.command != command || it.params.getOrNull(0) != target }) return null
        if (messages.any { MULTILINE_CONCAT_TAG in it.tags && it.params.getOrNull(1).orEmpty().isEmpty() }) {
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
        val synthetic = IrcMessage(
            tags = tree.opening.tags,
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
    ): List<IrcEvent> = tree.children.flatMap { child ->
        when (child) {
            is BatchChild.Message -> {
                val text = child.message.params.getOrNull(1).orEmpty()
                if ((child.message.command == "PRIVMSG" || child.message.command == "NOTICE") && text.isEmpty()) {
                    emptyList()
                } else {
                    listOfNotNull(
                        eventMapper.map(child.message, batchId = tree.ref, historical = historical),
                    )
                }
            }
            is BatchChild.Nested -> mapBatchTree(child.batch, historical)
        }
    }

    private fun BatchTree.leafMessages(): List<Pair<IrcMessage, String>> = children.flatMap { child ->
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
        val tokens = msg.params.last().split(' ').filter { it.isNotEmpty() }
        val caps = tokens.map { it.removePrefix("-").substringBefore('=') }.toSet()
        rememberAdvertisedCaps(msg)
        when (sub) {
            "NEW" -> {
                // REQ any tier cap newly advertised that we want.
                val alreadyAcked = ackedCaps.get().map { it.substringBefore('=') }.toSet()
                val want = CapNegotiator.runtimeRequestSet(caps, alreadyAcked, config.extraCaps)
                if (want.isNotEmpty()) {
                    _pendingFeatureCaps.value += want
                    for (b in CapNegotiator.batches(want)) runCatching { sendSerialized(t, "CAP REQ :$b") }
                }
            }
            "DEL" -> {
                ackedCaps.set(ackedCaps.get().filterNot { it.substringBefore('=') in caps }.toSet())
                updateReadyCaps(ackedCaps.get())
                publish(criticalEvents, IrcEvent.CapsChanged(emptySet(), caps))
            }
            "ACK" -> {
                val removed = tokens.filter { it.startsWith("-") }
                    .map { it.removePrefix("-").substringBefore('=') }
                    .toSet()
                val added = tokens.filterNot { it.startsWith("-") }.map { token ->
                    val name = token.substringBefore('=')
                    val value = token.substringAfter(
                        '=',
                        missingDelimiterValue = runtimeAdvertisedCaps[name].orEmpty(),
                    )
                    if (value.isEmpty()) name else "$name=$value"
                }.toSet()
                val updated = ackedCaps.get()
                    .filterNot { it.substringBefore('=') in removed }
                    .toSet() + added
                ackedCaps.set(updated)
                updateReadyCaps(ackedCaps.get())
                publish(
                    criticalEvents,
                    IrcEvent.CapsChanged(added.map { it.substringBefore('=') }.toSet(), removed),
                )
            }
            "NAK" -> Unit
        }
        if (sub == "ACK" || sub == "NAK" || sub == "DEL") {
            _pendingFeatureCaps.value -= caps
        }
    }

    private fun rememberAdvertisedCaps(msg: IrcMessage) {
        if (msg.command != "CAP") return
        val sub = msg.params.getOrNull(1) ?: return
        val tokens = msg.params.lastOrNull()?.split(' ')?.filter(String::isNotEmpty).orEmpty()
        when (sub) {
            "LS", "NEW" -> tokens.forEach { token ->
                val normalized = token.removePrefix("-")
                val name = normalized.substringBefore('=')
                runtimeAdvertisedCaps[name] = normalized.substringAfter('=', missingDelimiterValue = "")
            }
            "DEL" -> tokens.forEach { token ->
                runtimeAdvertisedCaps.remove(token.removePrefix("-").substringBefore('='))
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
        isupport.update(msg.params.drop(1).dropLast(1))
        if (isupport["CHANTYPES"] != null) _targetClassificationReady.value = true
        val current = _state.value as? IrcClientState.Ready ?: return
        val snapshot = isupportToMap(isupport)
        _state.value = current.copy(isupport = snapshot)
        publish(criticalEvents, IrcEvent.Registered(current.nick, current.caps, snapshot))
    }

    private suspend fun emitDisconnected(
        criticalEvents: Channel<IrcEvent>,
        disconnectedPublished: AtomicBoolean,
        reason: String?,
    ) {
        if (!disconnectedPublished.compareAndSet(false, true)) return
        publish(criticalEvents, IrcEvent.Disconnected(reason))
    }

    private suspend fun publish(
        criticalEvents: Channel<IrcEvent>,
        event: IrcEvent,
    ) {
        criticalEvents.send(event)
        val sequenced = SequencedIrcEvent(++nextObserverSequence, event)
        _events.emit(event)
        _sequencedEvents.emit(sequenced)
    }

    private suspend fun sendSerialized(t: IrcTransport, msg: IrcMessage) =
        sendSerialized(t, msg.serialize())

    private suspend fun sendSerialized(t: IrcTransport, line: String) {
        outboundLock.withLock { t.send(line) }
    }

    // -- public send API --

    suspend fun send(msg: IrcMessage) {
        transport?.let { sendSerialized(it, msg) }
    }

    /** Send one raw IRC message and report whether a live transport accepted the write. */
    suspend fun sendIfConnected(msg: IrcMessage): Boolean {
        val t = transport ?: return false
        sendSerialized(t, msg)
        return true
    }

    /** Send a logical protocol message without allowing another coroutine to interleave lines. */
    suspend fun sendAtomicallyIfConnected(messages: List<IrcMessage>): Boolean {
        if (messages.isEmpty()) return false
        val t = transport ?: return false
        outboundLock.withLock {
            messages.forEach { t.send(it.serialize()) }
        }
        return true
    }

    /** Attach a label tag, suspend until the labeled response/ack batch completes. */
    suspend fun sendLabeled(msg: IrcMessage): List<IrcMessage> {
        return sendLabeledResponse(msg).messages
    }

    private suspend fun sendLabeledResponse(msg: IrcMessage): CorrelatedResponse {
        val t = transport ?: throw IrcDisconnectedException(msg.command, null)
        // Degrade without labeled-response: send unlabeled, complete immediately with empty list.
        if (!hasCap("labeled-response")) {
            sendSerialized(t, msg)
            return CorrelatedResponse(emptyList(), rootBatch = null)
        }
        val label = labels.next()
        val deferred = CompletableDeferred<CorrelatedResponse>()
        labels.register(label, msg.command, deferred)
        val labeled = msg.copy(tags = msg.tags + ("label" to label))
        return try {
            sendSerialized(t, labeled)
            withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IrcTimeoutException(label)
        } finally {
            labels.unregister(label, deferred)
        }
    }

    /** Send one chat message using the app's exact durable attempt label. */
    suspend fun sendMessage(
        target: String,
        text: String,
        replyToMsgid: String?,
        label: String,
        forceLegacy: Boolean = false,
    ): Boolean {
        requireValidChatLabel(label)
        val t = transport ?: return false
        val labelTag = label.takeIf { hasCap("labeled-response") }
        val plan = planChatMessage(
            target = target,
            text = text,
            replyToMsgid = replyToMsgid,
            label = labelTag,
            multilineLimits = if (hasMultilineWireSupport() && !forceLegacy) multilineLimits else null,
            forceLegacy = forceLegacy,
        ) ?: return false
        // Do NOT register a correlator deferred: the labeled echo must flow through as a normal
        // self ChatMessage event (carrying label in ctx) so the app can dedup the pending row.
        when (plan) {
            is MultilineSendPlan.Single -> {
                if (plan.message.params.any { it.any { char -> char == '\r' || char == '\n' } }) {
                    return false
                }
                sendSerialized(t, plan.message)
            }
            is MultilineSendPlan.Batch -> outboundLock.withLock {
                t.send(plan.opening.serialize())
                plan.components.forEach { t.send(it.serialize()) }
                t.send(plan.closing.serialize())
            }
        }
        return true
    }

    suspend fun sendTyping(target: String, state: String) {
        if (!hasCap("message-tags")) return
        val t = transport ?: return
        if (!typingOutbox.shouldSend(target, state)) return
        val msg = IrcMessage(tags = mapOf("+typing" to state), command = "TAGMSG", params = listOf(target))
        sendSerialized(t, msg)
    }

    suspend fun sendReact(target: String, msgid: String, emoji: String) {
        if (!hasCap("message-tags")) return
        val t = transport ?: return
        val msg = IrcMessage(
            tags = mapOf("+draft/react" to emoji, "+reply" to msgid),
            command = "TAGMSG",
            params = listOf(target),
        )
        sendSerialized(t, msg)
    }

    suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
        val limit = clampHistoryLimit(req.limit)
        val msg = when (req.subcommand) {
            ChatHistoryRequest.Subcommand.LATEST ->
                ChatHistoryCommands.latest(req.target, req.bound1, limit)
            ChatHistoryRequest.Subcommand.BEFORE -> ChatHistoryCommands.before(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryCommands.after(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.AROUND -> ChatHistoryCommands.around(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.BETWEEN ->
                ChatHistoryCommands.between(req.target, req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.TARGETS ->
                ChatHistoryCommands.targets(req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
        }
        val response = if (hasCap("labeled-response")) {
            sendLabeledResponse(msg)
        } else {
            sendUnlabeledChatHistory(req, msg)
        }
        val root = response.rootBatch
            ?: throw IrcProtocolException("CHATHISTORY", "response did not contain a complete root batch")
        // Two accepted shapes, matching `search`: released soju sends a bare `chathistory` root,
        // while soju master (post-70c4ded) wraps that batch in an outer `labeled-response` batch.
        // In the wrapped shape the label opens the wrapper and the history batch sits one level in,
        // so rejecting it would turn a server upgrade into silently broken scrollback.
        // Unlike `search`, only the first batch inside the wrapper is the history batch: multiline
        // and netsplit batches nest within it rather than beside it, so they must not be matched
        // here. They are reassembled later by `mapCorrelatedHistory`, which walks the wrapper.
        val hist = if (root.params.getOrNull(1).orEmpty().equals("labeled-response", ignoreCase = true)) {
            response.messages.firstOrNull {
                it.command == "BATCH" && it.params.firstOrNull()?.startsWith("+") == true
            } ?: throw IrcProtocolException("CHATHISTORY", "labeled-response wrapper contained no batch")
        } else {
            root
        }
        val expectedType = if (req.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
            "draft/chathistory-targets"
        } else {
            "chathistory"
        }
        if (hist.command != "BATCH" || !hist.params.getOrNull(1).orEmpty().equals(expectedType, ignoreCase = true)) {
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
            val primaryMessages = mapped.filter { (message, _) ->
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
        val response = if (hasCap("labeled-response")) {
            sendLabeledResponse(msg)
        } else {
            sendUnlabeledSearch(msg)
        }
        val root = response.rootBatch
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
            val wrongInner = response.messages.firstOrNull {
                it.command == "BATCH" &&
                    it.params.firstOrNull()?.startsWith("+") == true &&
                    !it.params.getOrNull(1).orEmpty().equals("soju.im/search", ignoreCase = true)
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

        val rootRef = root.params.firstOrNull()?.removePrefix("+").orEmpty()
        val closed = assembler.route(IrcMessage(command = "BATCH", params = listOf("-$rootRef")))
            as? BatchAssembler.Outcome.Closed
            ?: throw IrcProtocolException("CHATHISTORY", "root batch did not assemble completely")
        return mapCorrelatedHistoryChildren(closed.tree)
    }

    private fun mapCorrelatedHistoryChildren(tree: BatchTree): List<Pair<IrcMessage, IrcEvent>> =
        tree.children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> listOfNotNull(
                    eventMapper.map(
                        child.message,
                        batchId = child.message.tags["batch"] ?: tree.ref,
                        historical = true,
                    )
                        ?.let { child.message to it },
                )
                is BatchChild.Nested -> {
                    val event = when (child.batch.type) {
                        MULTILINE_CAP -> mapMultilineBatch(child.batch, historical = true)
                        "netsplit", "netjoin" -> mapBatchTree(child.batch, historical = true).singleOrNull()
                        else -> null
                    }
                    if (event != null) {
                        val reference = historyReference(child.batch.opening)
                        val enriched = (event as? IrcEvent.NetworkBatch)?.copy(
                            historyMetadata = HistoryEventMetadata(
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
    ): CorrelatedResponse = unlabeledChatHistoryLock.withLock {
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

    suspend fun markRead(target: String, timestampMs: Long) {
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
    private fun readMarkerCommand(): String? = when {
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

    private suspend fun performWhox(mask: String, normalizedMask: String): WhoxResult {
        val token = whoxTokens.acquire() ?: return WhoxResult(emptyList(), completed = false)
        val t = transport ?: run {
            whoxTokens.release(token)
            return WhoxResult(emptyList(), completed = false)
        }
        val rows = ArrayList<IrcEvent.WhoxRow>()
        val collector = scope.async(start = CoroutineStart.UNDISPATCHED) {
            broadcastEvents.first { event ->
                when (event) {
                    is IrcEvent.WhoxRow -> {
                        if (event.token == token) rows += event
                        false
                    }
                    is IrcEvent.WhoxComplete ->
                        _isupport.get().normalize(event.mask) == normalizedMask
                    else -> false
                }
            }
        }
        return try {
            sendSerialized(t, WhoxCommands.request(mask, token))
            val completed = withTimeoutOrNull(WHOX_TIMEOUT_MS) {
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
        return snapshotBouncerNetworks()
    }

    private fun snapshotBouncerNetworks(): List<BouncerNetwork> =
        _bouncerNetworks.value.map { (id, attrs) -> BouncerNetwork(id, attrs) }

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
     * LIST. [mask] filters server-side when given; [minUsers] appends the ELIST ">n" filter only
     * when ISUPPORT ELIST contains 'U'. Uses labeled-response when available; otherwise collects
     * raw 322s until 323 or a 15s timeout. Returns only the [cap] most populated rows; the raw
     * collector stays memory-bounded even for a large unfiltered LIST.
     */
    suspend fun listChannels(mask: String? = null, minUsers: Int? = null, cap: Int = 2000): List<ChannelListing> {
        val params = buildList {
            mask?.takeIf { it.isNotBlank() }?.let { add(it) }
            // The user-count filter is only appended when the server advertises ELIST 'U'.
            val elistU = _isupport.get()["ELIST"]?.contains('U', ignoreCase = true) == true
            if (minUsers != null && elistU) add(">$minUsers")
        }
        val msg = IrcMessage(command = "LIST", params = params)

        if (hasCap("labeled-response")) {
            val response = sendLabeled(msg)
            val out = BoundedChannelListings(cap)
            response.mapNotNull(::parseListLine).forEach(out::add)
            return out.toList()
        }

        // Raw LIST numerics carry no request identity. Serialize requests and synchronously enter
        // collection before writing so a fast server cannot answer before the subscriber exists.
        return unlabeledChannelListLock.withLock {
            awaitPreviousChannelListResponse()
            val t = transport ?: throw IrcDisconnectedException("LIST", null)
            val out = BoundedChannelListings(cap)
            val collector = scope.launchUnlabeledChannelListCollector(broadcastEvents) { message ->
                parseListMessage(message)?.let(out::add)
            }
            var sent = false
            try {
                sendSerialized(t, msg)
                sent = true
                val completed = withTimeoutOrNull(LIST_TIMEOUT_MS) {
                    collector.join()
                    true
                } == true
                if (!completed) {
                    // Keep consuming this response through its 323 terminator. A later raw LIST
                    // must not see the tail of this response as its own result.
                    unlabeledChannelListDrain = collector
                    throw IrcTimeoutException("channel list")
                }
                out.toList()
            } catch (cancelled: CancellationException) {
                if (sent && collector.isActive) unlabeledChannelListDrain = collector
                throw cancelled
            } finally {
                if (unlabeledChannelListDrain !== collector) collector.cancelAndJoin()
            }
        }
    }

    /** Waits for a timed-out raw LIST's 323 before another uncorrelated LIST can be sent. */
    private suspend fun awaitPreviousChannelListResponse() {
        val previous = unlabeledChannelListDrain ?: return
        val drained = withTimeoutOrNull(LIST_DRAIN_WAIT_MS) {
            previous.join()
            true
        } == true
        if (!drained) throw IrcTimeoutException("previous channel list")
        if (unlabeledChannelListDrain === previous) unlabeledChannelListDrain = null
    }

    /** Parse an [IrcMessage] that is (or wraps) an RPL_LIST 322 into a [ChannelListing]. */
    private fun parseListLine(msg: IrcMessage): ChannelListing? =
        if (msg.command == "322") parseListMessage(msg) else null

    /** RPL_LIST: params = [me, channel, count, topic]. */
    private fun parseListMessage(msg: IrcMessage): ChannelListing? {
        if (msg.command != "322") return null
        val channel = msg.params.getOrNull(1) ?: return null
        val count = msg.params.getOrNull(2)?.toIntOrNull() ?: 0
        val topic = msg.params.getOrNull(3).orEmpty()
        return ChannelListing(channel, count, topic)
    }

    // -- soju webpush --

    suspend fun webpushRegister(endpoint: String, p256dh: ByteArray, auth: ByteArray) {
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
    private suspend fun webpushCommand(action: String, endpoint: String, message: IrcMessage) {
        val t = transport ?: throw IllegalStateException("IRC client is not connected")
        coroutineScope {
            val response = async(start = CoroutineStart.UNDISPATCHED) {
                broadcastEvents.mapNotNull { event ->
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
                        is IrcEvent.Disconnected -> WebPushResponse.Disconnected(event.reason)
                        else -> null
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

    fun hasCap(cap: String): Boolean =
        ackedCaps.get().any { it == cap || it.startsWith("$cap=") }

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
            hasCap("labeled-response") &&
            hasCap("echo-message") &&
            multilineLimits != null

    /** Live ISUPPORT state (normalize(), prefixModes, ...); empty until Ready. */
    val isupport: Isupport get() = _isupport.get()

    val historyAvailability: HistoryAvailability
        get() {
            if (_state.value !is IrcClientState.Ready) return HistoryAvailability.NegotiatingOrOffline
            if (!hasCap("draft/chathistory")) {
                val bouncerChild = config.bouncerNetId != null || config.saslUser?.contains('/') == true
                return if (bouncerChild) {
                    HistoryAvailability.NegotiatingOrOffline
                } else {
                    HistoryAvailability.Unsupported
                }
            }
            val referenceTypes = _isupport.get()["MSGREFTYPES"]?.let { advertised ->
                advertised.split(',', ' ').mapNotNullTo(linkedSetOf()) { type ->
                    when {
                        type.equals("timestamp", ignoreCase = true) -> HistoryReferenceType.TIMESTAMP
                        type.equals("msgid", ignoreCase = true) -> HistoryReferenceType.MSGID
                        else -> null
                    }
                }
            } ?: linkedSetOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)
            val advertisedLimit = _isupport.get()["CHATHISTORY"]?.toIntOrNull()
            val pageLimit = when {
                advertisedLimit == 0 -> Int.MAX_VALUE
                advertisedLimit != null && advertisedLimit > 0 -> advertisedLimit
                else -> DEFAULT_HISTORY_PAGE_LIMIT
            }
            return HistoryAvailability.Ready(referenceTypes, pageLimit)
        }

    // -- helpers --

    private fun clampHistoryLimit(requested: Int): Int {
        val advertised = _isupport.get()["CHATHISTORY"]?.toIntOrNull()
        val max = when {
            advertised == 0 -> Int.MAX_VALUE
            advertised != null && advertised > 0 -> advertised
            else -> DEFAULT_HISTORY_PAGE_LIMIT
        }
        return requested.coerceAtLeast(1).coerceAtMost(max)
    }

    private fun parseTargets(response: List<IrcMessage>): List<ChatHistoryTarget> = buildList {
        for (message in response) {
            // Generic labeled correlation retains nested batch framing. It is the only unrelated
            // protocol shape valid inside an otherwise completed TARGETS batch.
            if (message.command == "BATCH") continue
            if (message.command != "CHATHISTORY" ||
                !message.params.firstOrNull().orEmpty().equals("TARGETS", ignoreCase = true)
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
            val timestamp = runCatching {
                java.time.Instant.parse(message.params[2]).toEpochMilli()
            }.getOrElse {
                throw IrcProtocolException("CHATHISTORY TARGETS", "record timestamp is invalid")
            }
            add(ChatHistoryTarget(target, timestamp))
        }
    }

    private fun historyReference(message: IrcMessage): ChatHistoryReference? {
        val msgid = message.tags["msgid"]?.takeIf(String::isNotEmpty)
        val serverTime = message.tags["time"]?.let { encoded ->
            runCatching { java.time.Instant.parse(encoded).toEpochMilli() }.getOrNull()
        }
        if (msgid == null && serverTime == null) return null
        return ChatHistoryReference(msgid, serverTime)
    }

    private companion object {
        const val LABEL_TIMEOUT_MS = 30_000L
        const val LIST_TIMEOUT_MS = 15_000L
        const val LIST_DRAIN_WAIT_MS = 15_000L
        const val WEBPUSH_TIMEOUT_MS = 30_000L
        const val WHOX_TIMEOUT_MS = 15_000L
        const val CRITICAL_EVENT_CAPACITY = 4096
        const val OBSERVER_EVENT_CAPACITY = 4096
        const val DEFAULT_HISTORY_PAGE_LIMIT = 100
    }
}

private sealed interface WebPushResponse {
    data object Success : WebPushResponse
    data class Failure(val code: String, val text: String) : WebPushResponse
    data class Disconnected(val reason: String?) : WebPushResponse
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
        "CLIENTTAGDENY",
        "soju.im/FILEHOST",
    )) {
        isupport[key]?.let { out[key] = it }
    }
    return out
}
