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
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
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
    alreadyRead: Boolean = false,
): Boolean = !alreadyRead && !foreground && !senderIsFool && (!muted || senderIsFriend)

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
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) : MessageNotifier {

    private val manager = NotificationManagerCompat.from(context)

    // Per-buffer message history for MessagingStyle threading (notificationId = bufferId).
    private val history = HashMap<Long, NotificationCompat.MessagingStyle>()
    private val historyKeys = HashMap<Long, MutableList<NotificationMessageKey>>()
    private val latestNotifiedTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

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
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_INVITATIONS, "Invitations", NotificationManager.IMPORTANCE_HIGH),
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
        val foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId
        val muted = buffer?.muted == true
        val senderIsFriend = sender in settings.friends
        val senderIsFool = sender in settings.fools
        val alreadyRead = buffer?.readMarkerTime?.let { message.ctx.serverTime <= it } == true
        val decision = shouldPostNotification(foreground, muted, senderIsFriend, senderIsFool, alreadyRead)
        diagnostics.record("notifications", "message_evaluated") {
            mapOf(
                "network_id" to networkId,
                "buffer_id" to bufferId,
                "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                "sender_fp" to diagnostics.fingerprint(message.source.nick),
                "body_fp" to diagnostics.fingerprint(message.text),
                "foreground" to foreground,
                "muted" to muted,
                "friend" to senderIsFriend,
                "fool" to senderIsFool,
                "already_read" to alreadyRead,
                "post" to decision,
                "mention" to hasMention,
            )
        }
        if (!decision) return

        val channel = if (hasMention) CHANNEL_MENTIONS else CHANNEL_MESSAGES
        val title = buffer?.displayName ?: message.target
        val person = Person.Builder().setName(message.source.nick).build()
        val style = synchronized(history) {
            val key = NotificationMessageKey.from(message)
            val keys = historyKeys.getOrPut(bufferId, ::mutableListOf)
            val existingIndex = keys.indexOfFirst { it.matches(key) }
            if (existingIndex >= 0) {
                // A transient msgid-less push can notify before CHATHISTORY inserts the canonical
                // Room row. Upgrade that fallback notification identity when reconnect supplies
                // the durable representation so the body is not added a second time.
                keys[existingIndex] = keys[existingIndex].withDurableIdentityFrom(key)
                diagnostics.record("notifications", "message_deduplicated") {
                    mapOf(
                        "buffer_id" to bufferId,
                        "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                        "body_fp" to diagnostics.fingerprint(message.text),
                        "existing_index" to existingIndex,
                    )
                }
                return
            }
            keys += key
            if (keys.size > MAX_NOTIFICATION_MESSAGES) keys.removeAt(0)
            history.getOrPut(bufferId) {
                NotificationCompat.MessagingStyle(Person.Builder().setName("me").build())
                    .setConversationTitle(title)
                    .setGroupConversation(type == BufferType.CHANNEL)
            }.also { it.addMessage(message.text, message.ctx.serverTime, person) }
        }
        latestNotifiedTimes.merge(bufferId, message.ctx.serverTime, ::maxOf)

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

        // Tapping the notification opens the buffer AND jumps to this message. MainActivity reads
        // these extras and routes to ChatRoute(bufferId, jumpToMsgid, jumpToTime), reusing the
        // existing deep-jump path (local resolve → CHATHISTORY AROUND fallback). A distinct request
        // code per buffer keeps concurrent buffers' content intents separate; FLAG_UPDATE_CURRENT
        // refreshes the target msgid/time on each new message. Works cold (launcher intent) or warm.
        val contentIntent = PendingIntent.getActivity(
            context, bufferId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BUFFER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .putExtra(EXTRA_JUMP_MSGID, message.ctx.msgid)
                .putExtra(EXTRA_JUMP_TIME, message.ctx.serverTime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
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
        diagnostics.record("notifications", "message_post_finished") {
            mapOf(
                "buffer_id" to bufferId,
                "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                "body_fp" to diagnostics.fingerprint(message.text),
                "permission" to canPost,
            )
        }
    }

    override suspend fun onRead(bufferId: Long, upToTime: Long) {
        val activeLatest = runCatching {
            val notification = manager.activeNotifications
                .firstOrNull { it.id == bufferId.toInt() }
                ?.notification
                ?: return@runCatching null
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                ?.messages
                ?.maxOfOrNull { it.timestamp }
        }.getOrNull()
        val latest = listOfNotNull(latestNotifiedTimes[bufferId], activeLatest).maxOrNull() ?: return
        if (!readMarkerCoversNotification(upToTime, latest)) return
        synchronized(history) {
            history.remove(bufferId)
            historyKeys.remove(bufferId)
        }
        latestNotifiedTimes.remove(bufferId)
        manager.cancel(bufferId.toInt())
        diagnostics.record("notifications", "message_notification_cleared") {
            mapOf("buffer_id" to bufferId, "up_to_time" to upToTime)
        }
    }

    override suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) {
        val message = db.messageDao().byId(messageId) ?: return
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            invitationNotificationId(messageId),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BUFFER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .putExtra(EXTRA_JUMP_MSGID, message.msgid)
                .putExtra(EXTRA_JUMP_TIME, message.serverTime),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun actionIntent(action: String, requestOffset: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                invitationNotificationId(messageId) + requestOffset,
                Intent(context, InviteReceiver::class.java)
                    .setAction(action)
                    .putExtra(InviteReceiver.EXTRA_MESSAGE_ID, messageId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val dismissIntent = actionIntent(InviteReceiver.ACTION_DISMISS, 1)
        val joinIntent = PendingIntent.getActivity(
            context,
            invitationNotificationId(messageId) + 2,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_ACCEPT_INVITE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .putExtra(EXTRA_JUMP_MSGID, message.msgid)
                .putExtra(EXTRA_JUMP_TIME, message.serverTime)
                .putExtra(EXTRA_INVITE_MESSAGE_ID, messageId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INVITATIONS)
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setContentTitle("Invitation to ${buffer.displayName}")
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_add, "Join", joinIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissIntent)
            .build()
        val canPost = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) manager.notify(invitationNotificationId(messageId), notification)
    }

    override suspend fun onInvitationResolved(messageId: Long) {
        manager.cancel(invitationNotificationId(messageId))
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MENTIONS = "mentions"
        const val CHANNEL_INVITATIONS = "invitations"
        private const val MAX_NOTIFICATION_MESSAGES = 25

        // Deep-link extras carried by a message notification's content intent (tap → open + jump).
        const val ACTION_OPEN_BUFFER = "io.github.trevarj.motd.OPEN_BUFFER"
        const val ACTION_ACCEPT_INVITE = "io.github.trevarj.motd.ACCEPT_INVITE"
        const val EXTRA_BUFFER_ID = "notif_buffer_id"
        const val EXTRA_JUMP_MSGID = "notif_jump_msgid"
        const val EXTRA_JUMP_TIME = "notif_jump_time"
        const val EXTRA_INVITE_MESSAGE_ID = "notif_invite_message_id"

        internal fun invitationNotificationId(messageId: Long): Int =
            0x40000000 or (messageId xor (messageId ushr 32)).toInt().and(0x3fffffff)
    }
}

