package io.github.trevarj.motd.irc.event

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.time.Instant
import java.time.format.DateTimeParseException

sealed interface IrcClientState {
    data object Disconnected : IrcClientState

    data object Connecting : IrcClientState

    data object Registering : IrcClientState

    data class Ready(
        val nick: String,
        val caps: Set<String>,
        val isupport: Map<String, String>,
    ) : IrcClientState

    data class Failed(
        val reason: String,
        val fatal: Boolean,
    ) : IrcClientState // fatal = don't auto-retry (e.g. SASL fail)
}

enum class ServerTimeSource {
    TAG,
    LOCAL,

    /** Historical event had no valid server-time tag; never substitute the device clock. */
    UNKNOWN,
}

/** Context shared by chat-ish events. serverTime = epoch millis (from server-time tag or local clock). */
data class MessageContext(
    val msgid: String?,
    val serverTime: Long,
    val account: String?, // account-tag
    val batchId: String?, // enclosing batch, null when live
    val label: String?, // labeled-response echo correlation
    val serverTimeSource: ServerTimeSource = ServerTimeSource.TAG,
    val isHistoryContext: Boolean = false,
    /** Client-only IRCv3 tags, retained for feature-local protocol consumers. */
    val clientTags: Map<String, String> = emptyMap(),
    /** Namespaced server tags retained without teaching the pure IRC engine their semantics. */
    val extensionTags: Map<String, String> = emptyMap(),
)

sealed interface IrcEvent {
    // -- connection/registration
    data class Registered(
        val nick: String,
        val caps: Set<String>,
        val isupport: Map<String, String>,
    ) : IrcEvent

    data class CapsChanged(
        val added: Set<String>,
        val removed: Set<String>,
    ) : IrcEvent // CAP NEW/DEL

    data class Disconnected(
        val reason: String?,
    ) : IrcEvent

    // -- chat
    enum class ChatKind { PRIVMSG, NOTICE, ACTION }

    data class ChatMessage(
        val ctx: MessageContext,
        val kind: ChatKind,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String, // channel or our nick (query)
        val text: String,
        val isSelf: Boolean, // echo-message or self-inserted
        val replyToMsgid: String?, // +draft/reply
        val isBot: Boolean = false, // IRCv3 bot message tag
    ) : IrcEvent

    data class TagMessage( // TAGMSG: typing + react
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val typing: String?, // "active" | "paused" | "done"
        val reactEmoji: String?, // +draft/react
        val reactTargetMsgid: String?, // +draft/reply on a react carries the reacted-to msgid
    ) : IrcEvent

    data class MessageRedacted(
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val targetMsgid: String,
        val reason: String?,
    ) : IrcEvent

    /** Fully reassembled chathistory batch for one target, in server order. */
    data class HistoryBatch(
        val target: String,
        val events: List<IrcEvent>,
    ) : IrcEvent

    enum class PlaybackSource { CHATHISTORY, ZNC_PLAYBACK }

    enum class PlaybackPlacement { BEFORE, AFTER, LATEST, AUTOMATIC }

    data class PlaybackItem(
        val event: IrcEvent,
        val ordinal: Int,
        val isContext: Boolean = false,
        /** Raw protocol identity. Null means the server supplied no usable value. */
        val msgid: String? = null,
        /** Raw valid server-time tag. Null is not replaced by a local timestamp. */
        val serverTime: Long? = null,
    ) {
        companion object {
            fun from(
                event: IrcEvent,
                ordinal: Int,
            ): PlaybackItem {
                val metadata = event.historyEventMetadataOrNull()
                return PlaybackItem(
                    event = event,
                    ordinal = ordinal,
                    isContext = metadata?.isContext == true,
                    msgid = metadata?.msgid,
                    serverTime = metadata?.serverTime,
                )
            }
        }
    }

    /** Common ordered representation for automatic CHATHISTORY and native bouncer playback. */
    data class PlaybackBatch(
        val source: PlaybackSource,
        val target: String,
        val items: List<PlaybackItem>,
        val placement: PlaybackPlacement = PlaybackPlacement.AUTOMATIC,
    ) : IrcEvent {
        val events: List<IrcEvent> get() = items.map(PlaybackItem::event)
    }

    enum class NetworkBatchKind { NETSPLIT, NETJOIN }

    data class NetworkBatch(
        val kind: NetworkBatchKind,
        val serverA: String,
        val serverB: String,
        val events: List<IrcEvent>,
        val target: String? = null,
        val historyMetadata: HistoryEventMetadata? = null,
    ) : IrcEvent

