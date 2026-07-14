package io.github.trevarj.motd.irc.event

import io.github.trevarj.motd.irc.proto.IrcMessage

sealed interface IrcClientState {
    data object Disconnected : IrcClientState
    data object Connecting : IrcClientState
    data object Registering : IrcClientState
    data class Ready(val nick: String, val caps: Set<String>, val isupport: Map<String, String>) : IrcClientState
    data class Failed(val reason: String, val fatal: Boolean) : IrcClientState  // fatal = don't auto-retry (e.g. SASL fail)
}

/** Context shared by chat-ish events. serverTime = epoch millis (from server-time tag or local clock). */
data class MessageContext(
    val msgid: String?,
    val serverTime: Long,
    val account: String?,     // account-tag
    val batchId: String?,     // enclosing batch, null when live
    val label: String?,       // labeled-response echo correlation
)

sealed interface IrcEvent {
    // -- connection/registration
    data class Registered(val nick: String, val caps: Set<String>, val isupport: Map<String, String>) : IrcEvent
    data class CapsChanged(val added: Set<String>, val removed: Set<String>) : IrcEvent  // CAP NEW/DEL
    data class Disconnected(val reason: String?) : IrcEvent

    // -- chat
    enum class ChatKind { PRIVMSG, NOTICE, ACTION }
    data class ChatMessage(
        val ctx: MessageContext, val kind: ChatKind,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,          // channel or our nick (query)
        val text: String,
        val isSelf: Boolean,         // echo-message or self-inserted
        val replyToMsgid: String?,   // +draft/reply
    ) : IrcEvent
    data class TagMessage(           // TAGMSG: typing + react
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val typing: String?,         // "active" | "paused" | "done"
        val reactEmoji: String?,     // +draft/react
        val reactTargetMsgid: String?, // +draft/reply on a react carries the reacted-to msgid
    ) : IrcEvent
    /** Fully reassembled chathistory batch for one target, in server order. */
    data class HistoryBatch(val target: String, val events: List<IrcEvent>) : IrcEvent
    enum class NetworkBatchKind { NETSPLIT, NETJOIN }
    data class NetworkBatch(
        val kind: NetworkBatchKind,
        val serverA: String,
        val serverB: String,
        val events: List<IrcEvent>,
        val target: String? = null,
    ) : IrcEvent

    // -- membership & user state
    data class Joined(val ctx: MessageContext, val nick: String, val channel: String, val account: String?, val realname: String?, val isSelf: Boolean) : IrcEvent
    data class Parted(val ctx: MessageContext, val nick: String, val channel: String, val reason: String?, val isSelf: Boolean) : IrcEvent
    data class Quit(val ctx: MessageContext, val nick: String, val reason: String?) : IrcEvent
    data class Kicked(val ctx: MessageContext, val nick: String, val channel: String, val by: String, val reason: String?, val isSelf: Boolean) : IrcEvent
    data class NickChanged(val ctx: MessageContext, val from: String, val to: String, val isSelf: Boolean) : IrcEvent
    data class Names(val channel: String, val members: List<Member>) : IrcEvent {
        data class Member(
            val nick: String,
            val prefixes: String,
            val username: String?,
            val host: String?,
        )
    }
    data class NamesStarted(val channel: String) : IrcEvent
    data class AwayChanged(val nick: String, val awayMessage: String?) : IrcEvent
    data class AccountChanged(val nick: String, val account: String?) : IrcEvent
    data class HostChanged(val nick: String, val newUser: String, val newHost: String) : IrcEvent
    data class RealnameChanged(val nick: String, val realname: String) : IrcEvent
    data class WhoxRow(
        val token: Int,
        val username: String?,
        val host: String?,
        val nick: String,
        val account: String?,
        val flags: String?,
        val realname: String?,
    ) : IrcEvent
    data class WhoxComplete(val mask: String) : IrcEvent
    data class MonitorOnline(val identities: List<io.github.trevarj.motd.irc.proto.Prefix>) : IrcEvent
    data class MonitorOffline(val nicks: List<String>) : IrcEvent
    data class MonitorList(val nicks: List<String>) : IrcEvent
    data object MonitorListEnd : IrcEvent
    data class MonitorLimitExceeded(val limit: Int?, val targets: List<String>, val text: String) : IrcEvent

    // -- channel state
    data class TopicChanged(val ctx: MessageContext, val channel: String, val topic: String, val setBy: String?) : IrcEvent
    data class ModeChanged(val ctx: MessageContext, val target: String, val modes: String, val args: List<String>) : IrcEvent
    data class Invited(val ctx: MessageContext, val by: String, val nick: String, val channel: String) : IrcEvent

    // -- sync
    data class ReadMarker(val target: String, val timestamp: Long?) : IrcEvent  // MARKREAD; null = "*" (unset)
    data class BouncerNetworkState(val netId: String, val attrs: Map<String, String>) : IrcEvent // BOUNCER NETWORK notify
    data class ServerError(val code: String, val params: List<String>, val text: String) : IrcEvent
    /** Escape hatch: anything not mapped above (raw numerics for MOTD text, WHOIS, etc.). */
    data class Raw(val message: IrcMessage) : IrcEvent
}