/** Stable identity for one notification entry, independent of live/push delivery provenance. */
private data class NotificationMessageKey(
    val msgid: String?,
    val serverTime: Long,
    val sender: String,
    val text: String,
) {
    /**
     * Prefer durable identity when both deliveries carry it. When either side is the original
     * msgid-less transport line, use the stable fingerprint so a later identity promotion aliases
     * the already-notified body. Two different non-null msgids remain distinct even if a sender
     * sends identical text in the same millisecond.
     */
    fun matches(other: NotificationMessageKey): Boolean =
        if (msgid != null && other.msgid != null) {
            msgid == other.msgid
        } else {
            serverTime == other.serverTime && sender == other.sender && text == other.text
        }

    fun withDurableIdentityFrom(other: NotificationMessageKey): NotificationMessageKey =
        if (msgid == null && other.msgid != null) copy(msgid = other.msgid) else this

    companion object {
        fun from(message: IrcEvent.ChatMessage): NotificationMessageKey =
            NotificationMessageKey(
                msgid = message.ctx.msgid,
                serverTime = message.ctx.serverTime,
                sender = message.source.nick,
                text = message.text,
            )
    }
}

internal fun readMarkerCoversNotification(markerTime: Long, latestNotifiedTime: Long): Boolean =
    markerTime >= latestNotifiedTime
