package io.github.trevarj.motd.push

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.IrcParseException
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
 * reimplements the small subset that soju webpush actually delivers: PRIVMSG/NOTICE, including
 * CTCP ACTION. Notification posting is delegated to [notifier] (WP5's MessagingStyle rules) so
 * the mapping stays pure and unit-testable without an Android context.
 */
class PushEventHandler(
    private val crypto: WebPushCryptoFacade,
    private val eventSink: IrcEventSink,
    private val notifier: PushNotifier,
) {
    /**
     * Hilt entry point. The crypto facade defaults to the real JCA implementation and the
     * notifier to a no-op; WP10 can rebind a [PushNotifier] to post MessagingStyle
     * notifications. Only [IrcEventSink] (a WP1 contract) needs to come from the graph.
     */
    @Inject
    constructor(eventSink: IrcEventSink) : this(WebPushCryptoFacade.Default, eventSink, NoopPushNotifier)

    /**
     * Decrypt a push body for [networkId], parse the single IRC line, map it, feed it to the
     * sink, and post a notification. Returns the produced event, or null when the payload does
     * not map to a chat event (or fails to decrypt/parse — swallowed so a bad push is inert).
     */
    suspend fun handle(networkId: Long, body: ByteArray, keys: WebPushCrypto.KeyMaterial): IrcEvent? {
        val line = runCatching { String(crypto.decrypt(body, keys), Charsets.UTF_8) }
            .getOrNull() ?: return null
        val msg = runCatching { IrcMessage.parse(line) }
            .getOrElse { if (it is IrcParseException) return null else throw it }
        val event = mapToEvent(msg) ?: return null
        eventSink.process(networkId, event)
        if (event is IrcEvent.ChatMessage) notifier.notify(networkId, event)
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
            else -> null
        }

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
                replyToMsgid = msg.tags["+draft/reply"],
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

/**
 * Posts a MessagingStyle notification for a pushed chat message (plans/05 rules). The concrete
 * NotificationManagerCompat implementation is wired in WP5/WP10; WP9 depends only on this seam
 * so the handler stays testable without an Android context.
 */
fun interface PushNotifier {
    fun notify(networkId: Long, message: IrcEvent.ChatMessage)
}

/** No-op notifier for contexts where notifications are not wanted (e.g. tests / not yet wired). */
object NoopPushNotifier : PushNotifier {
    override fun notify(networkId: Long, message: IrcEvent.ChatMessage) = Unit
}
