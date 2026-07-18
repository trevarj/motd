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
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.avatar.notificationAvatarIcon
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.effectiveReadFloorTime
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.NotificationClaimSession
import io.github.trevarj.motd.data.sync.ROOM_MERGE_PRESENTATION_PREFIX
import io.github.trevarj.motd.data.sync.parseRoomMergePresentationKey
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Final notification suppression decision (plans/13 §2.6). Pure and unit-tested.
 *
 * Precedence (highest first): foreground buffer suppresses everything; an explicit buffer mute
 * always wins over friend status; a fool sender is fully silenced. The `(DM || mention)` gate lives upstream in
 * [io.github.trevarj.motd.data.sync.EventProcessor.maybeNotify], so by the time this runs the
 * message already qualifies as a DM or a mention.
 */
fun shouldPostNotification(
    foreground: Boolean,
    muted: Boolean,
    senderIsFriend: Boolean,
    senderIsFool: Boolean,
    alreadyRead: Boolean = false,
): Boolean = !alreadyRead && !foreground && !muted && !senderIsFool

/**
 * MessagingStyle notifications (plans/05). Owns the notification channels and applies the final
 * suppression rules that need Android state (muted buffer, foregrounded buffer) before posting.
 * Implements [MessageNotifier] (the EventProcessor hook) and hosts the status notification for
 * the foreground service.
 */
