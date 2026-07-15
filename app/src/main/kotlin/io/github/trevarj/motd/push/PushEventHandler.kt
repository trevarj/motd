package io.github.trevarj.motd.push

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
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
 * feeds it through the [IrcEventSink] contract (EventProcessor implements it in WP5), so a
 * pushed message reaches Room by the same write path as a live message.
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
) {
    /**
     * Hilt entry point. The crypto facade defaults to the real JCA implementation and the
     * [IrcEventSink] owns both persistence and the normal notification decision, exactly as it does
     * for live socket events. Keeping one owner prevents a pushed DM/highlight being notified twice.
     */
    @Inject
    constructor(eventSink: IrcEventSink) : this(
        WebPushCryptoFacade.Default,
        eventSink,
        NoopPushHealthStore,
    )

    /**
     * Decrypt a push body for [networkId], parse the single IRC line, map it, feed it to the
     * sink, and post a notification. Returns the produced event, or null when the payload does
     * not map to a chat event (or fails to decrypt/parse — swallowed so a bad push is inert).
     */
    suspend fun handle(networkId: Long, body: ByteArray, keys: WebPushCrypto.KeyMaterial): IrcEvent? {
        val line = runCatching { String(crypto.decrypt(body, keys), Charsets.UTF_8) }
            .getOrElse {
                healthStore.warning(networkId, "PAYLOAD_DECRYPT_FAILED")
                return null
            }
        val msg = runCatching { IrcMessage.parse(line) }
            .getOrElse {
                if (it is IrcParseException) {
                    healthStore.warning(networkId, "PAYLOAD_INVALID")
                    return null
                } else {
                    throw it
                }
            }
        if (isRegistrationProbe(msg)) {
            healthStore.probeDelivered(networkId)
            return null
        }
        val event = mapToEvent(msg) ?: return null
        eventSink.processPush(networkId, event)
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

        private fun context(msg: IrcMessage): MessageContext = MessageContext(
            msgid = msg.tags["msgid"],
            serverTime = parseServerTime(msg.tags["time"]),
            account = msg.tags["account"],
            batchId = null,
            label = null,
        )

        private fun parseServerTime(time: String?): Long {
            if (time == null) return System.currentTimeMillis()
            return try {
                Instant.parse(time).toEpochMilli()
            } catch (_: DateTimeParseException) {
                System.currentTimeMillis()
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
