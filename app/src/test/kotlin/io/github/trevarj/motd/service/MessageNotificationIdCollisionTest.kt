package io.github.trevarj.motd.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.AppStateEntity
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A conversation whose buffer id happened to be [IrcForegroundService.STATUS_ID] used to overwrite
 * the pinned foreground-service notification and then cancel it on read. Message ids are now
 * namespaced, and notifications left behind by the old raw-id build are retired on first run.
 */
@RunWith(RobolectricTestRunner::class)
class MessageNotificationIdCollisionTest {
    private lateinit var db: MotdDatabase
    private lateinit var repo: DataStoreSettingsRepository
    private lateinit var notifications: MotdNotifications
    private var networkId: Long = 0
    private var bufferId: Long = 0

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val shadowManager
        get() = shadowOf(context.getSystemService(NotificationManager::class.java))

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
            // Force the buffer id that collides with the status notification id.
            bufferId =
                db.bufferDao().insert(
                    BufferEntity(
                        id = IrcForegroundService.STATUS_ID.toLong(),
                        networkId = networkId,
                        name = "troll",
                        displayName = "troll",
                        type = BufferType.QUERY,
                    ),
                )
            assertEquals(IrcForegroundService.STATUS_ID.toLong(), bufferId)
            shadowOf(context as android.app.Application)
                .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
            repo = DataStoreSettingsRepository(context)
            notifications = MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo)
        }

    @After
    fun tearDown() {
        NotificationManagerCompat.from(context).cancelAll()
        db.close()
    }

    private fun chat(text: String) =
        IrcEvent.ChatMessage(
            ctx = MessageContext(msgid = null, serverTime = 1_000, account = null, batchId = null, label = null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("troll"),
            target = "me",
            text = text,
            isSelf = false,
            replyToMsgid = null,
        )

    private fun postStatusNotification() {
        NotificationManagerCompat.from(context).notify(
            IrcForegroundService.STATUS_ID,
            notifications.statusNotification(connectedCount = 1, reconnecting = false),
        )
    }

    private fun post(
        id: Int,
        channelId: String,
        tag: String? = null,
    ) {
        NotificationManagerCompat.from(context).notify(
            tag,
            id,
            NotificationCompat
                .Builder(context, channelId)
                .setSmallIcon(io.github.trevarj.motd.R.drawable.ic_notification_motd)
                .setContentTitle("legacy")
                .build(),
        )
    }

    @Test
    fun messageNotificationDoesNotReplaceOrCancelTheStatusNotification() =
        runTest {
            postStatusNotification()
            val delivered = chat("hey")
            val eventId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            MessageEntity(
                                bufferId = bufferId,
                                msgid = "collision",
                                serverTime = delivered.ctx.serverTime,
                                sender = "troll",
                                kind = MessageKind.PRIVMSG,
                                text = delivered.text,
                                dedupKey = "collision",
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

            assertEquals(
                setOf(IrcForegroundService.STATUS_ID, MotdNotifications.messageNotificationId(bufferId)),
                shadowManager.activeNotifications.map { it.id }.toSet(),
            )
            val status = shadowManager.activeNotifications.single { it.id == IrcForegroundService.STATUS_ID }
            assertEquals(MotdNotifications.CHANNEL_STATUS, status.notification.channelId)
            assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(status.notification))
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_NONE, requireNotNull(NotificationCompat.getAction(status.notification, 0)).semanticAction)

            // Reading the conversation clears only the message notification.
            notifications.onRead(bufferId, TimelineAnchor(delivered.ctx.serverTime, eventId))
            assertEquals(
                listOf(IrcForegroundService.STATUS_ID),
                shadowManager.activeNotifications.map { it.id },
            )
        }

    @Test
    fun watchEndedNotificationsKeepIndependentTapTargetsThroughStartupCleanup() =
        runTest {
            val channels =
                listOf("#first", "#second").map { name ->
                    db.bufferDao().insert(
                        BufferEntity(networkId = networkId, name = name, displayName = name, type = BufferType.CHANNEL),
                    )
                }
            val messageId = MotdNotifications.messageNotificationId(channels.first())
            post(messageId, MotdNotifications.CHANNEL_MESSAGES)
            channels.forEach { notifications.watchEnded(it) }
            notifications.retireLegacyMessageNotifications()

            assertEquals(
                channels.map { MotdNotifications.watchEndedNotificationId(it) }.toSet() + messageId,
                shadowManager.activeNotifications.map { it.id }.toSet(),
            )
            for (id in channels) {
                val notification =
                    shadowManager.activeNotifications.single { it.id == MotdNotifications.watchEndedNotificationId(id) }.notification
                assertEquals(id, shadowOf(notification.contentIntent).savedIntent.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1))
                assertNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification))
                assertTrue(notification.actions.isNullOrEmpty())
            }
        }

    @Test
    fun legacySweepRetiresRawIdMessageNotificationsAndSparesEverythingElse() {
        postStatusNotification()
        post(5, MotdNotifications.CHANNEL_MESSAGES) // pre-upgrade raw id
        post(7, MotdNotifications.CHANNEL_MENTIONS) // pre-upgrade raw id, mention channel
        post(5, MotdNotifications.CHANNEL_MENTIONS, "legacy-mention")
        post(5, MotdNotifications.CHANNEL_STATUS, "status-tag")
        val invitation = MotdNotifications.invitationNotificationId(3)
        post(invitation, MotdNotifications.CHANNEL_INVITATIONS)
        val transfer = MotdNotifications.transferNotificationId(3)
        post(transfer, MotdNotifications.CHANNEL_TRANSFERS)
        val current = MotdNotifications.messageNotificationId(bufferId)
        post(current, MotdNotifications.CHANNEL_MESSAGES)
        post(current, MotdNotifications.CHANNEL_MENTIONS, "channel_mentions")

        notifications.retireLegacyMessageNotifications()

        val survivors =
            setOf(
                null to IrcForegroundService.STATUS_ID,
                null to invitation,
                null to transfer,
                null to current,
                "channel_mentions" to current,
                "status-tag" to 5,
            )
        assertEquals(survivors, shadowManager.activeNotifications.map { it.tag to it.id }.toSet())

        // Idempotent: a second run leaves the surviving namespaced notification alone.
        notifications.retireLegacyMessageNotifications()
        assertNotNull(shadowManager.activeNotifications.singleOrNull { it.id == current && it.tag == null })
        assertTrue(
            shadowManager.activeNotifications.any { it.id == IrcForegroundService.STATUS_ID },
        )
    }

    /**
     * A pre-upgrade build could park a message notification on the status id itself. It is a stale
     * message notification, so the sweep retires it; the foreground service reposts its own status
     * on the next update.
     */
    @Test
    fun legacySweepRetiresAMessageNotificationParkedOnTheStatusId() {
        post(IrcForegroundService.STATUS_ID, MotdNotifications.CHANNEL_MESSAGES)

        notifications.retireLegacyMessageNotifications()

        assertEquals(emptyList<Int>(), shadowManager.activeNotifications.map { it.id })
    }

    @Test
    fun fullBufferAndLaneUrisKeepReplyReadAndOpenIndependentDespiteRequestCodeCollision() =
        runTest {
            val firstId = 33L
            val secondId = firstId + (1L shl 32)
            val targets = mapOf(firstId to "#room", secondId to "#other")
            for ((id, target) in targets) {
                db.bufferDao().insert(
                    BufferEntity(id = id, networkId = networkId, name = "#internal-$id", displayName = target, type = BufferType.CHANNEL),
                )
            }

            suspend fun incoming(
                id: Long,
                mention: Boolean,
                time: Long,
            ): MessageEntity {
                val key = "action-$id-$mention-$time"
                val message = chat(if (mention) "me: $key" else key).copy(target = targets.getValue(id), ctx = chat(key).ctx.copy(msgid = key, serverTime = time))
                val eventId =
                    db
                        .messageDao()
                        .insertAll(
                            listOf(
                                MessageEntity(
                                    bufferId = id,
                                    msgid = key,
                                    serverTime = time,
                                    sender = "troll",
                                    kind = MessageKind.PRIVMSG,
                                    text = message.text,
                                    dedupKey = key,
                                    hasMention = mention,
                                ),
                            ),
                        ).single()
                notifications.onCanonicalIncoming(networkId, id, BufferType.CHANNEL, mention, eventId, message, refreshOnly = false)
                return requireNotNull(db.messageDao().byId(eventId))
            }
            val anchors =
                mutableMapOf(
                    (firstId to null) to incoming(firstId, false, 1_000),
                    (firstId to "channel_mentions") to incoming(firstId, true, 2_000),
                    (secondId to null) to incoming(secondId, false, 3_000),
                    (secondId to "channel_mentions") to incoming(secondId, true, 4_000),
                )
            val captured = shadowManager.activeNotifications.map { it.tag to it.notification }
            assertEquals(4, captured.size)
            val destinations =
                captured.flatMap { (_, notification) ->
                    notification.actions.map { shadowOf(it.actionIntent).savedIntent.dataString } +
                        shadowOf(notification.contentIntent).savedIntent.dataString
                }
            assertEquals(
                targets.keys
                    .flatMap { id ->
                        listOf("default", "channel_mentions").flatMap { lane ->
                            listOf("reply", "read", "open").map { action -> "motd://notification/$id/$lane/$action" }
                        }
                    }.toSet(),
                destinations.toSet(),
            )
            anchors[firstId to null] = incoming(firstId, false, 5_000)

            // Inspect the originally captured PendingIntents after FLAG_UPDATE_CURRENT updates one lane.
            for ((tag, notification) in captured) {
                val open = shadowOf(notification.contentIntent).savedIntent
                val id = open.getLongExtra(MotdNotifications.EXTRA_BUFFER_ID, -1)
                val anchor = anchors.getValue(id to tag)
                val read = shadowOf(notification.actions.single { it.title.toString() == "Mark read" }.actionIntent).savedIntent
                val reply = shadowOf(notification.actions.single { it.title.toString() == "Reply" }.actionIntent).savedIntent
                assertEquals(id, read.getLongExtra(ReplyReceiver.EXTRA_BUFFER_ID, -1))
                assertEquals(id, reply.getLongExtra(ReplyReceiver.EXTRA_BUFFER_ID, -1))
                assertEquals(anchor.id, read.getLongExtra(ReplyReceiver.EXTRA_UP_TO_EVENT_ID, -1))
                assertEquals(anchor.serverTime, read.getLongExtra(ReplyReceiver.EXTRA_UP_TO_TIME, -1))
                assertEquals(anchor.id, open.getLongExtra(MotdNotifications.EXTRA_EVENT_ID, -1))
                assertEquals(anchor.serverTime, open.getLongExtra(MotdNotifications.EXTRA_JUMP_TIME, -1))
                assertEquals(anchor.msgid, open.getStringExtra(MotdNotifications.EXTRA_JUMP_MSGID))
                assertEquals(
                    targets.getValue(id),
                    NotificationCompat.MessagingStyle
                        .extractMessagingStyleFromNotification(notification)
                        ?.conversationTitle
                        .toString(),
                )
            }
        }

    @Test
    fun initializationResetCancelsBothActualLaneTagsAndPreservesStatusNotifications() =
        runTest {
            val channelId =
                db.bufferDao().insert(
                    BufferEntity(networkId = networkId, name = "#room", displayName = "#room", type = BufferType.CHANNEL),
                )
            notifications.onIncoming(networkId, channelId, BufferType.CHANNEL, false, chat("ordinary").copy(target = "#room"))
            notifications.onIncoming(networkId, channelId, BufferType.CHANNEL, true, chat("me: mention").copy(target = "#room"))
            postStatusNotification()
            val id = MotdNotifications.messageNotificationId(channelId)
            post(id, MotdNotifications.CHANNEL_STATUS, "status-tag")
            post(5, MotdNotifications.CHANNEL_MENTIONS, "legacy-tag")
            db.appStateDao().insert(AppStateEntity("v10_notification_reset"))

            coroutineScope {
                MotdNotifications(context, db, ForegroundBufferTrackerImpl(), repo, applicationScope = this)
            }

            assertEquals(
                setOf(null to IrcForegroundService.STATUS_ID, "status-tag" to id),
                shadowManager.activeNotifications.map { it.tag to it.id }.toSet(),
            )
            assertEquals(0, db.appStateDao().contains("v10_notification_reset"))
        }
}
