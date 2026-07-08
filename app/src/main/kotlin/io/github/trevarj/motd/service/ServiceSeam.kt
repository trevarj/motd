package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import kotlinx.coroutines.flow.StateFlow

enum class DeliveryMode { PERSISTENT_SOCKET, UNIFIED_PUSH }

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

    /** High-level send: resolves buffer -> network/target, handles pending insert + echo. */
    suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String? = null)
    suspend fun sendTyping(bufferId: Long, state: String)
    suspend fun sendReact(bufferId: Long, msgid: String, emoji: String)
    suspend fun joinChannel(networkId: Long, channel: String)
    suspend fun partChannel(bufferId: Long)

    /** Find-or-create a QUERY buffer for a DM (name Isupport-normalized); returns bufferId. */
    suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long

    /** THE mark-read entry point: advances Room (max-only) and sends MARKREAD when supported. */
    suspend fun markRead(bufferId: Long, upToTime: Long)

    /** Re-evaluate push-mode socket teardown after per-network endpoint changes.
     *  No-op unless deliveryMode == UNIFIED_PUSH. Called by MotdPushReceiver.onNewEndpoint. */
    suspend fun evaluatePushMode()
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
