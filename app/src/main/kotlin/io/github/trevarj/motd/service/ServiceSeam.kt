package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.TimelineEventId
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.SearchRequest
import io.github.trevarj.motd.irc.ext.SearchResultMessage
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }

enum class SendRejectionReason {
    BUFFER_NOT_FOUND,
    INVALID_CONTENT,
    UNSUPPORTED_BUFFER,
    EVENT_NOT_RETRYABLE,
    PERSISTENCE_FAILED,
    NOT_IN_CHANNEL,
    SIDECARS_DISABLED,
}

enum class ImmediateWireAcceptance {
    ACCEPTED,
    DISCONNECTED,
    FAILED,
}

sealed interface SendAcceptance {
    data class Accepted(
        val eventIds: List<TimelineEventId>,
        val immediateWireAcceptance: ImmediateWireAcceptance = ImmediateWireAcceptance.ACCEPTED,
        /**
         * Row identity texts, using exact IRC-formatted payloads when present, in order.
         *
         * A submission is not always represented as typed: a reply can gain a `nick: ` prefix and
         * physical newlines can split into several rows. Presentation must compare wire-preserving
         * identity, not only plain searchable text. Empty means unreported and therefore matching.
         */
        val storedTexts: List<String> = emptyList(),
    ) : SendAcceptance

    data class Rejected(
        val reason: SendRejectionReason,
    ) : SendAcceptance
}

enum class RosterLoadState { NOT_LOADED, LOADING, LOADED, FAILED }

enum class PresenceState { UNKNOWN, ONLINE, OFFLINE }

data class PresenceKey(
    val networkId: Long,
    val normalizedNick: String,
)

/** Ephemeral, target-keyed server rejection for a browser-initiated JOIN. */
enum class ChannelJoinRejectionKind { INVITE_ONLY, BAD_KEY, ACCOUNT_REQUIRED, OTHER }

sealed interface ChannelJoinOutcome {
    data class Rejected(
        val networkId: Long,
        val channel: String,
        val reason: String,
        val code: String,
        val kind: ChannelJoinRejectionKind,
    ) : ChannelJoinOutcome
}

private val JOIN_FAILURE_NUMERICS = setOf("403", "405", "471", "473", "474", "475", "476", "477")

/** Extracts only JOIN-specific numeric and IRCv3 FAIL replies; unrelated server errors stay inert. */
internal fun channelJoinOutcome(
    networkId: Long,
    event: IrcEvent,
    identityRules: IrcIdentityRules,
): ChannelJoinOutcome.Rejected? {
    val (params, reason, code) =
        when (event) {
            is IrcEvent.ServerError -> {
                if (event.code in JOIN_FAILURE_NUMERICS) {
                    Triple(event.params, event.text.ifBlank { event.code }, event.code)
                } else {
                    return null
                }
            }

            is IrcEvent.StandardReply -> {
                if (event.severity != IrcEvent.StandardReplySeverity.FAIL ||
                    !event.commandName.equals("JOIN", ignoreCase = true)
                ) {
                    return null
                }
                Triple(event.context, event.description.ifBlank { event.code }, event.code)
            }

            else -> {
                return null
            }
        }
    val channel = params.firstOrNull(identityRules::isChannel) ?: return null
    val kind =
        when {
            code == "473" -> {
                ChannelJoinRejectionKind.INVITE_ONLY
            }

            code == "475" -> {
                ChannelJoinRejectionKind.BAD_KEY
            }

            code == "477" || code.contains("ACCOUNT_REQUIRED", ignoreCase = true) -> {
                ChannelJoinRejectionKind.ACCOUNT_REQUIRED
            }

            else -> {
                ChannelJoinRejectionKind.OTHER
            }
        }
    return ChannelJoinOutcome.Rejected(networkId, channel, reason, code, kind)
}

internal fun rosterStateAfterNames(explicitRefreshInFlight: Boolean): RosterLoadState = if (explicitRefreshInFlight) RosterLoadState.LOADING else RosterLoadState.LOADED

internal fun rosterStateAfterExplicitRefresh(completed: Boolean): RosterLoadState = if (completed) RosterLoadState.LOADED else RosterLoadState.FAILED

