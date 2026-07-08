package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.*
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*

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
    factory: TransportFactory,
    scope: CoroutineScope,
) {
    val state: StateFlow<IrcClientState> get() = TODO("WP3")
    val events: SharedFlow<IrcEvent> get() = TODO("WP3")          // buffered, replay 0, DROP_OLDEST at 4096
    fun start(): Unit = TODO("WP3")
    fun stop(): Unit = TODO("WP3")

    suspend fun send(msg: IrcMessage): Unit = TODO("WP3")
    /** Attach a label tag, suspend until the labeled response/ack batch completes. */
    suspend fun sendLabeled(msg: IrcMessage): List<IrcMessage> = TODO("WP3")
    /** Convenience: PRIVMSG with label; returns the label used (for echo dedup). */
    suspend fun sendMessage(target: String, text: String, replyToMsgid: String? = null): String = TODO("WP3")
    suspend fun sendTyping(target: String, state: String): Unit = TODO("WP3")            // TAGMSG +typing
    suspend fun sendReact(target: String, msgid: String, emoji: String): Unit = TODO("WP3")
    suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult = TODO("WP3")
    suspend fun markRead(target: String, timestampMs: Long): Unit = TODO("WP3")          // MARKREAD set
    suspend fun fetchReadMarker(target: String): Unit = TODO("WP3")                      // MARKREAD get -> ReadMarker event

    // soju bouncer-networks (valid on an unbound "root" connection)
    suspend fun bouncerListNetworks(): List<BouncerNetwork> = TODO("WP3")
    suspend fun bouncerAddNetwork(attrs: Map<String, String>): String = TODO("WP3")   // returns netId
    suspend fun bouncerDeleteNetwork(netId: String): Unit = TODO("WP3")

    // soju webpush
    suspend fun webpushRegister(endpoint: String, p256dh: ByteArray, auth: ByteArray): Unit = TODO("WP3")
    suspend fun webpushUnregister(endpoint: String): Unit = TODO("WP3")

    /** Caps ACKed on this connection; empty until Ready. */
    val caps: Set<String> get() = TODO("WP3")
    fun hasCap(cap: String): Boolean = TODO("WP3")
    /** Live ISUPPORT state (normalize(), prefixModes, ...); empty until Ready. */
    val isupport: io.github.trevarj.motd.irc.proto.Isupport get() = TODO("WP3")
}
