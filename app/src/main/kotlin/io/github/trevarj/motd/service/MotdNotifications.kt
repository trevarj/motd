package io.github.trevarj.motd.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.R
import io.github.trevarj.motd.avatar.notificationAvatarIcon
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomEntity
import io.github.trevarj.motd.data.db.TimeProvenance
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.alertsAsMention
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
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import io.github.trevarj.motd.dickord.dickordChannelLabel
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.dickord.isDickordChannel
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import android.provider.Settings as AndroidSettings

/**
 * Final notification suppression decision. Pure and unit-tested.
 *
 * Precedence (highest first): already-read and foreground buffers suppress everything; an explicit
 * buffer mute wins unless the message qualified under a watch; sender category permission is required.
 * Notification policy eligibility is captured upstream by
 * [io.github.trevarj.motd.data.sync.incomingNotificationDecision].
 */
fun shouldPostNotification(
    foreground: Boolean,
    muted: Boolean,
    senderAllowsNotification: Boolean,
    alreadyRead: Boolean = false,
    watched: Boolean = false,
): Boolean = !alreadyRead && !foreground && !(muted && !watched) && senderAllowsNotification

internal enum class TestAlertCategory(
    val channelId: String,
) {
    MESSAGES(MotdNotifications.CHANNEL_MESSAGES),
    MENTIONS(MotdNotifications.CHANNEL_MENTIONS),
}

internal enum class TestAlertResult { SENT, APP_BLOCKED, CATEGORY_BLOCKED, UNAVAILABLE }

/**
 * MessagingStyle notifications. Owns the notification channels and applies the final
 * suppression rules that need Android state (muted buffer, foregrounded buffer) before posting.
 * Implements [MessageNotifier], the EventProcessor hook.
 */
