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
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Final notification suppression decision (plans/13 §2.6). Pure and unit-tested.
 *
 * Precedence (highest first): foreground buffer suppresses everything; a fool sender is fully
 * silenced (even in an un-muted DM/mention); a friend sender bypasses the muted-buffer
 * suppression; otherwise a muted buffer suppresses. The `(DM || mention)` gate lives upstream in
 * [io.github.trevarj.motd.data.sync.EventProcessor.maybeNotify], so by the time this runs the
 * message already qualifies as a DM or a mention.
 */
fun shouldPostNotification(
    foreground: Boolean,
    muted: Boolean,
    senderIsFriend: Boolean,
    senderIsFool: Boolean,
): Boolean = !foreground && !senderIsFool && (!muted || senderIsFriend)

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
    private val settingsRepository: SettingsRepository,
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
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setContentTitle("motd")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    // -- MessageNotifier (message/mention notifications) --

    override suspend fun onIncoming(networkId: Long, bufferId: Long, type: BufferType, hasMention: Boolean, message: IrcEvent.ChatMessage) {
        // Plain suspend reads: Room and DataStore dispatch off the main thread on their own. The
        // events collector runs on Dispatchers.Main, so the previous runBlocking { suspend query }
        // blocked the main thread and crashed once a (freshly-added) fool's message arrived.
        val buffer = runCatching { db.bufferDao().observeById(bufferId) }.getOrNull()
        // Friends/fools sets (single bounded DataStore read; null settings ⇒ empty sets).
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: Settings()
        val sender = normalizeNick(message.source.nick)

        // Round 4 (plans/13 §2.3/§2.4/§2.6): fools are fully silenced; friends bypass the
        // muted-buffer suppression. Foreground suppression still applies to everyone.
        val decision = shouldPostNotification(
            foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId,
            muted = buffer?.muted == true,
            senderIsFriend = sender in settings.friends,
            senderIsFool = sender in settings.fools,
        )
        if (!decision) return

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
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setStyle(style)
            .setAutoCancel(true)
            .addAction(replyAction)
            .addAction(markReadAction)
            .build()

        // POST_NOTIFICATIONS is only a runtime permission on API 33+; below that it is granted
        // at install. Inlined here (not extracted) so lint's flow analysis recognizes the guard.
        val canPost = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) {
            manager.notify(bufferId.toInt(), notification)
        }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MENTIONS = "mentions"
    }
}
