package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.*
import io.github.trevarj.motd.irc.ext.BatchAssembler
import io.github.trevarj.motd.irc.ext.BatchChild
import io.github.trevarj.motd.irc.ext.BatchTree
import io.github.trevarj.motd.irc.ext.BouncerCommands
import io.github.trevarj.motd.irc.ext.ChatHistoryCommands
import io.github.trevarj.motd.irc.ext.ReadMarkerCommands
import io.github.trevarj.motd.irc.ext.TypingOutbox
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

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

data class ChatHistoryResult(
    val events: List<IrcEvent>,               // empty = no (more) history
    val targets: List<Pair<String, Long>>,    // TARGETS only: (name, latest serverTime)
)

data class BouncerNetwork(val netId: String, val attrs: Map<String, String>) // attrs: name,host,state,nickname,...

/** One RPL_LIST (322) row. */
data class ChannelListing(val name: String, val userCount: Int, val topic: String)

data class WhoxResult(val rows: List<IrcEvent.WhoxRow>, val completed: Boolean)

/** One instance per physical socket. Restartable: start() after stop() reconnects fresh. */
class IrcClient(
    val config: IrcClientConfig,
    private val factory: TransportFactory,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<IrcClientState>(IrcClientState.Disconnected)
    val state: StateFlow<IrcClientState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<IrcEvent>(
        replay = 0,
        extraBufferCapacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<IrcEvent> = _events.asSharedFlow()

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
    private val batches = BatchAssembler()
    private val typingOutbox = TypingOutbox()
    private val eventMapper = EventMapper(selfNick = { selfNick.get() }, isupport = { _isupport.get() })
    private val whoxRequests = ConcurrentHashMap<String, Deferred<WhoxResult>>()
    private val whoxTokens = WhoxTokenPool()

    @Volatile private var transport: IrcTransport? = null
    @Volatile private var watchdog: PingWatchdog? = null
    private var runJob: Job? = null

    // Set once registration completes; used to gate steady-state routing.
    @Volatile private var registered = false

    fun start() {
        stop()
        registered = false
        _bouncerNetworks.value = emptyMap()   // drop any stale networks from a prior connection
        _state.value = IrcClientState.Connecting
        runJob = scope.launch { run() }
    }

    fun stop() {
        watchdog?.stop()
        watchdog = null
        runJob?.cancel()
        runJob = null
        labels.failAll(CancellationException("client stopped"))
        cancelWhoxRequests("client stopped")
        batches.reset()
        val t = transport
        transport = null
        registered = false
        ackedCaps.set(emptySet())
        runtimeAdvertisedCaps.clear()
        if (t != null) scope.launch { runCatching { t.close() } }
        if (_state.value != IrcClientState.Disconnected) {
            _state.value = IrcClientState.Disconnected
        }
    }

    private suspend fun run() {
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
            emitDisconnected(e.message)
            return
        }

        _state.value = IrcClientState.Registering
        val reg = RegistrationStateMachine(config)
        for (a in reg.start()) applyRegAction(a, t)

        val wd = PingWatchdog(
            scope = scope,
            sendPing = { payload -> runCatching { t.send("PING $payload") } },
            onDead = {
                _state.value = IrcClientState.Disconnected
                emitDisconnected("watchdog timeout")
                runCatching { t.close() }
            },
        )
        watchdog = wd
        wd.start()

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
                    runCatching { t.send("PONG ${msg.params.firstOrNull().orEmpty()}") }
                    return@collect
                }
                if (!registered) {
                    // Bouncer children can receive valued CAP NEW immediately before the
                    // registration machine marks them Ready. Retain those values so the
                    // subsequent value-less deferred ACK cannot widen a limited capability.
                    rememberAdvertisedCaps(msg)
                    for (a in reg.onMessage(msg)) applyRegAction(a, t)
                } else {
                    dispatch(msg, t)
                }
            }
            // Clean EOF.
            if (_state.value !is IrcClientState.Failed) {
                _state.value = IrcClientState.Disconnected
            }
            emitDisconnected(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_state.value !is IrcClientState.Failed) {
                _state.value = IrcClientState.Disconnected
            }
            emitDisconnected(e.message)
        } finally {
            wd.stop()
            labels.failAll(CancellationException("connection closed"))
            cancelWhoxRequests("connection closed")
            batches.reset()
        }
    }

    private fun cancelWhoxRequests(reason: String) {
        whoxRequests.values.forEach { it.cancel(CancellationException(reason)) }
        whoxRequests.clear()
        whoxTokens.clear()
    }

    private suspend fun applyRegAction(a: RegistrationStateMachine.Action, t: IrcTransport) {
        when (a) {
            is RegistrationStateMachine.Action.Send -> runCatching { t.send(a.line) }
            is RegistrationStateMachine.Action.SendDeferred -> scope.launch {
                delay(a.delayMs)
                if (transport === t) runCatching { t.send(a.line) }
            }
            is RegistrationStateMachine.Action.SetNick -> selfNick.set(a.nick)
            is RegistrationStateMachine.Action.Complete -> {
                selfNick.set(a.nick)
                _isupport.set(a.isupport)
                ackedCaps.set(a.caps)
                registered = true
                val isupportMap = isupportToMap(a.isupport)
                _state.value = IrcClientState.Ready(a.nick, a.caps, isupportMap)
                _events.emit(IrcEvent.Registered(a.nick, a.caps, isupportMap))
            }
            is RegistrationStateMachine.Action.Fail -> {
                _state.value = IrcClientState.Failed(a.reason, a.fatal)
                emitDisconnected(a.reason)
                runCatching { t.close() }
            }
        }
    }

    /** Steady-state dispatch: label correlation → CAP NEW/DEL → batch assembly → event mapping. */
    private suspend fun dispatch(msg: IrcMessage, t: IrcTransport) {
        // Labeled responses are consumed by the correlator (incl. their batch contents).
        if (labels.route(msg)) return

        // Runtime CAP NEW/DEL.
        if (msg.command == "CAP") {
            handleRuntimeCap(msg, t)
            return
        }

        // 005 normally arrives after 001. Keep Ready's snapshot current so app-owned feature
        // gates (notably CLIENTTAGDENY) do not operate on the empty registration-time map.
        if (msg.command == "005") updateRuntimeIsupport(msg)

        when (val outcome = batches.route(msg)) {
            BatchAssembler.Outcome.Buffered -> return
            is BatchAssembler.Outcome.Closed -> emitBatch(outcome)
            BatchAssembler.Outcome.PassThrough -> {
                val ev = eventMapper.map(msg, batchId = null) { reply ->
                    // CTCP auto-reply (e.g. VERSION) — fire and forget on the client scope.
                    scope.launch { runCatching { t.send(reply.serialize()) } }
                }
                if (ev != null) emitEvent(ev)
            }
        }
    }

    /** Emit an event, accumulating bouncer-network snapshots as a side effect. */
    private suspend fun emitEvent(ev: IrcEvent) {
        if (ev is IrcEvent.BouncerNetworkState) {
            _bouncerNetworks.update { cur ->
                // Empty attrs is soju's `BOUNCER NETWORK <id> *` deletion marker.
                if (ev.attrs.isEmpty()) cur - ev.netId else cur + (ev.netId to ev.attrs)
            }
        }
        _events.emit(ev)
    }

    private suspend fun emitBatch(closed: BatchAssembler.Outcome.Closed) {
        for (event in mapBatchTree(closed.tree)) emitEvent(event)
    }

    internal fun mapBatchTree(tree: BatchTree): List<IrcEvent> {
        if (tree.type == "netsplit" || tree.type == "netjoin") {
            val leaves = tree.leafMessages()
            val expected = if (tree.type == "netsplit") "QUIT" else "JOIN"
            if (tree.params.size == 2 && leaves.isNotEmpty() && leaves.all { it.first.command == expected }) {
                val events = leaves.mapNotNull { (message, batchRef) ->
                    eventMapper.map(message, batchId = batchRef)
                }
                if (events.size == leaves.size) {
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
                        ),
                    )
                }
            }
        }

        val flattened = tree.children.flatMap { child ->
            when (child) {
                is BatchChild.Message -> listOfNotNull(eventMapper.map(child.message, batchId = tree.ref))
                is BatchChild.Nested -> mapBatchTree(child.batch)
            }
        }
        return if (tree.type == "chathistory") {
            val target = tree.params.firstOrNull().orEmpty()
            listOf(
                IrcEvent.HistoryBatch(
                    target,
                    flattened.map { event ->
                        if (event is IrcEvent.NetworkBatch && event.target == null) {
                            event.copy(target = target)
                        } else {
                            event
                        }
                    },
                ),
            )
        } else {
            flattened
        }
    }

    private fun BatchTree.leafMessages(): List<Pair<IrcMessage, String>> = children.flatMap { child ->
        when (child) {
            is BatchChild.Message -> listOf(child.message to ref)
            is BatchChild.Nested -> child.batch.leafMessages()
        }
    }

    private suspend fun handleRuntimeCap(msg: IrcMessage, t: IrcTransport) {
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
                    for (b in CapNegotiator.batches(want)) runCatching { t.send("CAP REQ :$b") }
                }
            }
            "DEL" -> {
                ackedCaps.set(ackedCaps.get().filterNot { it.substringBefore('=') in caps }.toSet())
                updateReadyCaps(ackedCaps.get())
                _events.emit(IrcEvent.CapsChanged(emptySet(), caps))
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
                _events.emit(IrcEvent.CapsChanged(added.map { it.substringBefore('=') }.toSet(), removed))
            }
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

    private suspend fun updateRuntimeIsupport(msg: IrcMessage) {
        val isupport = _isupport.get()
        isupport.update(msg.params.drop(1).dropLast(1))
        val current = _state.value as? IrcClientState.Ready ?: return
        val snapshot = isupportToMap(isupport)
        _state.value = current.copy(isupport = snapshot)
        _events.emit(IrcEvent.Registered(current.nick, current.caps, snapshot))
    }

    private suspend fun emitDisconnected(reason: String?) {
        _events.emit(IrcEvent.Disconnected(reason))
    }

    // -- public send API --

    suspend fun send(msg: IrcMessage) {
        transport?.send(msg.serialize())
    }

    /** Attach a label tag, suspend until the labeled response/ack batch completes. */
    suspend fun sendLabeled(msg: IrcMessage): List<IrcMessage> {
        val t = transport ?: return emptyList()
        // Degrade without labeled-response: send unlabeled, complete immediately with empty list.
        if (!hasCap("labeled-response")) {
            t.send(msg.serialize())
            return emptyList()
        }
        val label = labels.next()
        val deferred = CompletableDeferred<List<IrcMessage>>()
        labels.register(label, deferred)
        val labeled = msg.copy(tags = msg.tags + ("label" to label))
        t.send(labeled.serialize())
        return try {
            withTimeout(LABEL_TIMEOUT_MS) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw IrcTimeoutException(label)
        }
    }

    /**
     * Convenience: PRIVMSG with label; returns the label used (for echo dedup). [beforeSend] runs
     * with the resolved label ("" when labeled-response is unavailable) IMMEDIATELY before the
     * message hits the wire, so the caller can persist its pending/optimistic row before any echo
     * can come back — on a fast (e.g. local) connection the echo-message reply otherwise races ahead
     * of the insert and the self-send duplicates.
     */
    suspend fun sendMessage(
        target: String,
        text: String,
        replyToMsgid: String?,
        beforeSend: suspend (String) -> Unit = {},
    ): String {
        val t = transport ?: return ""
        val tags = buildMap { if (replyToMsgid != null) put("+reply", replyToMsgid) }
        val base = IrcMessage(tags = tags, command = "PRIVMSG", params = listOf(target, text))
        if (!hasCap("labeled-response")) {
            beforeSend("")
            t.send(base.serialize())
            return ""
        }
        // Do NOT register a correlator deferred: the labeled echo must flow through as a normal
        // self ChatMessage event (carrying label in ctx) so the app can dedup the pending row.
        val label = labels.next()
        beforeSend(label)
        t.send(base.copy(tags = base.tags + ("label" to label)).serialize())
        return label
    }

    suspend fun sendTyping(target: String, state: String) {
        if (!hasCap("message-tags")) return
        val t = transport ?: return
        if (!typingOutbox.shouldSend(target, state)) return
        val msg = IrcMessage(tags = mapOf("+typing" to state), command = "TAGMSG", params = listOf(target))
        t.send(msg.serialize())
    }

    suspend fun sendReact(target: String, msgid: String, emoji: String) {
        if (!hasCap("message-tags")) return
        val t = transport ?: return
        val msg = IrcMessage(
            tags = mapOf("+draft/react" to emoji, "+reply" to msgid),
            command = "TAGMSG",
            params = listOf(target),
        )
        t.send(msg.serialize())
    }

    suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult {
        val limit = clampHistoryLimit(req.limit)
        val msg = when (req.subcommand) {
            ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryCommands.latest(req.target, limit)
            ChatHistoryRequest.Subcommand.BEFORE -> ChatHistoryCommands.before(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryCommands.after(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.AROUND -> ChatHistoryCommands.around(req.target, req.bound1.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.BETWEEN ->
                ChatHistoryCommands.between(req.target, req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
            ChatHistoryRequest.Subcommand.TARGETS ->
                ChatHistoryCommands.targets(req.bound1.orEmpty(), req.bound2.orEmpty(), limit)
        }
        val response = sendLabeled(msg)
        return if (req.subcommand == ChatHistoryRequest.Subcommand.TARGETS) {
            ChatHistoryResult(events = emptyList(), targets = parseTargets(response))
        } else {
            val events = response
                .filter { it.command != "BATCH" } // drop nested batch open/close markers
                .mapNotNull { eventMapper.map(it, batchId = it.tags["batch"]) }
            ChatHistoryResult(events = events, targets = emptyList())
        }
    }

    suspend fun markRead(target: String, timestampMs: Long) {
        val t = transport ?: return
        if (!hasCap("draft/read-marker")) return
        t.send(ReadMarkerCommands.set(target, timestampMs).serialize())
    }

    suspend fun fetchReadMarker(target: String) {
        val t = transport ?: return
        if (!hasCap("draft/read-marker")) return
        t.send(ReadMarkerCommands.get(target).serialize())
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
            events.first { event ->
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
            t.send(WhoxCommands.request(mask, token).serialize())
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
        val t = transport ?: return snapshotBouncerNetworks()
        // soju advertises no labeled-response, so sendLabeled would return empty. It instead
        // pushes BOUNCER NETWORK notifications (soju.im/bouncer-networks-notify) that we already
        // accumulate in _bouncerNetworks. Send an explicit LISTNETWORKS to force a refresh for
        // servers that do not push, then return the snapshot once it settles (notifications
        // usually arrive by the time the caller reaches Ready).
        runCatching { t.send(BouncerCommands.listNetworks().serialize()) }
        if (_bouncerNetworks.value.isEmpty()) {
            withTimeoutOrNull(2000) { while (_bouncerNetworks.value.isEmpty()) delay(50) }
        }
        return snapshotBouncerNetworks()
    }

    private fun snapshotBouncerNetworks(): List<BouncerNetwork> =
        _bouncerNetworks.value.map { (id, attrs) -> BouncerNetwork(id, attrs) }

    suspend fun bouncerAddNetwork(attrs: Map<String, String>): String {
        val response = sendLabeled(BouncerCommands.addNetwork(attrs))
        return response.firstNotNullOfOrNull { BouncerCommands.parseAddReply(it) } ?: ""
    }

    suspend fun bouncerDeleteNetwork(netId: String) {
        sendLabeled(BouncerCommands.deleteNetwork(netId))
    }

    // -- channel list (LIST / ELIST) --

    /**
     * LIST. [mask] filters server-side when given; [minUsers] appends the ELIST ">n" filter only
     * when ISUPPORT ELIST contains 'U'. Uses labeled-response when available; otherwise collects
     * raw 322s until 323 or a 15s timeout. Result truncated to [cap] rows.
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
            return response.mapNotNull { parseListLine(it) }.take(cap)
        }

        // Raw fallback: subscribe BEFORE sending so no 322/323 lines are missed, then collect until
        // the 323 terminator or a 15s timeout. Implemented inside IrcClient so the raw-numeric
        // fallback does not leak protocol into :app.
        val out = ArrayList<ChannelListing>()
        val collector = scope.launch {
            events.collect { ev ->
                if (ev !is IrcEvent.Raw) return@collect
                when (ev.message.command) {
                    "322" -> parseListMessage(ev.message)?.let { if (out.size < cap) out.add(it) }
                    "323" -> throw kotlinx.coroutines.CancellationException("LIST end")
                }
            }
        }
        transport?.send(msg.serialize())
        kotlinx.coroutines.withTimeoutOrNull(LIST_TIMEOUT_MS) { collector.join() }
        collector.cancel()
        return out.take(cap)
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
                events.mapNotNull { event ->
                    when (event) {
                        is IrcEvent.Raw -> {
                            val raw = event.message
                            when {
                                raw.command == "WEBPUSH" &&
                                    raw.params.getOrNull(0) == action &&
                                    raw.params.getOrNull(1) == endpoint -> WebPushResponse.Success
                                raw.command == "FAIL" &&
                                    raw.params.getOrNull(0) == "WEBPUSH" &&
                                    raw.params.drop(1).any { it == action } -> WebPushResponse.Failure(
                                        code = raw.params.getOrNull(1) ?: "FAIL",
                                        text = raw.params.lastOrNull().orEmpty(),
                                    )
                                else -> null
                            }
                        }
                        is IrcEvent.Disconnected -> WebPushResponse.Disconnected(event.reason)
                        else -> null
                    }
                }.first()
            }
            try {
                t.send(message.serialize())
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

    fun hasCap(cap: String): Boolean =
        ackedCaps.get().any { it == cap || it.startsWith("$cap=") }

    /** Live ISUPPORT state (normalize(), prefixModes, ...); empty until Ready. */
    val isupport: Isupport get() = _isupport.get()

    // -- helpers --

    private fun clampHistoryLimit(requested: Int): Int {
        val max = _isupport.get()["CHATHISTORY"]?.toIntOrNull() ?: 100
        return requested.coerceIn(1, max)
    }

    private fun parseTargets(response: List<IrcMessage>): List<Pair<String, Long>> =
        response.mapNotNull { m ->
            // CHATHISTORY TARGETS <target> <ISO timestamp>
            if (m.command != "CHATHISTORY" || m.params.getOrNull(0) != "TARGETS") return@mapNotNull null
            val target = m.params.getOrNull(1) ?: return@mapNotNull null
            val iso = m.params.getOrNull(2) ?: return@mapNotNull null
            val ts = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
            target to ts
        }

    private companion object {
        const val LABEL_TIMEOUT_MS = 30_000L
        const val LIST_TIMEOUT_MS = 15_000L
        const val WEBPUSH_TIMEOUT_MS = 30_000L
        const val WHOX_TIMEOUT_MS = 15_000L
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
        "BOUNCER_NETID",
        "VAPID",
        "NETWORK",
        "CLIENTTAGDENY",
    )) {
        isupport[key]?.let { out[key] = it }
    }
    return out
}