data class ConnectionActivitySnapshot(
    val states: Map<Long, IrcClientState> = emptyMap(),
    val progressing: Map<Long, Boolean> = emptyMap(),
    val initializationComplete: Boolean = true,
    val historyCatchUpPending: Set<Long> = emptySet(),
)

/**
 * A pending TOFU cert-trust decision surfaced to the UI. Published when a TLS handshake
 * hit an untrusted (self-signed / bare-IP / changed) leaf certificate. [changed] = true means a
 * previously-pinned cert now differs (possible MITM or rotation) and warrants a warning.
 */
data class CertPrompt(
    val networkId: Long,
    val host: String,
    val port: Int,
    val sha256: String, // lowercase hex of the presented leaf cert
    val subject: String,
    val issuer: String,
    val notBefore: Long, // epoch ms
    val notAfter: Long, // epoch ms
    val changed: Boolean,
)

interface ConnectionManager {
    /** Connection state per network row id. */
    val connectionStates: StateFlow<Map<Long, IrcClientState>>

    /** Atomically published connection state, actor liveness, and initial-reconcile readiness. */
    val connectionActivity: StateFlow<ConnectionActivitySnapshot>
    val rosterStates: StateFlow<Map<Long, RosterLoadState>>
    val presenceStates: StateFlow<Map<PresenceKey, PresenceState>>

    /** Latest PING/PONG round-trip latency (ms) per network id; null = unknown/disconnected (#34). */
    val lagStates: StateFlow<Map<Long, Long?>>
    val channelJoinOutcomes: Flow<ChannelJoinOutcome>

    /**
     * Our own server-confirmed away state per network id.
     *
     * A present key means the server told us we are away; the value is the away message when this
     * device is the one that set it, and null otherwise (306 RPL_NOWAWAY carries no message, so an
     * away set from another bouncer client is only known as "away"). Keys are authoritative,
     * values are best effort. Never updated optimistically.
     */
    val selfAwayStates: StateFlow<Map<Long, String?>>

    /** Live client for a connected network, null otherwise. */
    fun clientFor(networkId: Long): IrcClient?

    fun isSidecarNetwork(networkId: Long): Boolean = false

    fun sidecarsEnabled(): Boolean = true

    /** Start/stop the whole subsystem (invoked by service / delivery-mode changes). */
    suspend fun startAll()

    suspend fun stopAll()

    suspend fun connect(networkId: Long)

    suspend fun disconnect(networkId: Long)

    /**
     * Re-drive the wanted set and revive any actor that died/parked in the background (Doze/network
     * drop leaves it terminally Failed with a completed job). Used by app-foreground and device-wake
     * checkpoints. Ready actors receive one watchdog-style
     * liveness probe and are only restarted when the probe times out; healthy/connecting/retrying/
     * cert-parked actors otherwise remain untouched. Requests are conflated, so repeated lifecycle
     * callbacks cannot storm reconnects.
     */
    suspend fun reconnectStale()

    /**
     * Run one history checkpoint over the connected networks, exactly as a process foreground does.
     *
     * A notification tapped while the app is ALREADY foregrounded is re-delivered to the running
     * activity through `onNewIntent`, and no ProcessLifecycleOwner ON_START fires for it, so the
     * ordinary foreground verification never runs for that entry. A burst of taps cannot storm the
     * wire: the VERIFICATION half is skipped entirely for any connection that has stayed Ready
     * since its own catch-up converged, while the reconnect half is bounded by conflation --
     * probeReady coalesces on the registry's pending flag and the actor coalesces again on its own,
     * and reconcile is idempotent.
     *
     * "Exactly as a process foreground does" includes [reconnectStale]: the checkpoint paints
     * waiting badges on networks that are not Ready, and this entry has no other way to wake an
     * actor sitting out an exponential backoff behind them.
     *
     * [focusBufferId] is the buffer a notification tap is opening. It is reconciled FIRST and on
     * its own single-buffer request, so the conversation the user is looking at converges without
     * waiting for whatever the network-wide checkpoint decides to do; a request-level permit
     * interleaves it with any pass already on the wire.
     */
    suspend fun checkpointHistory(focusBufferId: Long? = null)

