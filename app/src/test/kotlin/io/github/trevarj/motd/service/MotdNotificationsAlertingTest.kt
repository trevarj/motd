package io.github.trevarj.motd.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executor

/**
 * Pins the channel-governed alerting contract for message/mention notifications:
 *
 * - the first presentation of a newly notified body alerts through its notification channel
 *   (heads-up, per-channel sound/vibration, and Do Not Disturb are user-governed);
 * - a crash-recovery repost from [MotdNotifications.recoverCanonicalNotifications] never
 *   re-alerts for state the user may already have been alerted about;
 * - an identity-upgrade re-post of an already-notified body (transient msgid-less push followed
 *   by the durable canonical row) never re-alerts either.
 */
@RunWith(RobolectricTestRunner::class)
class MotdNotificationsAlertingTest {
    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var networkId: Long = 0
    private var dmBufferId: Long = 0

    @Volatile private var beforeQuery: ((String, List<Any?>) -> Unit)? = null

    @Volatile private var beforeDatabaseQuery: (() -> Unit)? = null

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() =
        runTest {
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .openHelperFactory(
                        object : SupportSQLiteOpenHelper.Factory {
                            override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
                                val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
                                return object : SupportSQLiteOpenHelper by helper {
                                    override val writableDatabase: SupportSQLiteDatabase
                                        get() = interceptQueries(helper.writableDatabase)
                                    override val readableDatabase: SupportSQLiteDatabase
                                        get() = interceptQueries(helper.readableDatabase)
                                }
                            }
                        },
                    ).setQueryCallback(
                        { sql, args -> beforeQuery?.invoke(sql, args) },
                        Executor(Runnable::run),
                    ).build()
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            dmBufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "alert-peer",
                        displayName = "alert-peer",
                        type = BufferType.QUERY,
                    ),
                )
            shadowOf(context as android.app.Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            repo = DataStoreSettingsRepository(context)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
        }

    @After
    fun tearDown() {
        db.close()
    }

    private fun interceptQueries(database: SupportSQLiteDatabase): SupportSQLiteDatabase =
        object : SupportSQLiteDatabase by database {
            override fun query(query: SupportSQLiteQuery): Cursor = query(query, null)

            override fun query(
                query: SupportSQLiteQuery,
                cancellationSignal: CancellationSignal?,
            ): Cursor {
                beforeDatabaseQuery?.invoke()
                return if (cancellationSignal == null) database.query(query) else database.query(query, cancellationSignal)
            }
        }

    private fun chat(
        nick: String,
        text: String,
        msgid: String? = null,
        serverTime: Long = 1_000,
    ) = IrcEvent.ChatMessage(
        ctx =
            MessageContext(
                msgid = msgid,
                serverTime = serverTime,
                account = null,
                batchId = null,
                label = null,
            ),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(nick),
        target = "me",
        text = text,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun postedNotifications() =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications

    private fun lane(tag: String?) = postedNotifications().single { it.tag == tag }.notification

    private fun bodies(notification: android.app.Notification) =
        NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
            ?.messages
            ?.map { it.text.toString() }
            .orEmpty()

    private suspend fun TestScope.allSettings(): NotificationSettingsImpl =
        NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {}).also {
            it.setServer(networkId, NotificationMode.ALL)
        }

    private suspend fun channel(
        name: String = "#chan",
        displayName: String = name,
    ): Long =
        db.bufferDao().insert(
            BufferEntity(networkId = networkId, name = name, displayName = displayName, type = BufferType.CHANNEL),
        )

    private fun assertLaneAnchor(
        notification: android.app.Notification,
        event: MessageEntity,
    ) {
        val read = shadowOf(notification.actions.single { it.title.toString() == "Mark read" }.actionIntent).savedIntent
        assertEquals(event.serverTime, read.getLongExtra(ReplyReceiver.EXTRA_UP_TO_TIME, -1))
        assertEquals(event.id, read.getLongExtra(ReplyReceiver.EXTRA_UP_TO_EVENT_ID, -1))
        val open = shadowOf(notification.contentIntent).savedIntent
        assertEquals(event.serverTime, open.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, -1))
        assertEquals(event.id, open.getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1))
        assertEquals(event.msgid, open.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID))
    }

    private fun assertMessageActions(
        notification: Notification,
        bufferId: Long,
        tag: String?,
        automotiveMessaging: Boolean,
    ) {
        assertEquals(!automotiveMessaging, notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals(listOf("Reply", "Mark read"), notification.actions.map { it.title.toString() })
        val reply = requireNotNull(NotificationCompat.getAction(notification, 0))
        val read = requireNotNull(NotificationCompat.getAction(notification, 1))
        assertEquals(
            if (automotiveMessaging) NotificationCompat.Action.SEMANTIC_ACTION_REPLY else NotificationCompat.Action.SEMANTIC_ACTION_NONE,
            reply.semanticAction,
        )
        assertEquals(
            if (automotiveMessaging) NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ else NotificationCompat.Action.SEMANTIC_ACTION_NONE,
            read.semanticAction,
        )
        assertEquals(!automotiveMessaging, reply.showsUserInterface)
        assertEquals(!automotiveMessaging, read.showsUserInterface)
        val input = requireNotNull(reply.remoteInputs).single()
        assertEquals(ReplyReceiver.KEY_REPLY, input.resultKey)
        assertTrue(input.allowFreeFormInput)
        assertTrue(read.remoteInputs.isNullOrEmpty())
        val replyPending = shadowOf(reply.actionIntent)
        val readPending = shadowOf(read.actionIntent)
        assertTrue(replyPending.flags and PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(replyPending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(readPending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val lane = tag ?: "default"
        for ((pending, action, suffix) in listOf(
            Triple(replyPending, ReplyReceiver.ACTION_REPLY, "reply"),
            Triple(readPending, ReplyReceiver.ACTION_MARK_READ, "read"),
        )) {
            val intent = pending.savedIntent
            assertEquals(ReplyReceiver::class.java.name, intent.component?.className)
            assertEquals(action, intent.action)
            assertEquals(bufferId, intent.getLongExtra(ReplyReceiver.EXTRA_BUFFER_ID, -1))
            assertEquals("motd://notification/$bufferId/$lane/$suffix", intent.dataString)
        }
        val openPending = shadowOf(notification.contentIntent)
        assertTrue(openPending.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val open = openPending.savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, open.action)
        assertEquals(bufferId, open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
        assertEquals("motd://notification/$bufferId/$lane/open", open.dataString)
    }

    /** NotificationCompat implements setSilent(true) as the "silent" group + summary-only alerts. */
    private fun assertSilent(notification: android.app.Notification) {
        assertEquals("silent", notification.group)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
    }

    private fun assertAlerting(notification: android.app.Notification) {
        assertNull(notification.group)
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
    }

    private suspend fun mutedChannel(name: String = "#chan"): Long =
        db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = name,
                displayName = name,
                type = BufferType.CHANNEL,
                muted = true,
            ),
        )

    private val interruptedNotifier =
        object : MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                error("simulated interrupted presentation")
            }
        }

    @Test
    fun pushFirstWatchedMention_onMutedChannel_alertsOnlyOnce() =
        runTest {
            val channelId = mutedChannel()
            val watch = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            watch.startWatch(channelId, null)
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat("alert-peer", "me: watched ping", msgid = "watched-push").copy(target = "#chan")

            processor.processPush(networkId, message)

            val posted = postedNotifications().single().notification
            assertEquals(MotdNotifications.CHANNEL_MENTIONS, posted.channelId)
            assertAlerting(posted)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertEquals(listOf(message.text), style?.messages?.map { it.text.toString() })

            // A dismissed push must not reappear when its live duplicate arrives.
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            processor.process(networkId, message)
            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun interruptedWatchedMessages_afterWatchEnds_restoreHistoryAndRecoverSilentlyOnce() =
        runTest {
            val channelId = mutedChannel()
            val watch = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            watch.startWatch(channelId, null)
            val live = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = watch)
            live.onRegistered(networkId, "me", emptyMap())
            live.process(
                networkId,
                chat("alert-peer", "already presented", "watch-first").copy(target = "#chan"),
            )
            assertAlerting(postedNotifications().single().notification)

            val interrupted = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, notificationSettings = watch)
            interrupted.onRegistered(networkId, "me", emptyMap())
            interrupted.process(
                networkId,
                chat("alert-peer", "ordinary interrupted", "watch-ordinary", 2_000).copy(target = "#chan"),
            )
            interrupted.process(
                networkId,
                chat("alert-peer", "waves", "watch-action", 3_000).copy(target = "#chan", kind = IrcEvent.ChatKind.ACTION),
            )
            interrupted.processPush(
                networkId,
                chat("alert-peer", "me: interrupted mention", "watch-mention", 4_000).copy(target = "#chan"),
            )
            watch.stopWatch(channelId)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)

            notifications.recoverCanonicalNotifications()

            assertEquals(setOf(null, "channel_mentions"), postedNotifications().map { it.tag }.toSet())
            postedNotifications().forEach { assertSilent(it.notification) }
            assertEquals(
                listOf("already presented", "ordinary interrupted", "waves").sorted(),
                bodies(lane(null)).sorted(),
            )
            assertEquals(listOf("me: interrupted mention"), bodies(lane("channel_mentions")))
            assertMessageActions(lane(null), channelId, null, automotiveMessaging = false)
            assertMessageActions(lane("channel_mentions"), channelId, "channel_mentions", automotiveMessaging = true)
            assertLaneAnchor(lane(null), requireNotNull(db.messageDao().byMsgid(channelId, "watch-action")))
            assertLaneAnchor(lane("channel_mentions"), requireNotNull(db.messageDao().byMsgid(channelId, "watch-mention")))
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            notifications.recoverCanonicalNotifications()
            live.process(
                networkId,
                chat("alert-peer", "me: interrupted mention", "watch-mention", 4_000).copy(target = "#chan"),
            )
            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun watchedRecovery_survivesHistoryIdentityUpgradeAndRoomCoalescence() =
        runTest {
            val winnerId = mutedChannel("#renamed")
            val channelId = mutedChannel()
            val watch = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            val processor = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, notificationSettings = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat("alert-peer", "survives coalescence").copy(target = "#chan")
            processor.process(
                networkId,
                IrcEvent.HistoryBatch("#renamed", listOf(message.copy(target = "#renamed"))),
            )
            watch.startWatch(channelId, null)
            processor.process(networkId, message)
            watch.stopWatch(channelId)
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(message.copy(ctx = message.ctx.copy(msgid = "upgraded-watch"))),
                ),
            )
            BufferStore(db).mergeRooms(winnerId, channelId)

            notifications.recoverCanonicalNotifications()

            assertEquals(1, db.messageDao().countForBuffer(winnerId))
            val recovered = postedNotifications().single().notification
            assertSilent(recovered)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(recovered)
            assertEquals(listOf(message.text), style?.messages?.map { it.text.toString() })
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            notifications.recoverCanonicalNotifications()
            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun inheritedAll_recoveryRestoresContextSilentlyOnceWithoutBypassingMute() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#all", displayName = "#all", type = BufferType.CHANNEL),
                )
            val mutedId = mutedChannel("#muted")
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            settings.setServer(networkId, NotificationMode.ALL)
            val live = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            live.onRegistered(networkId, "me", emptyMap())
            live.process(
                networkId,
                IrcEvent.HistoryBatch("#all", listOf(chat("alert-peer", "history context", "all-history").copy(target = "#all"))),
            )
            live.process(networkId, chat("alert-peer", "first all", "all-first", 2_000).copy(target = "#all"))
            live.process(networkId, chat("alert-peer", "muted first", "all-muted-first", 2_000).copy(target = "#muted"))
            assertEquals(1, postedNotifications().size)
            assertAlerting(postedNotifications().single().notification)

            val interrupted = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, notificationSettings = settings)
            interrupted.onRegistered(networkId, "me", emptyMap())
            interrupted.process(networkId, chat("alert-peer", "interrupted all", "all-interrupted", 3_000).copy(target = "#all"))
            interrupted.process(networkId, chat("alert-peer", "muted interrupted", "all-muted", 3_000).copy(target = "#muted"))
            settings.setServer(networkId, NotificationMode.OFF)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)

            notifications.recoverCanonicalNotifications()

            val recovered = postedNotifications().single().notification
            assertSilent(recovered)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(recovered)
            assertEquals(listOf("history context", "first all", "interrupted all"), style?.messages?.map { it.text.toString() })
            val unmuted = requireNotNull(db.messageDao().byMsgid(channelId, "all-interrupted"))
            val muted = requireNotNull(db.messageDao().byMsgid(mutedId, "all-muted"))
            assertEquals(true, unmuted.notificationEligible)
            assertEquals(true, unmuted.notificationHandled)
            assertEquals(false, unmuted.notificationWatched)
            assertEquals(true, muted.notificationEligible)
            assertEquals(true, muted.notificationHandled)
            assertEquals(false, muted.notificationWatched)
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
            notifications.recoverCanonicalNotifications()
            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun currentWatch_doesNotMakeEarlierMessagesOrPlaybackRecoverable() =
        runTest {
            val channelId = mutedChannel()
            val watch = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            val processor = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, notificationSettings = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(
                networkId,
                chat("alert-peer", "before watch", "unwatched-live").copy(target = "#chan"),
            )
            processor.processPush(
                networkId,
                chat("alert-peer", "me: before watch", "unwatched-mention").copy(target = "#chan"),
            )
            watch.startWatch(channelId, null)
            processor.processPush(
                networkId,
                chat("alert-peer", "pushed ordinary", "watched-push-ordinary").copy(target = "#chan"),
            )
            processor.process(
                networkId,
                IrcEvent.HistoryBatch(
                    "#chan",
                    listOf(chat("alert-peer", "historical", "watched-history").copy(target = "#chan")),
                ),
            )
            processor.process(
                networkId,
                IrcEvent.PlaybackBatch(
                    source = IrcEvent.PlaybackSource.ZNC_PLAYBACK,
                    target = "#chan",
                    items =
                        listOf(
                            IrcEvent.PlaybackItem.from(
                                chat("alert-peer", "replayed", "watched-replay").copy(target = "#chan"),
                                ordinal = 0,
                            ),
                        ),
                ),
            )
            processor.process(
                networkId,
                chat("alert-peer", "notice", "watched-notice").copy(target = "#chan", kind = IrcEvent.ChatKind.NOTICE),
            )
            processor.process(
                networkId,
                chat("me", "me: self", "watched-self").copy(target = "#chan", isSelf = true),
            )

            notifications.recoverCanonicalNotifications()

            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun firstPresentation_ofDm_alertsThroughMessagesChannel() =
        runTest {
            notifications.onIncoming(
                networkId = networkId,
                bufferId = dmBufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = chat("alert-peer", "hello"),
            )

            val posted = postedNotifications().single().notification
            assertEquals(MotdNotifications.CHANNEL_MESSAGES, posted.channelId)
            assertAlerting(posted)
            assertEquals(listOf("hello"), bodies(posted))
            assertMessageActions(posted, dmBufferId, null, automotiveMessaging = true)
        }

    @Test
    fun firstPresentation_ofMention_alertsThroughMentionsChannel() =
        runTest {
            val channelId = channel(name = "#internal-alias", displayName = "#room")

            notifications.onIncoming(
                networkId = networkId,
                bufferId = channelId,
                type = BufferType.CHANNEL,
                hasMention = true,
                message = chat("alert-peer", "me: ping").copy(target = "#room"),
            )

            val posted = postedNotifications().single().notification
            assertEquals(MotdNotifications.CHANNEL_MENTIONS, posted.channelId)
            assertEquals(
                "#room",
                NotificationCompat.MessagingStyle
                    .extractMessagingStyleFromNotification(posted)
                    ?.conversationTitle
                    .toString(),
            )
            assertMessageActions(posted, channelId, "channel_mentions", automotiveMessaging = true)
            assertAlerting(posted)
        }

    /**
     * A recovery repost re-presents canonical state whose claim/present/complete cycle was
     * interrupted after an unknown amount of presentation work: the user may already have been
     * alerted, so the repost must stay silent.
     */
    @Test
    fun recoveryRepost_staysSilent() =
        runTest {
            val processor = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("alert-peer", "recover me", msgid = "recover-alert"))
            assertEquals(0, postedNotifications().size)
            val row = requireNotNull(db.messageDao().byMsgid(dmBufferId, "recover-alert"))
            assertEquals(false, row.notificationHandled)

            notifications.recoverCanonicalNotifications()

            val posted = postedNotifications().single().notification
            assertSilent(posted)
            assertEquals(true, db.messageDao().byId(row.id)?.notificationHandled)
        }

    /**
     * A transient msgid-less push alerts once; when reconnect supplies the durable canonical row
     * for the same body, the identity-upgrade re-post must not alert a second time.
     */
    @Test
    fun identityUpgradeRepost_ofAlreadyNotifiedBody_staysSilent() =
        runTest {
            val message = chat("alert-peer", "promoted later", serverTime = 2_000)
            notifications.onIncoming(
                networkId = networkId,
                bufferId = dmBufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = message,
            )
            assertAlerting(postedNotifications().single().notification)

            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = dmBufferId,
                                msgid = "durable-promotion",
                                serverTime = message.ctx.serverTime,
                                sender = "alert-peer",
                                kind = MessageKind.PRIVMSG,
                                text = message.text,
                                dedupKey = "durable-promotion",
                            ),
                        ),
                    ).single()
            notifications.onCanonicalIncoming(
                networkId = networkId,
                bufferId = dmBufferId,
                type = BufferType.QUERY,
                hasMention = false,
                eventId = eventId,
                message = message.copy(ctx = message.ctx.copy(msgid = "durable-promotion")),
                refreshOnly = false,
            )

            val reposted = postedNotifications().single().notification
            assertSilent(reposted)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(reposted)
            assertEquals(listOf("promoted later"), style?.messages?.map { it.text.toString() })
        }

    @Test
    fun allChannel_arrivalsOnlyUpdateTheirDisjointLane() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("alert-peer", "before", "before", 1_000).copy(target = "#chan"))
            assertAlerting(lane(null))
            processor.process(networkId, chat("alert-peer", "me: ping", "mention", 2_000).copy(target = "#chan"))
            val firstMention = lane("channel_mentions")
            assertAlerting(firstMention)
            assertEquals(listOf("before"), bodies(lane(null)))
            processor.process(networkId, chat("alert-peer", "after", "after", 3_000).copy(target = "#chan"))
            assertSame(firstMention, lane("channel_mentions"))
            assertAlerting(lane(null))
            assertEquals(listOf("before", "after"), bodies(lane(null)))
            val ordinary = lane(null)
            processor.process(networkId, chat("alert-peer", "me: again", "again", 4_000).copy(target = "#chan"))
            assertSame(ordinary, lane(null))
            assertEquals(listOf("me: ping", "me: again"), bodies(lane("channel_mentions")))
            assertAlerting(lane("channel_mentions"))
            assertEquals(
                setOf(null to MotdNotifications.messageNotificationId(channelId), "channel_mentions" to MotdNotifications.messageNotificationId(channelId)),
                postedNotifications().map { it.tag to it.id }.toSet(),
            )
            assertMessageActions(lane(null), channelId, null, automotiveMessaging = false)
            assertMessageActions(lane("channel_mentions"), channelId, "channel_mentions", automotiveMessaging = true)
            assertLaneAnchor(lane(null), requireNotNull(db.messageDao().byMsgid(channelId, "after")))
            assertLaneAnchor(lane("channel_mentions"), requireNotNull(db.messageDao().byMsgid(channelId, "again")))
        }

    @Test
    fun recreatedMentionLane_restoresEarlierMentionBeyondOrdinaryHistoryLimit() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("alert-peer", "me: early", "early", 1_000).copy(target = "#chan"))
            repeat(30) { index ->
                processor.process(networkId, chat("alert-peer", "ordinary $index", "ordinary-$index", 2_000L + index).copy(target = "#chan"))
            }
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            val recreated = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            recreated.onRegistered(networkId, "me", emptyMap())
            recreated.process(networkId, chat("alert-peer", "me: newest", "newest", 4_000).copy(target = "#chan"))
            assertEquals(listOf("me: early", "me: newest"), bodies(lane("channel_mentions")))
            assertEquals((5..29).map { "ordinary $it" }, bodies(lane(null)))
            assertEquals(
                channelId,
                shadowOf(lane("channel_mentions").contentIntent).savedIntent.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1),
            )
        }

    @Test
    fun unhandledHistoricalContext_firstAdmittedMentionMovesOutOfOrdinaryLane() = runTest { assertHistoricalContextPromotion(recreate = false) }

    @Test
    fun unhandledHistoricalContext_afterNotifierRecreationFirstAdmittedMentionMovesOutOfOrdinaryLane() = runTest { assertHistoricalContextPromotion(recreate = true) }

    private suspend fun TestScope.assertHistoricalContextPromotion(recreate: Boolean) {
        val channelId = channel()
        val settings = allSettings()
        var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
        processor.onRegistered(networkId, "me", emptyMap())
        processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
        val historical = chat("alert-peer", "historical reply", "historical", 1_000).copy(target = "#chan")
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(historical)))
        val unresolved = requireNotNull(db.messageDao().byMsgid(channelId, "historical"))
        assertEquals(false, unresolved.notificationHandled)
        assertEquals(false, unresolved.notificationEligibilityResolved)
        processor.process(networkId, chat("alert-peer", "later ordinary", "later", 2_000).copy(target = "#chan"))
        assertEquals(listOf(historical.text, "later ordinary"), bodies(lane(null)))
        assertEquals(false, db.messageDao().byMsgid(channelId, "historical")?.notificationHandled)
        if (recreate) {
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
        }

        processor.process(networkId, historical.copy(replyToMsgid = "self-parent"))

        val promoted = requireNotNull(db.messageDao().byMsgid(channelId, "historical"))
        val later = requireNotNull(db.messageDao().byMsgid(channelId, "later"))
        assertEquals(listOf("later ordinary"), bodies(lane(null)))
        assertEquals(listOf(historical.text), bodies(lane("channel_mentions")))
        assertSilent(lane(null))
        assertAlerting(lane("channel_mentions"))
        assertLaneAnchor(lane(null), later)
        assertLaneAnchor(lane("channel_mentions"), promoted)
        assertEquals(true, promoted.hasMention)
        assertEquals(true, promoted.notificationHandled)
        assertEquals(true, promoted.notificationEligible)
        assertEquals(true, promoted.notificationEligibilityResolved)
        assertEquals(false, promoted.notificationClaimed)
        val ordinary = lane(null)
        val mentions = lane("channel_mentions")
        processor.process(networkId, historical.copy(replyToMsgid = "self-parent"))
        assertSame(ordinary, lane(null))
        assertSame(mentions, lane("channel_mentions"))
    }

    @Test
    fun liveReplyParentEnrichment_movesLatestOrdinaryBodySilentlyAndRegeneratesAnchors() = runTest { assertReplyParentPromotion(recreate = false) }

    @Test
    fun historicalReplyParentEnrichment_afterNotifierRecreation_movesBodySilentlyAndRegeneratesAnchors() = runTest { assertReplyParentPromotion(recreate = true) }

    private suspend fun TestScope.assertReplyParentPromotion(recreate: Boolean) {
        val channelId = channel()
        val settings = allSettings()
        var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
        processor.onRegistered(networkId, "me", emptyMap())
        processor.process(networkId, chat("me", "my parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
        processor.process(networkId, chat("alert-peer", "older ordinary", "older", 1_000).copy(target = "#chan"))
        val toPromote = chat("alert-peer", "reply without literal nick", "promoted", 2_000).copy(target = "#chan")
        processor.process(networkId, toPromote)
        processor.process(networkId, chat("alert-peer", "me: later", serverTime = 3_000).copy(target = "#chan"))
        val older = requireNotNull(db.messageDao().byMsgid(channelId, "older"))
        val later = db.messageDao().historyRowsForMerge(channelId).single { it.text == "me: later" }
        val capturedMentionRead = lane("channel_mentions").actions.single { it.title.toString() == "Mark read" }.actionIntent
        if (recreate) {
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
        }
        val enriched = toPromote.copy(replyToMsgid = "self-parent")
        processor.process(networkId, if (recreate) IrcEvent.HistoryBatch("#chan", listOf(enriched)) else enriched)

        assertEquals(listOf("older ordinary"), bodies(lane(null)))
        assertEquals(listOf("me: later", toPromote.text).sorted(), bodies(lane("channel_mentions")).sorted())
        postedNotifications().forEach { assertSilent(it.notification) }
        assertLaneAnchor(lane(null), older)
        assertLaneAnchor(lane("channel_mentions"), later)
        val savedRead = shadowOf(capturedMentionRead).savedIntent
        assertEquals(later.serverTime, savedRead.getLongExtra(ReplyReceiver.EXTRA_UP_TO_TIME, -1))
        assertEquals(later.id, savedRead.getLongExtra(ReplyReceiver.EXTRA_UP_TO_EVENT_ID, -1))
        assertMessageActions(lane("channel_mentions"), channelId, "channel_mentions", automotiveMessaging = true)
        assertEquals(listOf(older.id), lane(null).extras.getLongArray("motd.notificationEventIds")?.toList())
        val promoted = requireNotNull(db.messageDao().byMsgid(channelId, "promoted"))
        assertEquals(true, promoted.hasMention)
        assertEquals(true, promoted.notificationHandled)
        assertEquals(true, promoted.notificationEligible)
        assertEquals(false, promoted.notificationClaimed)
        assertEquals(1, (bodies(lane(null)) + bodies(lane("channel_mentions"))).count { it == toPromote.text })
        val ordinary = lane(null)
        val mentions = lane("channel_mentions")
        processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(enriched.copy(ctx = enriched.ctx.copy(account = "enriched-account")))))
        assertSame(ordinary, lane(null))
        assertSame(mentions, lane("channel_mentions"))
    }

    @Test
    fun dismissedOrdinaryRow_enrichmentDoesNotResurrectFromMemoryOrDatabase() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
            val incoming = chat("alert-peer", "dismissed reply", "dismissed", 1_000).copy(target = "#chan")
            processor.process(networkId, incoming)
            context.getSystemService(android.app.NotificationManager::class.java).cancel(MotdNotifications.messageNotificationId(channelId))
            processor.process(networkId, incoming.copy(replyToMsgid = "self-parent"))
            assertEquals(0, postedNotifications().size)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(incoming.copy(replyToMsgid = "self-parent", ctx = incoming.ctx.copy(account = "extra")))))
            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun promotionOfOnlyOrdinaryBody_cancelsOrdinaryRowWithoutDuplicatingMention() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
            val incoming = chat("alert-peer", "only ordinary", "only", 1_000).copy(target = "#chan")
            processor.process(networkId, incoming)
            processor.process(networkId, incoming.copy(replyToMsgid = "self-parent"))
            assertEquals(listOf("channel_mentions"), postedNotifications().map { it.tag })
            assertEquals(listOf(incoming.text), bodies(lane("channel_mentions")))
            assertSilent(lane("channel_mentions"))
            assertLaneAnchor(lane("channel_mentions"), requireNotNull(db.messageDao().byMsgid(channelId, "only")))
        }

    @Test
    fun promotionWithUnresolvableRemainingAnchor_preservesBothPostedRowsAndReportsFailure() = runTest { assertUnresolvableRemainingAnchor(recreate = false) }

    @Test
    fun recreatedPromotionWithUnresolvableRemainingAnchor_preservesBothPostedRowsAndReportsFailure() = runTest { assertUnresolvableRemainingAnchor(recreate = true) }

    private suspend fun TestScope.assertUnresolvableRemainingAnchor(recreate: Boolean) {
        val channelId = channel()
        val settings = allSettings()
        val failures = mutableListOf<String>()
        val diagnostics =
            object : DiagnosticLogger by DiagnosticLogger.Noop {
                override fun record(
                    component: String,
                    event: String,
                    fields: () -> Map<String, Any?>,
                ) {
                    if (component == "notifications") failures += event
                }
            }
        var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings, diagnostics = diagnostics)
        processor.onRegistered(networkId, "me", emptyMap())
        processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
        processor.process(networkId, chat("alert-peer", "remaining ordinary", "remaining", 1_000).copy(target = "#chan"))
        val incoming = chat("alert-peer", "promote", "promote", 2_000).copy(target = "#chan")
        processor.process(networkId, incoming)
        processor.process(networkId, chat("alert-peer", "me: existing", "existing", 3_000).copy(target = "#chan"))
        val ordinary = lane(null)
        val mentions = lane("channel_mentions")
        val promoted = requireNotNull(db.messageDao().byMsgid(channelId, "promote"))
        val existing = requireNotNull(db.messageDao().byMsgid(channelId, "existing"))
        db.messageDao().deleteById(requireNotNull(db.messageDao().byMsgid(channelId, "remaining")).id)
        if (recreate) {
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings, diagnostics = diagnostics)
            processor.onRegistered(networkId, "me", emptyMap())
        }

        processor.process(networkId, incoming.copy(replyToMsgid = "self-parent"))

        assertSame(ordinary, lane(null))
        assertSame(mentions, lane("channel_mentions"))
        assertEquals(listOf("remaining ordinary", "promote"), bodies(lane(null)))
        assertEquals(listOf("me: existing"), bodies(lane("channel_mentions")))
        assertLaneAnchor(ordinary, promoted)
        assertLaneAnchor(mentions, existing)
        assertTrue("refresh_failed" in failures)
    }

    @Test
    fun promotionWithFailedMentionRestoration_preservesPostedRowsAndActionAnchors() = runTest { assertPromotionStagingFailure(cancelled = false) }

    @Test
    fun promotionCancelledWhileResolvingMentionAnchor_preservesPostedRowsAndActionAnchors() = runTest { assertPromotionStagingFailure(cancelled = true) }

    private suspend fun TestScope.assertPromotionStagingFailure(cancelled: Boolean) {
        val channelId = channel()
        val settings = allSettings()
        val failures = mutableListOf<String>()
        val diagnostics =
            object : DiagnosticLogger by DiagnosticLogger.Noop {
                override fun record(
                    component: String,
                    event: String,
                    fields: () -> Map<String, Any?>,
                ) {
                    if (component == "notifications") failures += event
                }
            }
        var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings, diagnostics = diagnostics)
        processor.onRegistered(networkId, "me", emptyMap())
        processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
        processor.process(networkId, chat("alert-peer", "remaining ordinary", "remaining", 1_000).copy(target = "#chan"))
        val incoming = chat("alert-peer", "promote", "promote", 2_000).copy(target = "#chan")
        processor.process(networkId, incoming)
        processor.process(networkId, chat("alert-peer", "me: existing", "existing", 3_000).copy(target = "#chan"))
        val ordinary = lane(null)
        val mentions = lane("channel_mentions")
        val promoted = requireNotNull(db.messageDao().byMsgid(channelId, "promote"))
        val existing = requireNotNull(db.messageDao().byMsgid(channelId, "existing"))
        notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
        processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings, diagnostics = diagnostics)
        processor.onRegistered(networkId, "me", emptyMap())
        var restoringMentions = false
        var injected = false
        val processing =
            async(start = CoroutineStart.LAZY) {
                processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(incoming.copy(replyToMsgid = "self-parent"))))
            }
        beforeQuery = { sql, args ->
            if (sql.contains("hasMention = ?") && (args[args.lastIndex - 1] as? Number)?.toInt() == 1) {
                restoringMentions = true
                if (!cancelled) {
                    // QueryCallback observes in a separate coroutine; fail the actual Room read.
                    beforeDatabaseQuery = {
                        beforeDatabaseQuery = null
                        injected = true
                        error("simulated mention restoration failure")
                    }
                }
            }
            if (cancelled && restoringMentions && sql.contains("SELECT * FROM messages WHERE id = COALESCE")) {
                injected = true
                processing.cancel(CancellationException("cancelled during mention anchor lookup"))
            }
        }
        var cancellationPropagated = false
        try {
            processing.start()
            processing.await()
        } catch (error: CancellationException) {
            cancellationPropagated = true
        } finally {
            processing.join()
            beforeQuery = null
            beforeDatabaseQuery = null
        }

        assertTrue("Fault must occur after the ordinary rebuild", injected)
        assertEquals(cancelled, cancellationPropagated)
        assertSame(ordinary, lane(null))
        assertSame(mentions, lane("channel_mentions"))
        assertEquals(listOf("remaining ordinary", "promote"), bodies(ordinary))
        assertEquals(listOf("me: existing"), bodies(mentions))
        assertLaneAnchor(ordinary, promoted)
        assertLaneAnchor(mentions, existing)
        if (!cancelled) assertTrue("refresh_failed" in failures)
        val enriched = requireNotNull(db.messageDao().byMsgid(channelId, "promote"))
        assertEquals(true, enriched.notificationHandled)
        assertEquals(false, enriched.notificationClaimed)
    }

    @Test
    fun promotionWithFallbackBody_doesNotAlignShorterEventIdExtrasWithStyleMessages() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
            processor.process(networkId, chat("alert-peer", "canonical ordinary", serverTime = 1_000).copy(target = "#chan"))
            notifications.onIncoming(networkId, channelId, BufferType.CHANNEL, false, chat("alert-peer", "fallback ordinary", serverTime = 1_500).copy(target = "#chan"))
            val incoming = chat("alert-peer", "promote", "promote", 2_000).copy(target = "#chan")
            processor.process(networkId, incoming)
            assertEquals(3, bodies(lane(null)).size)
            assertEquals(2, lane(null).extras.getLongArray("motd.notificationEventIds")?.size)

            processor.process(networkId, incoming.copy(replyToMsgid = "self-parent"))

            assertEquals(listOf("canonical ordinary", "fallback ordinary"), bodies(lane(null)))
            assertEquals(listOf("promote"), bodies(lane("channel_mentions")))
            val older = db.messageDao().historyRowsForMerge(channelId).single { it.text == "canonical ordinary" }
            assertLaneAnchor(lane(null), older)
            assertEquals(listOf(older.id), lane(null).extras.getLongArray("motd.notificationEventIds")?.toList())
            postedNotifications().forEach { assertSilent(it.notification) }
        }

    @Test
    fun refreshOnly_preservesForegroundReadMuteFoolAndRoomSuppression() =
        runTest {
            for (suppression in listOf("foreground", "read", "mute", "fool", "dismissed", "closing", "missing")) {
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                val channelId = channel("#$suppression")
                val settings = allSettings()
                val tracker = ForegroundBufferTrackerImpl()
                notifications = MotdNotifications(context, db, tracker, repo)
                val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
                processor.onRegistered(networkId, "me", emptyMap())
                val incoming = chat("suppressed-peer", "ordinary $suppression", suppression, 1_000).copy(target = "#$suppression")
                processor.process(networkId, incoming)
                val ordinary = lane(null)
                val event = requireNotNull(db.messageDao().byMsgid(channelId, suppression))
                db.messageDao().update(event.copy(hasMention = true))
                val buffer = requireNotNull(db.bufferDao().observeById(channelId))
                when (suppression) {
                    "foreground" -> tracker.set(channelId)
                    "read" -> db.bufferDao().update(buffer.copy(localReadAnchorTime = event.serverTime, localReadAnchorEventId = event.id))
                    "mute" -> db.bufferDao().update(buffer.copy(muted = true))
                    "fool" -> repo.setFool("suppressed-peer", true)
                    "dismissed" -> db.bufferDao().update(buffer.copy(dismissed = true))
                    "closing" -> db.bufferDao().update(buffer.copy(pendingCloseAt = 1_001))
                    "missing" -> db.bufferDao().deleteBuffer(channelId)
                }

                notifications.onCanonicalIncoming(
                    networkId,
                    channelId,
                    BufferType.CHANNEL,
                    true,
                    event.id,
                    incoming,
                    watched = false,
                    refreshOnly = true,
                )

                assertEquals(listOf(null), postedNotifications().map { it.tag })
                assertSame(ordinary, lane(null))
                assertEquals(listOf(incoming.text), bodies(lane(null)))
                repo.setFool("suppressed-peer", false)
            }
        }

    @Test
    fun watchedReplyEnrichment_afterWatchEndsStillUsesFrozenMuteBypass() =
        runTest {
            val channelId = mutedChannel()
            val watch = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            watch.startWatch(channelId, null)
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
            val incoming = chat("alert-peer", "watched reply", "watched", 1_000).copy(target = "#chan")
            processor.process(networkId, incoming)
            watch.stopWatch(channelId)

            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(incoming.copy(replyToMsgid = "self-parent"))))

            assertEquals(listOf("channel_mentions"), postedNotifications().map { it.tag })
            assertEquals(listOf(incoming.text), bodies(lane("channel_mentions")))
            assertSilent(lane("channel_mentions"))
            assertEquals(true, db.messageDao().byMsgid(channelId, "watched")?.notificationWatched)
        }

    @Test
    fun recreatedPromotion_rebuildsRemainingOrdinaryHistoryAboveReadFloorAndExcludesFools() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("me", "parent", "self-parent", 500).copy(target = "#chan", isSelf = true))
            processor.process(networkId, chat("alert-peer", "read ordinary", "read", 1_000).copy(target = "#chan"))
            processor.process(networkId, chat("earlier-fool", "hidden ordinary", "hidden", 1_500).copy(target = "#chan"))
            processor.process(networkId, chat("alert-peer", "remaining ordinary", "remaining", 2_000).copy(target = "#chan"))
            val incoming = chat("alert-peer", "promote", "promote", 3_000).copy(target = "#chan")
            processor.process(networkId, incoming)
            val read = requireNotNull(db.messageDao().byMsgid(channelId, "read"))
            val remaining = requireNotNull(db.messageDao().byMsgid(channelId, "remaining"))
            val buffer = requireNotNull(db.bufferDao().observeById(channelId))
            db.bufferDao().update(buffer.copy(localReadAnchorTime = read.serverTime, localReadAnchorEventId = read.id))
            repo.setFool("earlier-fool", true)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())

            processor.process(networkId, IrcEvent.HistoryBatch("#chan", listOf(incoming.copy(replyToMsgid = "self-parent"))))

            assertEquals(listOf("remaining ordinary"), bodies(lane(null)))
            assertEquals(listOf("promote"), bodies(lane("channel_mentions")))
            assertLaneAnchor(lane(null), remaining)
            assertEquals(listOf(remaining.id), lane(null).extras.getLongArray("motd.notificationEventIds")?.toList())
            postedNotifications().forEach { assertSilent(it.notification) }
            repo.setFool("earlier-fool", false)
        }

    @Test
    fun serverMessagesKeepPhoneButtonsButStayLocalOnlyWithoutAutomotiveActions() =
        runTest {
            val serverId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "*server*", displayName = "libera", type = BufferType.SERVER),
                )
            notifications.onIncoming(networkId, serverId, BufferType.SERVER, false, chat("server", "server notice"))

            val posted = postedNotifications().single().notification
            assertEquals(listOf("server notice"), bodies(posted))
            assertMessageActions(posted, serverId, null, automotiveMessaging = false)
        }

    @Test
    fun recreatedReadThroughMentionPreservesNewerOrdinary_thenLaterMarkerClearsBothLanes() =
        runTest {
            val channelId = channel()
            val settings = allSettings()
            var processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("alert-peer", "before", "before", 1_000).copy(target = "#chan"))
            processor.process(networkId, chat("alert-peer", "me: mention", "mention", 2_000).copy(target = "#chan"))
            val capturedRead = lane("channel_mentions").actions.single { it.title.toString() == "Mark read" }.actionIntent
            processor.process(networkId, chat("alert-peer", "after", "after", 3_000).copy(target = "#chan"))
            val mention = requireNotNull(db.messageDao().byMsgid(channelId, "mention"))
            val after = requireNotNull(db.messageDao().byMsgid(channelId, "after"))
            val savedRead = shadowOf(capturedRead).savedIntent
            assertEquals(mention.id, savedRead.getLongExtra(ReplyReceiver.EXTRA_UP_TO_EVENT_ID, -1))
            assertEquals(mention.serverTime, savedRead.getLongExtra(ReplyReceiver.EXTRA_UP_TO_TIME, -1))
            assertLaneAnchor(lane(null), after)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
            val buffer = requireNotNull(db.bufferDao().observeById(channelId))
            db.bufferDao().update(buffer.copy(localReadAnchorTime = mention.serverTime, localReadAnchorEventId = mention.id))

            notifications.onRead(channelId, TimelineAnchor(mention.serverTime, mention.id, mention.timelineOrder))

            assertEquals(listOf(null), postedNotifications().map { it.tag })
            assertEquals(listOf("before", "after"), bodies(lane(null)))
            assertLaneAnchor(lane(null), after)
            processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(networkId, chat("alert-peer", "me: next", "next", 3_500).copy(target = "#chan"))
            processor.process(networkId, chat("alert-peer", "latest ordinary", "latest", 4_000).copy(target = "#chan"))
            val latest = requireNotNull(db.messageDao().byMsgid(channelId, "latest"))
            assertEquals(setOf(null, "channel_mentions"), postedNotifications().map { it.tag }.toSet())

            notifications.onRead(channelId, TimelineAnchor(latest.serverTime, latest.id, latest.timelineOrder))

            assertEquals(0, postedNotifications().size)
        }

    @Test
    fun emptyOrUnresolvableDefaultLaneDoesNotAbortCoveredMentionCleanup() =
        runTest {
            for (defaultLane in listOf("absent", "fallback", "deleted")) {
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                val channelId = channel("#$defaultLane")
                notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
                val settings = allSettings()
                val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
                processor.onRegistered(networkId, "me", emptyMap())
                if (defaultLane == "fallback") {
                    notifications.onIncoming(
                        networkId,
                        channelId,
                        BufferType.CHANNEL,
                        false,
                        chat("alert-peer", "fallback ordinary", serverTime = 1_000).copy(target = "#$defaultLane"),
                    )
                } else if (defaultLane == "deleted") {
                    processor.process(networkId, chat("alert-peer", "deleted ordinary", "deleted", 1_000).copy(target = "#$defaultLane"))
                    db.messageDao().deleteById(requireNotNull(db.messageDao().byMsgid(channelId, "deleted")).id)
                }
                processor.process(networkId, chat("alert-peer", "me: read me", "read-$defaultLane", 2_000).copy(target = "#$defaultLane"))
                val mention = requireNotNull(db.messageDao().byMsgid(channelId, "read-$defaultLane"))

                notifications.onRead(channelId, TimelineAnchor(mention.serverTime, mention.id, mention.timelineOrder))

                assertEquals(if (defaultLane == "absent") emptyList<String?>() else listOf(null), postedNotifications().map { it.tag })
                if (defaultLane != "absent") assertEquals(listOf("$defaultLane ordinary"), bodies(lane(null)))
            }
        }
}