    /** Compatibility wrapper for callers constructing native playback directly. */
    data class ReplayBatch(
        val target: String,
        val events: List<IrcEvent>,
    ) : IrcEvent

    // -- membership & user state
    data class Joined(
        val ctx: MessageContext,
        val nick: String,
        val channel: String,
        val account: String?,
        val realname: String?,
        val isSelf: Boolean,
    ) : IrcEvent

    data class Parted(
        val ctx: MessageContext,
        val nick: String,
        val channel: String,
        val reason: String?,
        val isSelf: Boolean,
    ) : IrcEvent

    data class Quit(
        val ctx: MessageContext,
        val nick: String,
        val reason: String?,
    ) : IrcEvent

    data class Kicked(
        val ctx: MessageContext,
        val nick: String,
        val channel: String,
        val by: String,
        val reason: String?,
        val isSelf: Boolean,
    ) : IrcEvent

    data class NickChanged(
        val ctx: MessageContext,
        val from: String,
        val to: String,
        val isSelf: Boolean,
    ) : IrcEvent

    data class Names(
        val channel: String,
        val members: List<Member>,
    ) : IrcEvent {
        data class Member(
            val nick: String,
            val prefixes: String,
            val username: String?,
            val host: String?,
        )
    }

    data class NamesStarted(
        val channel: String,
    ) : IrcEvent

    data class AwayChanged(
        val nick: String,
        val awayMessage: String?,
    ) : IrcEvent

    /**
     * Server-confirmed change of OUR OWN away state (305 RPL_UNAWAY / 306 RPL_NOWAWAY).
     *
     * [text] is the server's informational line with our nick already dropped, so consumers can
     * render it verbatim. Neither numeric carries the away message itself; the sender is the only
     * one who knows it.
     */
    data class SelfAwayChanged(
        val isAway: Boolean,
        val text: String,
        val ctx: MessageContext? = null,
    ) : IrcEvent

    data class AccountChanged(
        val nick: String,
        val account: String?,
    ) : IrcEvent

    data class HostChanged(
        val nick: String,
        val newUser: String,
        val newHost: String,
    ) : IrcEvent

    data class RealnameChanged(
        val nick: String,
        val realname: String,
    ) : IrcEvent

    data class WhoxRow(
        val token: Int,
        val username: String?,
        val host: String?,
        val nick: String,
        val account: String?,
        val flags: String?,
        val realname: String?,
    ) : IrcEvent

    data class WhoxComplete(
        val mask: String,
    ) : IrcEvent

    data class MonitorOnline(
        val identities: List<io.github.trevarj.motd.irc.proto.Prefix>,
    ) : IrcEvent

    data class MonitorOffline(
        val nicks: List<String>,
    ) : IrcEvent

    data class MonitorList(
        val nicks: List<String>,
    ) : IrcEvent

    data object MonitorListEnd : IrcEvent

    data class MonitorLimitExceeded(
        val limit: Int?,
        val targets: List<String>,
        val text: String,
    ) : IrcEvent

    // -- channel state
    data class TopicChanged(
        val ctx: MessageContext,
        val channel: String,
        val topic: String,
        val setBy: String?,
    ) : IrcEvent

    /** Current channel topic supplied by RPL_TOPIC/RPL_NOTOPIC; this is state, not a timeline event. */
    data class TopicSnapshot(
        val channel: String,
        val topic: String,
    ) : IrcEvent

    data class ChannelRenamed(
        val ctx: MessageContext,
        val actor: String?,
        val oldName: String,
        val newName: String,
        val reason: String?,
    ) : IrcEvent

    data class ModeChanged(
        val ctx: MessageContext,
        val target: String,
        val modes: String,
        val args: List<String>,
    ) : IrcEvent

    data class Invited(
        val ctx: MessageContext,
        val by: String,
        val nick: String,
        val channel: String,
    ) : IrcEvent

    // -- DCC / CTCP direct connections
    enum class DccFileProtocol { SEND, SSEND }

    enum class DccAddressKind { IPV4_INTEGER, IPV4_DOTTED, IPV6_LITERAL }

    enum class DccUnsupportedReason { UNKNOWN_COMMAND, MALFORMED }

    data class DccEndpoint(
        val address: String,
        /** DCC passive/reverse offers use port 0 and carry a non-empty token. */
        val port: Int,
        val addressKind: DccAddressKind,
    )