    /** Bounded provider wake path: connect and reconcile only one durable companion network. */
    suspend fun checkpointNetwork(networkId: Long) {
        checkpointHistory()
    }

    /** Accepted means every chunk is durably represented, not necessarily written to the wire. */
    suspend fun sendMessage(
        bufferId: Long,
        text: String,
        replyToEventId: TimelineEventId? = null,
        channelContext: String? = null,
    ): SendAcceptance

    /** Retry the same durable row with a new attempt label. */
    suspend fun retryMessage(eventId: TimelineEventId): SendAcceptance

    suspend fun sendTyping(
        bufferId: Long,
        state: String,
    )

    suspend fun sendReact(
        bufferId: Long,
        msgid: String,
        emoji: String,
    )

    /** Ask the server to redact one canonical message. True means the transport accepted REDACT. */
    suspend fun redactMessage(
        bufferId: Long,
        msgid: String,
    ): Boolean = false

    /** Send a composer command while retaining the chat that owns its server feedback. */
    suspend fun sendCommand(
        networkId: Long,
        originBufferId: Long,
        message: IrcMessage,
        channelContext: String? = null,
    ) {
        clientFor(networkId)?.send(message)
    }

    /**
     * JOIN [channel], optionally with [key]. Both stay in their comma-joined wire form so a
     * multi-channel `/join #a,#b key-a,key-b` keeps JOIN's positional channel/key pairing.
     */
    suspend fun joinChannel(
        networkId: Long,
        channel: String,
        key: String? = null,
    ): Boolean

    /** Write one IRC INVITE for a canonical joined channel. True means the live transport accepted it. */
    suspend fun inviteToChannel(
        bufferId: Long,
        nick: String,
    ): Boolean = false

    /** Atomically claim a persisted invitation, connect if needed, then send exactly one JOIN. */
    suspend fun acceptInvite(messageId: Long)

    /** Resolve a persisted invitation without joining. */
    suspend fun dismissInvite(messageId: Long)

    /** Explicit lazy roster refresh; duplicate callers share the same in-flight request. */
    suspend fun requestMembers(
        bufferId: Long,
        force: Boolean = false,
    )

    /** Part the buffer's channel; [reason] (from `/part <reason>`) becomes the PART trailing param. */
    suspend fun partChannel(
        bufferId: Long,
        reason: String? = null,
    )

    /**
     * PART seam used by durable channel-close requests. Returns true only when the connection
     * boundary confirms that the write reached its live transport.
     */
    suspend fun partChannelForClose(
        bufferId: Long,
        reason: String? = null,
    ): Boolean

    /**
     * Write a channel TOPIC command. True means the live transport accepted the write; it does
     * not mean the server authorized or echoed the change. Room is updated only by the IRC echo.
     */
    suspend fun setChannelTopic(
        bufferId: Long,
        topic: String,
    ): Boolean

    /**
     * Write an AWAY command. A null/blank [message] is "back". [selfAwayStates] only moves when the
     * server confirms with 305/306, so a write that never reaches the wire cannot desync the state.
     */
    suspend fun setAway(
        networkId: Long,
        message: String? = null,
    )

    /** True when this network's live client can run a server-side soju SEARCH right now. */
    fun serverSearchAvailable(networkId: Long): Boolean = clientFor(networkId)?.searchAvailable == true

    /**
     * History paging capability of this network's live client right now, or
     * [HistoryAvailability.NegotiatingOrOffline] when there is no client to ask.
     *
     * Derived accessor over [clientFor], exactly like [serverSearchAvailable] above, and for the
     * same reason: the answer lives in the client's registration/CAP/ISUPPORT state, which a caller
     * outside the service layer has no business reaching through a protocol object to read. Having
     * it on the seam is also what lets a lightweight fake model "history is negotiated and ready"
     * without standing up a transport and driving a full registration.
     */
    fun historyAvailabilityFor(networkId: Long): HistoryAvailability = clientFor(networkId)?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

