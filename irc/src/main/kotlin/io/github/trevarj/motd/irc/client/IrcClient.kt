package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.*
import io.github.trevarj.motd.irc.ext.BatchAssembler
import io.github.trevarj.motd.irc.ext.BouncerCommands
import io.github.trevarj.motd.irc.ext.ChatHistoryCommands
import io.github.trevarj.motd.irc.ext.ReadMarkerCommands
import io.github.trevarj.motd.irc.ext.TypingOutbox
import io.github.trevarj.motd.irc.ext.WebPushCommands
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
    /** soju: bind this connection to a bouncer network before CAP END. */
    val bouncerNetId: String? = null,
    /** Extra caps to request beyond the built-in tiers (rarely needed). */
    val extraCaps: Set<String> = emptySet(),
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

    private val selfNick = AtomicReference(config.nick)
    private val _isupport = AtomicReference(Isupport())
    private val ackedCaps = AtomicReference<Set<String>>(emptySet())

    private val labels = LabelCorrelator()
    private val batches = BatchAssembler()
    private val typingOutbox = TypingOutbox()
    private val eventMapper = EventMapper(selfNick = { selfNick.get() }, isupport = { _isupport.get() })

    @Volatile private var transport: IrcTransport? = null
    @Volatile private var watchdog: PingWatchdog? = null
    private var runJob: Job? = null

    // Set once registration completes; used to gate steady-state routing.
    @Volatile private var registered = false

    fun start() {
        stop()
        registered = false
        _state.value = IrcClientState.Connecting
        runJob = scope.launch { run() }
    }

    fun stop() {
        watchdog?.stop()
        watchdog = null
        runJob?.cancel()
        runJob = null
        labels.failAll(CancellationException("client stopped"))
        val t = transport
        transport = null
        registered = false
        if (t != null) scope.launch { runCatching { t.close() } }
        if (_state.value != IrcClientState.Disconnected) {
            _state.value = IrcClientState.Disconnected
        }
    }

    private suspend fun run() {
        val t = factory.create(config.host, config.port, config.tls)
        transport = t
        try {
            t.connect()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = IrcClientState.Failed("connect failed: ${e.message}", fatal = false)
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
        }
    }

    private suspend fun applyRegAction(a: RegistrationStateMachine.Action, t: IrcTransport) {
        when (a) {
            is RegistrationStateMachine.Action.Send -> runCatching { t.send(a.line) }
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

        when (val outcome = batches.route(msg)) {
            BatchAssembler.Outcome.Buffered -> return
            is BatchAssembler.Outcome.Closed -> emitBatch(outcome)
            BatchAssembler.Outcome.PassThrough -> {
                val ev = eventMapper.map(msg, batchId = null) { reply ->
                    // CTCP auto-reply (e.g. VERSION) — fire and forget on the client scope.
                    scope.launch { runCatching { t.send(reply.serialize()) } }
                }
                if (ev != null) _events.emit(ev)
            }
        }
    }

    private suspend fun emitBatch(closed: BatchAssembler.Outcome.Closed) {
        val content = closed.messages.filter { it.command != "BATCH" }
        if (closed.type == "chathistory") {
            val target = closed.params.firstOrNull().orEmpty()
            val events = content.mapNotNull { eventMapper.map(it, batchId = closed.ref) }
            _events.emit(IrcEvent.HistoryBatch(target, events))
        } else {
            // Unknown batch type: flatten, emit contents as if live (still tagged with batchId).
            for (m in content) {
                val ev = eventMapper.map(m, batchId = closed.ref)
                if (ev != null) _events.emit(ev)
            }
        }
    }

    private suspend fun handleRuntimeCap(msg: IrcMessage, t: IrcTransport) {
        val sub = msg.params.getOrNull(1) ?: return
        val caps = msg.params.last().split(' ').filter { it.isNotEmpty() }.map { it.substringBefore('=') }.toSet()
        when (sub) {
            "NEW" -> {
                // REQ any tier cap newly advertised that we want.
                val want = CapNegotiator.requestSet(caps, config.extraCaps)
                if (want.isNotEmpty()) {
                    for (b in CapNegotiator.batches(want)) runCatching { t.send("CAP REQ :$b") }
                }
            }
            "DEL" -> {
                ackedCaps.set(ackedCaps.get().filterNot { it.substringBefore('=') in caps }.toSet())
                _events.emit(IrcEvent.CapsChanged(emptySet(), caps))
            }
            "ACK" -> {
                ackedCaps.set(ackedCaps.get() + caps)
                _events.emit(IrcEvent.CapsChanged(caps, emptySet()))
            }
        }
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

    /** Convenience: PRIVMSG with label; returns the label used (for echo dedup). */
    suspend fun sendMessage(target: String, text: String, replyToMsgid: String?): String {
        val t = transport ?: return ""
        val tags = buildMap { if (replyToMsgid != null) put("+draft/reply", replyToMsgid) }
        val base = IrcMessage(tags = tags, command = "PRIVMSG", params = listOf(target, text))
        if (!hasCap("labeled-response")) {
            t.send(base.serialize())
            return ""
        }
        // Do NOT register a correlator deferred: the labeled echo must flow through as a normal
        // self ChatMessage event (carrying label in ctx) so the app can dedup the pending row.
        val label = labels.next()
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
            tags = mapOf("+draft/react" to emoji, "+draft/reply" to msgid),
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

    // -- soju bouncer-networks --

    suspend fun bouncerListNetworks(): List<BouncerNetwork> {
        val response = sendLabeled(BouncerCommands.listNetworks())
        return response.mapNotNull { BouncerCommands.parseNetworkLine(it) }
            .map { (id, attrs) -> BouncerNetwork(id, attrs) }
    }

    suspend fun bouncerAddNetwork(attrs: Map<String, String>): String {
        val response = sendLabeled(BouncerCommands.addNetwork(attrs))
        return response.firstNotNullOfOrNull { BouncerCommands.parseAddReply(it) } ?: ""
    }

    suspend fun bouncerDeleteNetwork(netId: String) {
        sendLabeled(BouncerCommands.deleteNetwork(netId))
    }

    // -- soju webpush --

    suspend fun webpushRegister(endpoint: String, p256dh: ByteArray, auth: ByteArray) {
        val t = transport ?: return
        t.send(WebPushCommands.register(endpoint, p256dh, auth).serialize())
    }

    suspend fun webpushUnregister(endpoint: String) {
        val t = transport ?: return
        t.send(WebPushCommands.unregister(endpoint).serialize())
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
    }
}

/** Snapshot ISUPPORT into the plain map exposed on Ready/Registered. */
private fun isupportToMap(isupport: Isupport): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (key in listOf("CASEMAPPING", "CHANTYPES", "PREFIX", "CHATHISTORY", "BOUNCER_NETID", "VAPID", "NETWORK")) {
        isupport[key]?.let { out[key] = it }
    }
    return out
}