@Singleton
class MotdNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val db: MotdDatabase,
        private val foregroundBufferTracker: ForegroundBufferTracker,
        private val settingsRepository: SettingsRepository,
        private val dickordLabsPrefs: DickordLabsPrefs = DickordLabsPrefs(context),
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        @param:ApplicationScope private val applicationScope: CoroutineScope? = null,
    ) : MessageNotifier {
        private val manager = NotificationManagerCompat.from(context)

        private data class NotificationThreadKey(
            val bufferId: Long,
            val channelMentions: Boolean,
        )

        private val history = HashMap<NotificationThreadKey, NotificationCompat.MessagingStyle>()
        private val historyKeys = HashMap<NotificationThreadKey, MutableList<NotificationMessageKey>>()
        private val dickordBatches = HashMap<NotificationThreadKey, Boolean>()
        private val alertWindowPrefs by lazy { context.getSharedPreferences("notification_alert_windows", Context.MODE_PRIVATE) }
        private val alertWindowBoot by lazy {
            runCatching { AndroidSettings.Global.getInt(context.contentResolver, AndroidSettings.Global.BOOT_COUNT) }.getOrNull()
        }
        private val lastEligiblePost by lazy {
            val boot = alertWindowBoot
            val prefs = alertWindowPrefs
            val stored = prefs.all
            val times = HashMap<Long, Long>()
            if (boot != null && stored["boot"] == boot) {
                stored.forEach { (key, value) ->
                    key.removePrefix("buffer:").toLongOrNull()?.let { id ->
                        if (key.startsWith("buffer:") && value is Long) times[id] = value
                    }
                }
            } else {
                prefs.edit {
                    clear()
                    if (boot != null) putInt("boot", boot)
                }
            }
            times
        }

        private fun pruneAlertWindows(now: Long) {
            var editor: SharedPreferences.Editor? = null
            val iterator = lastEligiblePost.iterator()
            while (iterator.hasNext()) {
                val (bufferId, time) = iterator.next()
                if (time > now || now - time >= ALERT_QUIET_WINDOW_MS) {
                    iterator.remove()
                    if (alertWindowBoot != null) {
                        if (editor == null) editor = alertWindowPrefs.edit()
                        editor.remove("buffer:$bufferId")
                    }
                }
            }
            editor?.apply()
        }

        private fun recordEligiblePost(
            bufferId: Long,
            now: Long,
        ) {
            lastEligiblePost[bufferId] = now
            if (alertWindowBoot != null) alertWindowPrefs.edit { putLong("buffer:$bufferId", now) }
        }

        // Guarded by history; no presentation lock may span a Room read inside playback's transaction.
        private var historyRevision = 0L
        private var currentPreview: NotificationPreview? = null
        private val shortcutRooms = HashMap<String, Job>()

        init {
            ensureChannels()
            applicationScope?.launch {
                settingsRepository.notificationPreview
                    .retryWhen { _, _ ->
                        synchronized(history) { applyPreview(NotificationPreview.GENERIC) }
                        delay(PREVIEW_RETRY_MS)
                        true
                    }.collect { previewSnapshot() }
            }
            applicationScope?.launch {
                retireLegacyMessageNotifications()
                // Only the v9→v10 migration writes this marker. Fresh/empty databases must not infer
                // that another process instance's notifications are obsolete.
                runCatching {
                    db.withTransaction {
                        if (db.appStateDao().contains(V10_NOTIFICATION_RESET) > 0) {
                            manager.activeNotifications
                                .filter { it.notification.baseChannelId in RESETTABLE_CHANNELS && !isTestAlert(it.tag, it.id) }
                                .forEach { manager.cancel(it.tag, it.id) }
                            db.appStateDao().delete(V10_NOTIFICATION_RESET)
                        }
                    }
                }
                retireCommittedRoomMerges()
                retireInvalidCanonicalCards()
                recoverCanonicalNotifications()
            }
        }

        private suspend fun readPreview(): NotificationPreview =
            try {
                settingsRepository.notificationPreview.first()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NotificationPreview.GENERIC
            }

        private fun applyPreview(preview: NotificationPreview) {
            if (currentPreview == preview) return
            historyRevision++
            history.clear()
            historyKeys.clear()
            dickordBatches.clear()
            manager.activeNotifications
                .filter {
                    if (isTestAlert(it.tag, it.id)) return@filter false
                    val notification = it.notification
                    val channel = notification.baseChannelId
                    if (channel !in RESETTABLE_CHANNELS) return@filter false
                    if (notification.extras.getString(EXTRA_PREVIEW) != preview.name) return@filter true
                    val canHaveLegacyActions = channel == CHANNEL_SEND_FAILURES || (channel in MESSAGE_CHANNELS && !isWatchEndedNotificationId(it.id))
                    canHaveLegacyActions && !notification.extras.getBoolean(EXTRA_SERVICE_ACTIONS) &&
                        !notification.actions.isNullOrEmpty()
                }.forEach { manager.cancel(it.tag, it.id) }
            val shortcuts = conversationShortcuts().map { it.id }
            if (preview == NotificationPreview.GENERIC) {
                shortcutRooms.values.forEach { it.cancel() }
                shortcutRooms.clear()
                forgetConversationShortcuts(shortcuts)
            } else {
                shortcuts.forEach(::watchShortcutRoom)
            }
            currentPreview = preview
        }

        private suspend fun previewSnapshot(): Pair<NotificationPreview, Long> {
            while (true) {
                val revision = synchronized(history) { historyRevision }
                val preview = readPreview()
                synchronized(history) {
                    if (historyRevision == revision) {
                        applyPreview(preview)
                        return preview to historyRevision
                    }
                }
            }
        }

        private inline fun <T> commitPreview(
            preview: NotificationPreview,
            revision: Long,
            block: () -> T,
        ): T? =
            synchronized(history) {
                if (currentPreview == preview && historyRevision == revision) block() else null
            }

        private fun previewBuilder(
            channel: String,
            preview: NotificationPreview,
            genericText: Int,
        ): NotificationCompat.Builder =
            NotificationCompat
                .Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification_motd)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat
                        .Builder(context, channel)
                        .setSmallIcon(R.drawable.ic_notification_motd)
                        .setContentTitle(context.getString(R.string.app_name))
                        .setContentText(context.getString(genericText))
                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                        .build(),
                ).apply { extras.putString(EXTRA_PREVIEW, preview.name) }

        private inline fun NotificationPreview.showIdentity(value: () -> String?): String? = if (this == NotificationPreview.GENERIC) null else value()

        private suspend fun networkLabel(
            networkId: Long,
            preview: NotificationPreview,
        ): String? =
            preview.showIdentity {
                optionalMetadata {
                    db
                        .networkDao()
                        .byId(networkId)
                        ?.name
                        ?.takeIf { it.isNotBlank() }
                }
            }

        private inline fun <T> optionalMetadata(block: () -> T?): T? =
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }

        /**
         * Retire message notifications posted by a build that used the raw `bufferId` as the
         * notification id. The new code only ever cancels ids in the [messageNotificationId] namespace,
         * so an upgrade would otherwise strand those old notifications forever. A notification on a
         * message/mention channel whose id is outside the namespace and is not an explicit test
         * alert or watch-ended notice is legacy. The pinned status channel is not inspected.
         */
        internal fun retireLegacyMessageNotifications() {
            runCatching {
                manager.activeNotifications
                    .filter {
                        it.notification.baseChannelId in MESSAGE_CHANNELS &&
                            !isMessageNotificationId(it.id) &&
                            !isWatchEndedNotificationId(it.id) &&
                            !isTestAlert(it.tag, it.id)
                    }.forEach { legacy ->
                        manager.cancel(legacy.tag, legacy.id)
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
                // BufferStore deletes the marker after notifying both settings and presentation.
            }
        }

        /** Rebuild interrupted chat/invitation presentation directly from canonical database state. */
        internal suspend fun recoverCanonicalNotifications() {
            val dao = db.canonicalTimelineDao()
            dao.releaseInterruptedNotificationClaims(NotificationClaimSession.owner, RECOVERY_WINDOW_MS, RECOVERY_SCAN_ROWS)
            val owner = NotificationClaimSession.owner
            for (candidate in dao.pendingNotifications(MAX_RECOVERY_NOTIFICATIONS, RECOVERY_WINDOW_MS, RECOVERY_SCAN_ROWS)) {
                if (dao.claimNotification(candidate.id, owner) != 1) continue
                var completed = false
                try {
                    while (true) {
                        val event = dao.claimedRecoveryEvent(candidate.id, owner) ?: break
                        postRecoveredNotification(event)
                        completed = dao.completeRecoveryNotification(event.id, event.bufferId, owner, event.alertsAsMention, event.notificationWatched) == 1
                        if (completed) break
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    diagnostics.record("notifications", "recovery_failed") {
                        mapOf("event_id" to candidate.id, "error" to error::class.simpleName)
                    }
                } finally {
                    if (!completed) withContext(NonCancellable) { dao.releaseRecoveryNotification(candidate.id, owner) }
                }
            }
        }

        private suspend fun postRecoveredNotification(event: MessageEntity) {
            val buffer = db.bufferDao().observeById(event.bufferId) ?: return
            when (event.kind) {
                MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION -> {
                    postIncoming(
                        networkId = buffer.networkId,
                        bufferId = buffer.id,
                        type = buffer.type,
                        hasMention = event.alertsAsMention,
                        eventId = event.id,
                        // A claim may have been interrupted after posting; recovery must never alert again.
                        firstPresentation = false,
                        watched = event.notificationWatched,
                        recovering = true,
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
                    onRecoveredInvitation(buffer.networkId, buffer.id, event.id)
                }

                MessageKind.DCC_TRANSFER -> {
                    onDccTransferOffer(buffer.networkId, buffer.id, event.id, silent = true)
                }

                else -> {}
            }
        }

        private fun ensureChannels() {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_STATUS,
                        context.getString(R.string.connection_notification_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) },
                )
            }
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

        internal fun postTestAlert(category: TestAlertCategory): TestAlertResult {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return TestAlertResult.APP_BLOCKED
            }
            if (!manager.areNotificationsEnabled()) return TestAlertResult.APP_BLOCKED
            val nativeManager = context.getSystemService(NotificationManager::class.java) ?: return TestAlertResult.UNAVAILABLE
            val channel = nativeManager.getNotificationChannel(category.channelId) ?: return TestAlertResult.UNAVAILABLE
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return TestAlertResult.CATEGORY_BLOCKED
            val notification =
                NotificationCompat
                    .Builder(context, channel.id)
                    .setSmallIcon(R.drawable.ic_notification_motd)
                    .setContentTitle(context.getText(R.string.notification_local_test_title))
                    .setContentText(context.getText(R.string.notification_local_test_body))
                    .build()
            return try {
                nativeManager.cancel(TEST_ALERT_TAG, TEST_ALERT_ID)
                nativeManager.notify(TEST_ALERT_TAG, TEST_ALERT_ID, notification)
                TestAlertResult.SENT
            } catch (_: SecurityException) {
                TestAlertResult.APP_BLOCKED
            }
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
            watched: Boolean,
            refreshOnly: Boolean,
        ) = postIncoming(
            networkId,
            bufferId,
            type,
            hasMention,
            eventId,
            message,
            firstPresentation = !refreshOnly,
            watched = watched,
            refreshOnly = refreshOnly,
        )

        private suspend fun postIncoming(
            networkId: Long,
            bufferId: Long,
            type: BufferType,
            hasMention: Boolean,
            eventId: Long?,
            message: IrcEvent.ChatMessage,
            firstPresentation: Boolean,
            watched: Boolean = false,
            refreshOnly: Boolean = false,
            recovering: Boolean = false,
        ) {
            while (true) {
                val (preview, revision) = previewSnapshot()
                // Plain suspend reads: Room and DataStore dispatch off the main thread on their own.
                // Historical enrichment can call this inside Room's exclusive playback transaction.
                val buffer =
                    runCatching { db.bufferDao().observeById(bufferId) }.getOrElse { error ->
                        if (refreshOnly) throw error
                        null
                    }
                if (buffer != null && (buffer.id != bufferId || buffer.dismissed || buffer.pendingCloseAt != null)) return
                val canonicalEvent =
                    eventId?.let { id ->
                        db.messageDao().byCanonicalId(id)?.takeIf { event ->
                            buffer != null && db.bufferDao().canonicalId(event.bufferId) == buffer.id
                        }
                    }
                if ((eventId != null && canonicalEvent == null) || canonicalEvent?.kind == MessageKind.REDACTED ||
                    (eventId == null && message.ctx.msgid?.let { db.messageDao().byMsgid(bufferId, it)?.kind } == MessageKind.REDACTED)
                ) {
                    return
                }
                if (recovering) {
                    val ready = db.canonicalTimelineDao().claimedRecoveryEvent(checkNotNull(eventId), NotificationClaimSession.owner) ?: return
                    if (ready != canonicalEvent) continue
                }
                val canonicalEventId = canonicalEvent?.id
                val canonicalEventTime = canonicalEvent?.serverTime ?: message.ctx.serverTime
                val alertMention = canonicalEvent?.alertsAsMention ?: hasMention
                val thread = NotificationThreadKey(bufferId, type == BufferType.CHANNEL && alertMention)
                if (refreshOnly &&
                    (
                        !thread.channelMentions || canonicalEvent == null || canonicalEvent.isSelf ||
                            !canonicalEvent.notificationHandled || !canonicalEvent.notificationEligible ||
                            !canonicalEvent.notificationEligibilityResolved || buffer == null ||
                            buffer.dismissed || buffer.pendingCloseAt != null
                    )
                ) {
                    return
                }
                // One bounded DataStore read supplies both membership and category preferences.
                val settings = runCatching { settingsRepository.settings.first() }.getOrNull() ?: Settings()
                val identityRules =
                    db.networkIdentityDao().byNetwork(networkId)?.identityRules
                        ?: IrcIdentityRules()
                val foolPolicy =
                    MessageVisibilityPolicy(
                        MessageVisibilitySpec.from(settings),
                        identityRules,
                    )

                // Only a watch bypasses buffer mute, never sender category permission or read/foreground.
                val foreground = foregroundBufferTracker.foregroundBufferId.value == bufferId
                val muted = buffer?.muted == true
                val bypassesMute = if (recovering) checkNotNull(canonicalEvent).notificationWatched else watched
                val senderIsFriend = identityRules.matchesConfiguredNick(message.source.nick, settings.friends)
                val senderIsFool =
                    foolPolicy.matchesFoolIdentity(
                        if (canonicalEvent != null) canonicalEvent.senderAccount else message.ctx.account,
                        canonicalEvent?.normalizedActor ?: identityRules.normalize(message.source.nick),
                    )
                val senderAllowsNotification =
                    if (type == BufferType.SERVER) {
                        !senderIsFool
                    } else {
                        foolPolicy.allowsNotification(
                            if (canonicalEvent != null) canonicalEvent.senderAccount else message.ctx.account,
                            canonicalEvent?.normalizedActor ?: identityRules.normalize(message.source.nick),
                            directMessage = type == BufferType.QUERY,
                            hasMention = alertMention,
                        )
                    }
                val incomingAnchor =
                    canonicalEvent?.let {
                        TimelineAnchor(it.serverTime, it.id, it.timelineOrder)
                    }
                        ?: TimelineAnchor(message.ctx.serverTime, 0L)
                val effectiveReadAnchor = buffer?.let { effectiveLocalReadAnchor(it) }
                val alreadyRead = effectiveReadAnchor?.let { incomingAnchor <= it } == true
                val decision =
                    shouldPostNotification(foreground, muted, senderAllowsNotification, alreadyRead, watched = bypassesMute)
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
                        "watched" to bypassesMute,
                        "post" to decision,
                        "mention" to hasMention,
                    )
                }
                if (!decision) return

                val dickordEnabled = runCatching { dickordLabsPrefs.enabled.first() }.getOrDefault(false)
                // Stage the remaining ordinary row before touching either lane. A failed rebuild is not
                // an empty conversation: let the caller's notification error boundary report it.
                val promotedOrdinary =
                    if (thread.channelMentions && buffer != null && canonicalEvent != null &&
                        !buffer.dismissed && buffer.pendingCloseAt == null
                    ) {
                        stageOrdinaryPromotion(
                            buffer,
                            canonicalEvent,
                            effectiveReadAnchor,
                            foolPolicy,
                            settings,
                            identityRules,
                            dickordEnabled,
                            preview,
                        )
                    } else {
                        null
                    }
                if (refreshOnly && promotedOrdinary == null) {
                    if (synchronized(history) { historyRevision != revision }) continue
                    return
                }

                val channel = if (alertMention) CHANNEL_MENTIONS else CHANNEL_MESSAGES
                val title =
                    if (thread.channelMentions) buffer?.ircTarget ?: message.target else buffer?.displayName ?: message.target
                val dickordBatch =
                    synchronized(history) {
                        dickordBatches[thread] ?: (dickordEnabled && isDickordChannel(title))
                    }
                val network = networkLabel(networkId, preview)
                val cached =
                    synchronized(history) {
                        history[thread]?.let { style ->
                            style.messages.toList() to historyKeys[thread].orEmpty().toList()
                        }
                    }
                val cachedRows =
                    cached?.let { (_, keys) ->
                        keys.map { key ->
                            key.eventId?.let { db.messageDao().byCanonicalId(it) }?.also { row ->
                                check(db.bufferDao().canonicalId(row.bufferId) == bufferId) { "Notification anchor belongs to another room" }
                            }
                        }
                    }
                val rebuild = cached == null || cached.first.size != cached.second.size || cachedRows.orEmpty().any { it == null }
                val restored =
                    if (rebuild) {
                        runCatching {
                            db.messageDao().recentNotifiable(
                                bufferId = bufferId,
                                afterTime = effectiveReadAnchor?.serverTime ?: Long.MIN_VALUE,
                                afterEventId = effectiveReadAnchor?.eventId ?: Long.MIN_VALUE,
                                excludeEventId = canonicalEventId ?: -1L,
                                limit = MAX_NOTIFICATION_MESSAGES - 1,
                                hasMention = if (type == BufferType.CHANNEL) thread.channelMentions else null,
                            )
                        }.getOrElse { error ->
                            if (error is CancellationException || refreshOnly || promotedOrdinary != null || cached != null) throw error
                            emptyList()
                        }.filter { foolPolicy.allowsNotification(it, type) }
                            .asReversed()
                    } else {
                        emptyList()
                    }
                val keys = mutableListOf<NotificationMessageKey>()
                val style = chatStyle(preview, type, if (thread.channelMentions) title else dickordChannelLabel(title, dickordBatch))
                if (rebuild) {
                    restored.forEach { row ->
                        val key = NotificationMessageKey.from(row)
                        if (keys.none { it.matches(key) }) {
                            keys += key
                            style.addChatMessage(preview, key, networkId, dickordBatch, settings.avatarStyle, identityRules)
                        }
                    }
                } else {
                    cachedRows.orEmpty().forEachIndexed { index, row ->
                        checkNotNull(row)
                        if (foolPolicy.allowsNotification(row, type) &&
                            (type != BufferType.CHANNEL || row.alertsAsMention == thread.channelMentions) &&
                            (effectiveReadAnchor == null || TimelineAnchor(row.serverTime, row.id, row.timelineOrder) > effectiveReadAnchor)
                        ) {
                            val key = NotificationMessageKey.from(row)
                            if (keys.none { it.matches(key) }) {
                                keys += key
                                style.addMessage(checkNotNull(cached).first[index])
                            }
                        }
                    }
                }
                val key = canonicalEvent?.let(NotificationMessageKey::from) ?: NotificationMessageKey.from(canonicalEventId, message)
                val existingIndex = keys.indexOfFirst { it.matches(key) }
                // Rebuilding unresolved transport bodies must not turn an identity upgrade/duplicate
                // into another first alert, even when that old body could not be retained.
                val addedNewBody = existingIndex < 0 && cached?.second.orEmpty().none { it.matches(key) }
                if (existingIndex >= 0) {
                    keys[existingIndex] = keys[existingIndex].withDurableIdentityFrom(key)
                    diagnostics.record("notifications", "message_deduplicated") {
                        mapOf(
                            "buffer_id" to bufferId,
                            "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                            "body_fp" to diagnostics.fingerprint(message.text),
                            "existing_index" to existingIndex,
                        )
                    }
                } else {
                    keys += key
                    if (keys.size > MAX_NOTIFICATION_MESSAGES) keys.removeAt(0)
                    style.addChatMessage(preview, key, networkId, dickordBatch, settings.avatarStyle, identityRules)
                }
                val notificationEventIds = keys.mapNotNull { it.eventId }.distinct().toLongArray()
                val ordinaryEventIds =
                    promotedOrdinary
                        ?.takeIf { it.keys.isNotEmpty() }
                        ?.keys
                        ?.mapNotNull { it.eventId }
                        ?.distinct()
                        ?.toLongArray()
                val latestAnchor = resolveLatestNotificationAnchor(db, bufferId, notificationEventIds.toList())
                val latestEvent = latestAnchor?.let { db.messageDao().byCanonicalId(it.eventId) }
                if (readPreview() != preview) continue
                if (recovering &&
                    db.canonicalTimelineDao().claimedRecoveryEvent(checkNotNull(eventId), NotificationClaimSession.owner) != canonicalEvent
                ) {
                    continue
                }
                var requestedAlert = false
                val canPost =
                    commitPreview(preview, revision) {
                        val eligibleNewBody = firstPresentation && addedNewBody
                        val now = SystemClock.elapsedRealtime()
                        if (eligibleNewBody) pruneAlertWindows(now)
                        requestedAlert = eligibleNewBody && lastEligiblePost[bufferId] == null
                        // FLAG_UPDATE_CURRENT mutates already-posted action anchors, so build only
                        // after validation and commit both lanes without another presentation interleaving.
                        val shortcutId = conversationShortcut(buffer, network, preview, settings.avatarStyle)
                        val notification =
                            buildMessageNotification(
                                thread,
                                type,
                                channel,
                                style,
                                notificationEventIds,
                                if (latestEvent != null) latestEvent.msgid else message.ctx.msgid,
                                latestAnchor?.serverTime ?: canonicalEventTime,
                                latestAnchor?.eventId ?: canonicalEventId,
                                preview,
                                network,
                                shortcutId,
                                silent = !requestedAlert,
                                channelEventId = canonicalEventId.takeIf { type == BufferType.QUERY && alertMention },
                            )
                        val ordinaryNotification =
                            promotedOrdinary?.takeIf { it.keys.isNotEmpty() }?.let { staged ->
                                buildMessageNotification(
                                    NotificationThreadKey(bufferId, false),
                                    BufferType.CHANNEL,
                                    staged.channel,
                                    staged.style,
                                    checkNotNull(ordinaryEventIds),
                                    staged.msgid,
                                    staged.anchor.serverTime,
                                    staged.anchor.eventId.takeIf { it != 0L },
                                    preview,
                                    network,
                                    shortcutId,
                                    silent = true,
                                )
                            }
                        historyRevision++
                        history[thread] = style
                        historyKeys[thread] = keys
                        dickordBatches[thread] = dickordBatch
                        promotedOrdinary?.let { staged ->
                            val ordinary = NotificationThreadKey(bufferId, false)
                            if (staged.keys.isEmpty()) {
                                history.remove(ordinary)
                                historyKeys.remove(ordinary)
                                dickordBatches.remove(ordinary)
                            } else {
                                history[ordinary] = staged.style
                                historyKeys[ordinary] = staged.keys
                                dickordBatches[ordinary] = staged.dickordBatch
                            }
                            if (ordinaryNotification == null) {
                                manager.cancel(messageNotificationId(bufferId))
                            } else {
                                postMessageNotification(ordinary, ordinaryNotification)
                            }
                        }
                        postMessageNotification(thread, notification).also { posted ->
                            if (posted && eligibleNewBody) recordEligiblePost(bufferId, SystemClock.elapsedRealtime())
                        }
                    } ?: continue
                diagnostics.record("notifications", "message_post_finished") {
                    mapOf(
                        "buffer_id" to bufferId,
                        "msgid_fp" to diagnostics.fingerprint(message.ctx.msgid),
                        "body_fp" to diagnostics.fingerprint(message.text),
                        "permission" to canPost,
                        "alerted" to (canPost && requestedAlert),
                    )
                }
                return
            }
        }

        private fun buildMessageNotification(
            thread: NotificationThreadKey,
            type: BufferType,
            channel: String,
            style: NotificationCompat.MessagingStyle,
            notificationEventIds: LongArray,
            msgid: String?,
            canonicalEventTime: Long,
            canonicalEventId: Long?,
            preview: NotificationPreview,
            network: String?,
            shortcutId: String?,
            silent: Boolean,
            channelEventId: Long? = null,
        ): Notification {
            val bufferId = thread.bufferId
            val automotiveMessaging = type == BufferType.QUERY || thread.channelMentions

            fun destination(action: String): Uri = "motd://notification/$bufferId/${if (thread.channelMentions) CHANNEL_MENTION_TAG else "default"}/$action".toUri()
            val replyIntent =
                PendingIntent.getService(
                    context,
                    bufferId.toInt(),
                    Intent(context, NotificationActionService::class.java)
                        .setAction(NotificationActionService.ACTION_REPLY)
                        .setData(destination("reply"))
                        .putExtra(NotificationActionService.EXTRA_BUFFER_ID, bufferId),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val replyLabel = context.getString(R.string.notification_projection_reply)
            val remoteInput = RemoteInput.Builder(NotificationActionService.KEY_REPLY).setLabel(replyLabel).build()
            val replyAction =
                NotificationCompat.Action
                    .Builder(
                        android.R.drawable.ic_menu_send,
                        replyLabel,
                        replyIntent,
                    ).addRemoteInput(remoteInput)
                    .setSemanticAction(
                        if (automotiveMessaging) NotificationCompat.Action.SEMANTIC_ACTION_REPLY else NotificationCompat.Action.SEMANTIC_ACTION_NONE,
                    ).setShowsUserInterface(!automotiveMessaging)
                    .build()

            val markReadIntent =
                PendingIntent.getService(
                    context,
                    -bufferId.toInt(),
                    Intent(context, NotificationActionService::class.java)
                        .setAction(NotificationActionService.ACTION_MARK_READ)
                        .setData(destination("read"))
                        .putExtra(NotificationActionService.EXTRA_BUFFER_ID, bufferId)
                        .putExtra(NotificationActionService.EXTRA_UP_TO_TIME, canonicalEventTime)
                        .putExtra(NotificationActionService.EXTRA_UP_TO_EVENT_ID, canonicalEventId ?: 0L),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val markReadAction =
                NotificationCompat.Action
                    .Builder(
                        android.R.drawable.ic_menu_view,
                        context.getString(R.string.notification_projection_mark_read),
                        markReadIntent,
                    ).setSemanticAction(
                        if (automotiveMessaging) NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ else NotificationCompat.Action.SEMANTIC_ACTION_NONE,
                    ).setShowsUserInterface(!automotiveMessaging)
                    .build()

            // Open at this lane's latest represented anchor. The full buffer/lane URI keeps
            // PendingIntents independent even when their truncated request codes collide;
            // FLAG_UPDATE_CURRENT refreshes only this lane's deep-jump extras.
            val contentIntent =
                PendingIntent.getActivity(
                    context,
                    bufferId.toInt(),
                    Intent(context, MainActivity::class.java)
                        .setAction(ACTION_OPEN_BUFFER)
                        .setData(destination("open"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_BUFFER_ID, bufferId)
                        .putExtra(EXTRA_JUMP_MSGID, msgid)
                        .putExtra(EXTRA_JUMP_TIME, canonicalEventTime)
                        .putExtra(EXTRA_EVENT_ID, canonicalEventId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

            return previewBuilder(messageChannelId(channel, bufferId), preview, R.string.notification_projection_message)
                .apply {
                    if (shortcutId != null) {
                        setShortcutId(shortcutId)
                        setLocusId(LocusIdCompat(shortcutId))
                    }
                }.setSubText(network)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setLocalOnly(!automotiveMessaging)
                .setSilent(silent)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .addExtras(
                    android.os.Bundle().apply {
                        putString(EXTRA_MESSAGE_CHANNEL, channel)
                        putLongArray(EXTRA_NOTIFICATION_EVENT_IDS, notificationEventIds)
                        if (channelEventId != null) putLong(EXTRA_NOTIFICATION_CHANNEL_EVENT_ID, channelEventId)
                        putBoolean(EXTRA_SERVICE_ACTIONS, true)
                    },
                ).addAction(replyAction)
                .addAction(markReadAction)
                .build()
        }

        private val Notification.baseChannelId: String
            get() = extras.getString(EXTRA_MESSAGE_CHANNEL) ?: channelId

        private fun messageChannelId(
            parentChannel: String,
            bufferId: Long,
        ): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context
                    .getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(parentChannel, "$CONVERSATION_SHORTCUT_PREFIX$bufferId")
                    ?.id ?: parentChannel
            } else {
                parentChannel
            }

        private fun postMessageNotification(
            thread: NotificationThreadKey,
            notification: Notification,
        ): Boolean {
            // POST_NOTIFICATIONS is only a runtime permission on API 33+; below that it is granted
            // at install. Inlined here (not extracted) so lint's flow analysis recognizes the guard.
            val canPost =
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (canPost) {
                manager.notify(if (thread.channelMentions) CHANNEL_MENTION_TAG else null, messageNotificationId(thread.bufferId), notification)
            }
            return canPost
        }

        private data class OrdinaryPromotion(
            val style: NotificationCompat.MessagingStyle,
            val keys: MutableList<NotificationMessageKey>,
            val dickordBatch: Boolean,
            val channel: String,
            val msgid: String?,
            val anchor: TimelineAnchor,
        )

        private suspend fun stageOrdinaryPromotion(
            buffer: RoomEntity,
            promoted: MessageEntity,
            readFloor: TimelineAnchor?,
            foolPolicy: MessageVisibilityPolicy,
            settings: Settings,
            identityRules: IrcIdentityRules,
            dickordEnabled: Boolean,
            preview: NotificationPreview,
        ): OrdinaryPromotion? {
            val ordinary = NotificationThreadKey(buffer.id, false)
            // A cached body is not proof that the user still has its notification.
            val active =
                manager.activeNotifications.firstOrNull {
                    it.tag == null && it.id == messageNotificationId(buffer.id)
                } ?: return null
            val promotedKey = NotificationMessageKey.from(promoted)
            val cached =
                synchronized(history) {
                    history[ordinary]?.let { style ->
                        style.messages.toList() to historyKeys[ordinary].orEmpty().toList()
                    }
                }
            val cachedMatch = cached?.second?.any { it.matches(promotedKey) } == true
            val activeEvents =
                if (cachedMatch) {
                    emptyList()
                } else {
                    (active.notification.extras.getLongArray(EXTRA_NOTIFICATION_EVENT_IDS) ?: longArrayOf())
                        .map { db.messageDao().byCanonicalId(it) }
                }
            if (!cachedMatch && activeEvents.none { it?.id == promoted.id }) return null

            val dickordBatch =
                synchronized(history) { dickordBatches[ordinary] }
                    ?: (dickordEnabled && isDickordChannel(buffer.displayName))
            val style = chatStyle(preview, BufferType.CHANNEL, dickordChannelLabel(buffer.displayName, dickordBatch))
            val keys = mutableListOf<NotificationMessageKey>()
            val remainingEvents = mutableListOf<MessageEntity>()
            val cachedRows =
                if (cachedMatch) {
                    cached.second.map { key ->
                        key.eventId?.let { db.messageDao().byCanonicalId(it) }?.also { row ->
                            check(db.bufferDao().canonicalId(row.bufferId) == buffer.id) { "Notification anchor belongs to another room" }
                        }
                    }
                } else {
                    activeEvents.forEach { event ->
                        event?.let {
                            check(db.bufferDao().canonicalId(it.bufferId) == buffer.id) { "Notification anchor belongs to another room" }
                        }
                    }
                    null
                }
            if (cachedMatch && cached.first.size == cached.second.size && cachedRows.orEmpty().all { it != null }) {
                cachedRows.orEmpty().forEachIndexed { index, event ->
                    val row = checkNotNull(event)
                    if (row.id != promoted.id && !row.alertsAsMention &&
                        foolPolicy.allowsNotification(row, BufferType.CHANNEL) &&
                        (readFloor == null || TimelineAnchor(row.serverTime, row.id, row.timelineOrder) > readFloor)
                    ) {
                        val key = NotificationMessageKey.from(row)
                        if (keys.none { it.matches(key) }) {
                            keys += key
                            remainingEvents += row
                            style.addMessage(cached.first[index])
                        }
                    }
                }
            } else {
                // Missing canonical identities cannot be classified from a displayed Person.
                // Rebuild only an ordinary row which is still posted and represented this event.
                val rows =
                    db
                        .messageDao()
                        .recentNotifiable(
                            buffer.id,
                            readFloor?.serverTime ?: Long.MIN_VALUE,
                            readFloor?.eventId ?: Long.MIN_VALUE,
                            promoted.id,
                            MAX_NOTIFICATION_MESSAGES,
                            false,
                        ).filter { foolPolicy.allowsNotification(it, BufferType.CHANNEL) }
                        .asReversed()
                rows.forEach { row ->
                    val key = NotificationMessageKey.from(row)
                    if (keys.none { it.matches(key) }) {
                        keys += key
                        remainingEvents += row
                        style.addChatMessage(preview, key, buffer.networkId, dickordBatch, settings.avatarStyle, identityRules)
                    }
                }
            }
            val latest =
                remainingEvents.maxByOrNull { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
            val anchor =
                latest?.let { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
                    ?: TimelineAnchor(promoted.serverTime, 0L)
            return OrdinaryPromotion(
                style,
                keys,
                dickordBatch,
                active.notification.baseChannelId,
                latest?.msgid,
                anchor,
            )
        }

        private fun MessageVisibilityPolicy.allowsNotification(
            message: MessageEntity,
            type: BufferType,
        ): Boolean =
            message.kind != MessageKind.REDACTED &&
                if (type == BufferType.SERVER) {
                    !isFool(message)
                } else {
                    allowsNotification(message.senderAccount, message.normalizedActor, type == BufferType.QUERY, message.alertsAsMention)
                }

        private fun chatStyle(
            preview: NotificationPreview,
            type: BufferType,
            title: String,
        ): NotificationCompat.MessagingStyle =
            NotificationCompat
                .MessagingStyle(Person.Builder().setName(context.getString(R.string.notification_projection_you)).build())
                .setConversationTitle(preview.showIdentity { title } ?: context.getString(R.string.notification_projection_message))
                .setGroupConversation(type == BufferType.CHANNEL)

        private fun NotificationCompat.MessagingStyle.addChatMessage(
            preview: NotificationPreview,
            key: NotificationMessageKey,
            networkId: Long,
            dickordBatch: Boolean,
            avatarStyle: AvatarStyle,
            identityRules: IrcIdentityRules,
        ) {
            val person =
                if (preview == NotificationPreview.GENERIC) {
                    Person.Builder().setName(context.getString(R.string.notification_projection_sender)).build()
                } else {
                    notificationPerson(networkId, key.sender, dickordNickLabel(key.sender, dickordBatch), avatarStyle, identityRules)
                }
            val text = if (preview == NotificationPreview.FULL) key.text else context.getString(R.string.notification_projection_message)
            addMessage(text, key.serverTime, person)
        }

        private fun notificationPerson(
            networkId: Long,
            name: String,
            displayName: String,
            style: AvatarStyle,
            identityRules: IrcIdentityRules,
        ): Person =
            Person
                .Builder()
                .setName(displayName)
                .setKey("irc:$networkId:${identityRules.normalize(name)}")
                .setIcon(notificationAvatarIcon(context, name, style))
                .build()

        private fun conversationShortcut(
            buffer: RoomEntity?,
            network: String?,
            preview: NotificationPreview,
            avatarStyle: AvatarStyle,
        ): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || preview == NotificationPreview.GENERIC ||
                buffer == null || buffer.type == BufferType.SERVER ||
                (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        androidx.core.content.ContextCompat
                            .checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                )
            ) {
                return null
            }
            val id = "$CONVERSATION_SHORTCUT_PREFIX${buffer.id}"
            val title = buffer.displayName.takeIf { it.isNotBlank() } ?: return null
            val longLabel = shortcutLongLabel(title, network)
            val shortcuts = conversationShortcuts()
            val watched = shortcutRooms.iterator()
            while (watched.hasNext()) {
                val (watchedId, job) = watched.next()
                if (shortcuts.none { it.id == watchedId }) {
                    watched.remove()
                    job.cancel()
                }
            }
            val existing = shortcuts.find { it.id == id && (it.isDynamic || it.isCached) }
            if (existing?.shortLabel?.toString() == title && existing.longLabel?.toString() == longLabel) {
                watchShortcutRoom(id)
                return id
            }

            val icon = notificationAvatarIcon(context, title, avatarStyle)
            val shortcut =
                ShortcutInfoCompat
                    .Builder(context, id)
                    .setIsConversation()
                    .setShortLabel(title)
                    .setLongLabel(longLabel)
                    .setIcon(icon)
                    .setPerson(
                        Person
                            .Builder()
                            .setName(title)
                            .setKey(id)
                            .setIcon(icon)
                            .build(),
                    ).setIntent(openBufferIntent(buffer.id, "motd://notification/conversation/${buffer.id}".toUri()))
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
                        }
                    }.build()
            val published = runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }.getOrDefault(false)
            if (!published && existing == null) return null
            watchShortcutRoom(id)
            return id
        }

        private fun conversationShortcuts(): List<ShortcutInfoCompat> =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                emptyList()
            } else {
                ShortcutManagerCompat
                    .getShortcuts(
                        context,
                        ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or
                            ShortcutManagerCompat.FLAG_MATCH_CACHED or ShortcutManagerCompat.FLAG_MATCH_PINNED,
                    ).filter { it.id.startsWith(CONVERSATION_SHORTCUT_PREFIX) }
            }

        private fun shortcutLongLabel(
            title: String,
            network: String?,
        ): String = network?.let { context.getString(R.string.notification_projection_shortcut_on_network, title, it) } ?: title

        private fun forgetConversationShortcuts(ids: List<String>) {
            if (ids.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            ids.forEach { shortcutRooms.remove(it)?.cancel() }
            val label = context.getString(R.string.notification_projection_message)
            val pinned = conversationShortcuts().filter { it.isPinned && it.id in ids }
            if (pinned.isNotEmpty()) {
                val sanitized =
                    pinned.map {
                        ShortcutInfoCompat
                            .Builder(context, it.id)
                            .setShortLabel(label)
                            .setLongLabel(label)
                            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_notification_motd))
                            .setPerson(
                                Person
                                    .Builder()
                                    .setName(label)
                                    .setKey(it.id)
                                    .build(),
                            ).setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                            .build()
                    }
                if (!ShortcutManagerCompat.updateShortcuts(context, sanitized)) {
                    sanitized.forEach { shortcut -> optionalMetadata { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) } }
                    val remaining =
                        context
                            .getSystemService(ShortcutManager::class.java)
                            .pinnedShortcuts
                            .filter { it.id in ids && (it.shortLabel.toString() != label || it.longLabel.toString() != label) }
                            .map { it.id }
                    if (remaining.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, remaining, label)
                }
            }
            ShortcutManagerCompat.removeDynamicShortcuts(context, ids)
            ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
        }

        private fun watchShortcutRoom(id: String) {
            val scope = applicationScope ?: return
            if (id in shortcutRooms) return
            val bufferId = id.removePrefix(CONVERSATION_SHORTCUT_PREFIX).toLongOrNull() ?: return
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    db.bufferDao().observe(bufferId).first { it?.id != bufferId || it.dismissed || it.pendingCloseAt != null }
                    synchronized(history) {
                        if (shortcutRooms[id] !== coroutineContext[Job]) return@synchronized
                        historyRevision++
                        clearThread(NotificationThreadKey(bufferId, false))
                        clearThread(NotificationThreadKey(bufferId, true))
                        forgetConversationShortcuts(listOf(id))
                    }
                }
            shortcutRooms[id] = job
            job.start()
        }

        private fun clearThread(thread: NotificationThreadKey) {
            history.remove(thread)
            historyKeys.remove(thread)
            dickordBatches.remove(thread)
            manager.cancel(if (thread.channelMentions) CHANNEL_MENTION_TAG else null, messageNotificationId(thread.bufferId))
        }

        override suspend fun onMessageRedacted(
            bufferId: Long,
            messageId: Long,
        ) {
            val event = db.messageDao().byCanonicalId(messageId)
            val canonicalId = event?.id ?: messageId
            val ids = (db.canonicalTimelineDao().losingEventIds(canonicalId) + canonicalId).toSet()
            synchronized(history) {
                historyRevision++
                historyKeys
                    .filter { (thread, keys) ->
                        keys.any { it.eventId in ids || (thread.bufferId == bufferId && event?.msgid != null && it.msgid == event.msgid) }
                    }.keys
                    .forEach(::clearThread)
                manager.activeNotifications
                    .filter { row ->
                        row.notification.baseChannelId in MESSAGE_CHANNELS &&
                            row.notification.extras
                                .getLongArray(EXTRA_NOTIFICATION_EVENT_IDS)
                                ?.any { it in ids } == true
                    }.forEach { manager.cancel(it.tag, it.id) }
            }
        }

        private suspend fun retireInvalidCanonicalCards() {
            val dao = db.messageDao()
            val ids = mutableSetOf<Long>()
            val mentionIds = mutableSetOf<Long>()
            for (entry in manager.activeNotifications) {
                val channel = entry.notification.baseChannelId
                if (channel !in MESSAGE_CHANNELS) continue
                val eventIds = entry.notification.extras.getLongArray(EXTRA_NOTIFICATION_EVENT_IDS) ?: continue
                for (eventId in eventIds) {
                    ids += eventId
                    if (channel == CHANNEL_MENTIONS) mentionIds += eventId
                }
            }
            val mentionRooms = mutableSetOf<Long>()
            for (id in ids) {
                val event = dao.byCanonicalId(id) ?: continue
                if (event.kind == MessageKind.REDACTED) {
                    onMessageRedacted(event.bufferId, event.id)
                } else if (id in mentionIds) {
                    mentionRooms += event.bufferId
                }
            }
            mentionRooms.forEach { onMentionAlertChanged(it) }
        }

        override suspend fun onRead(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            // Invalidate a staged post even when it has not published any tracked IDs yet.
            synchronized(history) { historyRevision++ }
            for (thread in listOf(NotificationThreadKey(bufferId, false), NotificationThreadKey(bufferId, true))) {
                while (true) {
                    val (revision, inMemoryIds) =
                        synchronized(history) {
                            historyRevision to historyKeys[thread].orEmpty().mapNotNull { it.eventId }
                        }
                    val tag = if (thread.channelMentions) CHANNEL_MENTION_TAG else null
                    val activeIds =
                        runCatching {
                            manager.activeNotifications
                                .firstOrNull { it.id == messageNotificationId(bufferId) && it.tag == tag }
                                ?.notification
                                ?.extras
                                ?.getLongArray(EXTRA_NOTIFICATION_EVENT_IDS)
                                ?.toList()
                                .orEmpty()
                        }.getOrDefault(emptyList())
                    val trackedIds = (inMemoryIds + activeIds).distinct()
                    if (trackedIds.isEmpty()) break
                    val latest = resolveLatestNotificationAnchor(db, bufferId, trackedIds) ?: break
                    if (!readMarkerCoversNotification(anchor, latest)) break
                    val cleared =
                        synchronized(history) {
                            if (historyRevision != revision) {
                                false
                            } else {
                                historyRevision++
                                clearThread(thread)
                                true
                            }
                        }
                    if (!cleared) continue
                    diagnostics.record("notifications", "message_notification_cleared") {
                        mapOf(
                            "buffer_id" to bufferId,
                            "up_to_time" to anchor.serverTime,
                            "up_to_event_id" to anchor.eventId,
                        )
                    }
                    break
                }
            }
        }

        override suspend fun onRoomsMerged(
            winnerId: Long,
            loserId: Long,
        ) {
            synchronized(history) {
                historyRevision++
                pruneAlertWindows(SystemClock.elapsedRealtime())
                val loserTime = lastEligiblePost.remove(loserId)
                val winnerTime = lastEligiblePost[winnerId]
                if (loserTime != null && (winnerTime == null || loserTime > winnerTime)) {
                    recordEligiblePost(winnerId, loserTime)
                }
                if (alertWindowBoot != null) alertWindowPrefs.edit { remove("buffer:$loserId") }
                clearThread(NotificationThreadKey(loserId, false))
                clearThread(NotificationThreadKey(loserId, true))
                forgetConversationShortcuts(listOf("$CONVERSATION_SHORTCUT_PREFIX$loserId"))
            }
            onMentionAlertChanged(winnerId)
            diagnostics.record("notifications", "room_notification_retired") {
                mapOf("winner_id" to winnerId, "loser_id" to loserId)
            }
        }

        override suspend fun onMentionAlertChanged(bufferId: Long) {
            synchronized(history) { historyRevision++ }
            while (true) {
                val (preview, revision) = previewSnapshot()
                val room =
                    db
                        .bufferDao()
                        .observeById(bufferId)
                        ?.takeIf { it.id == bufferId && it.type != BufferType.SERVER } ?: return
                val thread = NotificationThreadKey(bufferId, channelMentions = room.type == BufferType.CHANNEL)
                val tag = if (thread.channelMentions) CHANNEL_MENTION_TAG else null
                val active =
                    manager.activeNotifications.firstOrNull {
                        it.id == messageNotificationId(bufferId) && it.tag == tag && it.notification.baseChannelId == CHANNEL_MENTIONS
                    } ?: return
                val ids = active.notification.extras.getLongArray(EXTRA_NOTIFICATION_EVENT_IDS) ?: return
                val messages = db.messageDao()
                val rows = ids.map { messages.byCanonicalId(it)?.takeIf { row -> row.bufferId == bufferId && row.kind != MessageKind.REDACTED } }
                val projected =
                    if (thread.channelMentions) {
                        null
                    } else {
                        val channelEventId = active.notification.extras.getLong(EXTRA_NOTIFICATION_CHANNEL_EVENT_ID)
                        rows.getOrNull(ids.indexOf(channelEventId))
                            ?: rows.filterNotNull().maxByOrNull { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
                    }
                if (rows.none { it != null && (!it.eligibleIn(room) || (thread.channelMentions && !it.alertsAsMention)) } &&
                    (thread.channelMentions || projected?.alertsAsMention != false)
                ) {
                    return
                }

                fun cardStillActive(): Boolean = manager.activeNotifications.any { it.id == active.id && it.tag == active.tag && it.postTime == active.postTime }

                val settings =
                    try {
                        settingsRepository.settings.first()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                val identityRules = db.networkIdentityDao().byNetwork(room.networkId)?.identityRules ?: IrcIdentityRules()
                val policy = settings?.let { MessageVisibilityPolicy(MessageVisibilitySpec.from(it), identityRules) }
                val remaining =
                    if (policy == null) {
                        emptyList()
                    } else {
                        val readAnchor = effectiveLocalReadAnchor(room)
                        rows
                            .filterNotNull()
                            .filter {
                                it.eligibleIn(room) &&
                                    (!thread.channelMentions || it.alertsAsMention) &&
                                    (readAnchor == null || TimelineAnchor(it.serverTime, it.id, it.timelineOrder) > readAnchor) &&
                                    policy.allowsNotification(it, room.type)
                            }.distinctBy { it.id }
                    }
                if (remaining.isEmpty()) {
                    val cleared =
                        commitPreview(preview, revision) {
                            if (cardStillActive()) {
                                historyRevision++
                                clearThread(thread)
                            }
                        }
                    if (cleared != null) return
                    continue
                }

                val avatarStyle = checkNotNull(settings).avatarStyle
                val dickordBatch =
                    synchronized(history) { dickordBatches[thread] }
                        ?: (dickordLabsPrefs.enabled.first() && isDickordChannel(room.displayName))
                val title = if (thread.channelMentions) room.ircTarget else dickordChannelLabel(room.displayName, dickordBatch)
                val style = chatStyle(preview, room.type, title)
                val keys = remaining.mapTo(mutableListOf(), NotificationMessageKey::from)
                keys.forEach { style.addChatMessage(preview, it, room.networkId, dickordBatch, avatarStyle, identityRules) }
                val latest = remaining.maxBy { TimelineAnchor(it.serverTime, it.id, it.timelineOrder) }
                val projection = remaining.firstOrNull { it.id == projected?.id } ?: latest
                val channel = if (projection.alertsAsMention) CHANNEL_MENTIONS else CHANNEL_MESSAGES
                val network = networkLabel(room.networkId, preview)
                if (readPreview() != preview) continue
                val committed =
                    commitPreview(preview, revision) {
                        if (!cardStillActive()) {
                            return@commitPreview false
                        }
                        val shortcut = conversationShortcut(room, network, preview, avatarStyle)
                        val replacement =
                            buildMessageNotification(
                                thread,
                                room.type,
                                channel,
                                style,
                                keys.mapNotNull { it.eventId }.toLongArray(),
                                latest.msgid,
                                latest.serverTime,
                                latest.id,
                                preview,
                                network,
                                shortcut,
                                silent = true,
                                channelEventId = projection.id.takeIf { room.type == BufferType.QUERY && projection.alertsAsMention },
                            )
                        historyRevision++
                        history[thread] = style
                        historyKeys[thread] = keys
                        dickordBatches[thread] = dickordBatch
                        if (channel != CHANNEL_MENTIONS) manager.cancel(active.tag, active.id)
                        postMessageNotification(thread, replacement).also { posted ->
                            if (!posted) clearThread(thread)
                        }
                    }
                if (committed != null) return
            }
        }

        private fun MessageEntity.eligibleIn(room: RoomEntity): Boolean = !isSelf && !failed && notificationEligible && (!room.muted || notificationWatched)

        override suspend fun onInvitation(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
        ) = postInvitation(networkId, bufferId, messageId, silent = false)

        override suspend fun onRecoveredInvitation(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
        ) = postInvitation(networkId, bufferId, messageId, silent = true)

        private suspend fun postInvitation(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
            silent: Boolean,
        ) {
            val message = db.messageDao().byCanonicalId(messageId) ?: return
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            postAuxiliary(invitationNotificationId(message.id), networkId) { preview, network ->
                val destination = "motd://notification/invitation/${message.id}"
                val contentIntent = openBufferPendingIntent(buffer.id, "$destination/open", message)
                val dismissIntent =
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, InviteReceiver::class.java)
                            .setAction(InviteReceiver.ACTION_DISMISS)
                            .setData("$destination/dismiss".toUri())
                            .putExtra(InviteReceiver.EXTRA_MESSAGE_ID, message.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val joinIntent =
                    PendingIntent.getActivity(
                        context,
                        0,
                        openBufferIntent(buffer.id, "$destination/join".toUri(), message)
                            .setAction(ACTION_ACCEPT_INVITE)
                            .putExtra(EXTRA_INVITE_MESSAGE_ID, message.id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val title =
                    preview.showIdentity { context.getString(R.string.notification_projection_invitation_to, buffer.displayName) }
                        ?: context.getString(R.string.notification_projection_invitation)
                previewBuilder(CHANNEL_INVITATIONS, preview, R.string.notification_projection_invitation)
                    .setContentTitle(title)
                    .setContentText(
                        if (preview == NotificationPreview.IDENTITY) {
                            context.getString(R.string.notification_projection_invitation_from, message.sender)
                        } else {
                            null
                        },
                    ).setSubText(network)
                    .previewBody(preview, message.text)
                    .setContentIntent(contentIntent)
                    .setDeleteIntent(dismissIntent)
                    .setSilent(silent)
                    .setAutoCancel(true)
                    .addAction(android.R.drawable.ic_menu_add, context.getString(R.string.notification_projection_join), joinIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.notification_projection_dismiss), dismissIntent)
                    .build()
            }
        }

        suspend fun watchEnded(bufferId: Long) {
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            postAuxiliary(watchEndedNotificationId(buffer.id), buffer.networkId) { preview, network ->
                val text =
                    preview.showIdentity { context.getString(R.string.notification_watch_ended, buffer.displayName) }
                        ?: context.getString(R.string.notification_projection_watch_ended)
                previewBuilder(CHANNEL_MESSAGES, preview, R.string.notification_watch_ended_title)
                    .setContentTitle(context.getString(R.string.notification_watch_ended_title))
                    .setContentText(text)
                    .setSubText(network)
                    .setContentIntent(openBufferPendingIntent(buffer.id, "motd://notification/watch/${buffer.id}"))
                    .setLocalOnly(true)
                    .setSilent(true)
                    .setAutoCancel(true)
                    .build()
            }
        }

        override suspend fun onInvitationResolved(messageId: Long) {
            val dao = db.canonicalTimelineDao()
            val canonicalId = dao.canonicalEventId(messageId)
            val ids = dao.losingEventIds(canonicalId) + canonicalId
            synchronized(history) {
                manager.activeNotifications
                    .filter { row ->
                        if (row.notification.channelId != CHANNEL_INVITATIONS) return@filter false
                        val legacy = row.notification.extras.getString(EXTRA_PREVIEW) == null
                        ids.any { id -> row.id == if (legacy) legacyInvitationNotificationId(id) else invitationNotificationId(id) }
                    }.forEach { manager.cancel(it.tag, it.id) }
            }
        }

        override suspend fun onDccTransferOffer(
            networkId: Long,
            bufferId: Long,
            messageId: Long,
            silent: Boolean,
        ) {
            val message = db.messageDao().byCanonicalId(messageId) ?: return
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            val foreground = foregroundBufferTracker.foregroundBufferId.value == buffer.id
            val incomingAnchor = TimelineAnchor(message.serverTime, message.id, message.timelineOrder)
            val alreadyRead = effectiveLocalReadAnchor(buffer)?.let { incomingAnchor <= it } == true
            if (foreground || buffer.muted || alreadyRead) return

            val canPost =
                postAuxiliary(transferNotificationId(message.id), networkId) { preview, network ->
                    val contentIntent = openBufferPendingIntent(buffer.id, "motd://notification/transfer/${message.id}", message)
                    val title =
                        preview.showIdentity { context.getString(R.string.notification_projection_offer_from, message.sender) }
                            ?: context.getString(R.string.notification_projection_offer)
                    previewBuilder(CHANNEL_TRANSFERS, preview, R.string.notification_projection_offer)
                        .setContentTitle(title)
                        .setSubText(network)
                        .previewBody(preview, message.text)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setSilent(silent)
                        .setAutoCancel(true)
                        .setContentIntent(contentIntent)
                        .addAction(android.R.drawable.ic_menu_save, context.getString(R.string.notification_projection_open_to_save), contentIntent)
                        .build()
                }
            diagnostics.record("notifications", "dcc_transfer_post_finished") {
                mapOf(
                    "network_id" to networkId,
                    "buffer_id" to bufferId,
                    "event_id" to message.id,
                    "permission" to canPost,
                )
            }
        }

        private fun NotificationCompat.Builder.previewBody(
            preview: NotificationPreview,
            body: String,
        ): NotificationCompat.Builder = if (preview == NotificationPreview.FULL) setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)) else this

        private suspend fun postAuxiliary(
            id: Int,
            networkId: Long?,
            build: (NotificationPreview, String?) -> Notification,
        ): Boolean {
            while (true) {
                val (preview, revision) = previewSnapshot()
                val network = networkId?.let { networkLabel(it, preview) }
                if (networkId != null && preview != NotificationPreview.GENERIC && readPreview() != preview) continue
                val posted =
                    commitPreview(preview, revision) {
                        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            manager.notify(id, build(preview, network))
                            true
                        } else {
                            false
                        }
                    }
                if (posted != null) return posted
            }
        }

        private fun openBufferIntent(
            bufferId: Long,
            destination: Uri,
            message: MessageEntity? = null,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_BUFFER)
                .setData(destination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_BUFFER_ID, bufferId)
                .apply {
                    if (message != null) {
                        putExtra(EXTRA_JUMP_MSGID, message.msgid)
                        putExtra(EXTRA_JUMP_TIME, message.serverTime)
                        putExtra(EXTRA_EVENT_ID, message.id)
                    }
                }

        private fun openBufferPendingIntent(
            bufferId: Long,
            destination: String,
            message: MessageEntity? = null,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                openBufferIntent(bufferId, destination.toUri(), message),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        // -- failed notification replies --

        /** A pre-persistence rejection can safely offer a text-based manual retry. */
        suspend fun onReplyFailed(
            bufferId: Long,
            text: String,
            reason: SendRejectionReason,
        ) = postReplyFailure(bufferId, text, context.getString(sendRejectionText(reason)), allowRetry = true)

        /** Durable rows may already have reached the server; require review instead of resending text. */
        suspend fun onReplyDeliveryUncertain(
            bufferId: Long,
            text: String,
        ) = postReplyFailure(
            bufferId,
            text,
            context.getString(R.string.notification_projection_send_unknown_explanation),
            allowRetry = false,
        )

        private suspend fun postReplyFailure(
            bufferId: Long,
            text: String,
            explanation: String,
            allowRetry: Boolean,
        ) {
            val buffer = optionalMetadata { db.bufferDao().observeById(bufferId) }
            val canPost =
                postAuxiliary(sendFailureNotificationId(bufferId), buffer?.networkId) { preview, network ->
                    val genericTitle =
                        if (allowRetry) R.string.notification_projection_send_failed else R.string.notification_projection_send_unknown
                    val title =
                        preview.showIdentity {
                            if (allowRetry) {
                                buffer?.displayName?.let { context.getString(R.string.notification_projection_send_failed_to, it) }
                            } else {
                                null
                            }
                        } ?: context.getString(genericTitle)
                    val destination = "motd://notification/send-failure/$bufferId"
                    val notification =
                        previewBuilder(CHANNEL_SEND_FAILURES, preview, genericTitle)
                            .setContentTitle(title)
                            .setSubText(network)
                            .setContentText(explanation)
                            .previewBody(preview, "$explanation\n$text")
                            .setCategory(NotificationCompat.CATEGORY_ERROR)
                            .setLocalOnly(true)
                            .setAutoCancel(true)
                            .setContentIntent(openBufferPendingIntent(bufferId, "$destination/open"))
                    if (allowRetry) {
                        notification.extras.putBoolean(EXTRA_SERVICE_ACTIONS, true)
                        val retryIntent =
                            PendingIntent.getService(
                                context,
                                0,
                                Intent(context, NotificationActionService::class.java)
                                    .setAction(NotificationActionService.ACTION_RETRY_REPLY)
                                    .setData("$destination/retry".toUri())
                                    .putExtra(NotificationActionService.EXTRA_BUFFER_ID, bufferId)
                                    .putExtra(NotificationActionService.EXTRA_REPLY_TEXT, text),
                                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                            )
                        notification.addAction(
                            NotificationCompat.Action
                                .Builder(android.R.drawable.ic_menu_send, context.getString(R.string.notification_projection_retry), retryIntent)
                                .setAuthenticationRequired(true)
                                .build(),
                        )
                    }
                    notification.build()
                }
            diagnostics.record("notifications", "reply_failure_post_finished") {
                mapOf(
                    "buffer_id" to bufferId,
                    "allow_retry" to allowRetry,
                    "body_fp" to diagnostics.fingerprint(text),
                    "permission" to canPost,
                )
            }
        }

        /** Retire the failure notice once the same text is accepted on the wire. */
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
            internal const val CHANNEL_STATUS = "status"
            const val CHANNEL_MESSAGES = "messages"
            const val CHANNEL_MENTIONS = "mentions"
            const val CHANNEL_INVITATIONS = "invitations"
            const val CHANNEL_TRANSFERS = "transfers"
            const val CHANNEL_SEND_FAILURES = "send_failures"
            private const val CHANNEL_MENTION_TAG = "channel_mentions"
            private const val MAX_NOTIFICATION_MESSAGES = 25
            private const val ALERT_QUIET_WINDOW_MS = 30_000L
            private const val PREVIEW_RETRY_MS = 1_000L
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
            private const val CONVERSATION_SHORTCUT_PREFIX = "motd:chat:"
            private const val EXTRA_MESSAGE_CHANNEL = "motd.messageChannel"
            private const val MAX_RECOVERY_NOTIFICATIONS = 200

            /** Recovery only re-presents events this close to the newest stored one; older ones are stale. */
            private const val RECOVERY_WINDOW_MS = 48L * 60 * 60 * 1000

            /** ...and never looks past this many of the newest events, however busy the last 48h were. */
            private const val RECOVERY_SCAN_ROWS = 5_000

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
             * ranges (invitations occupy `0x40000000..0x4fffffff`, transfers `0x50000000..0x5fffffff`).
             */
            internal fun messageNotificationId(bufferId: Long): Int = MESSAGE_ID_NAMESPACE or (bufferId xor (bufferId ushr 32)).toInt().and(0x0fffffff)

            /** True only for ids minted by [messageNotificationId]; used to retire legacy raw ids. */
            internal fun isMessageNotificationId(id: Int): Boolean = (id and NAMESPACE_MASK) == MESSAGE_ID_NAMESPACE

            internal fun invitationNotificationId(messageId: Long): Int = INVITATION_ID_NAMESPACE or (messageId xor (messageId ushr 32)).toInt().and(0x0fffffff)

            private fun legacyInvitationNotificationId(messageId: Long): Int = INVITATION_ID_NAMESPACE or (messageId xor (messageId ushr 32)).toInt().and(0x3fffffff)

            internal fun transferNotificationId(messageId: Long): Int = 0x50000000 or (messageId xor (messageId ushr 32)).toInt().and(0x0fffffff)

            /** Failure notices are keyed by buffer too, and must not alias any other range. */
            internal fun sendFailureNotificationId(bufferId: Long): Int = 0x20000000 or (bufferId xor (bufferId ushr 32)).toInt().and(0x0fffffff)

            internal fun watchEndedNotificationId(bufferId: Long): Int = WATCH_ENDED_NAMESPACE or (bufferId xor (bufferId ushr 32)).toInt().and(0x0fffffff)

            private fun isWatchEndedNotificationId(id: Int): Boolean = (id and NAMESPACE_MASK) == WATCH_ENDED_NAMESPACE

            private const val MESSAGE_ID_NAMESPACE = 0x10000000
            private const val NAMESPACE_MASK = -0x10000000 // 0xf0000000
            private const val WATCH_ENDED_NAMESPACE = 0x30000000
            private const val INVITATION_ID_NAMESPACE = 0x40000000
            private const val TEST_ALERT_TAG = "local_test_alert"
            private const val TEST_ALERT_ID = 0x60000001

            private fun isTestAlert(
                tag: String?,
                id: Int,
            ): Boolean = tag == TEST_ALERT_TAG && id == TEST_ALERT_ID
        }
    }

private fun sendRejectionText(reason: SendRejectionReason): Int =
    when (reason) {
        SendRejectionReason.NOT_IN_CHANNEL -> R.string.chat_not_in_channel

        SendRejectionReason.CONNECTION_UNAVAILABLE -> R.string.notification_projection_send_no_connection

        SendRejectionReason.BUFFER_NOT_FOUND -> R.string.notification_projection_send_no_chat

        SendRejectionReason.UNSUPPORTED_BUFFER -> R.string.notification_projection_send_unsupported

        SendRejectionReason.INVALID_CONTENT,
        SendRejectionReason.EVENT_NOT_RETRYABLE,
        SendRejectionReason.PERSISTENCE_FAILED,
        -> R.string.chat_send_rejected
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
private const val EXTRA_NOTIFICATION_CHANNEL_EVENT_ID = "motd.notificationChannelEventId"

private const val EXTRA_PREVIEW = "motd.notificationPreview"
private const val EXTRA_SERVICE_ACTIONS = "motd.notificationServiceActions"
