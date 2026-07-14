package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.push.PushNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real [PushNotifier] (the seam WP9 left as a no-op): posts a MessagingStyle notification for a
 * pushed chat message via [MotdNotifications]. Resolves the buffer for the message so the
 * (DM || hasMention) / muted / foregrounded suppression rules can be applied consistently.
 * WP10 binds this over the WP9 default.
 */
@Singleton
class PushNotifierImpl @Inject constructor(
    private val db: MotdDatabase,
    private val notifications: MotdNotifications,
) : PushNotifier {
    override suspend fun notify(networkId: Long, message: IrcEvent.ChatMessage) {
        // The push path has already inserted the row via EventProcessor; here we mirror the
        // notification decision. Resolve the destination buffer (DM keyed by sender).
        val isChannel = message.target.firstOrNull() in setOf('#', '&')
        val name = if (isChannel) message.target else message.source.nick
        val norm = name.lowercase()
        // Plain suspend Room read: dispatches off the main thread (no runBlocking).
        val buffer = runCatching { db.bufferDao().byName(networkId, norm) }.getOrNull() ?: return
        val type = if (isChannel) BufferType.CHANNEL else BufferType.QUERY
        // soju sends channel traffic through WebPush only for highlights. Direct messages belong
        // to the normal Messages channel; channel highlights belong to Mentions.
        notifications.onIncoming(networkId, buffer.id, type, hasMention = isChannel, message = message)
    }
}