    data class DccSendOffer(
        val protocol: DccFileProtocol,
        val filename: String,
        val endpoint: DccEndpoint,
        val sizeBytes: Long?,
        val token: String?,
    )

    data class DccResumeRequest(
        val filename: String,
        val port: Int,
        val positionBytes: Long,
        val token: String?,
    )

    data class DccResumeAccepted(
        val filename: String,
        val port: Int,
        val positionBytes: Long,
        val token: String?,
    )

    data class DccSend(
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val offer: DccSendOffer,
    ) : IrcEvent

    data class DccResume(
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val request: DccResumeRequest,
    ) : IrcEvent

    data class DccAccept(
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val accepted: DccResumeAccepted,
    ) : IrcEvent

    data class UnsupportedDcc(
        val ctx: MessageContext,
        val source: io.github.trevarj.motd.irc.proto.Prefix,
        val target: String,
        val command: String?,
        val reason: DccUnsupportedReason,
        val rawPayload: String,
    ) : IrcEvent

    // -- sync
    data class ReadMarker(
        val target: String,
        val timestamp: Long?,
    ) : IrcEvent // MARKREAD; null = "*" (unset)

    data class BouncerNetworkState(
        val netId: String,
        val attrs: Map<String, String>,
    ) : IrcEvent // BOUNCER NETWORK notify

    enum class StandardReplySeverity { FAIL, WARN, NOTE }

    data class StandardReply(
        val ctx: MessageContext,
        val severity: StandardReplySeverity,
        val commandName: String,
        val code: String,
        val context: List<String>,
        val description: String,
    ) : IrcEvent

    data class MultilineRejected(
        val ctx: MessageContext,
        val label: String,
        val code: String,
        val context: List<String>,
        val description: String,
    ) : IrcEvent

    data class ServerError(
        val code: String,
        val params: List<String>,
        val text: String,
        val ctx: MessageContext? = null,
    ) : IrcEvent

    /** Escape hatch: anything not mapped above (raw numerics for motd text, WHOIS, etc.). */
    data class Raw(
        val message: IrcMessage,
    ) : IrcEvent
}

fun IrcEvent.messageContextOrNull(): MessageContext? =
    when (this) {
        is IrcEvent.ChatMessage -> ctx
        is IrcEvent.TagMessage -> ctx
        is IrcEvent.Joined -> ctx
        is IrcEvent.Parted -> ctx
        is IrcEvent.Quit -> ctx
        is IrcEvent.Kicked -> ctx
        is IrcEvent.NickChanged -> ctx
        is IrcEvent.TopicChanged -> ctx
        is IrcEvent.ChannelRenamed -> ctx
        is IrcEvent.ModeChanged -> ctx
        is IrcEvent.Invited -> ctx
        is IrcEvent.DccSend -> ctx
        is IrcEvent.DccResume -> ctx
        is IrcEvent.DccAccept -> ctx
        is IrcEvent.UnsupportedDcc -> ctx
        is IrcEvent.StandardReply -> ctx
        is IrcEvent.MultilineRejected -> ctx
        is IrcEvent.SelfAwayChanged -> ctx
        is IrcEvent.ServerError -> ctx
        else -> null
    }

/** Wire metadata shared by mapped events and raw history-context fallbacks. */
data class HistoryEventMetadata(
    val isContext: Boolean,
    val msgid: String?,
    val serverTime: Long?,
)

fun IrcEvent.historyEventMetadataOrNull(): HistoryEventMetadata? {
    messageContextOrNull()?.let { context ->
        return HistoryEventMetadata(
            isContext = context.isHistoryContext,
            msgid = context.msgid,
            serverTime = context.serverTime.takeIf { context.serverTimeSource == ServerTimeSource.TAG },
        )
    }
    (this as? IrcEvent.NetworkBatch)?.historyMetadata?.let { return it }
    val raw = (this as? IrcEvent.Raw)?.message ?: return null
    val taggedTime =
        raw.tags["time"]?.let { value ->
            try {
                Instant.parse(value).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    if (
        "draft/chathistory-context" !in raw.tags &&
        "msgid" !in raw.tags &&
        "time" !in raw.tags
    ) {
        return null
    }
    return HistoryEventMetadata(
        isContext = "draft/chathistory-context" in raw.tags,
        msgid = raw.tags["msgid"],
        serverTime = taggedTime,
    )
}
