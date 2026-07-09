package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.flow.StateFlow

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }

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
     * invoked from ProcessLifecycleOwner's onStart. No-op unless the subsystem is started; leaves
     * healthy/connecting/retrying/cert-parked actors untouched, so it never storms reconnects.
     */
    suspend fun reconnectStale()

    /** High-level send: resolves buffer -> network/target, handles pending insert + echo. */
    suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String? = null)
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun sendReact(bufferId: Long, msgid: String, emoji: String)
    suspend fun joinChannel(networkId: Long, channel: String)

    /** Part the buffer's channel; [reason] (from `/part <reason>`) becomes the PART trailing param. */
    suspend fun partChannel(bufferId: Long, reason: String? = null)

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
