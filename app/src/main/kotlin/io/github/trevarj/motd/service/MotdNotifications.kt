package io.github.trevarj.motd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.irc.event.IrcEvent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MessagingStyle notifications (plans/05). Owns the notification channels and applies the final
 * suppression rules that need Android state (muted buffer, foregrounded buffer) before posting.
 * Implements [MessageNotifier] (the EventProcessor hook) and hosts the status notification for
 * the foreground service.
 */
@Singleton
class MotdNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MotdDatabase,
    private val foregroundBufferTracker: ForegroundBufferTracker,
) : MessageNotifier {

    private val manager = NotificationManagerCompat.from(context)

    // Per-buffer message history for MessagingStyle threading (notificationId = bufferId).
    private val history = HashMap<Long, NotificationCompat.MessagingStyle>()

    init { ensureChannels() }

    private fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Connection status", NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    // -- status notification (foreground service) --

    fun statusNotification(connectedCount: Int, reconnecting: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context, 1,
            Intent(context, IrcForegroundService::class.java).setAction(IrcForegroundService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (reconnecting) "Reconnecting…" else "Connected to $connectedCount networks"
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MOTD")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    // -- MessageNotifier (message/mention notifications) --

    override fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) {
        // Suppression: buffer currently foregrounded → no notification.
        if (foregroundBufferTracker.foregroundBufferId.value == bufferId) return
        // Muted-buffer suppression (Room read is blocking-safe here — off the collector's hot path).
        val buffer = runCatching {
            kotlinx.coroutines.runBlocking { db.bufferDao().observeById(bufferId) }
        }.getOrNull()
        if (buffer?.muted == true) return

        val channel = if (hasMention) CHANNEL_MENTIONS else CHANNEL_MESSAGES
        val title = buffer?.displayName ?: message.target
        val person = Person.Builder().setName(message.source.nick).build()
        val style = history.getOrPut(bufferId) {
            NotificationCompat.MessagingStyle(Person.Builder().setName("me").build())
                .setConversationTitle(title)
                .setGroupConversation(type == BufferType.CHANNEL)
        }
        style.addMessage(message.text, message.ctx.serverTime, person)

        val replyIntent = PendingIntent.getBroadcast(
            context, bufferId.toInt(),
            Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.ACTION_REPLY)
                .putExtra(ReplyReceiver.EXTRA_BUFFER_ID, bufferId),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY).setLabel("Reply").build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", replyIntent,
        ).addRemoteInput(remoteInput).build()

        val markReadIntent = PendingIntent.getBroadcast(
            context, -bufferId.toInt(),
            Intent(context, ReplyReceiver::class.java)
                .setAction(ReplyReceiver.ACTION_MARK_READ)
                .putExtra(ReplyReceiver.EXTRA_BUFFER_ID, bufferId)
                .putExtra(ReplyReceiver.EXTRA_UP_TO_TIME, message.ctx.serverTime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_view, "Mark read", markReadIntent,
        ).build()

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setStyle(style)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        if (hasPostPermission()) {
            manager.notify(bufferId.toInt(), notification)
        }
    }

    private fun hasPostPermission(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MENTIONS = "mentions"
    }
}
