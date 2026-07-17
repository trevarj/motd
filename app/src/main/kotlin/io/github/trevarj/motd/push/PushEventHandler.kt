package io.github.trevarj.motd.push

import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.service.IrcEventSink
import javax.inject.Inject

/**
 * Turns a decrypted Web Push payload (exactly one IRC line, no CRLF) into an [IrcEvent] and
 * feeds it through the [IrcEventSink] contract (EventProcessor implements it in WP5). The sink
 * persists every visible push observation immediately, including msgid-less provisional input.
 *
 * Socket, history, and push input share the `:irc` [EventMapper], preventing CTCP, tag, reply, and
 * semantic-event parsing from drifting between delivery paths.
 */
class PushEventHandler(
    private val crypto: WebPushCryptoFacade,
    private val eventSink: IrcEventSink,
    private val healthStore: PushHealthStore = NoopPushHealthStore,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) {
    /**
     * Hilt entry point. The crypto facade defaults to the real JCA implementation and the
     * [IrcEventSink] owns the durability decision and normal notification policy. Keeping one owner
     * prevents a pushed DM/highlight being notified twice.
     */
    @Inject
    constructor(eventSink: IrcEventSink, diagnostics: DiagnosticLogger) : this(
        WebPushCryptoFacade.Default,
        eventSink,
        NoopPushHealthStore,
        diagnostics,
    )

    /**
     * Decrypt a push body for [networkId], parse the single IRC line, map it, feed it to the
     * sink, and post a notification. Returns the produced event, or null when the payload does
     * not map to a chat event (or fails to decrypt/parse — swallowed so a bad push is inert).
     */
    suspend fun handle(
        networkId: Long,
        body: ByteArray,
        keys: WebPushCrypto.KeyMaterial,
        alreadyDecrypted: Boolean = false,
    ): IrcEvent? {
        diagnostics.record("push", "payload_received") {
            mapOf("network_id" to networkId, "bytes" to body.size)
        }
        val plaintext = if (alreadyDecrypted) {
            body
        } else {
            runCatching { crypto.decrypt(body, keys) }
                .getOrElse {
                    diagnostics.record("push", "decrypt_failed") { mapOf("network_id" to networkId) }
                    healthStore.warning(networkId, "PAYLOAD_DECRYPT_FAILED")
                    return null
                }
        }
        val line = String(plaintext, Charsets.UTF_8)
        val msg = runCatching { IrcMessage.parse(line) }
            .getOrElse {
                if (it is IrcParseException) {
                    diagnostics.record("push", "parse_failed") { mapOf("network_id" to networkId) }
                    healthStore.warning(networkId, "PAYLOAD_INVALID")
                    return null
                } else {
                    throw it
                }
            }
        if (isRegistrationProbe(msg)) {
            diagnostics.record("push", "registration_probe") { mapOf("network_id" to networkId) }
            healthStore.probeDelivered(networkId)
            return null
        }
        val event = mapToEvent(msg)
        if (event == null) {
            diagnostics.record("push", "payload_ignored") {
                mapOf("network_id" to networkId, "command" to msg.command.uppercase())
            }
            return null
        }
        eventSink.processPush(networkId, event)
        diagnostics.record("push", "event_delivered") {
            mapOf("network_id" to networkId, "type" to event::class.simpleName)
        }
        healthStore.messageDelivered(networkId)
        return event
    }

    companion object {
        /**
         * Map a webpush IRC line to an [IrcEvent]. Pure — no side effects. Currently covers the
         * chat commands soju pushes (PRIVMSG/NOTICE + CTCP ACTION); anything else returns null.
         */
        fun mapToEvent(msg: IrcMessage): IrcEvent? {
            val command = msg.command.uppercase()
            if (command !in PUSH_COMMANDS) return null
            return EventMapper(
                selfNick = { "" },
                isupport = { Isupport() },
            ).map(msg.copy(command = command))
        }

        internal fun isRegistrationProbe(msg: IrcMessage): Boolean =
            msg.command.equals("NOTE", ignoreCase = true) &&
                msg.params.getOrNull(0).equals("WEBPUSH", ignoreCase = true) &&
                msg.params.getOrNull(1).equals("REGISTERED", ignoreCase = true)

        private val PUSH_COMMANDS = setOf("PRIVMSG", "NOTICE", "TAGMSG", "INVITE")
    }
}

/**
 * Thin seam over [WebPushCrypto.decrypt] so [PushEventHandler] can be unit-tested with a fake
 * (the RFC vector itself is exercised directly against [WebPushCrypto] in WebPushCryptoTest).
 */
fun interface WebPushCryptoFacade {
    fun decrypt(body: ByteArray, keys: WebPushCrypto.KeyMaterial): ByteArray

    companion object {
        val Default = WebPushCryptoFacade { body, keys -> WebPushCrypto.decrypt(body, keys) }
    }
}