    /**
     * Run one server-side SEARCH, or return null when the network has no live client.
     *
     * Results are transient protocol data: they are deliberately NOT routed through
     * [io.github.trevarj.motd.data.sync.EventProcessor] and never persisted, because a hit says
     * nothing about which intervals of history this device holds. Context comes from the existing
     * CHATHISTORY AROUND jump path instead.
     */
    suspend fun searchMessages(
        networkId: Long,
        request: SearchRequest,
    ): List<SearchResultMessage>? = clientFor(networkId)?.search(request)

    /** Find-or-create a QUERY buffer for a DM (name Isupport-normalized); returns bufferId. */
    suspend fun ensureQueryBuffer(
        networkId: Long,
        nick: String,
    ): Long

    /** Find-or-create the per-network SERVER buffer (name "*", displayName = network name);
     *  returns bufferId. UI entry for the server-messages timeline. */
    suspend fun ensureServerBuffer(networkId: Long): Long

    /** Advance the exact local anchor; wire MARKREAD uses an authoritative boundary at/before it. */
    suspend fun markRead(
        bufferId: Long,
        anchor: TimelineAnchor,
    )

    /** Re-evaluate push-mode socket teardown after per-network endpoint changes.
     *  No-op unless deliveryMode == UNIFIED_PUSH. Called by MotdPushReceiver.onNewEndpoint. */
    suspend fun evaluatePushMode()

    // -- TOFU cert trust --

    /** Pending cert-trust prompts (deduped by networkId). Observed by the global dialog host. */
    val certPrompts: StateFlow<List<CertPrompt>>

    /** Trust: pin the leaf SHA-256, drop the prompt, and reconnect that network. */
    suspend fun trustCert(prompt: CertPrompt)

    /** Dismiss: drop the prompt; the network stays disconnected until manually reconnected. */
    fun dismissCertPrompt(prompt: CertPrompt)
}

/** Sole IRC→Room write path. Implemented by EventProcessor (WP5); ConnectionManager delegates
 *  its pending-send insert here; push (WP9) feeds decrypted lines through it. WP1 stub-binds. */
interface IrcEventSink {
    suspend fun process(
        networkId: Long,
        event: io.github.trevarj.motd.irc.event.IrcEvent,
    )

    /** Persist a push-delivered event without treating it as live IRC session state. */
    suspend fun processPush(
        networkId: Long,
        event: io.github.trevarj.motd.irc.event.IrcEvent,
    )

    /** Persist one completed protocol page together with its exact primary-message boundaries. */
    suspend fun persistHistoryPage(
        networkId: Long,
        request: io.github.trevarj.motd.irc.client.ChatHistoryRequest,
        response: io.github.trevarj.motd.irc.client.ChatHistoryResponse.Messages,
        expectedRoomId: Long? = null,
    ): Long
}

/** In-memory typing state. Written by EventProcessor (WP5), read by ChatViewModel (WP7). */
interface TypingTracker {
    fun typingNicks(bufferId: Long): StateFlow<List<String>>
}

/** Buffer currently visible in the foreground UI. Set by ChatViewModel (WP7), read by the
 *  notification suppression logic (WP5). WP1 provides the trivial impl (a MutableStateFlow). */
interface ForegroundBufferTracker {
    val foregroundBufferId: StateFlow<Long?>

    fun set(bufferId: Long?)
}

/** Nothing is ever visible: the default for fixtures and headless collaborators. */
object NoopForegroundBufferTracker : ForegroundBufferTracker {
    override val foregroundBufferId: StateFlow<Long?> = MutableStateFlow(null)

    override fun set(bufferId: Long?) = Unit
}

/**
 * Whether the app itself is on screen, independent of which destination happens to be composed.
 *
 * Navigation composes exactly one destination on a phone, so a screen's own lifecycle says nothing
 * about whether the user is still inside the app: opening a chat disposes the chat list. A pane
 * whose data must be TRUE on the frame it is composed again therefore has to hold its observation
 * against the process rather than against itself; this is that gate. Owned by the process
 * lifecycle (WP1 impl), read by ViewModels (WP7).
 */
interface AppVisibility {
    val onScreen: StateFlow<Boolean>
}

/** Always on screen: the default for fixtures and headless collaborators. */
object AlwaysOnScreen : AppVisibility {
    override val onScreen: StateFlow<Boolean> = MutableStateFlow(true)
}
