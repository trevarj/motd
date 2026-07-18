package io.github.trevarj.motd.service

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Regression for the "adding a nick to fools crashes the app" report. The events collector runs on
 * Dispatchers.Main, and [MotdNotifications.onIncoming] used to read Room + the fools DataStore via
 * `runBlocking { suspend query }`, which blocks the main thread and crashes/deadlocks once a
 * freshly-added fool's message arrives (same class of bug as the findSelfEchoCandidate main-thread
 * fix). onIncoming is now a plain suspend call.
 *
 * This exercises the real read path end to end from the main dispatcher: a fool's message must post
 * no notification (silenced), a normal sender's must post one — without blocking the main thread.
 */
@RunWith(RobolectricTestRunner::class)
class MotdNotificationsFoolTest {

    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var networkId: Long = 0
    private var bufferId: Long = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera", role = NetworkRole.DIRECT, host = "irc.libera.chat",
                port = 6697, nick = "me", username = "me", realname = "Me",
            ),
        )
        // A DM buffer keyed by the sender so (DM || mention) qualifies upstream.
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId, name = "troll", displayName = "troll",
                type = BufferType.QUERY,
            ),
        )
        // Grant POST_NOTIFICATIONS so the control (non-fool) case posts regardless of test SDK level.
        shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        repo = DataStoreSettingsRepository(context)
        notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
    }

    @After
    fun tearDown() = runTest {
        // The application-scoped DataStore outlives this Robolectric class; do not leak the fool
        // fixture into another test class or make this class depend on JUnit method order.
        repo.setFool("troll", false)
        db.close()
    }

    private fun chat(nick: String, text: String) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid = null, serverTime = 1000, account = null, batchId = null, label = null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(nick),
        target = "me",
        text = text,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun postedCount(): Int =
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.size

    private fun assertSilent(notification: android.app.Notification) {
        assertEquals("silent", notification.group)
        assertEquals(NotificationCompat.GROUP_ALERT_SUMMARY, notification.groupAlertBehavior)
    }

    /**
     * The crash repro path: add a fool, then run the notification pipeline for that fool's message.
     * onIncoming reads the fools DataStore + the buffer via suspend calls (no runBlocking on the
     * main-thread events collector), so it completes cleanly and posts nothing (fools are silenced).
     */
    @Test
    fun addedFool_incomingMessage_isSilenced_andDoesNotCrash() = runTest {
        repo.setFool("Troll", true)
        notifications.onIncoming(
            networkId = 1, bufferId = bufferId, type = BufferType.QUERY,
            hasMention = false, message = chat("troll", "hey"),
        )
        assertEquals(0, postedCount())
    }

    /** Control: a non-fool DM still posts a notification through the same suspend read path. */
    @Test
    fun nonFool_incomingMessage_posts() = runTest {
        notifications.onIncoming(
            networkId = 1, bufferId = bufferId, type = BufferType.QUERY,
            hasMention = false, message = chat("troll", "hey"),
        )
        assertEquals(1, postedCount())
        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        assertSilent(posted)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertNotNull(style?.messages?.single()?.person?.icon)
    }

    @Test
    fun roomMergeRetiresLosingNotificationAndCanonicalReadClearsReplacement() = runTest {
        val loserId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "other",
                displayName = "other",
                type = BufferType.QUERY,
            ),
        )
        val manager = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        notifications.onIncoming(
            networkId,
            loserId,
            BufferType.QUERY,
            false,
            chat("other", "before merge"),
        )
        assertEquals(listOf(loserId.toInt()), manager.activeNotifications.map { it.id })

        BufferStore(db, notifications).mergeRooms(bufferId, loserId)

        assertEquals(0, manager.activeNotifications.size)
        notifications.onIncoming(
            networkId,
            bufferId,
            BufferType.QUERY,
            false,
            chat("other", "after merge"),
        )
        assertEquals(listOf(bufferId.toInt()), manager.activeNotifications.map { it.id })

        notifications.onRead(bufferId, 1_000)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun interruptedNotificationPresentationIsRebuiltFromCanonicalDatabaseState() = runTest {
        val failing = object : io.github.trevarj.motd.data.sync.MessageNotifier {
            override suspend fun onIncoming(
                networkId: Long,
                bufferId: Long,
                type: BufferType,
                hasMention: Boolean,
                message: IrcEvent.ChatMessage,
            ) {
                error("simulated notifier failure")
            }
        }
        val processor = EventProcessor(db, TypingTrackerImpl(), failing)
        processor.onRegistered(networkId, "me", emptyMap())
        val incoming = chat("troll", "recover me").copy(
            ctx = chat("troll", "recover me").ctx.copy(msgid = "recover-event"),
        )

        processor.process(networkId, incoming)
        val before = db.messageDao().byMsgid(bufferId, "recover-event")
        assertEquals(false, before?.notificationHandled)
        assertEquals(false, before?.notificationClaimed)
        assertEquals(0, postedCount())

        notifications.recoverCanonicalNotifications()

        assertEquals(1, postedCount())
        assertEquals(true, db.messageDao().byId(before!!.id)?.notificationHandled)
        assertEquals(false, db.messageDao().byId(before.id)?.notificationClaimed)
    }

    @Test
    fun delayedPushAtLocalUnreadFloor_doesNotNotifyAfterUnmute() = runTest {
        val buffer = db.bufferDao().observeById(bufferId)!!
        db.bufferDao().update(
            buffer.copy(
                muted = false,
                readMarkerTime = null,
                localUnreadFloorTime = 1_000,
            ),
        )

        notifications.onIncoming(
            networkId = networkId,
            bufferId = bufferId,
            type = BufferType.QUERY,
            hasMention = false,
            message = chat("troll", "delayed while muted"),
        )

        assertEquals(0, postedCount())
    }

    @Test
    fun duplicateDelivery_addsBodyToMessagingStyleOnlyOnce() = runTest {
        val message = chat("troll", "only once")

        repeat(2) {
            notifications.onIncoming(
                networkId = 1, bufferId = bufferId, type = BufferType.QUERY,
                hasMention = false, message = message,
            )
        }

        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertEquals(listOf("only once"), style?.messages?.map { it.text.toString() })
    }

    @Test
    fun differentDurableMsgids_withSameFingerprint_remainDistinctNotifications() = runTest {
        val message = chat("durable-identity-fixture", "legitimately repeated")

        notifications.onIncoming(
            networkId = networkId,
            bufferId = bufferId,
            type = BufferType.QUERY,
            hasMention = false,
            message = message.copy(ctx = message.ctx.copy(msgid = "durable-a")),
        )
        notifications.onIncoming(
            networkId = networkId,
            bufferId = bufferId,
            type = BufferType.QUERY,
            hasMention = false,
            message = message.copy(ctx = message.ctx.copy(msgid = "durable-b")),
        )

        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertEquals(
            listOf("legitimately repeated", "legitimately repeated"),
            style?.messages?.map { it.text.toString() },
        )
    }

    @Test
    fun sojuHistoryThenDelayedPushAndReconnect_persistsAndNotifiesMentionOnlyOnce() = runTest {
        val channelId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#systemcrafters",
                displayName = "#systemcrafters",
                type = BufferType.CHANNEL,
            ),
        )
        val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
        processor.onRegistered(networkId, "me", emptyMap())
        val history = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = "soju-durable-mention",
                serverTime = 10_000,
                account = "soju-fixture",
                batchId = "history",
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("soju-fixture"),
            target = "#systemcrafters",
            text = "me: only one row and notification",
            isSelf = false,
            replyToMsgid = "parent-msgid",
        )

        processor.process(networkId, IrcEvent.HistoryBatch("#systemcrafters", listOf(history)))
        val msgidlessDelivery = history.copy(
            ctx = history.ctx.copy(msgid = null, account = null, batchId = null),
            replyToMsgid = null,
        )
        processor.processPush(networkId, msgidlessDelivery)
        assertEquals(1, db.messageDao().countForBuffer(channelId))
        processor.process(networkId, msgidlessDelivery.copy(ctx = msgidlessDelivery.ctx.copy(serverTime = 10_750)))

        assertEquals(1, db.messageDao().countForBuffer(channelId))
        val row = db.messageDao().byMsgid(channelId, "soju-durable-mention")
        assertNotNull(row)
        assertEquals("soju-fixture", row?.senderAccount)
        assertEquals("parent-msgid", row?.replyToMsgid)

        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertEquals(
            listOf("me: only one row and notification"),
            style?.messages?.map { it.text.toString() },
        )
    }

    @Test
    fun sojuMsgidlessPushThenHistoryAndReconnect_persistsAndNotifiesMentionOnlyOnce() = runTest {
        val channelId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#systemcrafters",
                displayName = "#systemcrafters",
                type = BufferType.CHANNEL,
            ),
        )
        val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
        processor.onRegistered(networkId, "me", emptyMap())
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = null,
                serverTime = 20_000,
                account = null,
                batchId = null,
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("soju-fixture"),
            target = "#systemcrafters",
            text = "me: identity promoted after notification",
            isSelf = false,
            replyToMsgid = null,
        )

        processor.processPush(networkId, push)
        assertEquals(1, db.messageDao().countForBuffer(channelId))
        processor.process(
            networkId,
            IrcEvent.HistoryBatch(
                "#systemcrafters",
                listOf(
                    push.copy(
                        ctx = push.ctx.copy(
                            msgid = "soju-promoted-mention",
                            account = "soju-fixture",
                            batchId = "history",
                        ),
                        replyToMsgid = "parent-msgid",
                    ),
                ),
            ),
        )
        assertEquals(1, db.messageDao().countForBuffer(channelId))
        processor.process(networkId, push.copy(ctx = push.ctx.copy(serverTime = 20_750)))

        assertEquals(1, db.messageDao().countForBuffer(channelId))
        assertNotNull(db.messageDao().byMsgid(channelId, "soju-promoted-mention"))
        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
        assertEquals(
            listOf("me: identity promoted after notification"),
            style?.messages?.map { it.text.toString() },
        )
    }

    @Test
    fun msgidlessNewDmPush_createsDeepLinkBufferButWaitsForHistoryRow() = runTest {
        val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
        processor.onRegistered(networkId, "me", emptyMap())
        val push = IrcEvent.ChatMessage(
            ctx = MessageContext(
                msgid = null,
                serverTime = 30_000,
                account = null,
                batchId = null,
                label = null,
            ),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("new-dm-push-fixture"),
            target = "me",
            text = "notification before history",
            isSelf = false,
            replyToMsgid = null,
        )

        processor.processPush(networkId, push)

        val buffer = requireNotNull(db.bufferDao().byName(networkId, "new-dm-push-fixture"))
        assertEquals(BufferType.QUERY, buffer.type)
        assertEquals(1, db.messageDao().countForBuffer(buffer.id))
        assertEquals(
            buffer.id,
            db.bufferDao().openTargets(networkId).single { it.name == "new-dm-push-fixture" }.id,
        )

        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        val open = shadowOf(posted.contentIntent).savedIntent
        assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, open.action)
        assertEquals(buffer.id, open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
        assertNull(open.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID))
        assertEquals(30_000, open.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, -1))
        val eventId = open.getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1)
        assertEquals(buffer.id, requireNotNull(db.messageDao().byId(eventId)).bufferId)
    }

    @Test
    fun invitationNotification_hasOpenJoinDismissAndSwipeContracts_thenCancelsOnResolution() = runTest {
        val messageId = db.messageDao().insertAll(
            listOf(
                MessageEntity(
                    bufferId = bufferId,
                    msgid = "invite-notification",
                    serverTime = 2_000,
                    sender = "alice",
                    kind = MessageKind.INVITE,
                    text = "alice invited you",
                    dedupKey = "invite-notification",
                    eventKey = "invite:msgid:invite-notification",
                    eventPayload = "invite-v1:YQ:Yg:Yw",
                    inviteState = InviteState.PENDING,
                ),
            ),
        ).single()

        notifications.onInvitation(1, bufferId, messageId)

        val posted = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .activeNotifications.single().notification
        assertEquals(MotdNotifications.CHANNEL_INVITATIONS, posted.channelId)
        assertSilent(posted)
        assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, shadowOf(posted.contentIntent).savedIntent.action)
        assertEquals(
            listOf("Join", "Dismiss"),
            posted.actions.map { it.title.toString() },
        )
        val join = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(MotdNotifications.ACTION_ACCEPT_INVITE, join.action)
        assertEquals(messageId, join.getLongExtra(MotdNotifications.EXTRA_INVITE_MESSAGE_ID, -1))
        val dismiss = shadowOf(posted.actions[1].actionIntent).savedIntent
        assertEquals(InviteReceiver.ACTION_DISMISS, dismiss.action)
        assertEquals(messageId, dismiss.getLongExtra(InviteReceiver.EXTRA_MESSAGE_ID, -1))
        assertNotNull(posted.deleteIntent)
        assertEquals(InviteReceiver.ACTION_DISMISS, shadowOf(posted.deleteIntent).savedIntent.action)

        notifications.onInvitationResolved(messageId)
        assertEquals(0, postedCount())
    }

    @Test
    fun invitationPresentationAndResolutionFollowCanonicalEventRedirects() = runTest {
        fun invite(key: String) = MessageEntity(
            bufferId = bufferId,
            msgid = key,
            serverTime = 3_000,
            sender = "alice",
            kind = MessageKind.INVITE,
            text = "alice invited you",
            dedupKey = key,
            eventPayload = "invite-v1:YQ:Yg:Yw",
            inviteState = InviteState.PENDING,
        )
        val winnerId = db.messageDao().insertAll(listOf(invite("winner-invite"))).single()
        val loserId = db.messageDao().insertAll(listOf(invite("loser-invite"))).single()
        notifications.onInvitation(1, bufferId, loserId)

        db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
        db.messageDao().deleteById(loserId)
        notifications.onInvitation(1, bufferId, loserId)

        val manager = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
        assertEquals(2, manager.activeNotifications.size)
        val canonical = manager.activeNotifications.single {
            it.id == MotdNotifications.invitationNotificationId(winnerId)
        }.notification
        val join = shadowOf(canonical.actions[0].actionIntent).savedIntent
        assertEquals(winnerId, join.getLongExtra(MotdNotifications.EXTRA_INVITE_MESSAGE_ID, -1))

        notifications.onInvitationResolved(winnerId)
        assertEquals(0, postedCount())
    }
}