@Singleton
class MotdNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val db: MotdDatabase,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val settingsRepository: SettingsRepository,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    @param:ApplicationScope private val applicationScope: CoroutineScope? = null,
) : MessageNotifier {

    private val manager = NotificationManagerCompat.from(context)

    // Per-buffer message history for MessagingStyle threading (notificationId = bufferId).
    private val history = HashMap<Long, NotificationCompat.MessagingStyle>()
    private val historyKeys = HashMap<Long, MutableList<NotificationMessageKey>>()
    private val latestNotifiedTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    init {
        ensureChannels()
        applicationScope?.launch {
            // Only the v9→v10 migration writes this marker. Fresh/empty databases must not infer
            // that another process instance's notifications are obsolete.
            runCatching {
                db.withTransaction {
                    if (db.appStateDao().contains(V10_NOTIFICATION_RESET) > 0) {
                        manager.activeNotifications
                            .filter { it.notification.channelId in RESETTABLE_CHANNELS }
                            .forEach { manager.cancel(it.id) }
                        db.appStateDao().delete(V10_NOTIFICATION_RESET)
                    }
                }
            }
            retireCommittedRoomMerges()
            recoverCanonicalNotifications()
        }
    }

    private suspend fun retireCommittedRoomMerges() {
        val state = db.appStateDao()
        state.keysLike("$ROOM_MERGE_PRESENTATION_PREFIX%").forEach { key ->
            val (winnerId, loserId) = parseRoomMergePresentationKey(key) ?: run {
                state.delete(key)
                return@forEach
            }
            onRoomsMerged(winnerId, loserId)
            state.delete(key)
        }
    }

    /** Rebuild interrupted chat/invitation presentation directly from canonical database state. */
    internal suspend fun recoverCanonicalNotifications() {
        val dao = db.canonicalTimelineDao()
        dao.releaseInterruptedNotificationClaims(NotificationClaimSession.owner)
        for (event in dao.pendingNotifications(MAX_RECOVERY_NOTIFICATIONS)) {
            if (dao.claimNotification(event.id, NotificationClaimSession.owner) != 1) continue
            try {
                val buffer = db.bufferDao().observeById(event.bufferId)
                if (buffer == null) {
                    dao.completeNotification(event.id)
                    continue
                }
                when (event.kind) {
                    MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION -> {
                        postIncoming(
                            networkId = buffer.networkId,
                            bufferId = buffer.id,
                            type = buffer.type,
                            hasMention = event.hasMention,
                            eventId = event.id,
                            message = IrcEvent.ChatMessage(
                                ctx = MessageContext(
                                    msgid = event.msgid,
                                    serverTime = event.serverTime,
                                    account = event.senderAccount,
                                    batchId = null,
                                    label = null,
                                    serverTimeSource = if (event.serverTimeAuthoritative) {
                                        ServerTimeSource.TAG
                                    } else {
                                        ServerTimeSource.LOCAL
                                    },
                                ),
                                kind = when (event.kind) {
                                    MessageKind.PRIVMSG -> IrcEvent.ChatKind.PRIVMSG
                                    MessageKind.NOTICE -> IrcEvent.ChatKind.NOTICE
                                    else -> IrcEvent.ChatKind.ACTION
                                },
                                source = Prefix(event.sender),
                                target = buffer.ircTarget,
                                text = event.text,
                                isSelf = false,
                                replyToMsgid = event.replyToMsgid,
                            ),
                        )
                    }
                    MessageKind.INVITE -> onInvitation(buffer.networkId, buffer.id, event.id)
                    else -> Unit
                }
                dao.completeNotification(event.id)
            } catch (cancelled: CancellationException) {
                dao.releaseNotification(event.id)
                throw cancelled
            } catch (error: Exception) {
                dao.releaseNotification(event.id)
                diagnostics.record("notifications", "recovery_failed") {
                    mapOf("event_id" to event.id, "error" to error::class.simpleName)
                }
            }
        }
    }

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

    fun statusNotification(
        connectedCount: Int,
        reconnecting: Boolean,
        starting: Boolean = false,
    ): Notification {
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
        val text = statusNotificationText(connectedCount, reconnecting, starting)
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

    override suspend fun onIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        message: IrcEvent.ChatMessage,
    ) = postIncoming(networkId, bufferId, type, hasMention, eventId = null, message)

    override suspend fun onCanonicalIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        eventId: Long,
        message: IrcEvent.ChatMessage,
    ) = postIncoming(networkId, bufferId, type, hasMention, eventId, message)

    private suspend fun postIncoming(
        networkId: Long,
        bufferId: Long,
        type: BufferType,
        hasMention: Boolean,
        eventId: Long?,
        message: IrcEvent.ChatMessage,
    ) {
        // Plain suspend reads: Room and DataStore dispatch off the main thread on their own. The
        // events collector runs on Dispatchers.Main, so the previous runBlocking { suspend query }
        // blocked the main thread and crashed once a (freshly-added) fool's message arrived.
        val buffer = runCatching { db.bufferDao().observeById(bufferId) }.getOrNull()
        // Friends/fools sets (single bounded DataStore read; null settings ⇒ empty sets).
        val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: Settings()
        val sender = normalizeNick(message.source.nick)

        // Fools and explicit buffer mute are fully silent. Foreground suppression also applies.
        val foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId
        val muted = buffer?.muted == true
        val senderIsFriend = sender in settings.friends
        val senderIsFool = sender in settings.fools
        val alreadyRead = buffer?.effectiveReadFloorTime?.let { message.ctx.serverTime <= it } == true
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
        val person = notificationPerson(networkId, message.source.nick, settings.avatarStyle)
        val restored = if (synchronized(history) { bufferId !in history }) {
            runCatching {
                db.messageDao().recentNotifiable(
                    bufferId = bufferId,
                    after = buffer?.effectiveReadFloorTime ?: Long.MIN_VALUE,
                    queryRoom = type == BufferType.QUERY,
                    excludeEventId = eventId ?: -1L,
                    limit = MAX_NOTIFICATION_MESSAGES - 1,
                )
            }.getOrDefault(emptyList()).asReversed()
        } else {
            emptyList()
        }
        val style = synchronized(history) {
            val keys = historyKeys.getOrPut(bufferId, ::mutableListOf)
            val conversation = history.getOrPut(bufferId) {
                NotificationCompat.MessagingStyle(Person.Builder().setName("me").build())
                    .setConversationTitle(title)
                    .setGroupConversation(type == BufferType.CHANNEL)
            }
            restored.forEach { row ->
                val restoredKey = NotificationMessageKey.from(row)
                if (keys.none { it.matches(restoredKey) }) {
                    keys += restoredKey
                    conversation.addMessage(
                        row.text,
                        row.serverTime,
                        notificationPerson(networkId, row.sender, settings.avatarStyle),
                    )
                }
            }
            val key = NotificationMessageKey.from(eventId, message)
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
            conversation.also { it.addMessage(message.text, message.ctx.serverTime, person) }
        }
        restored.maxOfOrNull { it.serverTime }
            ?.let { latestNotifiedTimes.merge(bufferId, it, ::maxOf) }
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
                .putExtra(EXTRA_JUMP_TIME, message.ctx.serverTime)
                .putExtra(EXTRA_EVENT_ID, eventId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Canonical ChatSoundPlayer owns persisted, mark-before-playback audio. Keeping the
            // OS notification presentation silent prevents a crash-recovery repost from sounding
            // a second time while retaining the high-importance visual notification channel.
            .setSilent(true)
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

    private fun notificationPerson(networkId: Long, name: String, style: AvatarStyle): Person =
        Person.Builder()
            .setName(name)
            .setKey("irc:$networkId:${normalizeNick(name)}")
            .setIcon(notificationAvatarIcon(context, name, style))
            .build()

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

    override suspend fun onRoomsMerged(winnerId: Long, loserId: Long) {
        synchronized(history) {
            history.remove(loserId)
            historyKeys.remove(loserId)
        }
        latestNotifiedTimes.remove(loserId)
        manager.cancel(loserId.toInt())
        diagnostics.record("notifications", "room_notification_retired") {
            mapOf("winner_id" to winnerId, "loser_id" to loserId)
        }
    }

    override suspend fun onInvitation(networkId: Long, bufferId: Long, messageId: Long) {
        val message = db.messageDao().byCanonicalId(messageId) ?: return
        val canonicalMessageId = message.id
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        val contentIntent = PendingIntent.getActivity(
            context,
            invitationNotificationId(canonicalMessageId),
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BUFFER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .putExtra(EXTRA_JUMP_MSGID, message.msgid)
                .putExtra(EXTRA_JUMP_TIME, message.serverTime)
                .putExtra(EXTRA_EVENT_ID, message.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun actionIntent(action: String, requestOffset: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                invitationNotificationId(canonicalMessageId) + requestOffset,
                Intent(context, InviteReceiver::class.java)
                    .setAction(action)
                    .putExtra(InviteReceiver.EXTRA_MESSAGE_ID, canonicalMessageId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val dismissIntent = actionIntent(InviteReceiver.ACTION_DISMISS, 1)
        val joinIntent = PendingIntent.getActivity(
            context,
            invitationNotificationId(canonicalMessageId) + 2,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_ACCEPT_INVITE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .putExtra(EXTRA_JUMP_MSGID, message.msgid)
                .putExtra(EXTRA_JUMP_TIME, message.serverTime)
                .putExtra(EXTRA_EVENT_ID, message.id)
                .putExtra(EXTRA_INVITE_MESSAGE_ID, canonicalMessageId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_INVITATIONS)
            .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
            .setContentTitle("Invitation to ${buffer.displayName}")
            .setContentText(message.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
            .setContentIntent(contentIntent)
            .setDeleteIntent(dismissIntent)
            // Invitation presentation is recoverable; it must not introduce an untracked system
            // sound that could replay if the process dies after notify() but before completion.
            .setSilent(true)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_add, "Join", joinIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissIntent)
            .build()
        val canPost = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canPost) manager.notify(invitationNotificationId(canonicalMessageId), notification)
    }

    override suspend fun onInvitationResolved(messageId: Long) {
        val canonicalId = db.canonicalTimelineDao().canonicalEventId(messageId)
        manager.cancel(invitationNotificationId(canonicalId))
        db.canonicalTimelineDao().losingEventIds(canonicalId).forEach { losingId ->
            manager.cancel(invitationNotificationId(losingId))
        }
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_MENTIONS = "mentions"
        const val CHANNEL_INVITATIONS = "invitations"
        private const val MAX_NOTIFICATION_MESSAGES = 25
        private val RESETTABLE_CHANNELS = setOf(
            CHANNEL_MESSAGES,
            CHANNEL_MENTIONS,
            CHANNEL_INVITATIONS,
        )
        private const val V10_NOTIFICATION_RESET = "v10_notification_reset"
        private const val MAX_RECOVERY_NOTIFICATIONS = 200

        // Deep-link extras carried by a message notification's content intent (tap → open + jump).
        const val ACTION_OPEN_BUFFER = "io.github.trevarj.motd.OPEN_BUFFER"
        const val ACTION_ACCEPT_INVITE = "io.github.trevarj.motd.ACCEPT_INVITE"
        const val EXTRA_BUFFER_ID = "notif_buffer_id"
        const val EXTRA_JUMP_MSGID = "notif_jump_msgid"
        const val EXTRA_JUMP_TIME = "notif_jump_time"
        const val EXTRA_EVENT_ID = "notif_event_id"
        const val EXTRA_INVITE_MESSAGE_ID = "notif_invite_message_id"

        internal fun invitationNotificationId(messageId: Long): Int =
            0x40000000 or (messageId xor (messageId ushr 32)).toInt().and(0x3fffffff)
    }
}

internal fun statusNotificationText(
    connectedCount: Int,
    reconnecting: Boolean,
    starting: Boolean,
): String = when {
    starting -> "Keeping chats connected"
    reconnecting -> "Reconnecting…"
    else -> "Connected to $connectedCount networks"
}

/** Stable identity for one notification entry, independent of live/push delivery provenance. */
private data class NotificationMessageKey(
    val eventId: Long?,
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
        if (eventId != null && other.eventId != null) {
            eventId == other.eventId
        } else if (msgid != null && other.msgid != null) {
            msgid == other.msgid
        } else {
            serverTime == other.serverTime && sender == other.sender && text == other.text
        }

    fun withDurableIdentityFrom(other: NotificationMessageKey): NotificationMessageKey =
        if (msgid == null && other.msgid != null) copy(msgid = other.msgid) else this

    companion object {
        fun from(eventId: Long?, message: IrcEvent.ChatMessage): NotificationMessageKey =
            NotificationMessageKey(
                eventId = eventId,
                msgid = message.ctx.msgid,
                serverTime = message.ctx.serverTime,
                sender = message.source.nick,
                text = message.text,
            )

        fun from(message: MessageEntity): NotificationMessageKey =
            NotificationMessageKey(
                eventId = message.id,
                msgid = message.msgid,
                serverTime = message.serverTime,
                sender = message.sender,
                text = message.text,
            )
    }
}

internal fun readMarkerCoversNotification(markerTime: Long, latestNotifiedTime: Long): Boolean =
    markerTime >= latestNotifiedTime
