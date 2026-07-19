package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }
enum class RetrySendResult {
    NO_ATTEMPT,
    REPLACEMENT_FAILED,
    ACCEPTED;

    val replacementPersisted: Boolean
        get() = this != NO_ATTEMPT
}
enum class RosterLoadState { NOT_LOADED, LOADING, LOADED, FAILED }
enum class PresenceState { UNKNOWN, ONLINE, OFFLINE }
data class PresenceKey(val networkId: Long, val normalizedNick: String)

internal fun rosterStateAfterNames(explicitRefreshInFlight: Boolean): RosterLoadState =
    if (explicitRefreshInFlight) RosterLoadState.LOADING else RosterLoadState.LOADED

internal fun rosterStateAfterExplicitRefresh(completed: Boolean): RosterLoadState =
    if (completed) RosterLoadState.LOADED else RosterLoadState.FAILED

private val EMPTY_ROSTER_STATES: StateFlow<Map<Long, RosterLoadState>> = MutableStateFlow(emptyMap())
private val EMPTY_PRESENCE_STATES: StateFlow<Map<PresenceKey, PresenceState>> = MutableStateFlow(emptyMap())

/**
 * A pending TOFU cert-trust decision surfaced to the UI (plans/12). Published when a TLS handshake
 * hit an untrusted (self-signed / bare-IP / changed) leaf certificate. [changed] = true means a
 * previously-pinned cert now differs (possible MITM or rotation) and warrants a warning.
 */
data class CertPrompt(
    val networkId: Long,
    val host: String,
    val port: Int,
    val sha256: String,            // lowercase hex of the presented leaf cert
    val subject: String,
    val issuer: String,
    val notBefore: Long,           // epoch ms
    val notAfter: Long,            // epoch ms
    val changed: Boolean,
)

interface ConnectionManager {
    /** Connection state per network row id. */
    val connectionStates: StateFlow<Map<Long, IrcClientState>>
    val rosterStates: StateFlow<Map<Long, RosterLoadState>> get() = EMPTY_ROSTER_STATES
    val presenceStates: StateFlow<Map<PresenceKey, PresenceState>> get() = EMPTY_PRESENCE_STATES

    /** Live client for a connected network, null otherwise. */
    fun clientFor(networkId: Long): IrcClient?

    /** Start/stop the whole subsystem (invoked by service / delivery-mode changes). */
    suspend fun startAll()
    suspend fun stopAll()
    suspend fun connect(networkId: Long)
    suspend fun disconnect(networkId: Long)

    /**
     * Re-drive the wanted set and revive any actor that died/parked in the background (Doze/network
     * drop leaves it terminally Failed with a completed job). Canonical app-foreground reconnect,
     * invoked from ProcessLifecycleOwner's onStart. Ready actors receive one watchdog-style
     * liveness probe and are only restarted when the probe times out; healthy/connecting/retrying/
     * cert-parked actors otherwise remain untouched. Requests are conflated, so repeated lifecycle
     * callbacks cannot storm reconnects.
     */
    suspend fun reconnectStale()

    /** High-level send: resolves buffer -> network/target, handles pending insert + echo. */
    suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String? = null)

    /** Retry seam that reports whether a replacement attempt was actually accepted. */
    suspend fun sendMessageForRetry(
        bufferId: Long,
        text: String,
        replyToMsgid: String? = null,
    ): RetrySendResult {
        sendMessage(bufferId, text, replyToMsgid)
        return RetrySendResult.ACCEPTED
    }
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun sendReact(bufferId: Long, msgid: String, emoji: String)
    suspend fun joinChannel(networkId: Long, channel: String)

    /** Atomically claim a persisted invitation, connect if needed, then send exactly one JOIN. */
    suspend fun acceptInvite(messageId: Long) = Unit

    /** Resolve a persisted invitation without joining. */
    suspend fun dismissInvite(messageId: Long) = Unit

    /** Explicit lazy roster refresh; duplicate callers share the same in-flight request. */
    suspend fun requestMembers(bufferId: Long, force: Boolean = false) = Unit

    /** Part the buffer's channel; [reason] (from `/part <reason>`) becomes the PART trailing param. */
    suspend fun partChannel(bufferId: Long, reason: String? = null)

    /**
     * PART seam used by durable channel-close requests. Returns true only when the connection
     * boundary confirms that the write reached its live transport. The default keeps existing
     * test/fake implementations source-compatible; the real manager overrides it with a strict
     * Ready/transport check.
     */
    suspend fun partChannelForClose(bufferId: Long, reason: String? = null): Boolean = try {
        partChannel(bufferId, reason)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    /** Find-or-create a QUERY buffer for a DM (name Isupport-normalized); returns bufferId. */
    suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long

    /** Find-or-create the per-network SERVER buffer (name "*", displayName = network name);
     *  returns bufferId. UI entry for the server-messages timeline (plans/16). */
    suspend fun ensureServerBuffer(networkId: Long): Long

    /** THE mark-read entry point: advances Room (max-only) and sends MARKREAD when supported. */
    suspend fun markRead(bufferId: Long, upToTime: Long)

    /** Re-evaluate push-mode socket teardown after per-network endpoint changes.
     *  No-op unless deliveryMode == UNIFIED_PUSH. Called by MotdPushReceiver.onNewEndpoint. */
    suspend fun evaluatePushMode()

    // -- TOFU cert trust (plans/12) --

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
    suspend fun process(networkId: Long, event: io.github.trevarj.motd.irc.event.IrcEvent)

    /** Persist a push-delivered event without treating it as live IRC session state. */
    suspend fun processPush(networkId: Long, event: io.github.trevarj.motd.irc.event.IrcEvent)

    /** Persist one completed protocol page together with its exact primary-message boundaries. */
    suspend fun persistHistoryPage(
        networkId: Long,
        request: io.github.trevarj.motd.irc.client.ChatHistoryRequest,
        response: io.github.trevarj.motd.irc.client.ChatHistoryResponse.Messages,
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
