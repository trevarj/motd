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
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.NotificationClaimSession
import io.github.trevarj.motd.data.sync.ROOM_MERGE_PRESENTATION_PREFIX
import io.github.trevarj.motd.data.sync.parseRoomMergePresentationKey
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Final notification suppression decision. Pure and unit-tested.
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
 * MessagingStyle notifications. Owns the notification channels and applies the final
 * suppression rules that need Android state (muted buffer, foregrounded buffer) before posting.
 * Implements [MessageNotifier] (the EventProcessor hook) and hosts the status notification for
 * the foreground service.
 */
@Singleton
class MotdNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: MotdDatabase,
        private val foregroundBufferTracker: ForegroundBufferTracker,
        private val settingsRepository: SettingsRepository,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        @param:ApplicationScope private val applicationScope: CoroutineScope? = null,
    ) : MessageNotifier {
        private val manager = NotificationManagerCompat.from(context)

        // Per-buffer message history for MessagingStyle threading (keyed by bufferId; the posted
        // notification id is messageNotificationId(bufferId)).
        private val history = HashMap<Long, NotificationCompat.MessagingStyle>()
        private val historyKeys = HashMap<Long, MutableList<NotificationMessageKey>>()

        init {
            ensureChannels()
            applicationScope?.launch {
                retireLegacyMessageNotifications()
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

        /**
         * Retire message notifications posted by a build that used the raw `bufferId` as the
         * notification id. The new code only ever cancels ids in the [messageNotificationId] namespace,
         * so an upgrade would otherwise strand those old notifications forever. A notification on a
         * message/mention channel whose id is outside the namespace cannot have been posted by this
         * build, which makes the sweep unconditional, idempotent, and harmless on a fresh install: it
         * never inspects, and therefore never retires, the pinned status notification.
         */
        internal fun retireLegacyMessageNotifications() {
            runCatching {
                manager.activeNotifications
                    .filter {
                        it.notification.channelId in MESSAGE_CHANNELS &&
                            !isMessageNotificationId(it.id) &&
                            it.id != WATCH_ENDED_ID
                    }.forEach { legacy ->
                        manager.cancel(legacy.id)
                        diagnostics.record("notifications", "legacy_message_id_retired") {
                            mapOf("notification_id" to legacy.id, "channel" to legacy.notification.channelId)
                        }
                    }
            }
        }

        private suspend fun retireCommittedRoomMerges() {
            val state = db.appStateDao()
            state.keysLike("$ROOM_MERGE_PRESENTATION_PREFIX%").forEach { key ->
                val (winnerId, loserId) =
                    parseRoomMergePresentationKey(key) ?: run {
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
                                // Recovery re-presents state the user may already have been alerted
                                // about (the claim/present/complete cycle was interrupted after an
                                // unknown amount of presentation work); it must never re-alert.
                                firstPresentation = false,
                                message =
                                    IrcEvent.ChatMessage(
                                        ctx =
                                            MessageContext(
                                                msgid = event.msgid,
                                                serverTime = event.serverTime,
                                                account = event.senderAccount,
                                                batchId = null,
                                                label = null,
                                                serverTimeSource =
                                                    when (event.timeProvenance) {
                                                        TimeProvenance.SERVER_TAG -> ServerTimeSource.TAG
                                                        TimeProvenance.LOCAL_CLOCK -> ServerTimeSource.LOCAL
                                                        TimeProvenance.UNKNOWN -> ServerTimeSource.UNKNOWN
                                                    },
                                            ),
                                        kind =
                                            when (event.kind) {
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

                        MessageKind.INVITE -> {
                            onInvitation(buffer.networkId, buffer.id, event.id)
                        }

                        MessageKind.DCC_TRANSFER -> {
                            onDccTransferOffer(buffer.networkId, buffer.id, event.id)
                        }

                        else -> {}
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
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_TRANSFERS, "File transfers", NotificationManager.IMPORTANCE_HIGH),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SEND_FAILURES, "Failed sends", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        // -- status notification (foreground service) --

        fun statusNotification(
            connectedCount: Int,
            reconnecting: Boolean,
            starting: Boolean = false,
        ): Notification {
            // Explicit component: getLaunchIntentForPackage resolves to the enabled launcher
            // activity-alias, and switching icons (feat(appearance): selectable launcher icon
            // variants) disables that alias, dead-ending an already-posted PendingIntent.
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val stopIntent =
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, IrcForegroundService::class.java).setAction(IrcForegroundService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val text = statusNotificationText(connectedCount, reconnecting, starting)
            return NotificationCompat
                .Builder(context, CHANNEL_STATUS)
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
        ) = postIncoming(networkId, bufferId, type, hasMention, eventId = null, message, firstPresentation = true)

        override suspend fun onCanonicalIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            eventId: Long,
            message: IrcEvent.ChatMessage,
        ) = postIncoming(networkId, bufferId, type, hasMention, eventId, message, firstPresentation = true)

        private suspend fun postIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            eventId: Long?,
            message: IrcEvent.ChatMessage,
            firstPresentation: Boolean,
        ) {
            // Plain suspend reads: Room and DataStore dispatch off the main thread on their own. The
            // events collector runs on Dispatchers.Main, so the previous runBlocking { suspend query }
            // blocked the main thread and crashed once a (freshly-added) fool's message arrived.
            val buffer = runCatching { db.bufferDao().observeById(bufferId) }.getOrNull()
            val canonicalEvent =
                eventId?.let { id ->
                    db.messageDao().byCanonicalId(id)?.takeIf { event ->
                        buffer != null && db.bufferDao().canonicalId(event.bufferId) == buffer.id
                    }
                }
            val canonicalEventId = canonicalEvent?.id
            val canonicalEventTime = canonicalEvent?.serverTime ?: message.ctx.serverTime
            // Friends/fools sets (single bounded DataStore read; null settings ⇒ empty sets).
            val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: Settings()
            val identityRules =
                db.networkIdentityDao().byNetwork(networkId)?.identityRules
                    ?: IrcIdentityRules()
            val foolPolicy =
                MessageVisibilityPolicy(
                    MessageVisibilitySpec(fools = settings.fools),
                    identityRules,
                )

            // Fools and explicit buffer mute are fully silent. Foreground suppression also applies.
            val foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId
            val muted = buffer?.muted == true
            val senderIsFriend = identityRules.matchesConfiguredNick(message.source.nick, settings.friends)
            val senderIsFool =
                foolPolicy.matchesFoolIdentity(
                    canonicalEvent?.senderAccount ?: message.ctx.account,
                    canonicalEvent?.normalizedActor ?: identityRules.normalize(message.source.nick),
                )
            val incomingAnchor =
                canonicalEvent?.let {
                    TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
                }
                    ?: TimelineAnchor(message.ctx.serverTime, 0L)
            val effectiveReadAnchor = buffer?.let { effectiveLocalReadAnchor(it) }
            val alreadyRead = effectiveReadAnchor?.let { incomingAnchor <= it } == true
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
            val person =
                notificationPerson(
                    networkId,
                    message.source.nick,
                    settings.avatarStyle,
                    identityRules,
                )
            val restored =
                if (synchronized(history) { bufferId !in history }) {
                    runCatching {
                        db.messageDao().recentNotifiable(
                            bufferId = bufferId,
                            afterTime = effectiveReadAnchor?.serverTime ?: Long.MIN_VALUE,
                            afterEventId = effectiveReadAnchor?.eventId ?: Long.MIN_VALUE,
                            queryRoom = type == BufferType.QUERY,
                            excludeEventId = canonicalEventId ?: -1L,
                            limit = MAX_NOTIFICATION_MESSAGES - 1,
                        )
                    }.getOrDefault(emptyList())
                        .filterNot(foolPolicy::isFool)
                        .asReversed()
                } else {
                    emptyList()
                }
            // True only when this call appends a body the user has not been notified about yet; an
            // identity-upgrade re-post of an already-notified body must never re-alert.
            var addedNewBody = false
            val style =
                synchronized(history) {
                    val keys = historyKeys.getOrPut(bufferId, ::mutableListOf)
                    val conversation =
                        history.getOrPut(bufferId) {
                            NotificationCompat
                                .MessagingStyle(Person.Builder().setName("me").build())
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
                                notificationPerson(
                                    networkId,
                                    row.sender,
                                    settings.avatarStyle,
                                    identityRules,
                                ),
                            )
                        }
                    }
                    val key = NotificationMessageKey.from(canonicalEventId, message)
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
                        conversation
                    } else {
                        keys += key
                        if (keys.size > MAX_NOTIFICATION_MESSAGES) keys.removeAt(0)
                        addedNewBody = true
                        conversation.also { it.addMessage(message.text, message.ctx.serverTime, person) }
                    }
                }
            val notificationEventIds =
                synchronized(history) {
                    historyKeys[bufferId]
                        .orEmpty()
                        .mapNotNull { it.eventId }
                        .distinct()
                        .toLongArray()
                }

            val replyIntent =
                PendingIntent.getBroadcast(
                    context,
                    bufferId.toInt(),
                    Intent(context, ReplyReceiver::class.java)
                        .setAction(ReplyReceiver.ACTION_REPLY)
                        .putExtra(ReplyReceiver.EXTRA_BUFFER_ID, bufferId),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val remoteInput = RemoteInput.Builder(ReplyReceiver.KEY_REPLY).setLabel("Reply").build()
            val replyAction =
                NotificationCompat.Action
                    .Builder(
                        android.R.drawable.ic_menu_send,
                        "Reply",
                        replyIntent,
                    ).addRemoteInput(remoteInput)
                    .build()

            val markReadIntent =
                PendingIntent.getBroadcast(
                    context,
                    -bufferId.toInt(),
                    Intent(context, ReplyReceiver::class.java)
                        .setAction(ReplyReceiver.ACTION_MARK_READ)
                        .putExtra(ReplyReceiver.EXTRA_BUFFER_ID, bufferId)
                        .putExtra(ReplyReceiver.EXTRA_UP_TO_TIME, canonicalEventTime)
                        .putExtra(ReplyReceiver.EXTRA_UP_TO_EVENT_ID, canonicalEventId ?: 0L),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val markReadAction =
                NotificationCompat.Action
                    .Builder(
                        android.R.drawable.ic_menu_view,
                        "Mark read",
                        markReadIntent,
                    ).build()

            // Tapping the notification opens the buffer AND jumps to this message. MainActivity reads
            // these extras and routes to ChatRoute(bufferId, jumpToMsgid, jumpToTime), reusing the
            // existing deep-jump path (local resolve → CHATHISTORY AROUND fallback). A distinct request
            // code per buffer keeps concurrent buffers' content intents separate; FLAG_UPDATE_CURRENT
            // refreshes the target msgid/time on each new message. Works cold (launcher intent) or warm.
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    bufferId.toInt(),
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_BUFFER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_BUFFER_ID, bufferId)
                        .putExtra(EXTRA_JUMP_MSGID, message.ctx.msgid)
                        .putExtra(EXTRA_JUMP_TIME, canonicalEventTime)
                        .putExtra(EXTRA_EVENT_ID, canonicalEventId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            val notification =
                NotificationCompat
                    .Builder(context, channel)
                    .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
                    .setStyle(style)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    // The first presentation of a newly notified body alerts through its notification
                    // channel, so heads-up peeking, per-channel sound/vibration, and Do Not Disturb are
                    // user-governed. Every re-presentation stays silent: a crash-recovery repost
                    // (firstPresentation = false) re-presents state the user may already have been alerted
                    // about, and an identity-upgrade re-post of an already-notified body
                    // (addedNewBody = false) must not re-alert either. This never doubles up with
                    // ChatSoundPlayer's in-conversation cue: that plays only when the buffer is
                    // foregrounded, and shouldPostNotification suppresses posting in exactly that case.
                    .setSilent(!(firstPresentation && addedNewBody))
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .addExtras(
                        android.os.Bundle().apply {
                            putLongArray(EXTRA_NOTIFICATION_EVENT_IDS, notificationEventIds)
                        },
                    ).addAction(replyAction)
                    .addAction(markReadAction)
                    .build()

            // POST_NOTIFICATIONS is only a runtime permission on API 33+; below that it is granted
            // at install. Inlined here (not extracted) so lint's flow analysis recognizes the guard.
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) {
                manager.notify(messageNotificationId(bufferId), notification)
            }
            diagnostics.record("notifications", "message_post_finished") {
                mapOf(
                    "buffer_id" to bufferId,
                    "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                    "body_fp" to diagnostics.fingerprint(message.text),
                    "permission" to canPost,
                    "alerted" to (firstPresentation && addedNewBody),
                )
            }
        }

        private fun notificationPerson(
            networkId: Long,
            name: String,
            style: AvatarStyle,
            identityRules: IrcIdentityRules,
        ): Person =
            Person
                .Builder()
                .setName(name)
                .setKey("irc:$networkId:${identityRules.normalize(name)}")
                .setIcon(notificationAvatarIcon(context, name, style))
                .build()

        override suspend fun onRead(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            val inMemoryIds =
                synchronized(history) {
                    historyKeys[bufferId].orEmpty().mapNotNull { it.eventId }
                }
            val activeIds =
                runCatching {
                    manager.activeNotifications
                        .firstOrNull { it.id == messageNotificationId(bufferId) }
                        ?.notification
                        ?.extras
                        ?.getLongArray(EXTRA_NOTIFICATION_EVENT_IDS)
                        ?.toList()
                        .orEmpty()
                }.getOrDefault(emptyList())
            val trackedIds = (inMemoryIds + activeIds).distinct()
            if (trackedIds.isEmpty()) return
            val latest = resolveLatestNotificationAnchor(db, bufferId, trackedIds)
            if (latest != null && !readMarkerCoversNotification(anchor, latest)) return
            synchronized(history) {
                history.remove(bufferId)
                historyKeys.remove(bufferId)
            }
            manager.cancel(messageNotificationId(bufferId))
            diagnostics.record("notifications", "message_notification_cleared") {
                mapOf(
                    "buffer_id" to bufferId,
                    "up_to_time" to anchor.serverTime,
                    "up_to_event_id" to anchor.eventId,
                )
            }
        }

        override suspend fun onRoomsMerged(
            winnerId: Long,
            loserId: Long,
        ) {
            synchronized(history) {
                history.remove(loserId)
                historyKeys.remove(loserId)
            }
            manager.cancel(messageNotificationId(loserId))
            diagnostics.record("notifications", "room_notification_retired") {
                mapOf("winner_id" to winnerId, "loser_id" to loserId)
            }
        }

        override suspend fun onInvitation(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
        ) {
            val message = db.messageDao().byCanonicalId(messageId) ?: return
            val canonicalMessageId = message.id
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            val contentIntent =
                PendingIntent.getActivity(
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

            fun actionIntent(
                action: String,
                requestOffset: Int,
            ): PendingIntent =
                PendingIntent.getBroadcast(
                    context,
                    invitationNotificationId(canonicalMessageId) + requestOffset,
                    Intent(context, InviteReceiver::class.java)
                        .setAction(action)
                        .putExtra(InviteReceiver.EXTRA_MESSAGE_ID, canonicalMessageId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val dismissIntent = actionIntent(InviteReceiver.ACTION_DISMISS, 1)
            val joinIntent =
                PendingIntent.getActivity(
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
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_INVITATIONS)
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
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) manager.notify(invitationNotificationId(canonicalMessageId), notification)
        }

        suspend fun watchEnded(bufferId: Long) {
            val name = db.bufferDao().observeById(bufferId)?.displayName ?: return
            val text = context.getString(io.github.trevarj.motd.R.string.notification_watch_ended, name)
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    WATCH_ENDED_ID,
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_BUFFER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_BUFFER_ID, bufferId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_MESSAGES)
                    .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
                    .setContentTitle(text)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .build()
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) manager.notify(WATCH_ENDED_ID, notification)
        }

        override suspend fun onInvitationResolved(messageId: Long) {
            val canonicalId = db.canonicalTimelineDao().canonicalEventId(messageId)
            manager.cancel(invitationNotificationId(canonicalId))
            db.canonicalTimelineDao().losingEventIds(canonicalId).forEach { losingId ->
                manager.cancel(invitationNotificationId(losingId))
            }
        }

        override suspend fun onDccTransferOffer(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
        ) {
            val message = db.messageDao().byCanonicalId(messageId) ?: return
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            val foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId
            val incomingAnchor = TimelineAnchor(message.serverTime, message.id, message.timelineOrder)
            val alreadyRead = effectiveLocalReadAnchor(buffer)?.let { incomingAnchor <= it } == true
            if (foreground || buffer.muted || alreadyRead) return

            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    transferNotificationId(message.id),
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_BUFFER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_BUFFER_ID, bufferId)
                        .putExtra(EXTRA_JUMP_MSGID, message.msgid)
                        .putExtra(EXTRA_JUMP_TIME, message.serverTime)
                        .putExtra(EXTRA_EVENT_ID, message.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_TRANSFERS)
                    .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
                    .setContentTitle("File offer from ${message.sender}")
                    .setContentText(message.text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .addAction(android.R.drawable.ic_menu_save, "Open to save", contentIntent)
                    .build()
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) manager.notify(transferNotificationId(message.id), notification)
            diagnostics.record("notifications", "dcc_transfer_post_finished") {
                mapOf(
                    "network_id" to networkId,
                    "buffer_id" to bufferId,
                    "event_id" to message.id,
                    "permission" to canPost,
                )
            }
        }

        // -- failed notification replies --

        /**
         * A rejected send never reached a durable row, so — unlike an echo timeout, which leaves a
         * `failed` timeline event with a retry affordance — there is nothing in the timeline to retry
         * from and the RemoteInput UI has already reported success. Mirror the in-app contract
         * (`ChatViewModel.submit` keeps the composer draft and raises a rejection snackbar): the caller
         * preserves the text in the buffer's composer draft, and this notification shows exactly what
         * was not sent, why, and offers a one-tap retry.
         */
        suspend fun onReplyFailed(
            bufferId: Long,
            text: String,
            reason: SendRejectionReason,
        ) {
            val buffer = runCatching { db.bufferDao().observeById(bufferId) }.getOrNull()
            val title = buffer?.displayName?.let { "Not sent to $it" } ?: "Message not sent"
            val retryIntent =
                PendingIntent.getBroadcast(
                    context,
                    sendFailureNotificationId(bufferId),
                    Intent(context, ReplyReceiver::class.java)
                        .setAction(ReplyReceiver.ACTION_RETRY_REPLY)
                        .putExtra(ReplyReceiver.EXTRA_BUFFER_ID, bufferId)
                        .putExtra(ReplyReceiver.EXTRA_REPLY_TEXT, text),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            // Tapping opens the conversation, where the preserved draft is waiting in the composer.
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    sendFailureNotificationId(bufferId) + 1,
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_BUFFER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_BUFFER_ID, bufferId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val body = "${sendRejectionText(reason)}\n$text"
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_SEND_FAILURES)
                    .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .addAction(android.R.drawable.ic_menu_send, "Retry", retryIntent)
                    .build()
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) manager.notify(sendFailureNotificationId(bufferId), notification)
            diagnostics.record("notifications", "reply_failure_post_finished") {
                mapOf(
                    "buffer_id" to bufferId,
                    "reason" to reason.name,
                    "body_fp" to diagnostics.fingerprint(text),
                    "permission" to canPost,
                )
            }
        }

        /** Retire the failure notice once the same text is finally accepted. */
        fun onReplyFailureResolved(bufferId: Long) {
            manager.cancel(sendFailureNotificationId(bufferId))
        }

        private suspend fun effectiveLocalReadAnchor(buffer: RoomEntity): TimelineAnchor? {
            val local =
                buffer.localReadAnchorTime?.let { serverTime ->
                    val eventId = buffer.localReadAnchorEventId ?: 0L
                    val event = db.messageDao().byCanonicalId(eventId)
                    TimelineAnchor(serverTime, event?.id ?: eventId, event?.timelineOrder ?: eventId)
                }
            val mute =
                buffer.localUnreadFloorTime?.let { serverTime ->
                    TimelineAnchor(serverTime, Long.MAX_VALUE, Long.MAX_VALUE)
                }
            return listOfNotNull(local, mute).maxOrNull()
        }

        companion object {
            const val CHANNEL_STATUS = "status"
            const val CHANNEL_MESSAGES = "messages"
            const val CHANNEL_MENTIONS = "mentions"
            const val CHANNEL_INVITATIONS = "invitations"
            const val CHANNEL_TRANSFERS = "transfers"
            const val CHANNEL_SEND_FAILURES = "send_failures"
            private const val MAX_NOTIFICATION_MESSAGES = 25
            private val MESSAGE_CHANNELS = setOf(CHANNEL_MESSAGES, CHANNEL_MENTIONS)
            private val RESETTABLE_CHANNELS =
                setOf(
                    CHANNEL_MESSAGES,
                    CHANNEL_MENTIONS,
                    CHANNEL_INVITATIONS,
                    CHANNEL_TRANSFERS,
                    CHANNEL_SEND_FAILURES,
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

            /**
             * Message notifications are keyed by buffer, and a raw `bufferId.toInt()` collided with
             * [IrcForegroundService.STATUS_ID]: the conversation that happens to own buffer id 1
             * overwrote and then cancelled the pinned foreground-service notification. Namespace the id
             * like the invitation/transfer ids so it can never alias the status id or either of those
             * ranges (invitations occupy `0x40000000..0x7fffffff`, transfers `0x50000000..0x5fffffff`).
             */
            internal fun messageNotificationId(bufferId: Long): Int = MESSAGE_ID_NAMESPACE or (bufferId xor (bufferId ushr 32)).toInt().and(0x0fffffff)

            /** True only for ids minted by [messageNotificationId]; used to retire legacy raw ids. */
            internal fun isMessageNotificationId(id: Int): Boolean = (id and NAMESPACE_MASK) == MESSAGE_ID_NAMESPACE

            internal fun invitationNotificationId(messageId: Long): Int = 0x40000000 or (messageId xor (messageId ushr 32)).toInt().and(0x3fffffff)

            internal fun transferNotificationId(messageId: Long): Int = 0x50000000 or (messageId xor (messageId ushr 32)).toInt().and(0x0fffffff)

            /** Failure notices are keyed by buffer too, and must not alias any other range. */
            internal fun sendFailureNotificationId(bufferId: Long): Int = 0x20000000 or (bufferId xor (bufferId ushr 32)).toInt().and(0x0fffffff)

            private const val MESSAGE_ID_NAMESPACE = 0x10000000
            private const val NAMESPACE_MASK = -0x10000000 // 0xf0000000
            private const val WATCH_ENDED_ID = 0x30000001
        }
    }

internal fun statusNotificationText(
    connectedCount: Int,
    reconnecting: Boolean,
    starting: Boolean,
): String =
    when {
        starting -> "Keeping chats connected"
        reconnecting -> "Reconnecting…"
        else -> "Connected to $connectedCount networks"
    }

/**
 * User-facing reason for a rejected send, worded like the in-app snackbars
 * (`chat_send_rejected`, `chat_not_in_channel`).
 */
internal fun sendRejectionText(reason: SendRejectionReason): String =
    when (reason) {
        SendRejectionReason.NOT_IN_CHANNEL -> "You're not in this channel"

        SendRejectionReason.BUFFER_NOT_FOUND -> "This conversation is no longer available"

        SendRejectionReason.UNSUPPORTED_BUFFER -> "This conversation can't receive messages"

        SendRejectionReason.INVALID_CONTENT,
        SendRejectionReason.EVENT_NOT_RETRYABLE,
        SendRejectionReason.PERSISTENCE_FAILED,
        -> "Couldn't save this message"
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
        copy(
            eventId = eventId ?: other.eventId,
            msgid = msgid ?: other.msgid,
        )

    companion object {
        fun from(
            eventId: Long?,
            message: IrcEvent.ChatMessage,
        ): NotificationMessageKey =
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

internal fun readMarkerCoversNotification(
    marker: TimelineAnchor,
    latestNotified: TimelineAnchor,
): Boolean = marker >= latestNotified

internal suspend fun resolveLatestNotificationAnchor(
    db: MotdDatabase,
    bufferId: Long,
    eventIds: Collection<Long>,
): TimelineAnchor? {
    val canonicalRoomId = db.bufferDao().canonicalId(bufferId) ?: return null
    var latest: TimelineAnchor? = null
    for (eventId in eventIds) {
        val event = db.messageDao().byCanonicalId(eventId) ?: continue
        if (db.bufferDao().canonicalId(event.bufferId) != canonicalRoomId) continue
        val current = TimelineAnchor(event.serverTime, event.id, event.timelineOrder)
        if (latest == null || current > latest) latest = current
    }
    return latest
}

private const val EXTRA_NOTIFICATION_EVENT_IDS = "motd.notificationEventIds"
