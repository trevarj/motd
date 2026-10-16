package io.github.trevarj.motd.service

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.sync.BufferStore
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
            // A DM buffer keyed by the sender so (DM || mention) qualifies upstream.
            bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "troll",
                        displayName = "troll",
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
    fun tearDown() =
        runTest {
            // The application-scoped DataStore outlives this Robolectric class; do not leak the fool
            // fixture into another test class or make this class depend on JUnit method order.
            repo.setFool("troll", false)
            repo.setFool("troll~", false)
            repo.setFool("stable-account", false)
            repo.setFool("new-nick", false)
            db.close()
        }

    private fun chat(
        nick: String,
        text: String,
    ) = IrcEvent.ChatMessage(
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

    /** A first presentation alerts through its channel (no NotificationCompat silent-group marker). */
    private fun assertAlerting(notification: android.app.Notification) {
        assertNull(notification.group)
        assertEquals(NotificationCompat.GROUP_ALERT_ALL, notification.groupAlertBehavior)
    }

    /**
     * The crash repro path: add a fool, then run the notification pipeline for that fool's message.
     * onIncoming reads the fools DataStore + the buffer via suspend calls (no runBlocking on the
     * main-thread events collector), so it completes cleanly and posts nothing (fools are silenced).
     */
    @Test
    fun addedFool_incomingMessage_isSilenced_andDoesNotCrash() =
        runTest {
            repo.setFool("Troll", true)
            notifications.onIncoming(
                networkId = 1,
                bufferId = bufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = chat("troll", "hey"),
            )
            assertEquals(0, postedCount())
        }

    /** Control: a non-fool DM still posts a notification through the same suspend read path. */
    @Test
    fun nonFool_incomingMessage_posts() =
        runTest {
            notifications.onIncoming(
                networkId = 1,
                bufferId = bufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = chat("troll", "hey"),
            )
            assertEquals(1, postedCount())
            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            assertAlerting(posted)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertNotNull(
                style
                    ?.messages
                    ?.single()
                    ?.person
                    ?.icon,
            )
        }

    @Test
    fun dickordNotificationCleansSenderButKeepsActualMentionChannelTitleAndRawIdentity() =
        runTest {
            val dickordBufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#internal-alias",
                        displayName = "#discord.me.chat.alice",
                        type = BufferType.CHANNEL,
                    ),
                )
            notifications =
                MotdNotifications(
                    context,
                    db,
                    ForegroundBufferTrackerImpl(),
                    repo,
                    dickordLabsPrefs =
                        object : DickordLabsPrefs(context) {
                            override val enabled = flowOf(true)
                        },
                )

            notifications.onIncoming(
                networkId = networkId,
                bufferId = dickordBufferId,
                type = BufferType.CHANNEL,
                hasMention = true,
                message = chat("Alice/discord", "me: hello"),
            )

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            val person = style?.messages?.single()?.person
            assertEquals("#discord.me.chat.alice", style?.conversationTitle.toString())
            assertEquals("Alice", person?.name.toString())
            assertEquals("irc:$networkId:alice/discord", person?.key)
            assertEquals(dickordBufferId, shadowOf(posted.contentIntent).savedIntent.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
            val reply = requireNotNull(NotificationCompat.getAction(posted, 0))
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
            assertEquals(dickordBufferId, shadowOf(reply.actionIntent).savedIntent.getLongExtra(ReplyReceiver.EXTRA_BUFFER_ID, -1))

            notifications.onIncoming(
                networkId,
                dickordBufferId,
                BufferType.CHANNEL,
                false,
                chat("Alice/discord", "ordinary"),
            )
            val ordinary =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single { it.tag == null }
                    .notification
            val ordinaryStyle = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(ordinary)
            assertEquals("#me.chat.alice", ordinaryStyle?.conversationTitle.toString())
            assertEquals(listOf("ordinary"), ordinaryStyle?.messages?.map { it.text.toString() })
            assertEquals(listOf("me: hello"), style?.messages?.map { it.text.toString() })
        }

    @Test
    fun rfc1459FoolEquivalenceSuppressesNotification() =
        runTest {
            repo.setFool("troll~", true)

            notifications.onIncoming(
                networkId = networkId,
                bufferId = bufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = chat("troll^", "folded fool"),
            )

            assertEquals(0, postedCount())
        }

    @Test
    fun strictRfc1459KeepsTildeAndCaretDistinctForNotificationSuppression() =
        runTest {
            db.networkIdentityDao().upsert(
                NetworkIdentityEntity(networkId, caseMapping = "rfc1459-strict"),
            )
            repo.setFool("troll~", true)

            notifications.onIncoming(
                networkId = networkId,
                bufferId = bufferId,
                type = BufferType.QUERY,
                hasMention = false,
                message = chat("troll^", "distinct sender"),
            )

            assertEquals(1, postedCount())
        }

    @Test
    fun canonicalAccountSuppressesNotificationAfterNickChange() =
        runTest {
            repo.setFool("stable-account", true)
            val delivered = chat("new-nick", "same account after rename")
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = bufferId,
                                msgid = "account-continuity",
                                serverTime = delivered.ctx.serverTime,
                                sender = "new-nick",
                                normalizedActor = "new-nick",
                                senderAccount = "stable-account",
                                kind = MessageKind.PRIVMSG,
                                text = delivered.text,
                                dedupKey = "account-continuity",
                            ),
                        ),
                    ).single()

            notifications.onCanonicalIncoming(
                networkId,
                bufferId,
                BufferType.QUERY,
                false,
                eventId,
                delivered,
                refreshOnly = false,
            )

            assertEquals(0, postedCount())
        }

    @Test
    fun defaultFool_liveAndPushMentionsAndDms_areHandledWithoutReplayAfterOptIn() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#fools", displayName = "#fools", type = BufferType.CHANNEL),
                )
            repo.setFool("troll", true)
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
            processor.onRegistered(networkId, "me", emptyMap())
            val deliveries =
                listOf(false, true).flatMap { push ->
                    listOf(false, true).map { direct ->
                        val key = "default-$push-$direct"
                        val message =
                            chat("troll", "me: $key").copy(
                                ctx = chat("troll", key).ctx.copy(msgid = key, serverTime = 2_000L + (if (push) 100 else 0) + (if (direct) 10 else 0)),
                                target = if (direct) "me" else "#fools",
                            )
                        if (push) processor.processPush(networkId, message) else processor.process(networkId, message)
                        val row = requireNotNull(db.messageDao().byMsgid(if (direct) bufferId else channelId, key))
                        assertTrue(row.notificationHandled)
                        assertFalse(row.notificationClaimed)
                        message
                    }
                }
            assertEquals(0, postedCount())
            repo.setFoolPreferences("troll", FoolPreferences(notifyMentions = true, notifyDirectMessages = true))
            notifications.recoverCanonicalNotifications()
            deliveries.forEach { processor.process(networkId, it) }
            assertEquals(0, postedCount())
        }

    @Test
    fun independentSwitches_useBufferTypeAndCanonicalReply_notAllOrWatchTraffic() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#fools", displayName = "#fools", type = BufferType.CHANNEL),
                )
            repo.setFool("troll", true)
            val settings = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
            settings.setServer(networkId, NotificationMode.ALL)
            settings.startWatch(channelId, null)
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
            processor.onRegistered(networkId, "me", emptyMap())
            processor.process(
                networkId,
                chat("me", "parent").copy(target = "#fools", isSelf = true, ctx = chat("me", "parent").ctx.copy(msgid = "self-parent", serverTime = 500)),
            )
            for (mentions in listOf(false, true)) {
                repo.setFoolPreferences("troll", FoolPreferences(notifyMentions = mentions, notifyDirectMessages = !mentions))
                for (category in listOf("mention", "reply", "ordinary", "dm", "dm-mention")) {
                    context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                    val key = "$mentions-$category"
                    val direct = category.startsWith("dm")
                    val expected = if (direct) !mentions else mentions && category != "ordinary"
                    val message =
                        chat("troll", if (category.endsWith("mention")) "me: $key" else key).copy(
                            ctx = chat("troll", key).ctx.copy(msgid = key, serverTime = if (mentions) 3_000 else 2_000),
                            target = if (direct) "me" else "#fools",
                            replyToMsgid = "self-parent".takeIf { category == "reply" },
                        )
                    processor.process(networkId, message)
                    assertEquals(key, if (expected) 1 else 0, postedCount())
                    val row = requireNotNull(db.messageDao().byMsgid(if (direct) bufferId else channelId, key))
                    assertEquals(category == "mention" || category == "reply" || category == "dm-mention", row.hasMention)
                    assertTrue(row.notificationHandled)
                }
            }
        }

    @Test
    fun hiddenOptIn_allowsDeviceDeliveryButRespectsScopeReadForegroundMuteAndWatchCategory() =
        runTest {
            repo.setFool("troll", true)
            for (guard in listOf("allowed", "off", "read", "foreground", "mute", "watched", "category-off")) {
                context.getSystemService(android.app.NotificationManager::class.java).cancelAll()
                val target = "#$guard"
                val channelId =
                    db.bufferDao().insert(
                        BufferEntity(
                            networkId = networkId,
                            name = target,
                            displayName = target,
                            type = BufferType.CHANNEL,
                            muted = guard in setOf("mute", "watched", "category-off"),
                            localUnreadFloorTime = 5_000L.takeIf { guard == "read" },
                        ),
                    )
                val tracker = ForegroundBufferTrackerImpl()
                if (guard == "foreground") tracker.set(channelId)
                notifications = MotdNotifications(context, db, tracker, repo)
                repo.setFoolPreferences("troll", FoolPreferences(visibility = FoolsMode.HIDE, notifyMentions = guard != "category-off"))
                val settings = NotificationSettingsImpl(backgroundScope, AppClock { 0L }, onExpired = {})
                settings.setServer(networkId, if (guard == "off") NotificationMode.OFF else NotificationMode.MENTIONS)
                if (guard == "watched" || guard == "category-off") settings.startWatch(channelId, null)
                val processor = EventProcessor(db, TypingTrackerImpl(), notifications, notificationSettings = settings)
                processor.onRegistered(networkId, "me", emptyMap())
                processor.process(
                    networkId,
                    chat("troll", "me: $guard").copy(target = target, ctx = chat("troll", guard).ctx.copy(msgid = guard)),
                )
                assertEquals(guard, if (guard == "allowed" || guard == "watched") 1 else 0, postedCount())
                val row = requireNotNull(db.messageDao().byMsgid(channelId, guard))
                val policy = MessageVisibilityPolicy(MessageVisibilitySpec.from(repo.settings.first()))
                assertFalse(policy.visibleUnread(row))
                assertFalse(policy.mentionAttention(row, directMessage = false))
            }
        }

    @Test
    fun canonicalIdentityAndMention_controlPermissionWhileDiagnosticsKeepActualMembership() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#fools", displayName = "#fools", type = BufferType.CHANNEL),
                )
            repo.setFool("stable-account", true)
            repo.setFoolPreferences("stable-account", FoolPreferences(visibility = FoolsMode.HIDE, notifyMentions = true))
            var evaluatedFields = emptyMap<String, Any?>()
            val diagnostics =
                object : DiagnosticLogger by DiagnosticLogger.Noop {
                    override fun record(
                        component: String,
                        event: String,
                        fields: () -> Map<String, Any?>,
                    ) {
                        if (component == "notifications" && event == "message_evaluated") evaluatedFields = fields()
                    }
                }
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo, diagnostics = diagnostics)
            val row =
                MessageEntity(
                    bufferId = channelId,
                    serverTime = 2_000,
                    sender = "new-nick",
                    normalizedActor = "new-nick",
                    senderAccount = "stable-account",
                    kind = MessageKind.PRIVMSG,
                    text = "reply without literal nickname",
                    hasMention = true,
                    dedupKey = "canonical-category",
                )
            val id = db.messageDao().insertAll(listOf(row)).single()
            notifications.onCanonicalIncoming(networkId, channelId, BufferType.CHANNEL, false, id, chat("new-nick", row.text), refreshOnly = false)
            assertEquals(1, postedCount())
            assertEquals(true, evaluatedFields["fool"])
            assertEquals(true, evaluatedFields["post"])
            val posted =
                NotificationManagerCompat
                    .from(context)
                    .activeNotifications
                    .single()
                    .notification
            repo.setFool("new-nick", true)
            val nextId = db.messageDao().insertAll(listOf(row.copy(serverTime = 3_000, dedupKey = "canonical-collision"))).single()
            notifications.onCanonicalIncoming(networkId, channelId, BufferType.CHANNEL, true, nextId, chat("new-nick", "collision"), refreshOnly = false)
            assertEquals(true, evaluatedFields["fool"])
            assertEquals(false, evaluatedFields["post"])
            assertSame(
                posted,
                NotificationManagerCompat
                    .from(context)
                    .activeNotifications
                    .single()
                    .notification,
            )
        }

    @Test
    fun roomMergeRetiresLosingNotificationAndCanonicalReadClearsReplacement() =
        runTest {
            val loserId =
                db.bufferDao().insert(
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
            assertEquals(
                listOf(MotdNotifications.messageNotificationId(loserId)),
                manager.activeNotifications.map { it.id },
            )

            BufferStore(db, notifications).mergeRooms(bufferId, loserId)

            assertEquals(0, manager.activeNotifications.size)
            val afterMerge = chat("other", "after merge")
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = bufferId,
                                msgid = "after-merge",
                                serverTime = afterMerge.ctx.serverTime,
                                sender = "other",
                                kind = MessageKind.PRIVMSG,
                                text = afterMerge.text,
                                dedupKey = "after-merge",
                            ),
                        ),
                    ).single()
            notifications.onCanonicalIncoming(
                networkId,
                bufferId,
                BufferType.QUERY,
                false,
                eventId,
                afterMerge,
                refreshOnly = false,
            )
            assertEquals(
                listOf(MotdNotifications.messageNotificationId(bufferId)),
                manager.activeNotifications.map { it.id },
            )

            notifications.onRead(
                bufferId,
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(1_000, eventId),
            )
            assertEquals(0, manager.activeNotifications.size)
        }

    @Test
    fun canonicalNotificationReadResolvesCoalescedAndRetimedEventIdentity() =
        runTest {
            val delivered = chat("troll", "retimed")
            val loserId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = bufferId,
                                msgid = null,
                                serverTime = delivered.ctx.serverTime,
                                sender = "troll",
                                kind = MessageKind.PRIVMSG,
                                text = delivered.text,
                                dedupKey = "notification-loser",
                            ),
                        ),
                    ).single()
            notifications.onCanonicalIncoming(
                networkId,
                bufferId,
                BufferType.QUERY,
                false,
                loserId,
                delivered,
                refreshOnly = false,
            )
            val winnerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = bufferId,
                                msgid = "notification-winner",
                                serverTime = 2_000,
                                sender = "troll",
                                kind = MessageKind.PRIVMSG,
                                text = delivered.text,
                                dedupKey = "notification-winner",
                            ),
                        ),
                    ).single()
            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
            db.messageDao().deleteById(loserId)
            val recreated = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)

            recreated.onRead(
                bufferId,
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(1_999, winnerId),
            )
            assertEquals(1, postedCount())

            recreated.onRead(
                bufferId,
                io.github.trevarj.motd.data.db
                    .TimelineAnchor(2_000, winnerId),
            )
            assertEquals(0, postedCount())
        }

    @Test
    fun interruptedNotificationPresentationIsRebuiltFromCanonicalDatabaseState() =
        runTest {
            val failing =
                object : io.github.trevarj.motd.data.sync.MessageNotifier {
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
            val incoming =
                chat("troll", "recover me").copy(
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
    fun delayedPushAtLocalUnreadFloor_doesNotNotifyAfterUnmute() =
        runTest {
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
    fun duplicateDelivery_addsBodyToMessagingStyleOnlyOnce() =
        runTest {
            val message = chat("troll", "only once")

            repeat(2) {
                notifications.onIncoming(
                    networkId = 1,
                    bufferId = bufferId,
                    type = BufferType.QUERY,
                    hasMention = false,
                    message = message,
                )
            }

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertEquals(listOf("only once"), style?.messages?.map { it.text.toString() })
        }

    @Test
    fun differentDurableMsgids_withSameFingerprint_remainDistinctNotifications() =
        runTest {
            val message = chat("durable-identity-fixture", "legitimately repeated")

            for (msgid in listOf("durable-a", "durable-b")) {
                val id =
                    db
                        .messageDao()
                        .insertAll(
                            listOf(
                                MessageEntity(
                                    bufferId = bufferId,
                                    msgid = msgid,
                                    serverTime = message.ctx.serverTime,
                                    sender = message.source.nick,
                                    normalizedActor = message.source.nick,
                                    kind = MessageKind.PRIVMSG,
                                    text = message.text,
                                    dedupKey = msgid,
                                ),
                            ),
                        ).single()
                notifications.onCanonicalIncoming(
                    networkId,
                    bufferId,
                    BufferType.QUERY,
                    false,
                    id,
                    message.copy(ctx = message.ctx.copy(msgid = msgid)),
                    refreshOnly = false,
                )
            }

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertEquals(
                listOf("legitimately repeated", "legitimately repeated"),
                style?.messages?.map { it.text.toString() },
            )
        }

    @Test
    fun sojuHistoryThenDelayedPushAndReconnect_persistsAndNotifiesMentionOnlyOnce() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#systemcrafters",
                        displayName = "#systemcrafters",
                        type = BufferType.CHANNEL,
                    ),
                )
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
            processor.onRegistered(networkId, "me", emptyMap())
            val history =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
            val msgidlessDelivery =
                history.copy(
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

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertEquals(
                listOf("me: only one row and notification"),
                style?.messages?.map { it.text.toString() },
            )
        }

    @Test
    fun sojuMsgidlessPushThenHistoryAndReconnect_persistsAndNotifiesMentionOnlyOnce() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(
                        networkId = networkId,
                        name = "#systemcrafters",
                        displayName = "#systemcrafters",
                        type = BufferType.CHANNEL,
                    ),
                )
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
            processor.onRegistered(networkId, "me", emptyMap())
            val push =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
                            ctx =
                                push.ctx.copy(
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
            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)
            assertEquals(
                listOf("me: identity promoted after notification"),
                style?.messages?.map { it.text.toString() },
            )
        }

    @Test
    fun msgidlessNewDmPush_createsDeepLinkBufferButWaitsForHistoryRow() =
        runTest {
            val processor = EventProcessor(db, TypingTrackerImpl(), notifications)
            processor.onRegistered(networkId, "me", emptyMap())
            val push =
                IrcEvent.ChatMessage(
                    ctx =
                        MessageContext(
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
                db
                    .bufferDao()
                    .openTargets(networkId)
                    .single { it.name == "new-dm-push-fixture" }
                    .id,
            )

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            val open = shadowOf(posted.contentIntent).savedIntent
            assertEquals(MotdNotifications.ACTION_OPEN_BUFFER, open.action)
            assertEquals(buffer.id, open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
            assertNull(open.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID))
            assertEquals(30_000, open.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, -1))
            val eventId = open.getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1)
            assertEquals(buffer.id, requireNotNull(db.messageDao().byId(eventId)).bufferId)
        }

    @Test
    fun invitationNotification_hasOpenJoinDismissAndSwipeContracts_thenCancelsOnResolution() =
        runTest {
            val messageId =
                db
                    .messageDao()
                    .insertAll(
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

            val posted =
                shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
                    .activeNotifications
                    .single()
                    .notification
            assertEquals(MotdNotifications.CHANNEL_INVITATIONS, posted.channelId)
            assertSilent(posted)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted))
            for (index in posted.actions.indices) {
                assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_NONE, requireNotNull(NotificationCompat.getAction(posted, index)).semanticAction)
            }
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
    fun invitationPresentationAndResolutionFollowCanonicalEventRedirects() =
        runTest {
            fun invite(key: String) =
                MessageEntity(
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
            val canonical =
                manager.activeNotifications
                    .single {
                        it.id == MotdNotifications.invitationNotificationId(winnerId)
                    }.notification
            val join = shadowOf(canonical.actions[0].actionIntent).savedIntent
            assertEquals(winnerId, join.getLongExtra(MotdNotifications.EXTRA_INVITE_MESSAGE_ID, -1))

            notifications.onInvitationResolved(winnerId)
            assertEquals(0, postedCount())
        }

    @Test
    fun channelRoomMergeRetiresBothLosingLanesAndPreservesWinnerAndStatus() =
        runTest {
            val winnerId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#room", displayName = "#room", type = BufferType.CHANNEL),
                )
            val loserId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#other", displayName = "#other", type = BufferType.CHANNEL),
                )
            for ((id, target) in listOf(winnerId to "#room", loserId to "#other")) {
                notifications.onIncoming(networkId, id, BufferType.CHANNEL, false, chat("troll", "ordinary $target").copy(target = target))
                notifications.onIncoming(networkId, id, BufferType.CHANNEL, true, chat("troll", "me: mention $target").copy(target = target))
            }
            NotificationManagerCompat.from(context).notify(
                IrcForegroundService.STATUS_ID,
                notifications.statusNotification(connectedCount = 1, reconnecting = false),
            )
            val manager = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            assertEquals(5, manager.activeNotifications.size)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)

            BufferStore(db, notifications).mergeRooms(winnerId, loserId)

            assertEquals(
                setOf(
                    null to IrcForegroundService.STATUS_ID,
                    null to MotdNotifications.messageNotificationId(winnerId),
                    "channel_mentions" to MotdNotifications.messageNotificationId(winnerId),
                ),
                manager.activeNotifications.map { it.tag to it.id }.toSet(),
            )
            for ((tag, body) in listOf(null to "ordinary #room", "channel_mentions" to "me: mention #room")) {
                val posted = manager.activeNotifications.single { it.tag == tag && it.id == MotdNotifications.messageNotificationId(winnerId) }.notification
                assertEquals(
                    listOf(body),
                    NotificationCompat.MessagingStyle
                        .extractMessagingStyleFromNotification(posted)
                        ?.messages
                        ?.map { it.text.toString() },
                )
            }
            assertEquals(
                MotdNotifications.CHANNEL_STATUS,
                manager.activeNotifications
                    .single { it.id == IrcForegroundService.STATUS_ID }
                    .notification.channelId,
            )
        }
}
