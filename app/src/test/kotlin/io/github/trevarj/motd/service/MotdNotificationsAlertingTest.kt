package io.github.trevarj.motd.service

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() =
        runTest {
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
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
            val watch = ChannelWatchImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            watch.start(channelId, null)
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, channelWatch = watch)
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
            val watch = ChannelWatchImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            watch.start(channelId, null)
            val live = EventProcessor(db, TypingTrackerImpl(), notifications, channelWatch = watch)
            live.onRegistered(networkId, "me", emptyMap())
            live.process(
                networkId,
                chat("alert-peer", "already presented", "watch-first").copy(target = "#chan"),
            )
            assertAlerting(postedNotifications().single().notification)

            val interrupted = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, channelWatch = watch)
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
            watch.stop()
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)

            notifications.recoverCanonicalNotifications()

            val recovered = postedNotifications().single().notification
            assertSilent(recovered)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(recovered)
            assertEquals(
                listOf("already presented", "ordinary interrupted", "waves", "me: interrupted mention").sorted(),
                style?.messages?.map { it.text.toString() }?.sorted(),
            )
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
            val watch = ChannelWatchImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            val processor = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, channelWatch = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            val message = chat("alert-peer", "survives coalescence").copy(target = "#chan")
            processor.process(
                networkId,
                IrcEvent.HistoryBatch("#renamed", listOf(message.copy(target = "#renamed"))),
            )
            watch.start(channelId, null)
            processor.process(networkId, message)
            watch.stop()
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
    fun currentWatch_doesNotMakeEarlierMessagesOrPlaybackRecoverable() =
        runTest {
            val channelId = mutedChannel()
            val watch = ChannelWatchImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            val processor = EventProcessor(db, TypingTrackerImpl(), interruptedNotifier, channelWatch = watch)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(
                networkId,
                chat("alert-peer", "before watch", "unwatched-live").copy(target = "#chan"),
            )
            processor.processPush(
                networkId,
                chat("alert-peer", "me: before watch", "unwatched-mention").copy(target = "#chan"),
            )
            watch.start(channelId, null)
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
        }

    @Test
    fun firstPresentation_ofMention_alertsThroughMentionsChannel() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#chan",
                        displayName = "#chan",
                        type = BufferType.CHANNEL,
                    ),
                )

            notifications.onIncoming(
                networkId = networkId,
                bufferId = channelId,
                type = BufferType.CHANNEL,
                hasMention = true,
                message = chat("alert-peer", "me: ping"),
            )

            val posted = postedNotifications().single().notification
            assertEquals(MotdNotifications.CHANNEL_MENTIONS, posted.channelId)
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
            )

            val reposted = postedNotifications().single().notification
            assertSilent(reposted)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(reposted)
            assertEquals(listOf("promoted later"), style?.messages?.map { it.text.toString() })
        }
}
