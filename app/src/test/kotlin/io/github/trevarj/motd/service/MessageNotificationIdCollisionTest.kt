package io.github.trevarj.motd.service

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    ) {
        NotificationManagerCompat.from(context).notify(
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
            )

            assertEquals(
                setOf(IrcForegroundService.STATUS_ID, MotdNotifications.messageNotificationId(bufferId)),
                shadowManager.activeNotifications.map { it.id }.toSet(),
            )
            val status = shadowManager.activeNotifications.single { it.id == IrcForegroundService.STATUS_ID }
            assertEquals(MotdNotifications.CHANNEL_STATUS, status.notification.channelId)

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
            }
        }

    @Test
    fun legacySweepRetiresRawIdMessageNotificationsAndSparesEverythingElse() {
        postStatusNotification()
        post(5, MotdNotifications.CHANNEL_MESSAGES) // pre-upgrade raw id
        post(7, MotdNotifications.CHANNEL_MENTIONS) // pre-upgrade raw id, mention channel
        val invitation = MotdNotifications.invitationNotificationId(3)
        post(invitation, MotdNotifications.CHANNEL_INVITATIONS)
        val transfer = MotdNotifications.transferNotificationId(3)
        post(transfer, MotdNotifications.CHANNEL_TRANSFERS)
        val current = MotdNotifications.messageNotificationId(bufferId)
        post(current, MotdNotifications.CHANNEL_MESSAGES)

        notifications.retireLegacyMessageNotifications()

        assertEquals(
            setOf(IrcForegroundService.STATUS_ID, invitation, transfer, current),
            shadowManager.activeNotifications.map { it.id }.toSet(),
        )

        // Idempotent: a second run leaves the surviving namespaced notification alone.
        notifications.retireLegacyMessageNotifications()
        assertNotNull(shadowManager.activeNotifications.singleOrNull { it.id == current })
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
}
