package io.github.trevarj.motd.push

import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcParseException
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.reactionValue
import io.github.trevarj.motd.irc.proto.replyReference
import io.github.trevarj.motd.irc.proto.unreactionValue
import io.github.trevarj.motd.service.IrcEventSink
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject

/**
 * Turns a decrypted Web Push payload (exactly one IRC line, no CRLF) into an [IrcEvent] and
 * feeds it through the [IrcEventSink] contract (EventProcessor implements it in WP5). The sink
 * persists durable msgid-bearing input, while a msgid-less chat push is notification-only and
 * waits for Soju CHATHISTORY to populate Room after reconnect.
 *
 * The `:irc` [io.github.trevarj.motd.irc.client] event mapper is module-`internal`, so this
 * reimplements the small subset that soju webpush actually delivers: PRIVMSG/NOTICE/TAGMSG/INVITE,
 * including CTCP ACTION and reaction mutations. Notification posting is delegated to [notifier] so
 * the mapping stays pure and unit-testable without an Android context.
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
        private const val CTCP = '\u0001'

        /**
         * Map a webpush IRC line to an [IrcEvent]. Pure — no side effects. Currently covers the
         * chat commands soju pushes (PRIVMSG/NOTICE + CTCP ACTION); anything else returns null.
         */
        fun mapToEvent(msg: IrcMessage): IrcEvent? = when (msg.command.uppercase()) {
            "PRIVMSG", "NOTICE" -> mapChat(msg)
            "TAGMSG" -> mapTagMessage(msg)
            "INVITE" -> mapInvite(msg)
            else -> null
        }

        private fun mapInvite(msg: IrcMessage): IrcEvent? {
            val target = msg.params.getOrNull(0) ?: return null
            val channel = msg.params.getOrNull(1) ?: return null
            return IrcEvent.Invited(
                ctx = context(msg),
                by = msg.source?.nick.orEmpty(),
                nick = target,
                channel = channel,
            )
        }

        internal fun isRegistrationProbe(msg: IrcMessage): Boolean =
            msg.command.equals("NOTE", ignoreCase = true) &&
                msg.params.getOrNull(0).equals("WEBPUSH", ignoreCase = true) &&
                msg.params.getOrNull(1).equals("REGISTERED", ignoreCase = true)

        private fun mapChat(msg: IrcMessage): IrcEvent? {
            val source = msg.source ?: return null
            val target = msg.params.getOrNull(0) ?: return null
            var text = msg.params.getOrNull(1) ?: return null

            var kind = if (msg.command.equals("NOTICE", ignoreCase = true)) {
                IrcEvent.ChatKind.NOTICE
            } else {
                IrcEvent.ChatKind.PRIVMSG
            }
            // CTCP ACTION: \x01ACTION <text>\x01
            if (text.length >= 2 && text.first() == CTCP && text.last() == CTCP) {
                val inner = text.substring(1, text.length - 1)
                if (inner.startsWith("ACTION ")) {
                    kind = IrcEvent.ChatKind.ACTION
                    text = inner.removePrefix("ACTION ")
                } else {
                    // Other CTCP (VERSION, PING, ...) is not a chat message.
                    return null
                }
            }

            return IrcEvent.ChatMessage(
                ctx = context(msg),
                kind = kind,
                source = source,
                target = target,
                text = text,
                isSelf = false, // push is delivered while we are offline; never our own echo
                replyToMsgid = msg.replyReference(),
            )
        }

        private fun mapTagMessage(msg: IrcMessage): IrcEvent? {
            if (msg.unreactionValue() != null) return IrcEvent.Raw(msg)
            val source = msg.source ?: return null
            val target = msg.params.firstOrNull() ?: return null
            val react = msg.reactionValue()
            return IrcEvent.TagMessage(
                ctx = context(msg),
                source = source,
                target = target,
                typing = msg.tags["+typing"],
                reactEmoji = react,
                reactTargetMsgid = react?.let { msg.replyReference() },
            )
        }

        private data class ParsedServerTime(val value: Long, val source: ServerTimeSource)

        private fun context(msg: IrcMessage): MessageContext {
            val parsedTime = parseServerTime(msg.tags["time"])
            return MessageContext(
                msgid = msg.tags["msgid"],
                serverTime = parsedTime.value,
                account = msg.tags["account"],
                batchId = null,
                label = null,
                serverTimeSource = parsedTime.source,
            )
        }

        private fun parseServerTime(time: String?): ParsedServerTime {
            if (time == null) {
                return ParsedServerTime(System.currentTimeMillis(), ServerTimeSource.LOCAL)
            }
            return try {
                ParsedServerTime(Instant.parse(time).toEpochMilli(), ServerTimeSource.TAG)
            } catch (_: DateTimeParseException) {
                ParsedServerTime(System.currentTimeMillis(), ServerTimeSource.LOCAL)
            }
        }
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
